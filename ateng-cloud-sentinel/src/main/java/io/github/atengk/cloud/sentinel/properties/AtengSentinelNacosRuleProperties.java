package io.github.atengk.cloud.sentinel.properties;

import com.alibaba.nacos.api.PropertyKeyConst;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.Properties;

/**
 * Sentinel Nacos 规则持久化与动态订阅配置属性
 *
 * @author Ateng
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = "ateng.cloud.sentinel.nacos-rules")
public class AtengSentinelNacosRuleProperties {

    /**
     * 是否启用 Sentinel Nacos 规则动态订阅与持久化桥接 (默认关闭以保障离线单测自洽)
     */
    private boolean enabled = false;

    /**
     * Nacos 服务端连接地址 (例如 127.0.0.1:8848 或 nacos-cluster:8848)
     */
    private String serverAddr = "127.0.0.1:8848";

    /**
     * Nacos 租户或命名空间 ID (默认为空字符串表示 public 命名空间)
     */
    private String namespace = "";

    /**
     * Nacos 配置分组 GroupId (默认 SENTINEL_GROUP)
     */
    private String groupId = "SENTINEL_GROUP";

    /**
     * Nacos 规则配置 DataId 前缀 (默认缺省时回退至 spring.application.name)
     */
    private String dataIdPrefix;

    /**
     * Nacos 访问鉴权用户名
     */
    private String username = "";

    /**
     * Nacos 访问鉴权密码
     */
    private String password = "";

    /**
     * Nacos 上下文路径 (默认空字符串)
     */
    private String contextPath = "";

    /**
     * 阿里云 RAM 或自定义鉴权 AccessKey
     */
    private String accessKey = "";

    /**
     * 阿里云 RAM 或自定义鉴权 SecretKey
     */
    private String secretKey = "";

    /**
     * 流控规则 (FlowRule) 订阅配置
     */
    @NestedConfigurationProperty
    private RuleItemProperties flow = new RuleItemProperties(true, "-flow.json");

    /**
     * 熔断降级规则 (DegradeRule) 订阅配置
     */
    @NestedConfigurationProperty
    private RuleItemProperties degrade = new RuleItemProperties(true, "-degrade.json");

    /**
     * 热点参数限流规则 (ParamFlowRule) 订阅配置
     */
    @NestedConfigurationProperty
    private RuleItemProperties paramFlow = new RuleItemProperties(true, "-param-flow.json");

    /**
     * 系统自适应防护规则 (SystemRule) 订阅配置
     */
    @NestedConfigurationProperty
    private RuleItemProperties system = new RuleItemProperties(true, "-system.json");

    /**
     * 来源访问控制规则 (AuthorityRule) 订阅配置
     */
    @NestedConfigurationProperty
    private RuleItemProperties authority = new RuleItemProperties(true, "-authority.json");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getDataIdPrefix() {
        return dataIdPrefix;
    }

    public void setDataIdPrefix(String dataIdPrefix) {
        this.dataIdPrefix = dataIdPrefix;
    }

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

    public String getContextPath() {
        return contextPath;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public RuleItemProperties getFlow() {
        return flow;
    }

    public void setFlow(RuleItemProperties flow) {
        this.flow = flow;
    }

    public RuleItemProperties getDegrade() {
        return degrade;
    }

    public void setDegrade(RuleItemProperties degrade) {
        this.degrade = degrade;
    }

    public RuleItemProperties getParamFlow() {
        return paramFlow;
    }

    public void setParamFlow(RuleItemProperties paramFlow) {
        this.paramFlow = paramFlow;
    }

    public RuleItemProperties getSystem() {
        return system;
    }

    public void setSystem(RuleItemProperties system) {
        this.system = system;
    }

    public RuleItemProperties getAuthority() {
        return authority;
    }

    public void setAuthority(RuleItemProperties authority) {
        this.authority = authority;
    }

    /**
     * 将当前配置转换为 Nacos 客户端原生属性对象
     *
     * @return Nacos 属性配置
     */
    public Properties toNacosProperties() {
        Properties properties = new Properties();
        if (serverAddr != null && !serverAddr.trim().isEmpty()) {
            properties.put(PropertyKeyConst.SERVER_ADDR, serverAddr.trim());
        }
        if (namespace != null && !namespace.trim().isEmpty()) {
            properties.put(PropertyKeyConst.NAMESPACE, namespace.trim());
        }
        if (username != null && !username.trim().isEmpty()) {
            properties.put(PropertyKeyConst.USERNAME, username.trim());
        }
        if (password != null && !password.trim().isEmpty()) {
            properties.put(PropertyKeyConst.PASSWORD, password.trim());
        }
        if (contextPath != null && !contextPath.trim().isEmpty()) {
            properties.put(PropertyKeyConst.CONTEXT_PATH, contextPath.trim());
        }
        if (accessKey != null && !accessKey.trim().isEmpty()) {
            properties.put(PropertyKeyConst.ACCESS_KEY, accessKey.trim());
        }
        if (secretKey != null && !secretKey.trim().isEmpty()) {
            properties.put(PropertyKeyConst.SECRET_KEY, secretKey.trim());
        }
        return properties;
    }

    /**
     * 单类规则订阅配置项
     */
    public static class RuleItemProperties {

        /**
         * 是否启用该项规则数据源动态订阅
         */
        private boolean enabled = true;

        /**
         * 规则在 Nacos 中的 DataId 后缀
         */
        private String dataIdSuffix;

        public RuleItemProperties() {
        }

        public RuleItemProperties(boolean enabled, String dataIdSuffix) {
            this.enabled = enabled;
            this.dataIdSuffix = dataIdSuffix;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDataIdSuffix() {
            return dataIdSuffix;
        }

        public void setDataIdSuffix(String dataIdSuffix) {
            this.dataIdSuffix = dataIdSuffix;
        }
    }
}
