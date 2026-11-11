package io.github.atengk.cloud.sample.nacos.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import io.github.atengk.cloud.nacos.event.SafeConfigRefreshFailureEvent;
import io.github.atengk.cloud.nacos.mask.ConfigMasker;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.sample.nacos.config.DemoNacosConfigChangeListener;
import io.github.atengk.cloud.sample.nacos.config.DemoSafeRefreshEventListener;
import io.github.atengk.cloud.sample.nacos.config.DemoTypedSubscriptionService;
import io.github.atengk.cloud.sample.nacos.config.OrderConfigProperties;
import io.github.atengk.cloud.sample.nacos.event.DemoConfigRefreshFailureEventListener;
import io.github.atengk.cloud.sample.nacos.model.ListenerStatusVO;
import io.github.atengk.cloud.sample.nacos.model.OrderConfigVO;
import io.github.atengk.cloud.sample.nacos.model.RefreshFailureStatusVO;
import io.github.atengk.cloud.sample.nacos.model.RefreshRequestDTO;
import io.github.atengk.cloud.sample.nacos.model.SafeConfigRefreshVO;
import io.github.atengk.cloud.sample.nacos.model.TradeRuleConfig;
import io.github.atengk.cloud.sample.nacos.model.TradeRuleListVO;
import io.github.atengk.cloud.sample.nacos.model.TradeRuleVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;

/**
 * Nacos 现代配置中心安全动态热更与电商订单配置演示控制器
 *
 * <p>显式遵循强类型 {@link Result} 契约与 JSR-303 格式校验防卫，演示纯属性增量重绑、
 * 敏感脱敏掩码与 SPI / EventListener 双模协同。</p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/nacos/config")
public class NacosConfigController {

    private final OrderConfigProperties orderConfigProperties;
    private final NacosTemplate nacosTemplate;
    private final DemoNacosConfigChangeListener demoListener;
    private final DemoSafeRefreshEventListener demoEventListener;
    private final DemoConfigRefreshFailureEventListener failureEventListener;
    private final DemoTypedSubscriptionService typedSubscriptionService;

    public NacosConfigController(OrderConfigProperties orderConfigProperties,
                                 NacosTemplate nacosTemplate,
                                 DemoNacosConfigChangeListener demoListener,
                                 DemoSafeRefreshEventListener demoEventListener,
                                 DemoConfigRefreshFailureEventListener failureEventListener,
                                 DemoTypedSubscriptionService typedSubscriptionService) {
        this.orderConfigProperties = orderConfigProperties;
        this.nacosTemplate = nacosTemplate;
        this.demoListener = demoListener;
        this.demoEventListener = demoEventListener;
        this.failureEventListener = failureEventListener;
        this.typedSubscriptionService = typedSubscriptionService;
    }

    /**
     * 读取当前电商订单业务配置（展示脱敏视图）
     */
    @GetMapping("/current")
    public Result<OrderConfigVO> getCurrentConfig() {
        String maskedKey = ConfigMasker.mask("sample.order.config.pay-channel-secret-key", orderConfigProperties.getPayChannelSecretKey());
        OrderConfigVO vo = new OrderConfigVO(
                orderConfigProperties.getFreightRate(),
                orderConfigProperties.getFreeFreightThreshold(),
                maskedKey,
                orderConfigProperties.getOrderTimeout(),
                orderConfigProperties.getMaxItemsPerOrder()
        );
        return Result.success(vo);
    }

    /**
     * 读取内存中的真实解密明文（验证“内存真明文”不变量）
     */
    @GetMapping("/secret")
    public Result<String> getRawSecret() {
        return Result.success(orderConfigProperties.getPayChannelSecretKey());
    }

    /**
     * 读取受管监听器触发状态统计
     */
    @GetMapping("/listener-status")
    public Result<ListenerStatusVO> getListenerStatus() {
        ListenerStatusVO vo = new ListenerStatusVO(
                demoListener.getChangeCount(),
                demoEventListener.getEventCount(),
                demoListener.getLastEvent() != null ? demoListener.getLastEvent().getTraceId() : "",
                demoEventListener.getLastEvent() != null ? demoEventListener.getLastEvent().getTraceId() : ""
        );
        return Result.success(vo);
    }

    /**
     * 模拟主动触发配置属性安全增量热更
     */
    @PostMapping("/simulate-refresh")
    public Result<SafeConfigRefreshVO> simulateRefresh(@Valid @RequestBody RefreshRequestDTO request) {
        SafeConfigRefreshEvent event = nacosTemplate.refreshConfig(request.getKeys());
        SafeConfigRefreshVO vo = new SafeConfigRefreshVO(
                event.getTraceId(),
                event.getChangedKeys(),
                event.getChangedKeys().size(),
                true
        );
        return Result.success(vo);
    }

    /**
     * 读取配置热更异常告警与失败事件统计状态
     */
    @GetMapping("/refresh-failure-status")
    public Result<RefreshFailureStatusVO> getRefreshFailureStatus() {
        SafeConfigRefreshFailureEvent lastEvent = failureEventListener.getLastEvent();
        RefreshFailureStatusVO vo = new RefreshFailureStatusVO(
                failureEventListener.getEventCount(),
                lastEvent != null ? lastEvent.getDataId() : "",
                lastEvent != null ? lastEvent.getGroup() : "",
                lastEvent != null ? lastEvent.getChangedKeys() : java.util.Collections.emptySet(),
                lastEvent != null ? lastEvent.getErrorMessage() : "",
                lastEvent != null ? lastEvent.getTraceId() : ""
        );
        return Result.success(vo);
    }

    /**
     * 模拟触发配置热更失败场景（演示异常隔离屏障与失败事件派发）
     */
    @PostMapping("/simulate-refresh-failure")
    public Result<SafeConfigRefreshVO> simulateRefreshFailure() {
        SafeConfigRefreshEvent event = nacosTemplate.refreshConfig(
                java.util.Set.of("sample.order.config.simulate-failure"),
                "order-service-failure.yml",
                "DEFAULT_GROUP"
        );
        SafeConfigRefreshVO vo = new SafeConfigRefreshVO(
                event.getTraceId(),
                event.getChangedKeys(),
                event.getChangedKeys().size(),
                false
        );
        return Result.success(vo);
    }

    /**
     * 读取当前编程式泛型订阅的交易规则状态
     */
    @GetMapping("/trade-rule")
    public Result<TradeRuleVO> getTradeRule() {
        TradeRuleConfig rule = typedSubscriptionService.getCurrentRule();
        TradeRuleVO vo = new TradeRuleVO(
                rule != null ? rule.getRuleName() : "default-rule",
                rule != null ? rule.getMaxLimit() : 1000,
                rule != null && rule.isEnabled(),
                typedSubscriptionService.getLastTraceId(),
                typedSubscriptionService.getNotificationCount()
        );
        return Result.success(vo);
    }

    /**
     * 模拟推送交易规则并触发强类型泛型回调
     */
    @PostMapping("/trade-rule/simulate")
    public Result<TradeRuleVO> simulateTradeRuleUpdate(@RequestBody @Valid TradeRuleConfig rule) {
        try {
            String rawJson = JsonUtil.toJsonString(rule);
            typedSubscriptionService.simulateConfigPush(rawJson);
        } catch (Exception ex) {
            typedSubscriptionService.onTradeRuleUpdated(rule);
        }
        return getTradeRule();
    }

    /**
     * 读取当前复杂泛型订阅的多级交易规则列表状态
     */
    @GetMapping("/trade-rules")
    public Result<TradeRuleListVO> getTradeRules() {
        TradeRuleListVO vo = new TradeRuleListVO(
                typedSubscriptionService.getCurrentRules(),
                typedSubscriptionService.getLastListTraceId(),
                typedSubscriptionService.getListNotificationCount()
        );
        return Result.success(vo);
    }

    /**
     * 模拟推送多级交易规则列表并触发 {@code TypeReference<List<TradeRuleConfig>>} 复杂泛型回调
     */
    @PostMapping("/trade-rules/simulate")
    public Result<TradeRuleListVO> simulateTradeRulesUpdate(@RequestBody @Valid ValidList<TradeRuleConfig> rules) {
        String rawJson = JsonUtil.toJsonString(new ArrayList<>(rules));
        typedSubscriptionService.simulateRulesConfigPush(rawJson);
        return getTradeRules();
    }
}
