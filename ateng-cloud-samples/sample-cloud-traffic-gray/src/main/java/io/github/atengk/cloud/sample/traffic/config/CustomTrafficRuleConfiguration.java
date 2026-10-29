package io.github.atengk.cloud.sample.traffic.config;

import io.github.atengk.cloud.traffic.rule.model.TrafficCondition;
import io.github.atengk.cloud.traffic.rule.model.TrafficFieldSource;
import io.github.atengk.cloud.traffic.rule.model.TrafficMatchOperator;
import io.github.atengk.cloud.traffic.rule.model.TrafficRule;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * 自定义流量规则提供者 SPI 扩展接管演示配置类
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Configuration
@ConditionalOnProperty(prefix = "ateng.cloud.traffic.sample", name = "custom-rule-provider-enabled", havingValue = "true")
public class CustomTrafficRuleConfiguration {

    @Bean
    @Primary
    public TrafficRuleProvider customTrafficRuleProvider() {
        return () -> List.of(
                TrafficRule.builder()
                        .id("rule-vip-channel")
                        .targetTag("v2")
                        .conditions(List.of(
                                TrafficCondition.builder()
                                        .fieldSource(TrafficFieldSource.HEADER)
                                        .fieldName("X-Channel")
                                        .operator(TrafficMatchOperator.EXACT)
                                        .expectedValue("vip_wechat")
                                        .build()
                        ))
                        .build()
        );
    }
}
