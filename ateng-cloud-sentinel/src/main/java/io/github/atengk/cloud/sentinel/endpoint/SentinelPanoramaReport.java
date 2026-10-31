package io.github.atengk.cloud.sentinel.endpoint;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Sentinel 全景健康与动态规则自省结构化报告对象
 *
 * <p>聚合五维全景生产观测视图：
 * <ul>
 *     <li><b>基础概览 (Basic Overview)</b>：应用名、Sentinel 核心版本、健康状态 (UP) 与时间戳；</li>
 *     <li><b>Nacos 动态规则数据源总览 (Nacos Rules Datasource Overview)</b>：订阅开关、DataId 前缀契约、脱敏连接参数与数据源监听状态；</li>
 *     <li><b>内存动态规则快照 (In-Memory Rules Snapshot)</b>：Flow、Degrade、ParamFlow、System、Authority 5 大规则实时数量与核心参数列表；</li>
 *     <li><b>Web MVC 防卫配置快照 (Web MVC Config Snapshot)</b>：RESTful 路径清洗规则、来源解析契约与 HTTP 状态码对齐策略；</li>
 *     <li><b>阻断事件统计 (Block Metrics Snapshot)</b>：原子累加的阻断计数与最近一次阻断事件上下文快照。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public record SentinelPanoramaReport(
        String status,
        String appName,
        String sentinelVersion,
        long timestamp,
        NacosRulesReport nacosRules,
        InMemoryRulesReport inMemoryRules,
        WebMvcConfigReport webMvcConfig,
        BlockMetricsReport blockMetrics
) implements Serializable {

    /**
     * Nacos 动态规则数据源订阅报告
     */
    public record NacosRulesReport(
            boolean enabled,
            String prefix,
            String groupId,
            String serverAddr,
            String namespace,
            String username,
            String password,
            String accessKey,
            String secretKey,
            boolean subscribed,
            int dataSourceCount,
            List<String> dataSources
    ) implements Serializable {
        public NacosRulesReport {
            if (dataSources == null) {
                dataSources = Collections.emptyList();
            }
        }
    }

    /**
     * 内存中生效规则快照报告
     */
    public record InMemoryRulesReport(
            int totalRuleCount,
            int flowRuleCount,
            int degradeRuleCount,
            int paramFlowRuleCount,
            int systemRuleCount,
            int authorityRuleCount,
            List<FlowRuleSnapshot> flowRules,
            List<DegradeRuleSnapshot> degradeRules,
            List<ParamFlowRuleSnapshot> paramFlowRules,
            List<SystemRuleSnapshot> systemRules,
            List<AuthorityRuleSnapshot> authorityRules
    ) implements Serializable {
        public InMemoryRulesReport {
            if (flowRules == null) flowRules = Collections.emptyList();
            if (degradeRules == null) degradeRules = Collections.emptyList();
            if (paramFlowRules == null) paramFlowRules = Collections.emptyList();
            if (systemRules == null) systemRules = Collections.emptyList();
            if (authorityRules == null) authorityRules = Collections.emptyList();
        }
    }

    /**
     * 限流规则快照
     */
    public record FlowRuleSnapshot(
            String resource,
            String limitApp,
            int grade,
            double count,
            int strategy,
            int controlBehavior,
            String refResource,
            int warmUpPeriodSec,
            int maxQueueingTimeMs,
            boolean clusterMode
    ) implements Serializable {}

    /**
     * 熔断降级规则快照
     */
    public record DegradeRuleSnapshot(
            String resource,
            String limitApp,
            int grade,
            double count,
            int timeWindow,
            int minRequestAmount,
            double slowRatioThreshold,
            int statIntervalMs
    ) implements Serializable {}

    /**
     * 热点参数限流规则快照
     */
    public record ParamFlowRuleSnapshot(
            String resource,
            String limitApp,
            int grade,
            int paramIdx,
            double count,
            int controlBehavior,
            int maxQueueingTimeMs,
            long durationInSec,
            boolean clusterMode
    ) implements Serializable {}

    /**
     * 系统保护规则快照
     */
    public record SystemRuleSnapshot(
            double highestSystemLoad,
            double highestCpuUsage,
            double qps,
            long avgRt,
            long maxThread
    ) implements Serializable {}

    /**
     * 授权访问黑白名单规则快照
     */
    public record AuthorityRuleSnapshot(
            String resource,
            String limitApp,
            int strategy
    ) implements Serializable {}

    /**
     * Web MVC 防卫配置快照
     */
    public record WebMvcConfigReport(
            UrlCleanReport urlClean,
            OriginParserReport originParser,
            BlockHandlerReport blockHandler,
            BlockAdviceReport blockAdvice
    ) implements Serializable {}

    /**
     * RESTful 路径归一化清洗配置
     */
    public record UrlCleanReport(
            boolean enabled,
            String numberReplacement,
            String uuidReplacement,
            int excludePatternCount,
            List<String> excludePatterns
    ) implements Serializable {
        public UrlCleanReport {
            if (excludePatterns == null) {
                excludePatterns = Collections.emptyList();
            }
        }
    }

    /**
     * 请求调用来源解析配置
     */
    public record OriginParserReport(
            String originHeader,
            String gatewayHeader,
            String defaultOrigin,
            boolean enableClientIpFallback
    ) implements Serializable {}

    /**
     * 阻断异常响应处理器配置
     */
    public record BlockHandlerReport(
            boolean enabled,
            boolean httpStatusAlign
    ) implements Serializable {}

    /**
     * @SentinelResource 切面兜底异常配置
     */
    public record BlockAdviceReport(
            boolean enabled,
            boolean httpStatusAlign
    ) implements Serializable {}

    /**
     * 阻断事件统计
     */
    public record BlockMetricsReport(
            @JsonProperty("blockedTotal")
            long blockedTotal,
            long flowCount,
            long degradeCount,
            long paramFlowCount,
            long systemCount,
            long authorityCount,
            long otherCount,
            SentinelBlockCounter.BlockEventSnapshot lastBlockEvent
    ) implements Serializable {

        /**
         * 兼容存量 getter 与序列化，等同于 {@link #blockedTotal()}
         */
        @JsonProperty("totalBlocked")
        public long totalBlocked() {
            return blockedTotal;
        }
    }
}
