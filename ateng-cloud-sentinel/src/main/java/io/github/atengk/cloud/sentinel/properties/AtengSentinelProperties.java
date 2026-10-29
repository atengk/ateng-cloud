package io.github.atengk.cloud.sentinel.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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
         * 优先提取的服务名请求头名称
         */
        private String headerName = "X-Origin-Service";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
