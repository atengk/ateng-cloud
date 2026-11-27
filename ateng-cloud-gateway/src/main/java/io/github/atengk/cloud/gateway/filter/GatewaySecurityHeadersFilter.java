package io.github.atengk.cloud.gateway.filter;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityHeadersProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OWASP 等保三级合规安全响应头强化与跨域出站标头去重响应式 Web 过滤器
 *
 * <p>核心治理语义：
 * 1. 响应出站自动注入 X-Content-Type-Options、X-Frame-Options、X-XSS-Protection、HSTS 与 Referrer-Policy；
 * 2. 自动对 Access-Control-Allow-Origin 与 Access-Control-Allow-Credentials 执行去重，保留首项以杜绝浏览器 CORS 冲突阻断；
 * 3. 在跨域请求生效时自动将已写出的签名/加密响应头（如 {@code X-Signature}、{@code X-Encrypt-Key} 等）增量合并至
 *    {@code Access-Control-Expose-Headers}，确保前端浏览器 JS 可直接读取密码信封标头。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GatewaySecurityHeadersFilter implements WebFilter, Ordered {

    private final AtengGatewaySecurityHeadersProperties properties;
    private final AtengGatewaySignProperties signProperties;
    private final AtengGatewayCryptoProperties cryptoProperties;

    public GatewaySecurityHeadersFilter(AtengGatewaySecurityHeadersProperties properties) {
        this(properties, null, null);
    }

    public GatewaySecurityHeadersFilter(AtengGatewaySecurityHeadersProperties properties,
                                        AtengGatewaySignProperties signProperties,
                                        AtengGatewayCryptoProperties cryptoProperties) {
        this.properties = properties;
        this.signProperties = signProperties;
        this.cryptoProperties = cryptoProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (properties == null || !properties.isEnabled()) {
            return chain.filter(exchange);
        }

        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();

            // 1. 防 MIME 嗅探
            if (StringUtils.hasText(properties.getContentTypeOptions()) && !headers.containsKey("X-Content-Type-Options")) {
                headers.set("X-Content-Type-Options", properties.getContentTypeOptions());
            }

            // 2. 防点击劫持
            if (StringUtils.hasText(properties.getFrameOptions()) && !headers.containsKey("X-Frame-Options")) {
                headers.set("X-Frame-Options", properties.getFrameOptions());
            }

            // 3. 浏览器 XSS 过滤器
            if (StringUtils.hasText(properties.getXssProtection()) && !headers.containsKey("X-XSS-Protection")) {
                headers.set("X-XSS-Protection", properties.getXssProtection());
            }

            // 4. 强制传输安全 HSTS
            if (StringUtils.hasText(properties.getHsts()) && !headers.containsKey("Strict-Transport-Security")) {
                headers.set("Strict-Transport-Security", properties.getHsts());
            }

            // 5. 来源跳转安全策略
            if (StringUtils.hasText(properties.getReferrerPolicy()) && !headers.containsKey("Referrer-Policy")) {
                headers.set("Referrer-Policy", properties.getReferrerPolicy());
            }

            // 6. 出站 CORS 标头去重 (保留首个，杜绝浏览器跨域冲突报错)
            List<String> origins = headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
            if (origins != null && origins.size() > 1) {
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origins.get(0));
            }
            List<String> credentials = headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
            if (credentials != null && credentials.size() > 1) {
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, credentials.get(0));
            }

            // 7. 跨域生效时自动向 Access-Control-Expose-Headers 增量暴露签名与加解密响应头
            appendSecurityExposeHeadersIfCorsActive(headers);

            return Mono.empty();
        });

        return chain.filter(exchange);
    }

    private void appendSecurityExposeHeadersIfCorsActive(HttpHeaders headers) {
        String allowOrigin = headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        if (!StringUtils.hasText(allowOrigin)) {
            return;
        }

        Map<String, String> merged = new LinkedHashMap<>();
        List<String> existingExpose = headers.get(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        if (existingExpose != null) {
            for (String headerLine : existingExpose) {
                if (!StringUtils.hasText(headerLine)) {
                    continue;
                }
                for (String token : headerLine.split(",")) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty()) {
                        merged.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
                    }
                }
            }
        }
        if (merged.containsKey("*")) {
            return;
        }

        int initialSize = merged.size();
        for (String candidate : resolveSecurityExposeHeaderCandidates()) {
            if (StringUtils.hasText(candidate) && headers.containsKey(candidate)) {
                merged.putIfAbsent(candidate.toLowerCase(Locale.ROOT), candidate);
            }
        }
        if (merged.size() > initialSize) {
            headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(", ", merged.values()));
        }
    }

    private List<String> resolveSecurityExposeHeaderCandidates() {
        String signTs = signProperties != null && StringUtils.hasText(signProperties.getHeaderTimestamp())
                ? signProperties.getHeaderTimestamp().trim() : GatewayConstants.X_TIMESTAMP;
        String signNonce = signProperties != null && StringUtils.hasText(signProperties.getHeaderNonce())
                ? signProperties.getHeaderNonce().trim() : GatewayConstants.X_NONCE;
        String signSig = signProperties != null && StringUtils.hasText(signProperties.getHeaderSignature())
                ? signProperties.getHeaderSignature().trim() : GatewayConstants.X_SIGNATURE;

        String encKey = cryptoProperties != null && StringUtils.hasText(cryptoProperties.getHeaderEncryptKey())
                ? cryptoProperties.getHeaderEncryptKey().trim() : GatewayConstants.X_ENCRYPT_KEY;
        String encIv = cryptoProperties != null && StringUtils.hasText(cryptoProperties.getHeaderEncryptIv())
                ? cryptoProperties.getHeaderEncryptIv().trim() : GatewayConstants.X_ENCRYPT_IV;
        String encKeyId = cryptoProperties != null && StringUtils.hasText(cryptoProperties.getHeaderKeyId())
                ? cryptoProperties.getHeaderKeyId().trim() : GatewayConstants.X_KEY_ID;
        String encAlg = cryptoProperties != null && StringUtils.hasText(cryptoProperties.getHeaderEncryptAlg())
                ? cryptoProperties.getHeaderEncryptAlg().trim() : GatewayConstants.X_ENCRYPT_ALG;

        return List.of(signTs, signNonce, signSig, encKey, encIv, encKeyId, encAlg);
    }

    @Override
    public int getOrder() {
        return GatewayConstants.ORDER_SECURITY_HEADERS;
    }
}
