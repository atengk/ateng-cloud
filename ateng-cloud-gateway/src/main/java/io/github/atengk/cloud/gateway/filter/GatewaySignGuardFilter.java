package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.provider.InMemoryGatewayNonceValidator;
import io.github.atengk.cloud.gateway.provider.LegacyGatewayNonceValidatorAdapter;
import io.github.atengk.cloud.gateway.provider.PropertiesGatewaySignKeyProvider;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignCanonicalMode;
import io.github.atengk.cloud.gateway.sign.signer.HmacSha256GatewaySigner;
import io.github.atengk.cloud.gateway.sign.signer.RsaGatewaySigner;
import io.github.atengk.cloud.gateway.sign.signer.Sm2GatewaySigner;
import io.github.atengk.cloud.gateway.sign.util.GatewaySignCanonicalUtil;
import io.github.atengk.cloud.gateway.spi.GatewayApiSigner;
import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.GatewaySignKeyProvider;
import io.github.atengk.cloud.gateway.spi.ReactiveGatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import io.github.atengk.cloud.gateway.util.GatewayResponseUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.MultiValueMap;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 开放接口 API 签名防篡改与 Nonce 防重放全局过滤器
 *
 * <p>核心治理语义：
 * 1. 严格校验必需标头：{@code X-App-Id}、{@code X-Timestamp}、{@code X-Nonce} 与 {@code X-Signature}（均支持配置自定义名称）；
 * 2. 时间戳时钟防偏：校验时间戳与当前系统时间差，超过阈值（默认 5 分钟）判定为过期；
 * 3. 非阻塞 Nonce 防重放：依托 {@link ReactiveGatewayNonceValidator}（Redis 原子写入或有界内存滑动窗口）拒绝重放请求；
 * 4. 双模规范待签串与多算法验签：支持 {@code LEGACY}（旧版兼容）与 {@code STANDARD}（对齐 {@code ateng-boot-web} 6 行规范串，含 Method 与 URI）
 *    及 {@code HMAC_SHA256}、{@code RSA}、{@code SM2} 多算法 SPI；
 * 5. 可选出站响应加签：开启 {@code signResponse=true} 时对非流式正常响应计算摘要并回写 {@code X-Signature}/{@code X-Timestamp}/{@code X-Nonce}，
 *    与内层 {@code GatewayCryptoFilter} 协同构成物理级 Encrypt-then-Sign；
 * 6. 堆外内存确定性释放：请求体或响应体验签失败、超限时主动释放 Netty PooledDataBuffer，杜绝堆外内存泄漏。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewaySignGuardFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewaySignGuardFilter.class);

    /**
     * 单个请求/响应体最大允许内存阈值 (2MB 载荷硬防爆)
     */
    public static final long MAX_BODY_SIZE = 2 * 1024 * 1024L;

    private final AtengGatewaySignProperties properties;
    private final GatewaySignKeyProvider keyProvider;
    private final ReactiveGatewayNonceValidator reactiveNonceValidator;
    private final List<GatewayApiSigner> signers;
    private final ObjectMapper objectMapper;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public GatewaySignGuardFilter(AtengGatewaySignProperties properties,
                                  SignSecretProvider secretProvider,
                                  ObjectMapper objectMapper) {
        this(properties, secretProvider,
                new InMemoryGatewayNonceValidator(
                        properties != null
                                ? properties.getNonceMaxCapacity()
                                : InMemoryGatewayNonceValidator.DEFAULT_MAX_CAPACITY),
                objectMapper);
    }

    public GatewaySignGuardFilter(AtengGatewaySignProperties properties,
                                  SignSecretProvider secretProvider,
                                  GatewayNonceValidator nonceValidator,
                                  ObjectMapper objectMapper) {
        this(properties,
                new PropertiesGatewaySignKeyProvider(properties, secretProvider),
                nonceValidator != null ? new LegacyGatewayNonceValidatorAdapter(nonceValidator) : null,
                defaultBuiltInSigners(),
                objectMapper);
    }

    public GatewaySignGuardFilter(AtengGatewaySignProperties properties,
                                  GatewaySignKeyProvider keyProvider,
                                  ReactiveGatewayNonceValidator reactiveNonceValidator,
                                  List<GatewayApiSigner> signers,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.keyProvider = keyProvider;
        this.reactiveNonceValidator = reactiveNonceValidator;
        this.signers = (signers != null && !signers.isEmpty()) ? List.copyOf(signers) : defaultBuiltInSigners();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    private static List<GatewayApiSigner> defaultBuiltInSigners() {
        return List.of(
                new HmacSha256GatewaySigner(),
                new RsaGatewaySigner(),
                new Sm2GatewaySigner()
        );
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

        GatewaySignCanonicalMode canonicalMode = properties.getCanonicalMode() != null
                ? properties.getCanonicalMode()
                : GatewaySignCanonicalMode.LEGACY;
        GatewaySignAlgorithm algorithm = properties.getAlgorithm() != null
                ? properties.getAlgorithm()
                : GatewaySignAlgorithm.HMAC_SHA256;

        String headerAppId = resolveHeaderName(properties.getHeaderAppId(), GatewayConstants.X_APP_ID);
        String headerTimestamp = resolveHeaderName(properties.getHeaderTimestamp(), GatewayConstants.X_TIMESTAMP);
        String headerNonce = resolveHeaderName(properties.getHeaderNonce(), GatewayConstants.X_NONCE);
        String headerSignature = resolveHeaderName(properties.getHeaderSignature(), GatewayConstants.X_SIGNATURE);

        HttpHeaders headers = exchange.getRequest().getHeaders();
        boolean inboundBodyNeeded = isInboundBodyCheckNeeded(exchange, canonicalMode);

        // 前置检查：若需请求体加签，前置快速防御超大报文 (默认 2MB 载荷硬防爆)
        if (inboundBodyNeeded) {
            long contentLength = headers.getContentLength();
            long maxBodySize = resolveMaxBodySize();
            if (contentLength > maxBodySize) {
                log.warn("[GatewaySign] 请求体超出最大允许加签限制: contentLength={} > maxBodySize={}", contentLength, maxBodySize);
                return writePayloadTooLarge(exchange);
            }
        }

        // 1. 提取签名相关核心标头
        String rawAppId = headers.getFirst(headerAppId);
        String appId = StringUtils.hasText(rawAppId)
                ? rawAppId.trim()
                : (StringUtils.hasText(properties.getAppId()) ? properties.getAppId().trim() : null);
        String timestampStr = headers.getFirst(headerTimestamp);
        String nonce = headers.getFirst(headerNonce);
        String signature = headers.getFirst(headerSignature);

        if (!StringUtils.hasText(appId)) {
            return writeJsonError(exchange, "缺少签名 AppId 标头 (" + headerAppId + ")");
        }
        if (!StringUtils.hasText(timestampStr)) {
            return writeJsonError(exchange, "缺少签名时间戳标头 (" + headerTimestamp + ")");
        }
        if (!StringUtils.hasText(nonce)) {
            return writeJsonError(exchange, "缺少签名随机数标头 (" + headerNonce + ")");
        }
        if (!StringUtils.hasText(signature)) {
            return writeJsonError(exchange, "缺少签名摘要标头 (" + headerSignature + ")");
        }

        String trimmedTimestamp = timestampStr.trim();
        String trimmedNonce = nonce.trim();
        String trimmedSignature = signature.trim();

        // 2. 时间戳格式与时钟防偏防过期校验
        long timestamp;
        try {
            timestamp = Long.parseLong(trimmedTimestamp);
            if (trimmedTimestamp.length() == 10) {
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

        GatewayApiSigner signer = findSigner(algorithm);
        if (signer == null) {
            return writeJsonError(exchange, HttpStatus.INTERNAL_SERVER_ERROR, 500,
                    "未装配支持指定签名算法的引擎: " + algorithm);
        }

        SignRequestContext ctx = new SignRequestContext(
                appId, trimmedTimestamp, trimmedNonce, trimmedSignature,
                canonicalMode, algorithm, signer, inboundBodyNeeded,
                headerTimestamp, headerNonce, headerSignature
        );

        // 3. 非阻塞响应式 Nonce 防重放校验 + 4. 非阻塞获取验签密钥
        Mono<Boolean> nonceMono = reactiveNonceValidator != null
                ? reactiveNonceValidator.validateAndStore(appId, trimmedNonce, properties.getTimestampThreshold())
                : Mono.just(true);

        return nonceMono
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(validNonce -> {
                    if (!Boolean.TRUE.equals(validNonce)) {
                        log.warn("[GatewaySign] 检测到重复 Nonce 请求，疑似重放攻击: appId={}, nonce={}", appId, trimmedNonce);
                        return writeJsonError(exchange, "请求随机数 Nonce 重复，疑似重放攻击");
                    }
                    if (keyProvider == null) {
                        return writeJsonError(exchange, "网关未配置签名密钥提供者");
                    }
                    return keyProvider.getVerifyKey(appId, algorithm)
                            .defaultIfEmpty("")
                            .flatMap(verifyKey -> {
                                if (!StringUtils.hasText(verifyKey)) {
                                    log.warn("[GatewaySign] 无效的 AppKey 或密钥不存在: appId={}, algorithm={}", appId, algorithm);
                                    return writeJsonError(exchange, "无效的 AppKey 或密钥不存在");
                                }
                                return verifyAndForward(exchange, chain, ctx, verifyKey);
                            });
                })
                .onErrorResume(BusinessException.class, ex -> writeBusinessException(exchange, ex));
    }

    private Mono<Void> verifyAndForward(ServerWebExchange exchange,
                                        GatewayFilterChain chain,
                                        SignRequestContext ctx,
                                        String verifyKey) {
        if (!ctx.inboundBodyNeeded()) {
            String canonicalString = buildCanonicalByMode(exchange, ctx, null);
            if (!ctx.signer().verify(canonicalString, ctx.signature(), verifyKey)) {
                log.warn("[GatewaySign] 签名比对失败: appId={}, algorithm={}, mode={}",
                        ctx.appId(), ctx.algorithm(), ctx.canonicalMode());
                return writeJsonError(exchange, "API 签名校验失败，请求可能被篡改");
            }
            return chain.filter(decorateExchangeIfResponseSign(exchange, ctx));
        }

        // 响应式缓存请求体并计算 SHA-256 摘要加签
        return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange, cachedRequest -> {
            DataBuffer dataBuffer = exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR);
            String bodyHash = null;
            if (dataBuffer != null && dataBuffer.readableByteCount() > 0) {
                long maxBodySize = resolveMaxBodySize();
                if (dataBuffer.readableByteCount() > maxBodySize) {
                    log.warn("[GatewaySign] 缓存请求体超出最大允许限制: size={} > maxBodySize={}",
                            dataBuffer.readableByteCount(), maxBodySize);
                    ServerWebExchangeUtils.clearCachedRequestBody(exchange);
                    return writePayloadTooLarge(exchange);
                }
                try {
                    bodyHash = computeSha256Hex(dataBuffer);
                } catch (Exception e) {
                    log.error("[GatewaySign] 计算请求体 SHA-256 摘要失败: {}", e.getMessage(), e);
                    ServerWebExchangeUtils.clearCachedRequestBody(exchange);
                    return writeJsonError(exchange, "计算请求体摘要失败");
                }
            }

            String canonicalString = buildCanonicalByMode(exchange, ctx, bodyHash);
            if (!ctx.signer().verify(canonicalString, ctx.signature(), verifyKey)) {
                log.warn("[GatewaySign] 签名比对失败: appId={}, algorithm={}, mode={}",
                        ctx.appId(), ctx.algorithm(), ctx.canonicalMode());
                ServerWebExchangeUtils.clearCachedRequestBody(exchange);
                return writeJsonError(exchange, "API 签名校验失败，请求可能被篡改");
            }

            ServerWebExchange mutatedExchange = exchange.mutate().request(cachedRequest).build();
            return chain.filter(decorateExchangeIfResponseSign(mutatedExchange, ctx))
                    .doFinally(signalType -> ServerWebExchangeUtils.clearCachedRequestBody(exchange));
        }).onErrorResume(DataBufferLimitException.class, ex -> {
            log.warn("[GatewaySign] 请求体流超出最大允许限制: {}", ex.getMessage());
            ServerWebExchangeUtils.clearCachedRequestBody(exchange);
            return writePayloadTooLarge(exchange);
        });
    }

    private ServerWebExchange decorateExchangeIfResponseSign(ServerWebExchange exchange, SignRequestContext ctx) {
        if (!properties.isSignResponse()) {
            return exchange;
        }
        int maxBodyBytes = resolveMaxBodyBytes();
        AtomicBoolean signedOnce = new AtomicBoolean(false);
        ServerHttpResponse originalResponse = exchange.getResponse();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> setComplete() {
                if (shouldSkipResponseSign(getStatusCode(), getHeaders()) || !signedOnce.compareAndSet(false, true)) {
                    return super.setComplete();
                }
                return computeResponseSignHeaders(this, new byte[0], ctx)
                        .then(Mono.defer(super::setComplete))
                        .onErrorResume(BusinessException.class, ex -> {
                            clearSignHeaders(getHeaders(), ctx);
                            return writeBusinessException(exchange, ex);
                        });
            }

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (shouldSkipResponseSign(getStatusCode(), getHeaders()) || !signedOnce.compareAndSet(false, true)) {
                    return super.writeWith(body);
                }
                Flux<? extends DataBuffer> bodyFlux = body != null ? Flux.from(body) : Flux.empty();
                return DataBufferUtils.join(bodyFlux, maxBodyBytes)
                        .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                        .map(joinedBuffer -> {
                            try {
                                byte[] bytes = new byte[joinedBuffer.readableByteCount()];
                                joinedBuffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(joinedBuffer);
                            }
                        })
                        .defaultIfEmpty(new byte[0])
                        .flatMap(bytes -> computeResponseSignHeaders(this, bytes, ctx)
                                .then(Mono.defer(() -> {
                                    if (bytes.length == 0) {
                                        getHeaders().setContentLength(0);
                                        getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
                                        return getDelegate().setComplete();
                                    }
                                    getHeaders().setContentLength(bytes.length);
                                    getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
                                    DataBuffer outBuffer = getDelegate().bufferFactory().wrap(bytes);
                                    return getDelegate().writeWith(Mono.just(outBuffer));
                                })))
                        .onErrorResume(DataBufferLimitException.class, ex -> {
                            log.warn("[GatewaySign] 出站响应体超过加签最大允许限制 ({} 字节)", maxBodyBytes);
                            clearSignHeaders(getHeaders(), ctx);
                            return writePayloadTooLarge(exchange);
                        })
                        .onErrorResume(BusinessException.class, ex -> {
                            clearSignHeaders(getHeaders(), ctx);
                            return writeBusinessException(exchange, ex);
                        });
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                if (shouldSkipResponseSign(getStatusCode(), getHeaders())) {
                    return super.writeAndFlushWith(body);
                }
                Flux<? extends DataBuffer> flattened = body != null
                        ? Flux.from(body).flatMapSequential(Flux::from)
                        : Flux.empty();
                return writeWith(flattened);
            }
        };

        return exchange.mutate().response(decoratedResponse).build();
    }

    private Mono<Void> computeResponseSignHeaders(ServerHttpResponseDecorator response,
                                                  byte[] bodyBytes,
                                                  SignRequestContext ctx) {
        if (keyProvider == null) {
            return Mono.error(new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置签名密钥提供者，无法执行响应加签"));
        }
        return keyProvider.getSignKey(ctx.appId(), ctx.algorithm())
                .defaultIfEmpty("")
                .flatMap(signKey -> {
                    if (!StringUtils.hasText(signKey)) {
                        return Mono.error(new BusinessException(
                                CommonResultCode.INTERNAL_SERVER_ERROR,
                                "未配置对应应用的出站加签密钥: " + ctx.appId()));
                    }
                    String respTimestamp = StringUtils.hasText(ctx.timestamp())
                            ? ctx.timestamp()
                            : String.valueOf(System.currentTimeMillis());
                    String respNonce = StringUtils.hasText(ctx.nonce())
                            ? ctx.nonce()
                            : IdUtil.fastSimpleUUID();
                    String bodySha256 = DigestUtil.sha256Hex(bodyBytes != null ? bodyBytes : new byte[0]);
                    String responseCanonical = GatewaySignCanonicalUtil.buildResponseCanonicalString(
                            respTimestamp, respNonce, bodySha256);

                    String responseSignature;
                    try {
                        responseSignature = ctx.signer().sign(responseCanonical, signKey);
                    } catch (Exception ex) {
                        return Mono.error(new BusinessException(
                                CommonResultCode.INTERNAL_SERVER_ERROR,
                                "出站响应签名计算失败",
                                ex));
                    }
                    if (!StringUtils.hasText(responseSignature)) {
                        return Mono.error(new BusinessException(
                                CommonResultCode.INTERNAL_SERVER_ERROR,
                                "出站响应签名计算结果为空"));
                    }

                    HttpHeaders headers = response.getHeaders();
                    headers.set(ctx.headerSignature(), responseSignature);
                    headers.set(ctx.headerTimestamp(), respTimestamp);
                    headers.set(ctx.headerNonce(), respNonce);
                    return Mono.empty();
                });
    }

    private boolean shouldSkipResponseSign(HttpStatusCode statusCode, HttpHeaders headers) {
        if (statusCode != null && statusCode.value() >= 400 && !properties.isSignErrorResponse()) {
            return true;
        }
        if (headers == null) {
            return false;
        }
        if (isBinaryOrStreamingContentType(headers.getContentType())) {
            return true;
        }
        String disposition = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        return StringUtils.hasText(disposition)
                && disposition.toLowerCase(Locale.ROOT).contains("attachment");
    }

    private static void clearSignHeaders(HttpHeaders headers, SignRequestContext ctx) {
        if (headers == null) {
            return;
        }
        headers.remove(ctx.headerTimestamp());
        headers.remove(ctx.headerNonce());
        headers.remove(ctx.headerSignature());
    }

    private String buildCanonicalByMode(ServerWebExchange exchange, SignRequestContext ctx, String bodyHash) {
        if (ctx.canonicalMode() == GatewaySignCanonicalMode.STANDARD) {
            HttpMethod method = exchange.getRequest().getMethod();
            String methodName = method != null ? method.name() : "GET";
            String uriPath = exchange.getRequest().getPath().value();
            return GatewaySignCanonicalUtil.buildStandardRequestCanonicalString(
                    methodName, uriPath, ctx.timestamp(), ctx.nonce(),
                    exchange.getRequest().getQueryParams(), bodyHash);
        }
        return buildCanonicalString(exchange, ctx.appId(), ctx.timestamp(), ctx.nonce(), bodyHash);
    }

    private GatewayApiSigner findSigner(GatewaySignAlgorithm algorithm) {
        if (signers == null) {
            return null;
        }
        for (GatewayApiSigner signer : signers) {
            if (signer != null && signer.supports(algorithm)) {
                return signer;
            }
        }
        return null;
    }

    /**
     * 判断当前请求是否需要执行请求体 (Request Body) 加签验签
     */
    private boolean isInboundBodyCheckNeeded(ServerWebExchange exchange, GatewaySignCanonicalMode canonicalMode) {
        if (properties == null) {
            return false;
        }
        HttpMethod method = exchange.getRequest().getMethod();
        boolean isBodyMethod = HttpMethod.POST.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method);
        if (!isBodyMethod) {
            return false;
        }
        if (canonicalMode == GatewaySignCanonicalMode.STANDARD) {
            return !isBinaryOrStreamingContentType(exchange.getRequest().getHeaders().getContentType());
        }
        return properties.isBodyEnabled();
    }

    private static boolean isBinaryOrStreamingContentType(MediaType contentType) {
        if (contentType == null) {
            return false;
        }
        if (MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)
                || MediaType.APPLICATION_OCTET_STREAM.isCompatibleWith(contentType)
                || MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            return true;
        }
        String type = contentType.getType().toLowerCase(Locale.ROOT);
        String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
        return "multipart".equals(type)
                || subtype.contains("octet-stream")
                || subtype.contains("event-stream");
    }

    /**
     * 获取允许加签的最大请求/响应体容量 (字节)
     */
    private long resolveMaxBodySize() {
        if (properties != null && properties.getMaxBodySize() != null) {
            long bytes = properties.getMaxBodySize().toBytes();
            if (bytes > 0) {
                return bytes;
            }
        }
        return MAX_BODY_SIZE;
    }

    private int resolveMaxBodyBytes() {
        return (int) Math.min(resolveMaxBodySize(), Integer.MAX_VALUE);
    }

    private static String resolveHeaderName(String configured, String defaultName) {
        return StringUtils.hasText(configured) ? configured.trim() : defaultName;
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
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256 摘要算法", e);
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
     * 构建兼容模式规范签名字符串 (appId + nonce + timestamp + 排序后的 query 参数)
     */
    public String buildCanonicalString(ServerWebExchange exchange, String appId, String timestamp, String nonce) {
        return buildCanonicalString(exchange, appId, timestamp, nonce, null);
    }

    /**
     * 构建兼容模式规范签名字符串 (按照参数名字典升序排序：appId -> bodyHash -> nonce -> timestamp -> queryParams)
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
        GatewayApiSigner hmacSigner = findSigner(GatewaySignAlgorithm.HMAC_SHA256);
        if (hmacSigner != null) {
            return hmacSigner.verify(canonicalString, actualSignature, secret);
        }
        return new HmacSha256GatewaySigner().verify(canonicalString, actualSignature, secret);
    }

    private Mono<Void> writeJsonError(ServerWebExchange exchange, String msg) {
        return writeJsonError(exchange, HttpStatus.UNAUTHORIZED, 401, msg);
    }

    private Mono<Void> writePayloadTooLarge(ServerWebExchange exchange) {
        return writeJsonError(exchange, HttpStatus.PAYLOAD_TOO_LARGE, 413, "请求报文超出最大允许限制 (2MB)");
    }

    private Mono<Void> writeBusinessException(ServerWebExchange exchange, BusinessException ex) {
        int code = ex.getCode() != null ? ex.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode();
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("[GatewaySign] 签名治理服务端异常: code={}, msg={}", code, ex.getMessage(), ex);
        } else {
            log.warn("[GatewaySign] 签名治理客户端拦截: code={}, msg={}", code, ex.getMessage());
        }
        return writeJsonError(exchange, status, code, ex.getMessage());
    }

    private Mono<Void> writeJsonError(ServerWebExchange exchange, HttpStatus status, int code, String msg) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        return GatewayResponseUtils.writeJsonError(exchange, objectMapper, status, code, msg);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_SIGN_GUARD;
    }

    private record SignRequestContext(
            String appId,
            String timestamp,
            String nonce,
            String signature,
            GatewaySignCanonicalMode canonicalMode,
            GatewaySignAlgorithm algorithm,
            GatewayApiSigner signer,
            boolean inboundBodyNeeded,
            String headerTimestamp,
            String headerNonce,
            String headerSignature
    ) {
    }
}
