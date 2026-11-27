package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.crypto.cipher.RsaAesGcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.cipher.Sm2Sm4GcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoResponseKeyMode;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCryptoContext;
import io.github.atengk.cloud.gateway.crypto.model.GatewayEncryptedPayload;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties;
import io.github.atengk.cloud.gateway.spi.GatewayApiCipher;
import io.github.atengk.cloud.gateway.spi.GatewayCryptoKeyProvider;
import io.github.atengk.cloud.gateway.util.GatewayResponseUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 边缘 API 响应式混合加解密防窃听全局过滤器
 *
 * <p>核心治理语义：
 * <ul>
 *     <li><b>Encrypt-then-Sign 秩序</b>：固定 {@code ORDER_CRYPTO = -95}，严格置于外层
 *     {@code GatewaySignGuardFilter (-101)} 与 {@code SaTokenGatewayFilter (-100)} 之后，
 *     保证入站“先验密文签名、鉴权，后解密”、出站“先加密、后回签”，并彻底隔离共享缓存体；</li>
 *     <li><b>双套件混合密码与 AAD 绑定</b>：支持 {@code RSA_AES_GCM} 与 {@code SM2_SM4_GCM}，可选将
 *     {@code appId|keyId|algorithm} 绑定为 AEAD 关联认证数据（AAD）抵御跨应用/跨版本重放替换；</li>
 *     <li><b>双响应密钥模式</b>：支持 {@code CLIENT_PUBLIC_KEY}（静态客户端公钥）与 {@code REQUEST_SESSION_KEY}
 *     （复用单次请求解封会话密钥并生成新随机 12B IV 加密响应，支持无体 GET 握手）；</li>
 *     <li><b>确定性堆外内存与密钥擦除</b>：所有加解密缓冲流均通过 {@code DataBufferUtils.join} 上限拦截与即时释放，
 *     并在整条响应式链结束的 {@code doFinally} 中物理清零临时会话密钥字节数组。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class GatewayCryptoFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayCryptoFilter.class);

    /**
     * 默认单个加解密请求/响应体内存容量上限（2MB 防堆外 OOM）
     */
    public static final long MAX_BODY_SIZE = 2 * 1024 * 1024L;

    private final AtengGatewayCryptoProperties properties;
    private final GatewayCryptoKeyProvider keyProvider;
    private final List<GatewayApiCipher> ciphers;
    private final ObjectMapper objectMapper;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public GatewayCryptoFilter(AtengGatewayCryptoProperties properties,
                               GatewayCryptoKeyProvider keyProvider,
                               ObjectMapper objectMapper) {
        this(properties, keyProvider, defaultBuiltInCiphers(), objectMapper);
    }

    public GatewayCryptoFilter(AtengGatewayCryptoProperties properties,
                               GatewayCryptoKeyProvider keyProvider,
                               List<GatewayApiCipher> ciphers,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.keyProvider = keyProvider;
        this.ciphers = (ciphers != null && !ciphers.isEmpty()) ? List.copyOf(ciphers) : defaultBuiltInCiphers();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    private static List<GatewayApiCipher> defaultBuiltInCiphers() {
        return List.of(
                new RsaAesGcmGatewayCipher(),
                new Sm2Sm4GcmGatewayCipher()
        );
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (matchesAny(path, properties.getExcludePaths())) {
            return chain.filter(exchange);
        }

        boolean needDecrypt = resolveDirectionEnabled(
                path,
                properties.isDecryptEnabled(),
                properties.getDecryptIncludePaths(),
                properties.getEncryptIncludePaths());
        boolean needEncrypt = resolveDirectionEnabled(
                path,
                properties.isEncryptEnabled(),
                properties.getEncryptIncludePaths(),
                properties.getDecryptIncludePaths());

        if (!needDecrypt && !needEncrypt) {
            return chain.filter(exchange);
        }

        HttpHeaders reqHeaders = exchange.getRequest().getHeaders();
        if (needDecrypt && isBinaryOrStreamingContentType(reqHeaders.getContentType())) {
            needDecrypt = false;
        }
        if (!needDecrypt && !needEncrypt) {
            return chain.filter(exchange);
        }

        CryptoHeaderNames headerNames = CryptoHeaderNames.from(properties);
        String rawAlg = reqHeaders.getFirst(headerNames.encryptAlg());
        GatewayCryptoAlgorithm algorithm = resolveAlgorithm(rawAlg);
        if (algorithm == null) {
            log.warn("[GatewayCrypto] 拦截未知或不支持的加密算法标头: path={}", path);
            return writeJsonError(exchange, HttpStatus.BAD_REQUEST, CommonResultCode.BAD_REQUEST.getCode(),
                    "不支持的加密算法套件");
        }

        GatewayApiCipher cipher = findCipher(algorithm);
        if (cipher == null) {
            return writeJsonError(exchange, HttpStatus.INTERNAL_SERVER_ERROR,
                    CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "未装配支持指定加解密算法的引擎: " + algorithm);
        }

        String rawAppId = reqHeaders.getFirst(headerNames.appId());
        String appId = StringUtils.hasText(rawAppId)
                ? rawAppId.trim()
                : (StringUtils.hasText(properties.getAppId()) ? properties.getAppId().trim() : "default");
        String requestKeyId = trimToNull(reqHeaders.getFirst(headerNames.keyId()));
        String defaultKeyId = StringUtils.hasText(properties.getActiveKeyId())
                ? properties.getActiveKeyId().trim() : "v1";
        String effectiveKeyId = requestKeyId != null ? requestKeyId : defaultKeyId;

        GatewayCryptoResponseKeyMode responseKeyMode = properties.getResponseKeyMode() != null
                ? properties.getResponseKeyMode()
                : GatewayCryptoResponseKeyMode.CLIENT_PUBLIC_KEY;

        GatewayCryptoContext ctx = new GatewayCryptoContext(
                appId, effectiveKeyId, algorithm, responseKeyMode, properties.isBindAad());

        boolean inboundBodyNeeded = needDecrypt && supportsRequestBody(exchange.getRequest().getMethod(), reqHeaders);
        if (inboundBodyNeeded) {
            long contentLength = reqHeaders.getContentLength();
            long maxBodySize = resolveMaxBodySize();
            if (contentLength > maxBodySize) {
                log.warn("[GatewayCrypto] 入站加密请求体超出最大容量限制: contentLength={} > maxBodySize={}",
                        contentLength, maxBodySize);
                return writePayloadTooLarge(exchange);
            }
        }

        AtomicReference<byte[]> sessionKeyHolder = new AtomicReference<>();
        boolean finalNeedEncrypt = needEncrypt;

        Mono<ServerWebExchange> preparedExchangeMono;
        if (!inboundBodyNeeded) {
            preparedExchangeMono = prepareBodylessExchange(exchange, ctx, cipher, headerNames, finalNeedEncrypt, sessionKeyHolder);
        } else {
            preparedExchangeMono = readRequestBodyBytes(exchange.getRequest())
                    .flatMap(rawBytes -> {
                        if (rawBytes.length == 0) {
                            ServerWebExchange emptyBodyExchange = buildMutatedRequestExchange(
                                    exchange, new byte[0], false, headerNames);
                            return prepareBodylessExchange(
                                    emptyBodyExchange, ctx, cipher, headerNames, finalNeedEncrypt, sessionKeyHolder);
                        }
                        return prepareBodyExchange(
                                exchange, ctx, cipher, headerNames, rawBytes, finalNeedEncrypt, sessionKeyHolder);
                    });
        }

        return preparedExchangeMono
                .onErrorResume(DataBufferLimitException.class, ex -> {
                    log.warn("[GatewayCrypto] 入站请求体流超出最大允许限制: {}", ex.getMessage());
                    return writePayloadTooLarge(exchange).then(Mono.empty());
                })
                .onErrorResume(BusinessException.class, ex -> writeBusinessException(exchange, ex).then(Mono.empty()))
                .flatMap(chain::filter)
                .doFinally(signalType -> wipeHolder(sessionKeyHolder));
    }

    private Mono<ServerWebExchange> prepareBodylessExchange(ServerWebExchange exchange,
                                                            GatewayCryptoContext ctx,
                                                            GatewayApiCipher cipher,
                                                            CryptoHeaderNames headerNames,
                                                            boolean needEncrypt,
                                                            AtomicReference<byte[]> sessionKeyHolder) {
        if (!needEncrypt || ctx.responseKeyMode() != GatewayCryptoResponseKeyMode.REQUEST_SESSION_KEY) {
            ServerWebExchange forwardExchange = stripHeadersIfConfigured(exchange, headerNames);
            return Mono.just(wrapResponseIfNeeded(forwardExchange, exchange, ctx, cipher, headerNames, needEncrypt, sessionKeyHolder));
        }

        if (isExplicitStreamingAccept(exchange.getRequest().getHeaders())) {
            ServerWebExchange forwardExchange = stripHeadersIfConfigured(exchange, headerNames);
            return Mono.just(forwardExchange);
        }

        String encKey = trimToNull(exchange.getRequest().getHeaders().getFirst(headerNames.encryptKey()));
        if (encKey == null) {
            if (properties.isRequired()) {
                return Mono.error(new BusinessException(
                        CommonResultCode.BAD_REQUEST,
                        "REQUEST_SESSION_KEY 模式下缺少会话密钥信封标头 (" + headerNames.encryptKey() + ")"));
            }
            return Mono.just(stripHeadersIfConfigured(exchange, headerNames));
        }

        if (keyProvider == null) {
            return Mono.error(new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "未配置网关加解密密钥提供者"));
        }

        return keyProvider.getDecryptPrivateKey(ctx.appId(), ctx.keyId(), ctx.algorithm())
                .switchIfEmpty(Mono.error(new BusinessException(
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "未获取到服务端解密私钥")))
                .map(serverPrivateKey -> {
                    byte[] sessionKey = cipher.unwrapSessionKey(encKey, serverPrivateKey);
                    sessionKeyHolder.set(sessionKey);
                    ServerWebExchange forwardExchange = stripHeadersIfConfigured(exchange, headerNames);
                    return wrapResponseIfNeeded(forwardExchange, exchange, ctx, cipher, headerNames, true, sessionKeyHolder);
                });
    }

    private Mono<ServerWebExchange> prepareBodyExchange(ServerWebExchange exchange,
                                                        GatewayCryptoContext ctx,
                                                        GatewayApiCipher cipher,
                                                        CryptoHeaderNames headerNames,
                                                        byte[] rawBytes,
                                                        boolean needEncrypt,
                                                        AtomicReference<byte[]> sessionKeyHolder) {
        HttpHeaders reqHeaders = exchange.getRequest().getHeaders();
        String encKey = trimToNull(reqHeaders.getFirst(headerNames.encryptKey()));
        String encIv = trimToNull(reqHeaders.getFirst(headerNames.encryptIv()));

        if (encKey == null || encIv == null) {
            if (properties.isRequired()) {
                return Mono.error(new BusinessException(
                        CommonResultCode.BAD_REQUEST,
                        "缺少请求加密信封标头 (" + headerNames.encryptKey() + "/" + headerNames.encryptIv() + ")"));
            }
            // 非强制模式下放行未加密明文，并跳过出站加密防止明文客户端无法解包
            ServerWebExchange passthroughExchange = buildMutatedRequestExchange(
                    exchange, rawBytes, false, headerNames);
            return Mono.just(passthroughExchange);
        }

        byte[] cipherBytes = parseEncryptedPayloadBytes(rawBytes);
        if (keyProvider == null) {
            return Mono.error(new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "未配置网关加解密密钥提供者"));
        }

        return keyProvider.getDecryptPrivateKey(ctx.appId(), ctx.keyId(), ctx.algorithm())
                .switchIfEmpty(Mono.error(new BusinessException(
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "未获取到服务端解密私钥")))
                .map(serverPrivateKey -> {
                    byte[] plainBytes;
                    byte[] sessionKey = cipher.unwrapSessionKey(encKey, serverPrivateKey);
                    boolean keepSessionKey = needEncrypt
                            && ctx.responseKeyMode() == GatewayCryptoResponseKeyMode.REQUEST_SESSION_KEY;
                    if (keepSessionKey) {
                        sessionKeyHolder.set(sessionKey);
                    }
                    try {
                        plainBytes = cipher.decryptWithSessionKey(cipherBytes, sessionKey, encIv, ctx.buildAadBytes());
                    } finally {
                        if (!keepSessionKey) {
                            wipeBytes(sessionKey);
                        }
                    }

                    ServerWebExchange decryptedExchange = buildMutatedRequestExchange(
                            exchange, plainBytes, properties.isStripCryptoHeaders(), headerNames);
                    return wrapResponseIfNeeded(
                            decryptedExchange, exchange, ctx, cipher, headerNames, needEncrypt, sessionKeyHolder);
                });
    }

    private ServerWebExchange wrapResponseIfNeeded(ServerWebExchange forwardExchange,
                                                   ServerWebExchange originalExchange,
                                                   GatewayCryptoContext ctx,
                                                   GatewayApiCipher cipher,
                                                   CryptoHeaderNames headerNames,
                                                   boolean needEncrypt,
                                                   AtomicReference<byte[]> sessionKeyHolder) {
        if (!needEncrypt) {
            return forwardExchange;
        }
        if (ctx.responseKeyMode() == GatewayCryptoResponseKeyMode.REQUEST_SESSION_KEY && sessionKeyHolder.get() == null) {
            return forwardExchange;
        }

        int maxBodyBytes = resolveMaxBodyBytes();
        AtomicBoolean encryptedOnce = new AtomicBoolean(false);
        HttpMethod requestMethod = originalExchange.getRequest().getMethod();
        ServerHttpResponse downstreamResponse = forwardExchange.getResponse();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(downstreamResponse) {
            @Override
            public Mono<Void> setComplete() {
                if (shouldSkipResponseEncrypt(requestMethod, getStatusCode(), getHeaders())
                        || !encryptedOnce.compareAndSet(false, true)) {
                    return super.setComplete();
                }
                return encryptAndWriteResponse(new byte[0]);
            }

            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (shouldSkipResponseEncrypt(requestMethod, getStatusCode(), getHeaders())
                        || !encryptedOnce.compareAndSet(false, true)) {
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
                        .flatMap(this::encryptAndWriteResponse)
                        .onErrorResume(DataBufferLimitException.class, ex -> {
                            log.warn("[GatewayCrypto] 出站响应体超出加密最大允许限制 ({} 字节)", maxBodyBytes);
                            clearCryptoResponseHeaders(getHeaders(), headerNames);
                            return writePayloadTooLarge(originalExchange);
                        })
                        .onErrorResume(BusinessException.class, ex -> {
                            clearCryptoResponseHeaders(getHeaders(), headerNames);
                            return writeBusinessException(originalExchange, ex);
                        });
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                if (shouldSkipResponseEncrypt(requestMethod, getStatusCode(), getHeaders())
                        || encryptedOnce.get()) {
                    return super.writeAndFlushWith(body);
                }
                Flux<? extends DataBuffer> flattened = body != null
                        ? Flux.from(body).flatMapSequential(Flux::from)
                        : Flux.empty();
                return writeWith(flattened);
            }

            private Mono<Void> encryptAndWriteResponse(byte[] plainBytes) {
                return computeResponseCipher(plainBytes, ctx, cipher, sessionKeyHolder)
                        .flatMap(cipherResult -> {
                            byte[] envelopeBytes = serializeEncryptedPayload(cipherResult.cipherBytes());
                            HttpHeaders headers = getHeaders();
                            headers.remove(HttpHeaders.TRANSFER_ENCODING);
                            headers.remove(HttpHeaders.CONTENT_ENCODING);
                            headers.setContentType(MediaType.APPLICATION_JSON);
                            headers.setContentLength(envelopeBytes.length);

                            if (ctx.responseKeyMode() == GatewayCryptoResponseKeyMode.REQUEST_SESSION_KEY) {
                                headers.remove(headerNames.encryptKey());
                            } else if (StringUtils.hasText(cipherResult.encryptedKey())) {
                                headers.set(headerNames.encryptKey(), cipherResult.encryptedKey());
                            }
                            headers.set(headerNames.encryptIv(), cipherResult.iv());
                            headers.set(headerNames.keyId(), ctx.keyId());
                            headers.set(headerNames.encryptAlg(), ctx.algorithm().name());

                            DataBuffer outBuffer = getDelegate().bufferFactory().wrap(envelopeBytes);
                            return getDelegate().writeWith(Mono.just(outBuffer));
                        })
                        .onErrorResume(BusinessException.class, ex -> {
                            clearCryptoResponseHeaders(getHeaders(), headerNames);
                            return writeBusinessException(originalExchange, ex);
                        });
            }
        };

        return forwardExchange.mutate().response(decoratedResponse).build();
    }

    private Mono<GatewayCipherResult> computeResponseCipher(byte[] plainBytes,
                                                            GatewayCryptoContext ctx,
                                                            GatewayApiCipher cipher,
                                                            AtomicReference<byte[]> sessionKeyHolder) {
        byte[] aad = ctx.buildAadBytes();
        if (ctx.responseKeyMode() == GatewayCryptoResponseKeyMode.REQUEST_SESSION_KEY) {
            byte[] sessionKey = sessionKeyHolder.get();
            if (sessionKey == null) {
                return Mono.error(new BusinessException(
                        CommonResultCode.BAD_REQUEST,
                        "REQUEST_SESSION_KEY 模式下不存在有效请求会话密钥"));
            }
            return Mono.fromSupplier(() -> cipher.encryptWithSessionKey(plainBytes, sessionKey, aad));
        }

        if (keyProvider == null) {
            return Mono.error(new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "未配置网关加解密密钥提供者"));
        }
        return keyProvider.getEncryptPublicKey(ctx.appId(), ctx.keyId(), ctx.algorithm())
                .switchIfEmpty(Mono.error(new BusinessException(
                        CommonResultCode.INTERNAL_SERVER_ERROR,
                        "未获取到用于加密响应的客户端公钥")))
                .map(clientPublicKey -> cipher.encrypt(plainBytes, clientPublicKey, aad));
    }

    private byte[] parseEncryptedPayloadBytes(byte[] rawBytes) {
        GatewayEncryptedPayload payload;
        try {
            payload = objectMapper.readValue(rawBytes, GatewayEncryptedPayload.class);
        } catch (Exception ex) {
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "请求密文 JSON 信封解析失败，预期结构 {\"cipherText\":\"...\"}",
                    ex);
        }
        if (payload == null || !StringUtils.hasText(payload.getCipherText())) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求加密载荷缺少 cipherText 字段");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(payload.getCipherText().trim());
            if (decoded.length == 0) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求加密载荷 cipherText 内容为空");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求 cipherText Base64 格式非法", ex);
        }
    }

    private byte[] serializeEncryptedPayload(byte[] cipherBytes) {
        GatewayEncryptedPayload envelope = new GatewayEncryptedPayload(
                Base64.getEncoder().encodeToString(cipherBytes));
        try {
            return objectMapper.writeValueAsBytes(envelope);
        } catch (Exception ex) {
            throw new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "序列化出站密文 JSON 信封失败",
                    ex);
        }
    }

    private Mono<byte[]> readRequestBodyBytes(ServerHttpRequest request) {
        int maxBodyBytes = resolveMaxBodyBytes();
        return DataBufferUtils.join(request.getBody(), maxBodyBytes)
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                .map(joined -> {
                    try {
                        byte[] bytes = new byte[joined.readableByteCount()];
                        joined.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(joined);
                    }
                })
                .defaultIfEmpty(new byte[0]);
    }

    private ServerWebExchange stripHeadersIfConfigured(ServerWebExchange exchange, CryptoHeaderNames headerNames) {
        if (!properties.isStripCryptoHeaders()) {
            return exchange;
        }
        HttpHeaders existing = exchange.getRequest().getHeaders();
        boolean hasCryptoHeader = existing.containsKey(headerNames.encryptKey())
                || existing.containsKey(headerNames.encryptIv())
                || existing.containsKey(headerNames.keyId())
                || existing.containsKey(headerNames.encryptAlg());
        if (!hasCryptoHeader) {
            return exchange;
        }
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> stripCryptoHeaders(headers, headerNames))
                .build();
        return exchange.mutate().request(mutatedRequest).build();
    }

    private ServerWebExchange buildMutatedRequestExchange(ServerWebExchange exchange,
                                                          byte[] forwardBytes,
                                                          boolean stripHeaders,
                                                          CryptoHeaderNames headerNames) {
        ServerHttpRequest originalRequest = exchange.getRequest();
        HttpHeaders mutatedHeaders = new HttpHeaders();
        mutatedHeaders.putAll(originalRequest.getHeaders());
        mutatedHeaders.remove(HttpHeaders.TRANSFER_ENCODING);
        mutatedHeaders.setContentLength(forwardBytes.length);
        if (stripHeaders) {
            stripCryptoHeaders(mutatedHeaders, headerNames);
        }

        ServerHttpRequestDecorator decoratedRequest = new ServerHttpRequestDecorator(originalRequest) {
            @Override
            public HttpHeaders getHeaders() {
                return mutatedHeaders;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                if (forwardBytes.length == 0) {
                    return Flux.empty();
                }
                return Flux.defer(() -> {
                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(forwardBytes);
                    return Flux.just(buffer);
                });
            }
        };
        return exchange.mutate().request(decoratedRequest).build();
    }

    private static void stripCryptoHeaders(HttpHeaders headers, CryptoHeaderNames headerNames) {
        headers.remove(headerNames.encryptKey());
        headers.remove(headerNames.encryptIv());
        headers.remove(headerNames.keyId());
        headers.remove(headerNames.encryptAlg());
    }

    private static void clearCryptoResponseHeaders(HttpHeaders headers, CryptoHeaderNames headerNames) {
        if (headers == null) {
            return;
        }
        headers.remove(headerNames.encryptKey());
        headers.remove(headerNames.encryptIv());
        headers.remove(headerNames.keyId());
        headers.remove(headerNames.encryptAlg());
    }

    private boolean shouldSkipResponseEncrypt(HttpMethod requestMethod, HttpStatusCode statusCode, HttpHeaders headers) {
        if (HttpMethod.HEAD.equals(requestMethod)) {
            return true;
        }
        if (statusCode != null) {
            int statusValue = statusCode.value();
            if (statusValue == HttpStatus.NO_CONTENT.value()
                    || statusValue == HttpStatus.NOT_MODIFIED.value()
                    || statusCode.is1xxInformational()) {
                return true;
            }
            if (statusValue >= 400 && !properties.isEncryptError()) {
                return true;
            }
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

    private static boolean isExplicitStreamingAccept(HttpHeaders headers) {
        if (headers == null || headers.getAccept().isEmpty()) {
            return false;
        }
        boolean hasMedia = false;
        for (MediaType mt : headers.getAccept()) {
            if (mt == null || "*".equals(mt.getType())) {
                continue;
            }
            hasMedia = true;
            String type = mt.getType().toLowerCase(Locale.ROOT);
            String subtype = mt.getSubtype().toLowerCase(Locale.ROOT);
            boolean streaming = ("text".equals(type) && "event-stream".equals(subtype))
                    || subtype.contains("octet-stream");
            if (!streaming) {
                return false;
            }
        }
        return hasMedia;
    }

    private boolean resolveDirectionEnabled(String path,
                                            boolean switchEnabled,
                                            List<String> directionIncludes,
                                            List<String> oppositeDirectionIncludes) {
        if (!switchEnabled) {
            return false;
        }
        if (directionIncludes != null && !directionIncludes.isEmpty()) {
            return matchesAny(path, directionIncludes);
        }
        List<String> commonIncludes = properties.getIncludePaths();
        if (commonIncludes != null && !commonIncludes.isEmpty()) {
            return matchesAny(path, commonIncludes);
        }
        return oppositeDirectionIncludes == null || oppositeDirectionIncludes.isEmpty();
    }

    private boolean matchesAny(String path, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            if (StringUtils.hasText(pattern) && pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private GatewayCryptoAlgorithm resolveAlgorithm(String rawHeaderAlg) {
        if (StringUtils.hasText(rawHeaderAlg)) {
            return GatewayCryptoAlgorithm.fromCode(rawHeaderAlg);
        }
        return properties.getAlgorithm() != null
                ? properties.getAlgorithm()
                : GatewayCryptoAlgorithm.RSA_AES_GCM;
    }

    private GatewayApiCipher findCipher(GatewayCryptoAlgorithm algorithm) {
        if (ciphers == null) {
            return null;
        }
        for (GatewayApiCipher cipher : ciphers) {
            if (cipher != null && cipher.supports(algorithm)) {
                return cipher;
            }
        }
        return null;
    }

    private static boolean supportsRequestBody(HttpMethod method, HttpHeaders headers) {
        if (method == null) {
            return headers != null && headers.getContentLength() > 0;
        }
        if (HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.OPTIONS.equals(method)
                || HttpMethod.TRACE.equals(method)) {
            return false;
        }
        return true;
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

    private Mono<Void> writePayloadTooLarge(ServerWebExchange exchange) {
        return writeJsonError(
                exchange,
                HttpStatus.PAYLOAD_TOO_LARGE,
                CommonResultCode.PAYLOAD_TOO_LARGE.getCode(),
                "加解密报文超出最大允许限制 (" + formatSizeLabel(resolveMaxBodySize()) + ")");
    }

    private static String formatSizeLabel(long bytes) {
        if (bytes >= 1024L * 1024L && bytes % (1024L * 1024L) == 0) {
            return (bytes / (1024L * 1024L)) + "MB";
        }
        if (bytes >= 1024L && bytes % 1024L == 0) {
            return (bytes / 1024L) + "KB";
        }
        return bytes + "B";
    }

    private Mono<Void> writeBusinessException(ServerWebExchange exchange, BusinessException ex) {
        int code = ex.getCode() != null ? ex.getCode() : CommonResultCode.INTERNAL_SERVER_ERROR.getCode();
        HttpStatus status = HttpStatus.resolve(code);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error("[GatewayCrypto] 边缘加解密服务端异常: code={}, msg={}", code, ex.getMessage(), ex);
        } else {
            log.warn("[GatewayCrypto] 边缘加解密客户端拦截: code={}, msg={}", code, ex.getMessage());
        }
        return writeJsonError(exchange, status, code, ex.getMessage());
    }

    private Mono<Void> writeJsonError(ServerWebExchange exchange, HttpStatus status, int code, String msg) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        return GatewayResponseUtils.writeJsonError(exchange, objectMapper, status, code, msg);
    }

    private static void wipeHolder(AtomicReference<byte[]> holder) {
        if (holder != null) {
            byte[] secret = holder.getAndSet(null);
            wipeBytes(secret);
        }
    }

    private static void wipeBytes(byte[] bytes) {
        if (bytes != null && bytes.length > 0) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_CRYPTO;
    }

    private record CryptoHeaderNames(
            String appId,
            String encryptKey,
            String encryptIv,
            String keyId,
            String encryptAlg
    ) {
        private static CryptoHeaderNames from(AtengGatewayCryptoProperties props) {
            return new CryptoHeaderNames(
                    resolve(props.getHeaderAppId(), GatewayConstants.X_APP_ID),
                    resolve(props.getHeaderEncryptKey(), GatewayConstants.X_ENCRYPT_KEY),
                    resolve(props.getHeaderEncryptIv(), GatewayConstants.X_ENCRYPT_IV),
                    resolve(props.getHeaderKeyId(), GatewayConstants.X_KEY_ID),
                    resolve(props.getHeaderEncryptAlg(), GatewayConstants.X_ENCRYPT_ALG)
            );
        }

        private static String resolve(String configured, String defaultName) {
            return StringUtils.hasText(configured) ? configured.trim() : defaultName;
        }
    }
}
