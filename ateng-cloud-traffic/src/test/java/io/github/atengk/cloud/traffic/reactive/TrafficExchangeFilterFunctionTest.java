package io.github.atengk.cloud.traffic.reactive;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.loadbalancer.TrafficTagServiceInstanceListSupplier;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebClient 响应式跨线程流量染色透传过滤器单元测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
class TrafficExchangeFilterFunctionTest {

    private AtengTrafficProperties properties;
    private TrafficExchangeFilterFunction filterFunction;

    @BeforeEach
    void setUp() {
        properties = new AtengTrafficProperties();
        filterFunction = new TrafficExchangeFilterFunction(properties);
        TrafficContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
    }

    @Test
    @DisplayName("测试跨 Schedulers.boundedElastic 线程切换后从 Reactor Context 自动透传并被 LoadBalancer 识别")
    void testCrossThreadPropagationAndLoadBalancerRecognition() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction stubExchange = req -> {
            capturedRequest.set(req);
            return Mono.just(ClientResponse.create(HttpStatus.OK).body("ok").build());
        };
        WebClient webClient = WebClient.builder()
                .filter(filterFunction)
                .exchangeFunction(stubExchange)
                .build();

        // 确保当前线程及 boundedElastic 工作线程的 ThreadLocal 上下文均为空
        TrafficContextHolder.clear();

        String body = Mono.defer(() -> webClient.get().uri("http://order-service/api/ping")
                        .retrieve()
                        .bodyToMono(String.class))
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(ctx -> ctx.put(TrafficContext.class, TrafficContext.of("v2-reactive")))
                .block();

        assertThat(body).isEqualTo("ok");
        ClientRequest outboundRequest = capturedRequest.get();
        assertThat(outboundRequest).isNotNull();
        assertThat(outboundRequest.headers().getFirst(TrafficConstants.HEADER_TRAFFIC_TAG)).isEqualTo("v2-reactive");

        // 模拟 ReactorLoadBalancer 通过 RequestDataContext 识别出站 ClientRequest 中的灰度标签
        ServiceInstance baseInstance = new DefaultServiceInstance("base-1", "order-service", "10.0.0.1", 8080, false, Collections.emptyMap());
        ServiceInstance grayInstance = new DefaultServiceInstance("gray-1", "order-service", "10.0.0.2", 8080, false, Map.of("tag", "v2-reactive"));
        ServiceInstanceListSupplier delegate = new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return "order-service";
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(List.of(baseInstance, grayInstance));
            }
        };
        TrafficTagServiceInstanceListSupplier tagSupplier = new TrafficTagServiceInstanceListSupplier(delegate, properties);

        RequestData requestData = new RequestData(outboundRequest);
        List<ServiceInstance> selected = tagSupplier.get(new DefaultRequest<>(new RequestDataContext(requestData))).blockFirst();
        assertThat(selected).containsExactly(grayInstance);
    }

    @Test
    @DisplayName("测试显式同名标头不覆盖、TrafficContextHolder 兜底以及 laneContinuity=false 时跳过注入")
    void testExplicitHeaderPreservedHolderFallbackAndDisabled() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction stubExchange = req -> {
            capturedRequest.set(req);
            return Mono.just(ClientResponse.create(HttpStatus.OK).body("ok").build());
        };
        WebClient webClient = WebClient.builder()
                .filter(filterFunction)
                .exchangeFunction(stubExchange)
                .build();

        // 1. Reactor Context 无标签时兜底读取 TrafficContextHolder
        TrafficContextHolder.setTag("holder-v3");
        webClient.get().uri("http://order-service/api/1").retrieve().bodyToMono(String.class).block();
        assertThat(capturedRequest.get().headers().getFirst(TrafficConstants.HEADER_TRAFFIC_TAG)).isEqualTo("holder-v3");

        // 2. 调用方显式指定有效同名标头时绝不覆盖
        webClient.get().uri("http://order-service/api/2")
                .header(TrafficConstants.HEADER_TRAFFIC_TAG, "explicit-tag")
                .retrieve().bodyToMono(String.class).block();
        assertThat(capturedRequest.get().headers().getFirst(TrafficConstants.HEADER_TRAFFIC_TAG)).isEqualTo("explicit-tag");

        // 3. laneContinuity=false 时不注入
        properties.setLaneContinuity(false);
        webClient.get().uri("http://order-service/api/3").retrieve().bodyToMono(String.class).block();
        assertThat(capturedRequest.get().headers().containsKey(TrafficConstants.HEADER_TRAFFIC_TAG)).isFalse();
    }
}
