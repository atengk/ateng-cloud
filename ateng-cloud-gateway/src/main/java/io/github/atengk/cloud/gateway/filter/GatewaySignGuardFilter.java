package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import io.github.atengk.cloud.gateway.util.GatewayResponseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.MultiValueMap;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * 开放接口 API 签名防篡改与 Nonce 防重放全局过滤器
 *
 * <p>核心治理语义：
 * 1. 严格校验必需标头：{@code X-App-Id}、{@code X-Timestamp}、{@code X-Nonce} 与 {@code X-Signature}；
 * 2. 时间戳时钟防偏：校验时间戳与当前系统时间差，超过阈值（默认 5 分钟）判定为过期；
 * 3. Nonce 防重放攻击：依托可插拔 {@link GatewayNonceValidator} 检验 Nonce 唯一性，拒绝重复 Nonce 请求；
 * 4. HMAC-SHA256 签名校验：规范化请求参数与标头（支持可选 Request Body SHA-256 摘要），比对摘要防止请求被中间人篡改；
 * 5. 堆外内存确定性释放：请求体验签失败或超限时主动释放 Netty PooledDataBuffer，杜绝堆外内存泄漏；
 * 6. 路径白名单与路由边界：支持配置 {@code includePaths} 与 {@code excludePaths} 明确过滤范围，防御范围蔓延。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewaySignGuardFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewaySignGuardFilter.class);

    /**
     * 单个请求体最大允许内存阈值 (2MB 载荷硬防爆)
     */
    public static final long MAX_BODY_SIZE = 2 * 1024 * 1024L;

    private final AtengGatewaySignProperties properties;
    private final SignSecretProvider secretProvider;
    private final GatewayNonceValidator nonceValidator;
    private final ObjectMapper objectMapper;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public GatewaySignGuardFilter(AtengGatewaySignProperties properties,
                                  SignSecretProvider secretProvider,
                                  ObjectMapper objectMapper) {
        this(properties, secretProvider, new io.github.atengk.cloud.gateway.provider.InMemoryGatewayNonceValidator(), objectMapper);
    }

    public GatewaySignGuardFilter(AtengGatewaySignProperties properties,
                                  SignSecretProvider secretProvider,
                                  GatewayNonceValidator nonceValidator,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.secretProvider = secretProvider;
        this.nonceValidator = nonceValidator;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (!shouldFilter(path)) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();

        // 前置检查 Content-Length 是否超限 (2MB 载荷硬防爆)
        long contentLength = headers.getContentLength();
        if (contentLength > MAX_BODY_SIZE) {
            log.warn("[GatewaySign] 请求体超出最大允许限制 (2MB): contentLength={}", contentLength);
            return GatewayResponseUtils.writeJsonError(
                    exchange, objectMapper, HttpStatus.PAYLOAD_TOO_LARGE, 413, "请求报文超出最大允许限制 (2MB)");
        }

        // 1. 提取签名相关核心标头
        String appId = headers.getFirst(GatewayConstants.X_APP_ID);
        String timestampStr = headers.getFirst(GatewayConstants.X_TIMESTAMP);
        String nonce = headers.getFirst(GatewayConstants.X_NONCE);
        String signature = headers.getFirst(GatewayConstants.X_SIGNATURE);

        if (!StringUtils.hasText(appId)) {
            return writeJsonError(exchange, "缺少签名 AppId 标头 (X-App-Id)");
        }
        if (!StringUtils.hasText(timestampStr)) {
            return writeJsonError(exchange, "缺少签名时间戳标头 (X-Timestamp)");
        }
        if (!StringUtils.hasText(nonce)) {
            return writeJsonError(exchange, "缺少签名随机数标头 (X-Nonce)");
        }
        if (!StringUtils.hasText(signature)) {
            return writeJsonError(exchange, "缺少签名摘要标头 (X-Signature)");
        }

        // 2. 时间戳格式与时钟防偏防过期校验
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr.trim());
            // 兼容 10 位秒级时间戳
            if (timestampStr.trim().length() == 10) {
                timestamp *= 1000L;
            }
        } catch (NumberFormatException e) {
            return writeJsonError(exchange, "无效的签名时间戳格式");
        }

        long now = System.currentTimeMillis();
        long allowedDeviation = properties.getTimestampThreshold() != null
                ? properties.getTimestampThreshold().toMillis()
                : 300_000L;

        if (Math.abs(now - timestamp) > allowedDeviation) {
            log.warn("[GatewaySign] 签名时间戳偏差超限: clientTime={}, serverTime={}, deviation={}ms > allowed={}ms",
                    timestamp, now, Math.abs(now - timestamp), allowedDeviation);
            return writeJsonError(exchange, "请求时间戳偏差过大或已过期");
        }

        // 3. Nonce 防重放校验 (委托无状态 SPI 校验)
        if (nonceValidator != null) {
            boolean validNonce = nonceValidator.validateAndStore(nonce.trim(), properties.getTimestampThreshold());
            if (!validNonce) {
                log.warn("[GatewaySign] 检测到重复 Nonce 请求，疑似重放攻击: nonce={}", nonce);
                return writeJsonError(exchange, "请求随机数 Nonce 重复，疑似重放攻击");
            }
        }

        // 4. 获取 AppId 对应 Secret
        if (secretProvider == null) {
            return writeJsonError(exchange, "网关未配置签名密钥提供者");
        }
        String secret = secretProvider.getSecret(appId.trim());
        if (!StringUtils.hasText(secret)) {
            log.warn("[GatewaySign] 无效的 AppKey 或密钥不存在: appId={}", appId);
            return writeJsonError(exchange, "无效的 AppKey 或密钥不存在");
        }

        // 5. 判断是否需要请求体加签验签
        if (!isBodyCheckNeeded(exchange)) {
            String canonicalString = buildCanonicalString(exchange, appId.trim(), timestampStr.trim(), nonce.trim(), null);
            if (!verifySignature(canonicalString, secret, signature.trim())) {
                log.warn("[GatewaySign] 签名比对失败: appId={}, signature={}", appId, signature);
                return writeJsonError(exchange, "API 签名校验失败，请求可能被篡改");
            }
            return chain.filter(exchange);
        }

        // 6. 响应式缓存请求体并计算 SHA-256 摘要加签
        return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange, cachedRequest -> {
            DataBuffer dataBuffer = exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR);
            String bodyHash = null;
            if (dataBuffer != null && dataBuffer.readableByteCount() > 0) {
                if (dataBuffer.readableByteCount() > MAX_BODY_SIZE) {
                    log.warn("[GatewaySign] 缓存请求体超出最大允许限制 (2MB): size={}", dataBuffer.readableByteCount());
                    ServerWebExchangeUtils.clearCachedRequestBody(exchange);
                    return GatewayResponseUtils.writeJsonError(
                            exchange, objectMapper, HttpStatus.PAYLOAD_TOO_LARGE, 413, "请求报文超出最大允许限制 (2MB)");
                }
                bodyHash = computeSha256Hex(dataBuffer);
            }

            String canonicalString = buildCanonicalString(exchange, appId.trim(), timestampStr.trim(), nonce.trim(), bodyHash);
            if (!verifySignature(canonicalString, secret, signature.trim())) {
                log.warn("[GatewaySign] 签名比对失败: appId={}, signature={}", appId, signature);
                // 确定性释放缓存的 PooledDataBuffer，彻底杜绝堆外内存泄漏
                ServerWebExchangeUtils.clearCachedRequestBody(exchange);
                return writeJsonError(exchange, "API 签名校验失败，请求可能被篡改");
            }

            ServerWebExchange mutatedExchange = exchange.mutate().request(cachedRequest).build();
            return chain.filter(mutatedExchange);
        });
    }

    /**
     * 判断当前请求是否需要执行请求体 (Request Body) 加签验签
     */
    private boolean isBodyCheckNeeded(ServerWebExchange exchange) {
        if (properties == null || !properties.isBodyEnabled()) {
            return false;
        }
        HttpMethod method = exchange.getRequest().getMethod();
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method);
    }

    /**
     * 计算 DataBuffer 的 SHA-256 摘要十六进制小写字符串
     *
     * <p>依托 {@link DataBuffer#readableByteBuffers()} 进行非破坏性只读切片迭代，
     * 严禁修改底层 ByteBuf 的 readerIndex，保障下游过滤器与转发路由 100% 正常读取。</p>
     *
     * @param dataBuffer 待计算摘要的响应式数据缓冲
     * @return 64 位 SHA-256 十六进制摘要，若缓冲为空或为 0 字节则返回 null
     */
    public String computeSha256Hex(DataBuffer dataBuffer) {
        if (dataBuffer == null || dataBuffer.readableByteCount() == 0) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            var iterator = dataBuffer.readableByteBuffers();
            while (iterator.hasNext()) {
                ByteBuffer byteBuffer = iterator.next();
                md.update(byteBuffer.asReadOnlyBuffer());
            }
            byte[] digest = md.digest();
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.error("[GatewaySign] 计算请求体 SHA-256 摘要异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 判断当前请求路径是否需要执行签名拦截
     */
    private boolean shouldFilter(String path) {
        List<String> excludes = properties.getExcludePaths();
        if (excludes != null && !excludes.isEmpty()) {
            for (String pattern : excludes) {
                if (pathMatcher.match(pattern, path)) {
                    return false;
                }
            }
        }
        List<String> includes = properties.getIncludePaths();
        if (includes == null || includes.isEmpty()) {
            return true;
        }
        for (String pattern : includes) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建规范签名字符串 (appId + nonce + timestamp + 排序后的 query 参数)
     */
    public String buildCanonicalString(ServerWebExchange exchange, String appId, String timestamp, String nonce) {
        return buildCanonicalString(exchange, appId, timestamp, nonce, null);
    }

    /**
     * 构建规范签名字符串 (按照参数名字典升序排序：appId -> bodyHash -> nonce -> timestamp -> queryParams)
     */
    public String buildCanonicalString(ServerWebExchange exchange, String appId, String timestamp, String nonce, String bodyHash) {
        StringBuilder sb = new StringBuilder();
        sb.append("appId=").append(appId);
        if (StringUtils.hasText(bodyHash)) {
            sb.append("&bodyHash=").append(bodyHash);
        }
        sb.append("&nonce=").append(nonce)
                .append("&timestamp=").append(timestamp);

        MultiValueMap<String, String> queryParams = exchange.getRequest().getQueryParams();
        if (!queryParams.isEmpty()) {
            List<String> sortedKeys = new ArrayList<>(queryParams.keySet());
            Collections.sort(sortedKeys);
            for (String key : sortedKeys) {
                List<String> values = queryParams.get(key);
                if (values != null && !values.isEmpty()) {
                    for (String val : values) {
                        sb.append('&').append(key).append('=').append(val);
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * 校验 HMAC-SHA256 签名（支持 HEX 或 Base64 格式，常量时间比对）
     */
    public boolean verifySignature(String canonicalString, String secret, String actualSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expectedBytes = mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8));

            String expectedHex = HexFormat.of().formatHex(expectedBytes);
            String expectedBase64 = Base64.getEncoder().encodeToString(expectedBytes);

            byte[] actualBytes = actualSignature.getBytes(StandardCharsets.UTF_8);

            return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.UTF_8), actualSignature.toLowerCase().getBytes(StandardCharsets.UTF_8))
                    || MessageDigest.isEqual(expectedBase64.getBytes(StandardCharsets.UTF_8), actualBytes);
        } catch (Exception e) {
            log.error("[GatewaySign] 计算 HMAC 签名异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 统一写出 HTTP 401 签名错误响应
     */
    private Mono<Void> writeJsonError(ServerWebExchange exchange, String msg) {
        return GatewayResponseUtils.writeJsonError(exchange, objectMapper, HttpStatus.UNAUTHORIZED, 401, msg);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_SA_TOKEN - 1;
    }
}
