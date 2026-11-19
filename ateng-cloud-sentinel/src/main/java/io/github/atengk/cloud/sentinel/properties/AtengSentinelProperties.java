package io.github.atengk.cloud.sentinel.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
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
     * Web 异常熔断自动追踪桥接配置
     */
    @NestedConfigurationProperty
    private TraceProperties trace = new TraceProperties();

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
        this.blockHandler = blockHandler != null ? blockHandler : new BlockHandlerProperties();
    }

    public UrlCleanProperties getUrlClean() {
        return urlClean;
    }

    public void setUrlClean(UrlCleanProperties urlClean) {
        this.urlClean = urlClean != null ? urlClean : new UrlCleanProperties();
    }

    public OriginParserProperties getOriginParser() {
        return originParser;
    }

    public void setOriginParser(OriginParserProperties originParser) {
        this.originParser = originParser != null ? originParser : new OriginParserProperties();
    }

    public BlockAdviceProperties getBlockAdvice() {
        return blockAdvice;
    }

    public void setBlockAdvice(BlockAdviceProperties blockAdvice) {
        this.blockAdvice = blockAdvice != null ? blockAdvice : new BlockAdviceProperties();
    }

    public TraceProperties getTrace() {
        return trace;
    }

    public void setTrace(TraceProperties trace) {
        this.trace = trace != null ? trace : new TraceProperties();
    }

    public EndpointProperties getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(EndpointProperties endpoint) {
        this.endpoint = endpoint != null ? endpoint : new EndpointProperties();
    }

    /**
     * 阻断响应码输出模式
     */
    public enum BlockCodeMode {
        /**
         * 输出 Sentinel 细分错误码 (4201~4205)，默认兼容存量契约
         */
        SENTINEL_CODE,
        /**
         * 对齐网关与标准 HTTP 语义状态码 (429 / 503 / 403，且 429 默认附带 Retry-After: 1)
         */
        HTTP_STATUS
    }

    /**
     * 阻断响应处理公共配置基类
     */
    public abstract static class AbstractBlockResponseProperties {

        /**
         * 是否启用处理器
         */
        private boolean enabled = true;

        /**
         * 是否物理对齐 HTTP 状态码 (限流返回 429 Too Many Requests，降级返回 503 Service Unavailable)
         * <p>若设为 false，则物理 HTTP 状态码返回 200 OK，仅在响应体 Result 中体现错误码</p>
         */
        private boolean httpStatusAlign = true;

        /**
         * 阻断响应 JSON {@code Result.code} 输出口径模式（默认 {@link BlockCodeMode#SENTINEL_CODE} 保持 4201~4205 向后兼容；
         * 设为 {@link BlockCodeMode#HTTP_STATUS} 时输出 429/503/403，与网关保持一致）
         */
        private BlockCodeMode codeMode = BlockCodeMode.SENTINEL_CODE;

        /**
         * 阻断响应附带的 Retry-After 退避时长 (小于等于 0 时不输出该响应头，默认 {@link Duration#ZERO})
         */
        private Duration retryAfter = Duration.ZERO;

        /**
         * 内部标记是否由调用方显式配置过 retryAfter（用于区分未配置与显式配置 0s 禁用）
         */
        private transient boolean retryAfterConfigured = false;

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

        public BlockCodeMode getCodeMode() {
            return codeMode;
        }

        public void setCodeMode(BlockCodeMode codeMode) {
            this.codeMode = codeMode != null ? codeMode : BlockCodeMode.SENTINEL_CODE;
        }

        public Duration getRetryAfter() {
            return retryAfter;
        }

        public void setRetryAfter(Duration retryAfter) {
            this.retryAfterConfigured = true;
            this.retryAfter = (retryAfter != null && !retryAfter.isNegative()) ? retryAfter : Duration.ZERO;
        }

        /**
         * 解析最终生效的 Retry-After 退避时长：若用户未显式配置且处于 {@link BlockCodeMode#HTTP_STATUS} 模式的 429 场景，
         * 自动对齐网关默认返回 {@code 1s}
         *
         * @param targetStatus 目标语义 HTTP 状态码
         * @return 生效的 Retry-After 时长
         */
        public Duration resolveEffectiveRetryAfter(org.springframework.http.HttpStatus targetStatus) {
            if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()) {
                return retryAfter;
            }
            if (!retryAfterConfigured
                    && codeMode == BlockCodeMode.HTTP_STATUS
                    && targetStatus == org.springframework.http.HttpStatus.TOO_MANY_REQUESTS) {
                return Duration.ofSeconds(1);
            }
            return Duration.ZERO;
        }

        /**
         * 获取退避秒数 (兼容数值秒数配置与 {@link Duration} 的整数秒转换)
         */
        public int getRetryAfterSeconds() {
            long seconds = retryAfter.toSeconds();
            return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
        }

        /**
         * 设置退避秒数 (兼容 {@code retry-after-seconds} 配置绑定，小于等于 0 时归一为 {@link Duration#ZERO})
         */
        public void setRetryAfterSeconds(int retryAfterSeconds) {
            this.retryAfterConfigured = true;
            this.retryAfter = retryAfterSeconds > 0 ? Duration.ofSeconds(retryAfterSeconds) : Duration.ZERO;
        }

        /**
         * 重置 Retry-After 退避配置至默认未显式配置状态
         */
        public void resetRetryAfter() {
            this.retryAfterConfigured = false;
            this.retryAfter = Duration.ZERO;
        }
    }

    /**
     * 阻断异常响应处理配置
     */
    public static class BlockHandlerProperties extends AbstractBlockResponseProperties {
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
         * 路径中 24/32 位纯十六进制哈希 ID 替换占位符 (默认 "{id}")
         */
        private String hexReplacement = "{id}";

        /**
         * 自定义预编译路径收敛正则规则列表 (按声明顺序执行)
         */
        private List<CustomPattern> customPatterns = new ArrayList<>();

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

        public String getHexReplacement() {
            return hexReplacement;
        }

        public void setHexReplacement(String hexReplacement) {
            this.hexReplacement = hexReplacement;
        }

        public List<CustomPattern> getCustomPatterns() {
            return customPatterns;
        }

        public void setCustomPatterns(List<CustomPattern> customPatterns) {
            this.customPatterns = customPatterns != null ? customPatterns : new ArrayList<>();
        }

        public List<String> getExcludePatterns() {
            return excludePatterns;
        }

        public void setExcludePatterns(List<String> excludePatterns) {
            this.excludePatterns = excludePatterns != null ? excludePatterns : new ArrayList<>();
        }

        /**
         * 自定义路径正则清洗规则
         */
        public static class CustomPattern {

            /**
             * 正则表达式模式
             */
            private String pattern;

            /**
             * 命中后的替换文本
             */
            private String replacement = "{custom}";

            public CustomPattern() {
            }

            public CustomPattern(String pattern, String replacement) {
                this.pattern = pattern;
                this.replacement = replacement;
            }

            public String getPattern() {
                return pattern;
            }

            public void setPattern(String pattern) {
                this.pattern = pattern;
            }

            public String getReplacement() {
                return replacement;
            }

            public void setReplacement(String replacement) {
                this.replacement = replacement;
            }
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

        /**
         * 当请求头缺失时是否开启多租户上下文回退溯源 (读取 TenantContextHolder，默认 false)
         */
        private boolean enableTenantFallback = false;

        /**
         * 多租户调用源命名空间前缀 (默认 "tenant:")
         */
        private String tenantPrefix = "tenant:";

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

        public boolean isEnableTenantFallback() {
            return enableTenantFallback;
        }

        public void setEnableTenantFallback(boolean enableTenantFallback) {
            this.enableTenantFallback = enableTenantFallback;
        }

        public String getTenantPrefix() {
            return tenantPrefix;
        }

        public void setTenantPrefix(String tenantPrefix) {
            this.tenantPrefix = tenantPrefix;
        }
    }

    /**
     * @SentinelResource 切面异常兜底配置
     */
    public static class BlockAdviceProperties extends AbstractBlockResponseProperties {
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

    /**
     * Web 异常 Sentinel 熔断自动追踪桥接配置
     */
    public static class TraceProperties {

        /**
         * 是否启用 Web 异常 Sentinel 熔断自动追踪桥接 (默认 true)
         */
        private boolean enabled = true;

        /**
         * 是否将常规 BusinessException 计入 Sentinel 异常熔断统计 (默认 false 豁免)
         */
        private boolean traceBusinessException = false;

        /**
         * 额外声明豁免不计入 Sentinel 异常熔断统计的异常类型列表
         */
        private List<Class<? extends Throwable>> ignoreExceptions = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isTraceBusinessException() {
            return traceBusinessException;
        }

        public void setTraceBusinessException(boolean traceBusinessException) {
            this.traceBusinessException = traceBusinessException;
        }

        public List<Class<? extends Throwable>> getIgnoreExceptions() {
            return ignoreExceptions;
        }

        public void setIgnoreExceptions(List<Class<? extends Throwable>> ignoreExceptions) {
            this.ignoreExceptions = ignoreExceptions != null ? new ArrayList<>(ignoreExceptions) : new ArrayList<>();
        }
    }
}
