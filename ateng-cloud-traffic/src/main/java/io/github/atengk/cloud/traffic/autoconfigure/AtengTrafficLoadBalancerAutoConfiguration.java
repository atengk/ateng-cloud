package io.github.atengk.cloud.traffic.autoconfigure;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.loadbalancer.AtengTrafficLoadBalancerClientConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;

/**
 * 全链路流量治理 Spring Cloud LoadBalancer 复合责任链自动装配
 *
 * <p>核心装配时序不变量：
 * 1. 在 {@code AtengNacosLoadBalancerAutoConfiguration} 之前执行装配，优先由三层复合责任链接管客户端上下文；
 * 2. 在 {@code AtengGatewayAutoConfiguration} 之后执行，避免对纯响应式网关环境产生重复干预；
 * 3. 默认向微服务东西向 Feign / RestTemplate 客户端注入版本隔离与机房亲和责任链。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@AutoConfiguration
@AutoConfigureBefore(name = "io.github.atengk.cloud.nacos.loadbalancer.AtengNacosLoadBalancerAutoConfiguration")
@AutoConfigureAfter(name = "io.github.atengk.cloud.gateway.autoconfigure.AtengGatewayAutoConfiguration")
@ConditionalOnClass({ServiceInstanceListSupplier.class, LoadBalancerClientFactory.class})
@ConditionalOnProperty(prefix = TrafficConstants.PROPERTIES_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@LoadBalancerClients(defaultConfiguration = AtengTrafficLoadBalancerClientConfiguration.class)
public class AtengTrafficLoadBalancerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengTrafficLoadBalancerAutoConfiguration.class);

    public AtengTrafficLoadBalancerAutoConfiguration() {
        log.info("[ateng-cloud-traffic] 全链路流量灰度 LoadBalancer 复合责任链已激活 (Three-Tier Routing Chain active).");
    }
}
