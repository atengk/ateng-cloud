package io.github.atengk.cloud.traffic.nacos;

import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.config.listener.Listener;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.properties.TrafficNacosProperties;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 Nacos 配置中心的动态流量分流规则分级监听器与零锁原子热更引擎
 * <p>
 * 核心机制：
 * 1. 优先监听应用专属分流规则 {@code ${spring.application.name}-traffic-rules.json}，未配置时平滑回退至全局规则 {@code traffic-rules.json}；
 * 2. 内存采用 {@link AtomicReference} 维护生效规则快照，达成读写无锁、毫秒级热更与高并发纳秒级读取；
 * 3. 具备缺省韧性自检：在配置中心未开启或网络不可达时自动捕获降级，不阻塞 Spring 容器启动并打印告警日志；
 * 4. 守护生命周期不变量：容器销毁时幂等移除 Nacos 监听器，杜绝内存与回调句柄悬挂。
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class NacosTrafficRuleListener implements TrafficRuleProvider, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NacosTrafficRuleListener.class);

    private final ObjectProvider<NacosTemplate> nacosTemplateProvider;
    private final AtengTrafficProperties properties;
    private final Environment environment;

    /**
     * 内存当前生效规则快照 (无锁原子读写)
     */
    private final AtomicReference<List<TrafficRule>> activeRules = new AtomicReference<>(Collections.emptyList());

    /**
     * 应用专属规则缓存
     */
    private final AtomicReference<List<TrafficRule>> appRules = new AtomicReference<>(Collections.emptyList());

    /**
     * 全局规则缓存
     */
    private final AtomicReference<List<TrafficRule>> globalRules = new AtomicReference<>(Collections.emptyList());

    /**
     * 是否已成功完成初始加载
     */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private String resolvedAppDataId;
    private String resolvedGlobalDataId;
    private String resolvedGroup;

    private Listener appListener;
    private Listener globalListener;

    public NacosTrafficRuleListener(ObjectProvider<NacosTemplate> nacosTemplateProvider,
                                    AtengTrafficProperties properties,
                                    Environment environment) {
        this.nacosTemplateProvider = nacosTemplateProvider;
        this.properties = properties != null ? properties : new AtengTrafficProperties();
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        resolveDataIdsAndGroup();

        NacosTemplate nacosTemplate = nacosTemplateProvider.getIfAvailable();
        if (nacosTemplate == null || !nacosTemplate.isConfigAvailable()) {
            log.warn("[TrafficNacos] Nacos 配置中心不可用或未启用，流量分流规则监听平滑降级为离线纯静态模式 (Hermetic Offline Mode)");
            return;
        }

        try {
            initListeners(nacosTemplate);
            initialized.set(true);
        } catch (Exception ex) {
            log.warn("[TrafficNacos] 初始化 Nacos 流量分流规则监听器失败，平滑降级 [cause={}]", ex.getMessage(), ex);
        }
    }

    /**
     * 解析应用专属 DataId、全局 DataId 与 Group
     */
    private void resolveDataIdsAndGroup() {
        TrafficNacosProperties nacosProps = properties.getNacos();
        if (nacosProps == null) {
            nacosProps = new TrafficNacosProperties();
        }

        this.resolvedGroup = StringUtils.hasText(nacosProps.getGroup())
                ? nacosProps.getGroup() : "DEFAULT_GROUP";

        this.resolvedGlobalDataId = StringUtils.hasText(nacosProps.getGlobalDataId())
                ? nacosProps.getGlobalDataId() : "traffic-rules.json";

        if (StringUtils.hasText(nacosProps.getAppDataId())) {
            this.resolvedAppDataId = nacosProps.getAppDataId();
        } else {
            String appName = environment != null ? environment.getProperty("spring.application.name") : null;
            if (!StringUtils.hasText(appName)) {
                appName = "application";
            }
            this.resolvedAppDataId = appName + "-traffic-rules.json";
        }
    }

    /**
     * 初始加载规则并向 Nacos 注册动态监听器
     */
    private void initListeners(NacosTemplate nacosTemplate) {
        log.info("[TrafficNacos] 正在初始化 Nacos 动态流量分流规则监听器 [appDataId='{}', globalDataId='{}', group='{}']",
                resolvedAppDataId, resolvedGlobalDataId, resolvedGroup);

        // 1. 初始拉取应用专属配置
        Optional<String> appConfigOpt = nacosTemplate.findConfig(resolvedAppDataId, resolvedGroup);
        appConfigOpt.ifPresent(this::parseAndSetAppRules);

        // 2. 初始拉取全局通用配置
        Optional<String> globalConfigOpt = nacosTemplate.findConfig(resolvedGlobalDataId, resolvedGroup);
        globalConfigOpt.ifPresent(this::parseAndSetGlobalRules);

        // 3. 计算生效快照
        recomputeActiveRules();

        // 4. 注册应用专属规则监听器
        this.appListener = new AbstractListener() {
            @Override
            public void receiveConfigInfo(String configInfo) {
                receiveAppConfig(configInfo);
            }
        };
        nacosTemplate.addConfigListener(resolvedAppDataId, resolvedGroup, this.appListener);

        // 5. 注册全局通用规则监听器
        this.globalListener = new AbstractListener() {
            @Override
            public void receiveConfigInfo(String configInfo) {
                receiveGlobalConfig(configInfo);
            }
        };
        nacosTemplate.addConfigListener(resolvedGlobalDataId, resolvedGroup, this.globalListener);
    }

    /**
     * 响应应用专属规则动态变更推送
     *
     * @param configInfo 新的配置内容
     */
    public void receiveAppConfig(String configInfo) {
        log.info("[TrafficNacos] 接收到应用专属流量规则动态变更推送 [dataId='{}', group='{}']", resolvedAppDataId, resolvedGroup);
        parseAndSetAppRules(configInfo);
        recomputeActiveRules();
    }

    /**
     * 响应全局通用规则动态变更推送
     *
     * @param configInfo 新的配置内容
     */
    public void receiveGlobalConfig(String configInfo) {
        log.info("[TrafficNacos] 接收到全局流量分流规则动态变更推送 [dataId='{}', group='{}']", resolvedGlobalDataId, resolvedGroup);
        parseAndSetGlobalRules(configInfo);
        recomputeActiveRules();
    }

    /**
     * 解析并设置应用专属规则
     */
    private void parseAndSetAppRules(String configInfo) {
        List<TrafficRule> parsed = parseRules(configInfo, resolvedAppDataId);
        if (parsed != null) {
            appRules.set(parsed);
        }
    }

    /**
     * 解析并设置全局通用规则
     */
    private void parseAndSetGlobalRules(String configInfo) {
        List<TrafficRule> parsed = parseRules(configInfo, resolvedGlobalDataId);
        if (parsed != null) {
            globalRules.set(parsed);
        }
    }

    /**
     * 反序列化 JSON 规则列表；语法错误时打印告警并返回 null，以保全既有规则快照
     */
    private List<TrafficRule> parseRules(String configInfo, String dataId) {
        if (!StringUtils.hasText(configInfo)) {
            return Collections.emptyList();
        }
        try {
            List<TrafficRule> rules = JsonUtil.parseList(configInfo.trim(), TrafficRule.class);
            return rules != null ? rules : Collections.emptyList();
        } catch (Exception ex) {
            log.error("[TrafficNacos] 解析 Nacos 流量分流规则 JSON 失败，保持既有规则快照不变 [dataId='{}', content='{}']",
                    dataId, configInfo, ex);
            return null;
        }
    }

    /**
     * 依据优先策略重新计算并在内存原子替换当前生效规则快照
     */
    private void recomputeActiveRules() {
        TrafficNacosProperties nacosProps = properties.getNacos();
        boolean preferAppRule = nacosProps == null || nacosProps.isPreferAppRule();
        String mergeStrategy = nacosProps != null ? nacosProps.getMergeStrategy() : "OVERWRITE";

        List<TrafficRule> currentAppRules = appRules.get();
        List<TrafficRule> currentGlobalRules = globalRules.get();

        List<TrafficRule> merged;
        if ("MERGE".equalsIgnoreCase(mergeStrategy)) {
            // 合并应用规则与全局规则并按优先级升序排序
            List<TrafficRule> combined = new ArrayList<>(currentAppRules.size() + currentGlobalRules.size());
            combined.addAll(currentAppRules);
            combined.addAll(currentGlobalRules);
            combined.sort(Comparator.comparingInt(TrafficRule::getPriority));
            merged = Collections.unmodifiableList(combined);
        } else {
            // 默认 OVERWRITE: 优先应用专属规则，存在专属规则即采用；若专属规则为空则回退至全局规则
            if (preferAppRule && !currentAppRules.isEmpty()) {
                merged = Collections.unmodifiableList(new ArrayList<>(currentAppRules));
            } else {
                merged = Collections.unmodifiableList(new ArrayList<>(currentGlobalRules));
            }
        }

        activeRules.set(merged);
        log.info("[TrafficNacos] 流量分流生效规则已无锁原子热更新 [activeRulesCount={}, preferApp={}, strategy={}]",
                merged.size(), preferAppRule, mergeStrategy);
    }

    @Override
    public List<TrafficRule> getRules() {
        List<TrafficRule> rules = activeRules.get();
        return rules != null ? rules : Collections.emptyList();
    }

    public List<TrafficRule> getAppRules() {
        List<TrafficRule> rules = appRules.get();
        return rules != null ? rules : Collections.emptyList();
    }

    public List<TrafficRule> getGlobalRules() {
        List<TrafficRule> rules = globalRules.get();
        return rules != null ? rules : Collections.emptyList();
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public String getResolvedAppDataId() {
        return resolvedAppDataId;
    }

    public String getResolvedGlobalDataId() {
        return resolvedGlobalDataId;
    }

    public String getResolvedGroup() {
        return resolvedGroup;
    }

    @Override
    public void destroy() {
        NacosTemplate nacosTemplate = nacosTemplateProvider.getIfAvailable();
        if (nacosTemplate != null && nacosTemplate.isConfigAvailable()) {
            try {
                if (appListener != null) {
                    nacosTemplate.removeConfigListener(resolvedAppDataId, resolvedGroup, appListener);
                }
                if (globalListener != null) {
                    nacosTemplate.removeConfigListener(resolvedGlobalDataId, resolvedGroup, globalListener);
                }
                log.info("[TrafficNacos] 已成功注销 Nacos 流量分流规则监听器");
            } catch (Exception ex) {
                log.warn("[TrafficNacos] 注销 Nacos 监听器时发生异常: {}", ex.getMessage());
            }
        }
    }
}
