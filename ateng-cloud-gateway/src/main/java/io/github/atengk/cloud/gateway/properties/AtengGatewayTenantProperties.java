package io.github.atengk.cloud.gateway.properties;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /**
     * 是否允许平台超级管理员通过请求头受控临时切换目标租户上下文 (默认 false 关闭防越权)
     */
    private boolean allowSuperAdminSwitch = false;

    /**
     * 允许执行跨租户切换的平台租户标识白名单 (默认空集合表示不额外限制超管会话归属租户；非空时须同时命中平台租户与超管身份)
     */
    private List<String> platformTenantIds = new ArrayList<>();

    /**
     * 判定为超级管理员的通用角色编码集合 (默认 ["admin", "super_admin"]，下游业务可按需收紧覆盖)
     */
    private List<String> superAdminRoles = new ArrayList<>(Arrays.asList("admin", "super_admin"));

    /**
     * 判定为超级管理员的通配权限编码集合 (默认 ["*", "*:*:*"])
     */
    private List<String> superAdminPermissions = new ArrayList<>(Arrays.asList("*", "*:*:*"));

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

    public boolean isAllowSuperAdminSwitch() {
        return allowSuperAdminSwitch;
    }

    public void setAllowSuperAdminSwitch(boolean allowSuperAdminSwitch) {
        this.allowSuperAdminSwitch = allowSuperAdminSwitch;
    }

    public List<String> getPlatformTenantIds() {
        return platformTenantIds;
    }

    public void setPlatformTenantIds(List<String> platformTenantIds) {
        this.platformTenantIds = platformTenantIds != null ? platformTenantIds : new ArrayList<>();
    }

    public List<String> getSuperAdminRoles() {
        return superAdminRoles;
    }

    public void setSuperAdminRoles(List<String> superAdminRoles) {
        this.superAdminRoles = superAdminRoles != null ? superAdminRoles : new ArrayList<>();
    }

    public List<String> getSuperAdminPermissions() {
        return superAdminPermissions;
    }

    public void setSuperAdminPermissions(List<String> superAdminPermissions) {
        this.superAdminPermissions = superAdminPermissions != null ? superAdminPermissions : new ArrayList<>();
    }
}
