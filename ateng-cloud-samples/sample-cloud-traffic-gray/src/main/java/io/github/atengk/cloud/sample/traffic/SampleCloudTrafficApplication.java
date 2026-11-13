package io.github.atengk.cloud.sample.traffic;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequestTransformer;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 全链路微服务流量染色与金丝雀灰度场景演示应用
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootApplication
@EnableFeignClients
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
     * 具备 Spring Cloud LoadBalancer 负载均衡能力的 Spring 6 RestClient.Builder (显式配置连接与读超时)
     */
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(ObjectProvider<RestClientCustomizer> customizers) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder;
    }

    /**
     * 基于负载均衡 RestClient.Builder 构建的无状态 Spring 6 RestClient 客户端
     */
    @Bean
    public RestClient loadBalancedRestClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder) {
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
