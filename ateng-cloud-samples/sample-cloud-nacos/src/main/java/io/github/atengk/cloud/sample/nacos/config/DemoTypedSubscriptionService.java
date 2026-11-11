package io.github.atengk.cloud.sample.nacos.config;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.cloud.nacos.constant.ConfigFormat;
import io.github.atengk.cloud.nacos.context.NacosContextBarrier;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.sample.nacos.model.TradeRuleConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 编程式动态配置泛型强类型订阅演示服务
 *
 * <p>基于 {@link NacosTemplate#subscribeConfig(String, String, Class, java.util.function.Consumer)}
 * 与 {@link NacosTemplate#subscribeConfig(String, String, TypeReference, java.util.function.Consumer)}
 * 演示单对象与复杂泛型集合反序列化、三元上下文隔离屏障及受管生命周期注销。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class DemoTypedSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(DemoTypedSubscriptionService.class);

    public static final String DATA_ID_TRADE_RULE = "trade-rule.json";
    public static final String DATA_ID_TRADE_RULES = "trade-rules.json";

    private final NacosTemplate nacosTemplate;
    private final AtomicInteger notificationCount = new AtomicInteger(0);
    private final AtomicReference<TradeRuleConfig> currentRule = new AtomicReference<>();
    private final AtomicReference<String> lastTraceId = new AtomicReference<>("");
    private AutoCloseable subscriptionHandle;

    private final AtomicInteger listNotificationCount = new AtomicInteger(0);
    private final AtomicReference<List<TradeRuleConfig>> currentRules = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<String> lastListTraceId = new AtomicReference<>("");
    private AutoCloseable rulesSubscriptionHandle;

    public DemoTypedSubscriptionService(NacosTemplate nacosTemplate) {
        this.nacosTemplate = nacosTemplate;
    }

    @PostConstruct
    public void init() {
        // 1. 编程式订阅 trade-rule.json 单类型配置集
        try {
            this.subscriptionHandle = nacosTemplate.subscribeConfig(
                    DATA_ID_TRADE_RULE,
                    "DEFAULT_GROUP",
                    TradeRuleConfig.class,
                    this::onTradeRuleUpdated
            );
            log.info("[DemoTypedSubscriptionService] 成功注册 '{}' 泛型强类型动态配置订阅句柄", DATA_ID_TRADE_RULE);
        } catch (Exception ex) {
            log.warn("[DemoTypedSubscriptionService] 初始化单规则动态配置订阅跳过 (离线或无可用配置中心): {}", ex.getMessage());
        }

        // 2. 编程式订阅 trade-rules.json 复杂泛型 List<TradeRuleConfig> 多级规则配置集
        try {
            this.rulesSubscriptionHandle = nacosTemplate.subscribeConfig(
                    DATA_ID_TRADE_RULES,
                    "DEFAULT_GROUP",
                    new TypeReference<List<TradeRuleConfig>>() {},
                    this::onTradeRulesUpdated
            );
            log.info("[DemoTypedSubscriptionService] 成功注册 '{}' 复杂泛型列表动态配置订阅句柄", DATA_ID_TRADE_RULES);
        } catch (Exception ex) {
            log.warn("[DemoTypedSubscriptionService] 初始化多级规则列表订阅跳过 (离线或无可用配置中心): {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        closeHandle(subscriptionHandle, DATA_ID_TRADE_RULE);
        closeHandle(rulesSubscriptionHandle, DATA_ID_TRADE_RULES);
    }

    private void closeHandle(AutoCloseable handle, String dataId) {
        if (handle != null) {
            try {
                handle.close();
                log.info("[DemoTypedSubscriptionService] 已安全注销 '{}' 动态配置订阅句柄", dataId);
            } catch (Exception ex) {
                log.warn("Error closing subscription for '{}': {}", dataId, ex.getMessage());
            }
        }
    }

    /**
     * 模拟 Nacos 服务端动态下发单规则配置文本，全真经过 NacosConfigParser 反序列化与三元上下文隔离屏障
     */
    public void simulateConfigPush(String rawConfigJson) {
        NacosContextBarrier.run(() -> {
            TradeRuleConfig parsed = nacosTemplate.getConfigParser().parse(
                    rawConfigJson, ConfigFormat.JSON, TradeRuleConfig.class);
            if (parsed != null) {
                onTradeRuleUpdated(parsed);
            }
        });
    }

    /**
     * 模拟 Nacos 服务端动态下发多级规则列表文本，验证 TypeReference 复杂泛型反序列化与上下文隔离屏障
     */
    public void simulateRulesConfigPush(String rawRulesJson) {
        NacosContextBarrier.run(() -> {
            List<TradeRuleConfig> parsed = nacosTemplate.getConfigParser().parse(
                    rawRulesJson, ConfigFormat.JSON, new TypeReference<List<TradeRuleConfig>>() {});
            if (parsed != null) {
                onTradeRulesUpdated(parsed);
            }
        });
    }

    public void onTradeRuleUpdated(TradeRuleConfig rule) {
        int count = notificationCount.incrementAndGet();
        currentRule.set(rule);
        String traceId = MDC.get("traceId");
        lastTraceId.set(traceId != null ? traceId : "");
        log.info("[DemoTypedSubscriptionService] 收到交易规则动态变更通知 #{}, traceId={}, ruleName={}, limit={}",
                count, traceId, rule.getRuleName(), rule.getMaxLimit());
    }

    public void onTradeRulesUpdated(List<TradeRuleConfig> rules) {
        int count = listNotificationCount.incrementAndGet();
        List<TradeRuleConfig> safeList = rules != null
                ? Collections.unmodifiableList(new ArrayList<>(rules))
                : Collections.emptyList();
        currentRules.set(safeList);
        String traceId = MDC.get("traceId");
        lastListTraceId.set(traceId != null ? traceId : "");
        log.info("[DemoTypedSubscriptionService] 收到多级交易规则列表变更通知 #{}, traceId={}, rulesCount={}",
                count, traceId, safeList.size());
    }

    public TradeRuleConfig getCurrentRule() {
        return currentRule.get();
    }

    public int getNotificationCount() {
        return notificationCount.get();
    }

    public String getLastTraceId() {
        return lastTraceId.get();
    }

    public List<TradeRuleConfig> getCurrentRules() {
        return currentRules.get();
    }

    public int getListNotificationCount() {
        return listNotificationCount.get();
    }

    public String getLastListTraceId() {
        return lastListTraceId.get();
    }
}
