package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 边缘安全防御检测响应视图
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class PerimeterSecurityVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 经网关解析后的真实客户端 IP
     */
    private String clientIp;

    /**
     * 当前请求规范化路径
     */
    private String requestPath;

    /**
     * 边缘防御状态
     */
    private String defenseStatus;

    /**
     * 下游接收到的脱敏清洗标头快照
     */
    private Map<String, String> sanitizedHeaders;

    public PerimeterSecurityVO() {
        this.sanitizedHeaders = Collections.emptyMap();
    }

    public PerimeterSecurityVO(String clientIp, String requestPath, String defenseStatus, Map<String, String> sanitizedHeaders) {
        this.clientIp = clientIp;
        this.requestPath = requestPath;
        this.defenseStatus = defenseStatus;
        this.sanitizedHeaders = sanitizedHeaders != null ? sanitizedHeaders : Collections.emptyMap();
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public void setRequestPath(String requestPath) {
        this.requestPath = requestPath;
    }

    public String getDefenseStatus() {
        return defenseStatus;
    }

    public void setDefenseStatus(String defenseStatus) {
        this.defenseStatus = defenseStatus;
    }

    public Map<String, String> getSanitizedHeaders() {
        return sanitizedHeaders;
    }

    public void setSanitizedHeaders(Map<String, String> sanitizedHeaders) {
        this.sanitizedHeaders = sanitizedHeaders;
    }
}
