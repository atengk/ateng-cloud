package io.github.atengk.cloud.sentinel.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 流量防卫底座核心配置属性
 *
 * @author Ateng
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = "ateng.cloud.sentinel")
public class AtengSentinelProperties {

    /**
     * 是否启用 ateng-cloud-sentinel 流量防卫增强底座
     */
    private boolean enabled = true;

    /**
     * 阻断异常响应对齐配置
     */
    @NestedConfigurationProperty
    private BlockHandlerProperties blockHandler = new BlockHandlerProperties();

    /**
     * RESTful 路径清洗与资源防爆配置
     */
    @NestedConfigurationProperty
    private UrlCleanProperties urlClean = new UrlCleanProperties();

    /**
     * 请求来源解析配置
     */
    @NestedConfigurationProperty
    private OriginParserProperties originParser = new OriginParserProperties();

    /**
     * @SentinelResource 切面异常兜底配置
     */
    @NestedConfigurationProperty
    private BlockAdviceProperties blockAdvice = new BlockAdviceProperties();

    /**
     * Sentinel 全景健康与规则自省 Actuator 端点配置
     */
    @NestedConfigurationProperty
    private EndpointProperties endpoint = new EndpointProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public BlockHandlerProperties getBlockHandler() {
        return blockHandler;
    }

    public void setBlockHandler(BlockHandlerProperties blockHandler) {
        this.blockHandler = blockHandler;
    }

    public UrlCleanProperties getUrlClean() {
        return urlClean;
    }

    public void setUrlClean(UrlCleanProperties urlClean) {
        this.urlClean = urlClean;
    }

    public OriginParserProperties getOriginParser() {
        return originParser;
    }

    public void setOriginParser(OriginParserProperties originParser) {
        this.originParser = originParser;
    }

    public BlockAdviceProperties getBlockAdvice() {
        return blockAdvice;
    }

    public void setBlockAdvice(BlockAdviceProperties blockAdvice) {
        this.blockAdvice = blockAdvice;
    }

    public EndpointProperties getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(EndpointProperties endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * 阻断异常响应处理配置
     */
    public static class BlockHandlerProperties {

        /**
         * 是否启用统一阻断异常处理器
         */
        private boolean enabled = true;

        /**
         * 是否物理对齐 HTTP 状态码 (限流返回 429 Too Many Requests，降级返回 503 Service Unavailable)
         * <p>若设为 false，则物理 HTTP 状态码返回 200 OK，仅在响应体 Result 中体现错误码</p>
         */
        private boolean httpStatusAlign = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isHttpStatusAlign() {
            return httpStatusAlign;
        }

        public void setHttpStatusAlign(boolean httpStatusAlign) {
            this.httpStatusAlign = httpStatusAlign;
        }
    }

    /**
     * RESTful 路径清洗配置
     */
    public static class UrlCleanProperties {

        /**
         * 是否启用 Spring MVC RESTful 路径归一化清洗
         */
        private boolean enabled = true;

        /**
         * 路径中纯数字 ID 替换占位符 (默认 "{id}")
         */
        private String numberReplacement = "{id}";

        /**
         * 路径中 UUID 字符串替换占位符 (默认 "{uuid}")
         */
        private String uuidReplacement = "{uuid}";

        /**
         * 排除白名单路径列表 (支持 AntPathMatcher 语法，匹配路径直接返回空字符串跳过创建资源节点)
         */
        private List<String> excludePatterns = new ArrayList<>(List.of(
                "/actuator/**",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/doc.html",
                "/webjars/**",
                "/favicon.ico"
        ));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNumberReplacement() {
            return numberReplacement;
        }

        public void setNumberReplacement(String numberReplacement) {
            this.numberReplacement = numberReplacement;
        }

        public String getUuidReplacement() {
            return uuidReplacement;
        }

        public void setUuidReplacement(String uuidReplacement) {
            this.uuidReplacement = uuidReplacement;
        }

        public List<String> getExcludePatterns() {
            return excludePatterns;
        }

        public void setExcludePatterns(List<String> excludePatterns) {
            this.excludePatterns = excludePatterns != null ? excludePatterns : new ArrayList<>();
        }
    }

    /**
     * 请求来源解析配置
     */
    public static class OriginParserProperties {

        /**
         * 是否启用多级调用源解析器
         */
        private boolean enabled = true;

        /**
         * 优先提取的服务名请求头名称 (默认 "X-Origin-Service")
         */
        private String originHeader = "X-Origin-Service";

        /**
         * 第二优先级网关透传来源请求头 (默认 "X-Gateway-Caller")
         */
        private String gatewayHeader = "X-Gateway-Caller";

        /**
         * 无来源标识且未启用 IP 保底时的默认回退来源 (默认 "default")
         */
        private String defaultOrigin = "default";

        /**
         * 当请求头缺失时是否回退至远端客户端 IP 作为来源标识 (默认 true)
         */
        private boolean enableClientIpFallback = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getOriginHeader() {
            return originHeader;
        }

        public void setOriginHeader(String originHeader) {
            this.originHeader = originHeader;
        }

        /**
         * 兼容存量 getter，等同于 {@link #getOriginHeader()}
         */
        public String getHeaderName() {
            return getOriginHeader();
        }

        /**
         * 兼容存量 setter，等同于 {@link #setOriginHeader(String)}
         */
        public void setHeaderName(String headerName) {
            setOriginHeader(headerName);
        }

        public String getGatewayHeader() {
            return gatewayHeader;
        }

        public void setGatewayHeader(String gatewayHeader) {
            this.gatewayHeader = gatewayHeader;
        }

        public String getDefaultOrigin() {
            return defaultOrigin;
        }

        public void setDefaultOrigin(String defaultOrigin) {
            this.defaultOrigin = defaultOrigin;
        }

        public boolean isEnableClientIpFallback() {
            return enableClientIpFallback;
        }

        public void setEnableClientIpFallback(boolean enableClientIpFallback) {
            this.enableClientIpFallback = enableClientIpFallback;
        }
    }

    /**
     * @SentinelResource 切面异常兜底配置
     */
    public static class BlockAdviceProperties {

        /**
         * 是否启用 @SentinelResource 切面未捕获异常统一兜底转译
         */
        private boolean enabled = true;

        /**
         * 切面兜底拦截时是否物理对齐 HTTP 状态码 (默认 true)
         */
        private boolean httpStatusAlign = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isHttpStatusAlign() {
            return httpStatusAlign;
        }

        public void setHttpStatusAlign(boolean httpStatusAlign) {
            this.httpStatusAlign = httpStatusAlign;
        }
    }

    /**
     * Sentinel 全景健康与规则自省 Actuator 端点配置
     */
    public static class EndpointProperties {

        /**
         * 是否启用 Sentinel 全景健康与动态规则自省端点 (/actuator/sentinel-panorama)
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
