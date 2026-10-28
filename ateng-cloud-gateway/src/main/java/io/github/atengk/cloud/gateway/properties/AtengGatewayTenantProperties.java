package io.github.atengk.cloud.gateway.properties;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;

/**
 * 网关多租户入站解析配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewayTenantProperties {

    /**
     * 是否启用多租户入站安全解析与隔离过滤器
     */
    private boolean enabled = true;

    /**
     * 是否允许开放/白名单未登录接口直接读取客户端传入的 X-Tenant-Id 请求标头
     * <p>默认 false 强制置空，杜绝未授权租户越权；开启后允许特定开放接口传递外部租户标识</p>
     */
    private boolean allowHeader = false;

    /**
     * 多租户标头名称 (默认 X-Tenant-Id)
     */
    private String headerName = GatewayConstants.X_TENANT_ID;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowHeader() {
        return allowHeader;
    }

    public void setAllowHeader(boolean allowHeader) {
        this.allowHeader = allowHeader;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }
}
