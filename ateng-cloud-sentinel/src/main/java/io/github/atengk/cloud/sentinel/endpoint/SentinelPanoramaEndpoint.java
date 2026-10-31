package io.github.atengk.cloud.sentinel.endpoint;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import io.github.atengk.cloud.sentinel.datasource.SentinelNacosRuleSubscriber;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelNacosRuleProperties;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sentinel 全景健康与规则自省 Actuator 端点
 *
 * <p>暴露路径：{@code /actuator/sentinelPanorama} 与 {@code /actuator/sentinel-panorama}。
 * 遵循非阻塞无锁架构，所有指标读取自内存原子快照与只读规则管理器，微秒级极速响应，零外部网络 IO 与锁竞争。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Endpoint(id = "sentinel-panorama")
public class SentinelPanoramaEndpoint {

    private final ObjectProvider<AtengSentinelProperties> propertiesProvider;
    private final ObjectProvider<SentinelNacosRuleSubscriber> subscriberProvider;
    private final ObjectProvider<SentinelBlockCounter> counterProvider;
    private final Environment environment;

    public SentinelPanoramaEndpoint(
            ObjectProvider<AtengSentinelProperties> propertiesProvider,
            ObjectProvider<SentinelNacosRuleSubscriber> subscriberProvider,
            ObjectProvider<SentinelBlockCounter> counterProvider,
            Environment environment) {
        this.propertiesProvider = propertiesProvider;
        this.subscriberProvider = subscriberProvider;
        this.counterProvider = counterProvider;
        this.environment = environment;
    }

    /**
     * 生成并输出 Sentinel 五维全景诊断报告
     *
     * @return 结构化诊断报告
     */
    @ReadOperation
    public SentinelPanoramaReport report() {
        String appName = environment != null
                ? environment.getProperty("spring.application.name", "unknown")
                : "unknown";
        String version = Constants.SENTINEL_VERSION != null ? Constants.SENTINEL_VERSION : "1.8.x";
        long now = System.currentTimeMillis();

        // 1. Nacos 动态规则数据源总览 (含凭据安全脱敏)
        SentinelPanoramaReport.NacosRulesReport nacosReport = buildNacosReport();

        // 2. 内存规则快照
        SentinelPanoramaReport.InMemoryRulesReport inMemoryReport = buildInMemoryReport();

        // 3. Web MVC 防卫配置快照
        SentinelPanoramaReport.WebMvcConfigReport webMvcReport = buildWebMvcReport();

        // 4. 阻断事件统计快照
        SentinelPanoramaReport.BlockMetricsReport metricsReport = buildMetricsReport();

        return new SentinelPanoramaReport(
                "UP",
                appName,
                version,
                now,
                nacosReport,
                inMemoryReport,
                webMvcReport,
                metricsReport
        );
    }

    private SentinelPanoramaReport.NacosRulesReport buildNacosReport() {
        SentinelNacosRuleSubscriber subscriber = subscriberProvider != null ? subscriberProvider.getIfAvailable() : null;
        if (subscriber == null) {
            return new SentinelPanoramaReport.NacosRulesReport(
                    false, null, null, null, null, null, null, null, null, false, 0, Collections.emptyList()
            );
        }

        AtengSentinelNacosRuleProperties nacosProps = subscriber.getProperties();
        boolean enabled = nacosProps != null && nacosProps.isEnabled();
        String prefix = subscriber.getResolvedDataIdPrefix();
        String groupId = nacosProps != null ? nacosProps.getGroupId() : null;
        String serverAddr = subscriber.getEffectiveServerAddr();
        String namespace = subscriber.getEffectiveNamespace();

        String rawUsername = nacosProps != null ? nacosProps.resolveUsername(subscriber.getEnvironment()) : null;
        String rawPassword = nacosProps != null ? nacosProps.resolvePassword(subscriber.getEnvironment()) : null;
        String rawAk = nacosProps != null ? nacosProps.resolveAccessKey(subscriber.getEnvironment()) : null;
        String rawSk = nacosProps != null ? nacosProps.resolveSecretKey(subscriber.getEnvironment()) : null;

        String password = (rawPassword != null && !rawPassword.isEmpty()) ? "******" : null;
        String accessKey = (rawAk != null && !rawAk.isEmpty()) ? "******" : null;
        String secretKey = (rawSk != null && !rawSk.isEmpty()) ? "******" : null;

        boolean subscribed = !subscriber.getDataSources().isEmpty();
        int dsCount = subscriber.getDataSources().size();

        List<String> dataSources = new ArrayList<>();
        if (prefix != null && nacosProps != null) {
            dataSources.add(prefix + nacosProps.getFlow().getDataIdSuffix());
            dataSources.add(prefix + nacosProps.getDegrade().getDataIdSuffix());
            dataSources.add(prefix + nacosProps.getParamFlow().getDataIdSuffix());
            dataSources.add(prefix + nacosProps.getSystem().getDataIdSuffix());
            dataSources.add(prefix + nacosProps.getAuthority().getDataIdSuffix());
        }

        return new SentinelPanoramaReport.NacosRulesReport(
                enabled,
                prefix,
                groupId,
                serverAddr,
                namespace,
                rawUsername,
                password,
                accessKey,
                secretKey,
                subscribed,
                dsCount,
                dataSources
        );
    }

    private SentinelPanoramaReport.InMemoryRulesReport buildInMemoryReport() {
        List<FlowRule> rawFlowRules = FlowRuleManager.getRules();
        List<SentinelPanoramaReport.FlowRuleSnapshot> flowRules = Collections.emptyList();
        if (rawFlowRules != null && !rawFlowRules.isEmpty()) {
            flowRules = rawFlowRules.stream()
                    .map(r -> new SentinelPanoramaReport.FlowRuleSnapshot(
                            r.getResource(),
                            r.getLimitApp(),
                            r.getGrade(),
                            r.getCount(),
                            r.getStrategy(),
                            r.getControlBehavior(),
                            r.getRefResource(),
                            r.getWarmUpPeriodSec(),
                            r.getMaxQueueingTimeMs(),
                            r.isClusterMode()
                    )).toList();
        }

        List<DegradeRule> rawDegradeRules = DegradeRuleManager.getRules();
        List<SentinelPanoramaReport.DegradeRuleSnapshot> degradeRules = Collections.emptyList();
        if (rawDegradeRules != null && !rawDegradeRules.isEmpty()) {
            degradeRules = rawDegradeRules.stream()
                    .map(r -> new SentinelPanoramaReport.DegradeRuleSnapshot(
                            r.getResource(),
                            r.getLimitApp(),
                            r.getGrade(),
                            r.getCount(),
                            r.getTimeWindow(),
                            r.getMinRequestAmount(),
                            r.getSlowRatioThreshold(),
                            r.getStatIntervalMs()
                    )).toList();
        }

        List<ParamFlowRule> rawParamRules = ParamFlowRuleManager.getRules();
        List<SentinelPanoramaReport.ParamFlowRuleSnapshot> paramRules = Collections.emptyList();
        if (rawParamRules != null && !rawParamRules.isEmpty()) {
            paramRules = rawParamRules.stream()
                    .map(r -> new SentinelPanoramaReport.ParamFlowRuleSnapshot(
                            r.getResource(),
                            r.getLimitApp(),
                            r.getGrade(),
                            r.getParamIdx() != null ? r.getParamIdx() : 0,
                            r.getCount(),
                            r.getControlBehavior(),
                            r.getMaxQueueingTimeMs(),
                            r.getDurationInSec(),
                            r.isClusterMode()
                    )).toList();
        }

        List<SystemRule> rawSystemRules = SystemRuleManager.getRules();
        List<SentinelPanoramaReport.SystemRuleSnapshot> systemRules = Collections.emptyList();
        if (rawSystemRules != null && !rawSystemRules.isEmpty()) {
            systemRules = rawSystemRules.stream()
                    .map(r -> new SentinelPanoramaReport.SystemRuleSnapshot(
                            r.getHighestSystemLoad(),
                            r.getHighestCpuUsage(),
                            r.getQps(),
                            r.getAvgRt(),
                            r.getMaxThread()
                    )).toList();
        }

        List<AuthorityRule> rawAuthorityRules = AuthorityRuleManager.getRules();
        List<SentinelPanoramaReport.AuthorityRuleSnapshot> authorityRules = Collections.emptyList();
        if (rawAuthorityRules != null && !rawAuthorityRules.isEmpty()) {
            authorityRules = rawAuthorityRules.stream()
                    .map(r -> new SentinelPanoramaReport.AuthorityRuleSnapshot(
                            r.getResource(),
                            r.getLimitApp(),
                            r.getStrategy()
                    )).toList();
        }

        int total = flowRules.size() + degradeRules.size() + paramRules.size() + systemRules.size() + authorityRules.size();

        return new SentinelPanoramaReport.InMemoryRulesReport(
                total,
                flowRules.size(),
                degradeRules.size(),
                paramRules.size(),
                systemRules.size(),
                authorityRules.size(),
                flowRules,
                degradeRules,
                paramRules,
                systemRules,
                authorityRules
        );
    }

    private SentinelPanoramaReport.WebMvcConfigReport buildWebMvcReport() {
        AtengSentinelProperties props = propertiesProvider != null ? propertiesProvider.getIfAvailable() : null;
        if (props == null) {
            props = new AtengSentinelProperties();
        }

        AtengSentinelProperties.UrlCleanProperties urlClean = props.getUrlClean();
        List<String> excludePatterns = urlClean.getExcludePatterns() != null ? urlClean.getExcludePatterns() : Collections.emptyList();
        SentinelPanoramaReport.UrlCleanReport cleanReport = new SentinelPanoramaReport.UrlCleanReport(
                urlClean.isEnabled(),
                urlClean.getNumberReplacement(),
                urlClean.getUuidReplacement(),
                excludePatterns.size(),
                excludePatterns
        );

        AtengSentinelProperties.OriginParserProperties originParser = props.getOriginParser();
        SentinelPanoramaReport.OriginParserReport originReport = new SentinelPanoramaReport.OriginParserReport(
                originParser.getOriginHeader(),
                originParser.getGatewayHeader(),
                originParser.getDefaultOrigin(),
                originParser.isEnableClientIpFallback()
        );

        SentinelPanoramaReport.BlockHandlerReport handlerReport = new SentinelPanoramaReport.BlockHandlerReport(
                props.getBlockHandler().isEnabled(),
                props.getBlockHandler().isHttpStatusAlign()
        );

        SentinelPanoramaReport.BlockAdviceReport adviceReport = new SentinelPanoramaReport.BlockAdviceReport(
                props.getBlockAdvice().isEnabled(),
                props.getBlockAdvice().isHttpStatusAlign()
        );

        return new SentinelPanoramaReport.WebMvcConfigReport(cleanReport, originReport, handlerReport, adviceReport);
    }

    private SentinelPanoramaReport.BlockMetricsReport buildMetricsReport() {
        SentinelBlockCounter counter = counterProvider != null ? counterProvider.getIfAvailable() : null;
        if (counter == null) {
            return new SentinelPanoramaReport.BlockMetricsReport(
                    0, 0, 0, 0, 0, 0, 0, null
            );
        }

        return new SentinelPanoramaReport.BlockMetricsReport(
                counter.getTotalBlocked(),
                counter.getFlowCount(),
                counter.getDegradeCount(),
                counter.getParamFlowCount(),
                counter.getSystemCount(),
                counter.getAuthorityCount(),
                counter.getOtherCount(),
                counter.getLastBlockEvent()
        );
    }
}
