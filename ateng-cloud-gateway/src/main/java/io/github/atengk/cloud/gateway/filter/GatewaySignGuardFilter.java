package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开放接口 API 签名防篡改与 Nonce 防重放全局过滤器
 *
 * <p>核心治理语义：
 * 1. 严格校验必需标头：{@code X-App-Id}、{@code X-Timestamp}、{@code X-Nonce} 与 {@code X-Signature}；
 * 2. 时间戳时钟防偏：校验时间戳与当前系统时间差，超过阈值（默认 5 分钟）判定为过期；
 * 3. Nonce 防重放攻击：在时间窗口内检验 Nonce 唯一性，拒绝重复 Nonce 请求；
 * 4. HMAC-SHA256 签名校验：规范化请求参数与标头，比对摘要防止请求被中间人篡改。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewaySignGuardFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewaySignGuardFilter.class);

    private final AtengGatewaySignProperties properties;
    private final SignSecretProvider secretProvider;
    private final ObjectMapper objectMapper;

    /**
     * 内存滑动窗口 Nonce 缓存 (Key: Nonce, Value: 接收时间戳)
     */
    private final Map<String, Long> nonceCache = new ConcurrentHashMap<>();

    public GatewaySignGuardFilter(AtengGatewaySignProperties properties,
                                  SignSecretProvider secretProvider,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.secretProvider = secretProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = exchange.getRequest().getHeaders();

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

        // 3. Nonce 防重放校验
        cleanExpiredNonces(now, allowedDeviation);
        Long previous = nonceCache.putIfAbsent(nonce.trim(), now);
        if (previous != null) {
            log.warn("[GatewaySign] 检测到重复 Nonce 请求，疑似重放攻击: nonce={}", nonce);
            return writeJsonError(exchange, "请求随机数 Nonce 重复，疑似重放攻击");
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

        // 5. 规范化参数构建规范签名串
        String canonicalString = buildCanonicalString(exchange, appId.trim(), timestampStr.trim(), nonce.trim());

        // 6. 计算并比对 HMAC-SHA256 签名 (常量时间比较防时序侧信道攻击)
        if (!verifySignature(canonicalString, secret, signature.trim())) {
            log.warn("[GatewaySign] 签名比对失败: appId={}, signature={}", appId, signature);
            return writeJsonError(exchange, "API 签名校验失败，请求可能被篡改");
        }

        return chain.filter(exchange);
    }

    /**
     * 构建规范签名字符串 (appId + nonce + timestamp + 排序后的 query 参数)
     */
    public String buildCanonicalString(ServerWebExchange exchange, String appId, String timestamp, String nonce) {
        StringBuilder sb = new StringBuilder();
        sb.append("appId=").append(appId)
                .append("&nonce=").append(nonce)
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
     * 定期清洗过期 Nonce 记录，防止内存无界膨胀
     */
    private void cleanExpiredNonces(long now, long allowedDeviation) {
        if (nonceCache.size() > 5000) {
            nonceCache.entrySet().removeIf(entry -> now - entry.getValue() > allowedDeviation * 2);
        }
    }

    /**
     * 统一写出 HTTP 401 签名错误响应
     */
    private Mono<Void> writeJsonError(ServerWebExchange exchange, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String traceId = (String) exchange.getAttributes().get(GatewayConstants.ATTR_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            response.getHeaders().set(GatewayConstants.X_TRACE_ID, traceId);
        }

        Result<Void> result = Result.fail(401, msg);
        result.setTraceId(traceId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":401,\"msg\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_SA_TOKEN - 1;
    }
}
