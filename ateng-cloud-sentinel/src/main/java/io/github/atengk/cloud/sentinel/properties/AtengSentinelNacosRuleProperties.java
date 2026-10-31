package io.github.atengk.cloud.sentinel.properties;

import com.alibaba.nacos.api.PropertyKeyConst;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * Sentinel Nacos 规则持久化与动态订阅配置属性
 *
 * <p>支持 Nacos 连接参数四级自适应平滑继承：
 * <ul>
 *     <li>第一级（最高）：{@code ateng.cloud.sentinel.nacos-rules.*} 显式声明的参数；</li>
 *     <li>第二级：Spring Cloud Nacos 配置中心参数（{@code spring.cloud.nacos.config.*}）；</li>
 *     <li>第三级：Spring Cloud Nacos 全局/注册中心参数（{@code spring.cloud.nacos.discovery.*} 或 {@code spring.cloud.nacos.*}）；</li>
 *     <li>第四级（保底）：框架合理生产默认值（如 {@code 127.0.0.1:8848}）。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = "ateng.cloud.sentinel.nacos-rules")
public class AtengSentinelNacosRuleProperties {

    /**
     * 默认 Nacos 服务端连接地址
     */
    public static final String DEFAULT_SERVER_ADDR = "127.0.0.1:8848";

    /**
     * 是否启用 Sentinel Nacos 规则动态订阅与持久化桥接 (默认关闭以保障离线单测自洽)
     */
    private boolean enabled = false;

    /**
     * Nacos 服务端连接地址 (显式指定优先，缺省时自动回退继承 Spring Cloud Nacos 连接配置，保底 127.0.0.1:8848)
     */
    private String serverAddr;

    /**
     * Nacos 租户或命名空间 ID (显式指定优先，缺省时自动回退继承 Spring Cloud Nacos 命名空间，保底空字符串表示 public)
     */
    private String namespace;

    /**
     * Nacos 配置分组 GroupId (默认 SENTINEL_GROUP)
     */
    private String groupId = "SENTINEL_GROUP";

    /**
     * Nacos 规则配置 DataId 前缀 (默认缺省时回退至 spring.application.name)
     */
    private String dataIdPrefix;

    /**
     * Nacos 访问鉴权用户名 (显式指定优先，缺省时自动回退继承 Spring Cloud Nacos 用户名)
     */
    private String username;

    /**
     * Nacos 访问鉴权密码 (显式指定优先，缺省时自动回退继承 Spring Cloud Nacos 密码)
     */
    private String password;

    /**
     * Nacos 上下文路径 (显式指定优先，缺省时自动回退继承 Spring Cloud Nacos 上下文路径，保底空字符串)
     */
    private String contextPath;

    /**
     * 阿里云 RAM 或自定义鉴权 AccessKey (显式指定优先，缺省时自动回退继承 Spring Cloud Nacos AccessKey)
     */
    private String accessKey;

    /**
     * 阿里云 RAM 或自定义鉴权 SecretKey (显式指定优先，缺省时自动回退继承 Spring Cloud Nacos SecretKey)
     */
    private String secretKey;

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
        return this.serverAddr != null ? this.serverAddr : DEFAULT_SERVER_ADDR;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getNamespace() {
        return this.namespace != null ? this.namespace : "";
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
        return this.username != null ? this.username : "";
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return this.password != null ? this.password : "";
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getContextPath() {
        return this.contextPath != null ? this.contextPath : "";
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public String getAccessKey() {
        return this.accessKey != null ? this.accessKey : "";
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return this.secretKey != null ? this.secretKey : "";
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
     * 按照四级时序自适应解析 Nacos 服务端连接地址
     */
    public String resolveServerAddr(Environment env) {
        String val = resolveNacosProperty(this.serverAddr, env, "server-addr");
        return StringUtils.hasText(val) ? val.trim() : DEFAULT_SERVER_ADDR;
    }

    /**
     * 按照四级时序自适应解析 Nacos 命名空间
     */
    public String resolveNamespace(Environment env) {
        String val = resolveNacosProperty(this.namespace, env, "namespace");
        return val != null ? val.trim() : "";
    }

    /**
     * 按照四级时序自适应解析 Nacos 用户名
     */
    public String resolveUsername(Environment env) {
        String val = resolveNacosProperty(this.username, env, "username");
        return val != null ? val.trim() : "";
    }

    /**
     * 按照四级时序自适应解析 Nacos 密码
     */
    public String resolvePassword(Environment env) {
        String val = resolveNacosProperty(this.password, env, "password");
        return val != null ? val.trim() : "";
    }

    /**
     * 按照四级时序自适应解析 Nacos 上下文路径
     */
    public String resolveContextPath(Environment env) {
        String val = resolveNacosProperty(this.contextPath, env, "context-path");
        return val != null ? val.trim() : "";
    }

    /**
     * 按照四级时序自适应解析 Nacos AccessKey
     */
    public String resolveAccessKey(Environment env) {
        String val = resolveNacosProperty(this.accessKey, env, "access-key");
        return val != null ? val.trim() : "";
    }

    /**
     * 按照四级时序自适应解析 Nacos SecretKey
     */
    public String resolveSecretKey(Environment env) {
        String val = resolveNacosProperty(this.secretKey, env, "secret-key");
        return val != null ? val.trim() : "";
    }

    private String resolveNacosProperty(String explicitVal, Environment env, String suffix) {
        return resolveProperty(explicitVal, env,
                "spring.cloud.nacos.config." + suffix,
                "spring.cloud.nacos.discovery." + suffix,
                "spring.cloud.nacos." + suffix);
    }

    private String resolveProperty(String explicitVal, Environment env, String... fallbackKeys) {
        if (StringUtils.hasText(explicitVal)) {
            return explicitVal.trim();
        }
        if (env != null && fallbackKeys != null) {
            for (String key : fallbackKeys) {
                if (key != null) {
                    String val = env.getProperty(key);
                    if (StringUtils.hasText(val)) {
                        return val.trim();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 将当前配置转换为 Nacos 客户端原生属性对象 (无环境上下文回退)
     *
     * @return Nacos 属性配置
     */
    public Properties toNacosProperties() {
        return toNacosProperties(null);
    }

    /**
     * 支持自适应继承环境上下文参数的 Nacos 属性转换
     *
     * @param environment Spring 运行环境上下文
     * @return 最终生效的 Nacos 原生属性配置
     */
    public Properties toNacosProperties(Environment environment) {
        Properties properties = new Properties();

        String resolvedServerAddr = resolveServerAddr(environment);
        if (StringUtils.hasText(resolvedServerAddr)) {
            properties.put(PropertyKeyConst.SERVER_ADDR, resolvedServerAddr.trim());
        }

        String resolvedNamespace = resolveNamespace(environment);
        if (StringUtils.hasText(resolvedNamespace)) {
            properties.put(PropertyKeyConst.NAMESPACE, resolvedNamespace.trim());
        }

        String resolvedUsername = resolveUsername(environment);
        if (StringUtils.hasText(resolvedUsername)) {
            properties.put(PropertyKeyConst.USERNAME, resolvedUsername.trim());
        }

        String resolvedPassword = resolvePassword(environment);
        if (StringUtils.hasText(resolvedPassword)) {
            properties.put(PropertyKeyConst.PASSWORD, resolvedPassword.trim());
        }

        String resolvedContextPath = resolveContextPath(environment);
        if (StringUtils.hasText(resolvedContextPath)) {
            properties.put(PropertyKeyConst.CONTEXT_PATH, resolvedContextPath.trim());
        }

        String resolvedAccessKey = resolveAccessKey(environment);
        if (StringUtils.hasText(resolvedAccessKey)) {
            properties.put(PropertyKeyConst.ACCESS_KEY, resolvedAccessKey.trim());
        }

        String resolvedSecretKey = resolveSecretKey(environment);
        if (StringUtils.hasText(resolvedSecretKey)) {
            properties.put(PropertyKeyConst.SECRET_KEY, resolvedSecretKey.trim());
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
