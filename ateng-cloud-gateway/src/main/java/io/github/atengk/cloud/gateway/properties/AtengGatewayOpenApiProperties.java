package io.github.atengk.cloud.gateway.properties;

import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * 网关 SpringDoc 微服务接口文档动态聚合配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewayOpenApiProperties {

    /**
     * 是否启用微服务接口文档动态聚合
     */
    private boolean enabled = true;

    /**
     * 文档统一访问入口路径 (默认 /docs，兼容 /swagger-ui.html 自动重定向)
     */
    private String path = "/docs";

    /**
     * 生产安全守卫模式 (DISABLED: 404熔断 | BASIC: HTTP Basic Auth | SA_TOKEN: 角色鉴权 | PUBLIC: 免密直访)
     */
    private GuardMode guardMode = GuardMode.PUBLIC;

    /**
     * HTTP Basic Auth 挑战认证凭据 (当 guardMode 为 BASIC 时生效)
     */
    @NestedConfigurationProperty
    private BasicAuthProperties basicAuth = new BasicAuthProperties();

    public enum GuardMode {
        /**
         * 生产硬阻断，直接返回 HTTP 404
         */
        DISABLED,
        /**
         * HTTP Basic Auth 弹窗认证挑战
         */
        BASIC,
        /**
         * 校验 Sa-Token 登录态与管理员角色
         */
        SA_TOKEN,
        /**
         * 公开直访 (开发/测试环境免密直连)
         */
        PUBLIC
    }

    public static class BasicAuthProperties {
        private String username = "admin";
        private String password = "admin";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public GuardMode getGuardMode() {
        return guardMode;
    }

    public void setGuardMode(GuardMode guardMode) {
        this.guardMode = guardMode;
    }

    public BasicAuthProperties getBasicAuth() {
        return basicAuth;
    }

    public void setBasicAuth(BasicAuthProperties basicAuth) {
        this.basicAuth = basicAuth;
    }
}
