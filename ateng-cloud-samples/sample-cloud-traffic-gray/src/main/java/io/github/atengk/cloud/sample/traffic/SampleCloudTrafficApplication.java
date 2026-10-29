package io.github.atengk.cloud.sample.traffic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * 全链路微服务流量染色与金丝雀灰度场景演示应用
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootApplication
public class SampleCloudTrafficApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleCloudTrafficApplication.class, args);
    }

    /**
     * 具备 Spring Cloud LoadBalancer 负载均衡能力的 RestTemplate 客户端
     */
    @Bean
    @LoadBalanced
    public RestTemplate loadBalancedRestTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    /**
     * 将负载均衡器选中的实例元数据写入出站请求头，便于多跳调用轨迹观测
     */
    @Bean
    public LoadBalancerRequestTransformer instanceMetadataHeaderTransformer() {
        return (request, instance) -> {
            if (instance != null) {
                String tag = instance.getMetadata().getOrDefault("tag", "none");
                request.getHeaders().add("X-Selected-Instance-Id", instance.getInstanceId());
                request.getHeaders().add("X-Selected-Instance-Tag", tag);
            }
            return request;
        };
    }
}
