package io.github.atengk.cloud.sample.nacos.config;

import io.github.atengk.cloud.nacos.constant.ConfigFormat;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.sample.nacos.model.TradeRuleConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 编程式动态配置泛型强类型订阅演示服务
 *
 * <p>基于 {@link NacosTemplate#subscribeConfig(String, String, Class, java.util.function.Consumer)}
 * 演示自动反序列化、MDC 链路传递与受管生命周期注销。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class DemoTypedSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(DemoTypedSubscriptionService.class);

    public static final String DATA_ID_TRADE_RULE = "trade-rule.json";

    private final NacosTemplate nacosTemplate;
    private final AtomicInteger notificationCount = new AtomicInteger(0);
    private final AtomicReference<TradeRuleConfig> currentRule = new AtomicReference<>();
    private final AtomicReference<String> lastTraceId = new AtomicReference<>("");
    private AutoCloseable subscriptionHandle;

    public DemoTypedSubscriptionService(NacosTemplate nacosTemplate) {
        this.nacosTemplate = nacosTemplate;
    }

    @PostConstruct
    public void init() {
        // 编程式订阅 trade-rule.json 泛型强类型配置集，自动挂载 MDC traceId 穿透与异常屏障
        try {
            this.subscriptionHandle = nacosTemplate.subscribeConfig(
                    DATA_ID_TRADE_RULE,
                    "DEFAULT_GROUP",
                    TradeRuleConfig.class,
                    this::onTradeRuleUpdated
            );
            log.info("[DemoTypedSubscriptionService] 成功注册 '{}' 泛型强类型动态配置订阅句柄", DATA_ID_TRADE_RULE);
        } catch (Exception ex) {
            log.warn("[DemoTypedSubscriptionService] 初始化动态配置订阅跳过 (离线或无可用配置中心): {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (subscriptionHandle != null) {
            try {
                subscriptionHandle.close();
                log.info("[DemoTypedSubscriptionService] 已安全注销 '{}' 动态配置订阅句柄", DATA_ID_TRADE_RULE);
            } catch (Exception ex) {
                log.warn("Error closing subscription: {}", ex.getMessage());
            }
        }
    }

    /**
     * 模拟 Nacos 服务端动态下发配置原始文本，全真经过 NacosConfigParser 泛型反序列化与 MDC 链路装饰
     */
    public void simulateConfigPush(String rawConfigJson) {
        String traceId = MDC.get("traceId");
        boolean traceCreated = false;
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            MDC.put("traceId", traceId);
            traceCreated = true;
        }
        try {
            TradeRuleConfig parsed = nacosTemplate.getConfigParser().parse(
                    rawConfigJson, ConfigFormat.JSON, TradeRuleConfig.class);
            if (parsed != null) {
                onTradeRuleUpdated(parsed);
            }
        } finally {
            if (traceCreated) {
                MDC.remove("traceId");
            }
        }
    }

    public void onTradeRuleUpdated(TradeRuleConfig rule) {
        int count = notificationCount.incrementAndGet();
        currentRule.set(rule);
        String traceId = MDC.get("traceId");
        lastTraceId.set(traceId != null ? traceId : "");
        log.info("[DemoTypedSubscriptionService] 收到交易规则动态变更通知 #{}, traceId={}, ruleName={}, limit={}",
                count, traceId, rule.getRuleName(), rule.getMaxLimit());
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
}
