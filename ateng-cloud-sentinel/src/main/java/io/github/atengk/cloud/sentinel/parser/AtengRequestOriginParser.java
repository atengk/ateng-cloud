package io.github.atengk.cloud.sentinel.parser;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import io.github.atengk.cloud.sentinel.util.SentinelServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Sentinel 多级请求调用源解析器
 *
 * <p>微服务调用溯源与黑白名单授权防卫：
 * <ul>
 *     <li><b>微服务调用标头优先</b>：优先读取配置指定的微服务身份标头（默认 {@code X-Origin-Service}）；</li>
 *     <li><b>网关来源标头二次回溯</b>：读取网关注入的受信调用源标头（默认 {@code X-Gateway-Caller}）；</li>
 *     <li><b>真实客户端 IP 兜底溯源</b>：若启用 IP 回退，解析 {@code X-Forwarded-For} / {@code X-Real-IP} 提取发起方真实 IP；</li>
 *     <li><b>保底默认源</b>：无任何来源特征时回退为配置的默认源（默认 {@code default}）。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengRequestOriginParser implements RequestOriginParser {

    private final AtengSentinelProperties properties;

    public AtengRequestOriginParser(AtengSentinelProperties properties) {
        this.properties = properties != null ? properties : new AtengSentinelProperties();
    }

    @Override
    public String parseOrigin(HttpServletRequest request) {
        String defaultOrigin = properties.getOriginParser().getDefaultOrigin();
        String fallbackOrigin = StringUtils.hasText(defaultOrigin) ? defaultOrigin.trim() : "default";

        if (request == null) {
            return fallbackOrigin;
        }

        // 1. 优先读取微服务出站注入的服务名标头 (默认 X-Origin-Service)
        String originHeader = properties.getOriginParser().getOriginHeader();
        if (!StringUtils.hasText(originHeader)) {
            originHeader = properties.getOriginParser().getHeaderName();
        }
        if (StringUtils.hasText(originHeader)) {
            String originService = request.getHeader(originHeader.trim());
            if (StringUtils.hasText(originService)) {
                return originService.trim();
            }
        }

        // 2. 读取网关注入的调用方标头 (默认 X-Gateway-Caller)
        String gatewayHeader = properties.getOriginParser().getGatewayHeader();
        if (StringUtils.hasText(gatewayHeader)) {
            String gatewayCaller = request.getHeader(gatewayHeader.trim());
            if (StringUtils.hasText(gatewayCaller)) {
                return gatewayCaller.trim();
            }
        }

        // 3. 提取真实客户端 IP (若启用 IP 回退)
        if (properties.getOriginParser().isEnableClientIpFallback()) {
            String clientIp = SentinelServletUtil.resolveClientIp(request);
            if (StringUtils.hasText(clientIp) && !"unknown".equalsIgnoreCase(clientIp)) {
                return clientIp;
            }
        }

        // 4. 兜底默认来源
        return fallbackOrigin;
    }
}
