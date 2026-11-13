package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.metrics.TrafficMetricsManager;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import io.github.atengk.cloud.traffic.reactive.ReactiveTrafficContextWebFilter;
import io.github.atengk.cloud.traffic.reactive.TrafficExchangeFilterFunction;
import io.github.atengk.cloud.traffic.rule.matcher.TrafficRuleMatcher;
import io.github.atengk.cloud.traffic.rule.provider.TrafficRuleProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilter;

/**
 * WebFlux 响应式流量染色与 WebClient 跨线程泳道透传自动装配配置类
 * <p>
 * 遵循响应式与 Servlet 物理隔离铁律：
 * 仅在 {@code ConditionalOnWebApplication.Type.REACTIVE} 且 Classpath 存在 {@link WebFilter} 与 {@link WebClient} 时激活，
 * 与阻塞式 {@code TrafficContextFilter} 完全隔离零碰撞。
 *
 * @author Ateng
 * @since 2026-11-13
 */
@AutoConfiguration
@AutoConfigureAfter(AtengTrafficAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({WebFilter.class, WebClient.class})
@ConditionalOnProperty(prefix = TrafficConstants.PROPERTIES_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AtengTrafficProperties.class)
public class AtengTrafficReactiveAutoConfiguration {

    /**
     * 注册 WebFlux 响应式入站流量染色评估与双通道绑定过滤器 Bean
     *
     * @param properties             流量治理配置属性
     * @param ruleMatcherProvider    规则匹配引擎提供者
     * @param ruleProvider           规则提供者
     * @param metricsManagerProvider 监控指标管理器提供者
     * @return ReactiveTrafficContextWebFilter
     */
    @Bean
    @ConditionalOnMissingBean
    public ReactiveTrafficContextWebFilter reactiveTrafficContextWebFilter(
            AtengTrafficProperties properties,
            ObjectProvider<TrafficRuleMatcher> ruleMatcherProvider,
            ObjectProvider<TrafficRuleProvider> ruleProvider,
            ObjectProvider<TrafficMetricsManager> metricsManagerProvider) {
        return new ReactiveTrafficContextWebFilter(
                properties,
                ruleMatcherProvider::getIfAvailable,
                ruleProvider::getIfAvailable,
                metricsManagerProvider::getIfAvailable);
    }

    /**
     * 注册 WebClient 响应式出站流量染色透传过滤器 Bean
     *
     * @param properties 流量治理配置属性
     * @return TrafficExchangeFilterFunction
     */
    @Bean
    @ConditionalOnMissingBean
    public TrafficExchangeFilterFunction trafficExchangeFilterFunction(AtengTrafficProperties properties) {
        return new TrafficExchangeFilterFunction(properties);
    }

    /**
     * 注册 WebClient 自动定制器，将响应式透传过滤器附加至 WebClient.Builder
     *
     * @param filterFunction 响应式出站透传过滤器
     * @return WebClientCustomizer
     */
    @Bean(name = "trafficWebClientCustomizer")
    @ConditionalOnMissingBean(name = "trafficWebClientCustomizer")
    @ConditionalOnClass(WebClientCustomizer.class)
    public WebClientCustomizer trafficWebClientCustomizer(TrafficExchangeFilterFunction filterFunction) {
        return webClientBuilder -> webClientBuilder.filters(filters -> {
            if (!filters.contains(filterFunction)) {
                filters.add(filterFunction);
            }
        });
    }
}
