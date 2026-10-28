package io.github.atengk.cloud.sample.nacos.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.nacos.event.SafeConfigRefreshEvent;
import io.github.atengk.cloud.nacos.mask.ConfigMasker;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.sample.nacos.config.DemoConfigProperties;
import io.github.atengk.cloud.sample.nacos.config.DemoNacosConfigChangeListener;
import io.github.atengk.cloud.sample.nacos.config.DemoSafeRefreshEventListener;
import io.github.atengk.cloud.sample.nacos.config.DemoTypedSubscriptionService;
import io.github.atengk.cloud.sample.nacos.model.DemoConfigVO;
import io.github.atengk.cloud.sample.nacos.model.ListenerStatusVO;
import io.github.atengk.cloud.sample.nacos.model.RefreshRequestDTO;
import io.github.atengk.cloud.sample.nacos.model.SafeConfigRefreshVO;
import io.github.atengk.cloud.sample.nacos.model.TradeRuleConfig;
import io.github.atengk.cloud.sample.nacos.model.TradeRuleVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nacos 现代配置中心安全动态热更演示控制器
 *
 * <p>显式遵循强类型 {@link Result} 契约与 JSR-303 格式校验防卫，演示纯属性增量重绑、
 * 敏感脱敏掩码与 SPI / EventListener 双模协同。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/nacos/config")
public class NacosConfigDemoController {

    private final DemoConfigProperties demoConfigProperties;
    private final NacosTemplate nacosTemplate;
    private final DemoNacosConfigChangeListener demoListener;
    private final DemoSafeRefreshEventListener demoEventListener;
    private final DemoTypedSubscriptionService typedSubscriptionService;

    public NacosConfigDemoController(DemoConfigProperties demoConfigProperties,
                                     NacosTemplate nacosTemplate,
                                     DemoNacosConfigChangeListener demoListener,
                                     DemoSafeRefreshEventListener demoEventListener,
                                     DemoTypedSubscriptionService typedSubscriptionService) {
        this.demoConfigProperties = demoConfigProperties;
        this.nacosTemplate = nacosTemplate;
        this.demoListener = demoListener;
        this.demoEventListener = demoEventListener;
        this.typedSubscriptionService = typedSubscriptionService;
    }

    /**
     * 读取当前业务配置（展示脱敏视图）
     */
    @GetMapping("/current")
    public Result<DemoConfigVO> getCurrentConfig() {
        String maskedKey = ConfigMasker.mask("demo.config.secret-key", demoConfigProperties.getSecretKey());
        DemoConfigVO vo = new DemoConfigVO(
                demoConfigProperties.getTitle(),
                maskedKey,
                demoConfigProperties.getTimeout(),
                demoConfigProperties.getPoolSize()
        );
        return Result.success(vo);
    }

    /**
     * 读取内存中的真实解密明文（验证“内存真明文”不变量）
     */
    @GetMapping("/secret")
    public Result<String> getRawSecret() {
        return Result.success(demoConfigProperties.getSecretKey());
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
            String rawJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rule);
            typedSubscriptionService.simulateConfigPush(rawJson);
        } catch (Exception ex) {
            typedSubscriptionService.onTradeRuleUpdated(rule);
        }
        return getTradeRule();
    }
}
