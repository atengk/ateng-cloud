package io.github.atengk.cloud.sentinel.parser;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Sentinel 多级请求调用源解析器
 *
 * <p>微服务调用溯源与黑白名单授权防卫：
 * <ul>
 *     <li><b>微服务调用标头优先</b>：优先读取配置指定的微服务身份标头（默认 {@code X-Origin-Service}）；</li>
 *     <li><b>网关来源标头二次回溯</b>：读取网关注入的受信调用源标头 {@code X-Gateway-Caller}；</li>
 *     <li><b>真实客户端 IP 兜底溯源</b>：解析 {@code X-Forwarded-For} / {@code X-Real-IP} 提取发起方 IP；</li>
 *     <li><b>保底默认源</b>：无任何来源特征时回退为 {@code default}。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class AtengRequestOriginParser implements RequestOriginParser {

    private static final String DEFAULT_ORIGIN = "default";
    private static final String GATEWAY_CALLER_HEADER = "X-Gateway-Caller";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";

    private final AtengSentinelProperties properties;

    public AtengRequestOriginParser(AtengSentinelProperties properties) {
        this.properties = properties != null ? properties : new AtengSentinelProperties();
    }

    @Override
    public String parseOrigin(HttpServletRequest request) {
        if (request == null) {
            return DEFAULT_ORIGIN;
        }

        // 1. 优先读取微服务出站注入的服务名标头 (默认 X-Origin-Service)
        String headerName = properties.getOriginParser().getHeaderName();
        if (StringUtils.hasText(headerName)) {
            String originService = request.getHeader(headerName);
            if (StringUtils.hasText(originService)) {
                return originService.trim();
            }
        }

        // 2. 读取网关注入的调用方标头
        String gatewayCaller = request.getHeader(GATEWAY_CALLER_HEADER);
        if (StringUtils.hasText(gatewayCaller)) {
            return gatewayCaller.trim();
        }

        // 3. 提取真实客户端 IP
        String clientIp = resolveClientIp(request);
        if (StringUtils.hasText(clientIp)) {
            return clientIp;
        }

        // 4. 兜底默认来源
        return DEFAULT_ORIGIN;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String ip = request.getHeader(X_FORWARDED_FOR);
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            int index = ip.indexOf(',');
            return index != -1 ? ip.substring(0, index).trim() : ip.trim();
        }
        ip = request.getHeader(X_REAL_IP);
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddr) ? remoteAddr.trim() : null;
    }
}
