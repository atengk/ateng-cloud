package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextSnapshotProvider;
import io.github.atengk.cloud.traffic.filter.TrafficContextFilter;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 全链路流量治理与灰度调度自动装配配置类
 *
 * @author Ateng
 * @since 2026-10-29
 */
@AutoConfiguration
@EnableConfigurationProperties(AtengTrafficProperties.class)
@ConditionalOnProperty(prefix = TrafficConstants.PROPERTIES_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengTrafficAutoConfiguration {

    /**
     * 注册 Servlet 运行时入站流量染色过滤器
     *
     * @param properties 流量治理配置属性
     * @return 过滤器注册 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<TrafficContextFilter> trafficContextFilter(AtengTrafficProperties properties) {
        FilterRegistrationBean<TrafficContextFilter> registration = new FilterRegistrationBean<>(
                new TrafficContextFilter(properties)
        );
        registration.setOrder(properties.getFilterOrder());
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * 注册异步线程池快照提供者 Bean (同时已通过 ServiceLoader 注册至 AsyncContext)
     *
     * @return 异步上下文快照提供者
     */
    @Bean
    @ConditionalOnMissingBean
    public TrafficContextSnapshotProvider trafficContextSnapshotProvider() {
        return new TrafficContextSnapshotProvider();
    }
}
