package io.github.atengk.cloud.sample.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.route.NacosRouteDefinitionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复合智能负载均衡全域最高测试接缝（HTTP 外部调用契约边界）端到端集成测试 (100% 离线自洽)
 *
 * <p>核心端到端治理语义验证：
 * 1. 灰度分流与同机房亲和：携带灰度标签的请求精准命中同机房灰度实例；
 * 2. 灰度全下线平滑降级：灰度实例全宕时平滑降级调度至基线默认实例（HTTP 200，杜绝 503）；
 * 3. 基线流量安全隔离：普通未染色请求绝对不落入金丝雀节点；
 * 4. 冷启动平滑预热升权：包含启动时间戳的新节点在预热期内流量呈现平滑升权分布；
 * 5. 节点过载自适应加权与容灾：过载节点流量显著降低，全集群过载时保底回退为均匀轮询；
 * 6. 双轨动态路由即时生效：独立数据源 (Track A) 与微服务主配置 (Track B) 路由变更即时消费。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.loadbalancer.zone=zone-a",
                "ateng.cloud.gateway.traffic.enabled=true",
                "ateng.cloud.gateway.traffic.fallback-to-default=true",
                "ateng.cloud.nacos.loadbalancer.enabled=true",
                "ateng.cloud.nacos.loadbalancer.zone-affinity=true",
                "ateng.cloud.nacos.loadbalancer.warmup=true",
                "ateng.cloud.nacos.loadbalancer.adaptive-weight.enabled=true",
                "ateng.cloud.nacos.loadbalancer.adaptive-weight.medium-load-threshold=0.70",
                "ateng.cloud.nacos.loadbalancer.adaptive-weight.high-load-threshold=0.85"
        }
)
@AutoConfigureWebTestClient(timeout = "15000")
@Import(SampleGatewayHttpBoundaryE2EIntegrationTest.HttpBoundaryTestConfig.class)
public class SampleGatewayHttpBoundaryE2EIntegrationTest {

    private static final Map<String, DisposableServer> MOCK_SERVERS = new ConcurrentHashMap<>();
    private static final Map<String, List<ServiceInstance>> SERVICE_REGISTRY = new ConcurrentHashMap<>();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ConfigurableEnvironment environment;

    @BeforeAll
    static void initMockClusters() {
        // 1. 灰度精准匹配集群 (zone-a 与 zone-b)
        DisposableServer srvGrayAz1 = startServer("srv-gray-az1");
        DisposableServer srvGrayAz2 = startServer("srv-gray-az2");
        DisposableServer srvBaseAz1 = startServer("srv-base-az1");
        DisposableServer srvBaseAz2 = startServer("srv-base-az2");

        SERVICE_REGISTRY.put("gray-precision-service", List.of(
                createInstance("gray-precision-service", "srv-gray-az1", srvGrayAz1.port(), "zone-a", "v2", 1.0, true, 3600_000, null),
                createInstance("gray-precision-service", "srv-gray-az2", srvGrayAz2.port(), "zone-b", "v2", 1.0, true, 3600_000, null),
                createInstance("gray-precision-service", "srv-base-az1", srvBaseAz1.port(), "zone-a", "baseline", 1.0, true, 3600_000, null),
                createInstance("gray-precision-service", "srv-base-az2", srvBaseAz2.port(), "zone-b", "baseline", 1.0, true, 3600_000, null)
        ));

        // 2. 灰度全下线降级集群 (无任何灰度节点)
        SERVICE_REGISTRY.put("gray-fallback-service", List.of(
                createInstance("gray-fallback-service", "srv-base-az1", srvBaseAz1.port(), "zone-a", "baseline", 1.0, true, 3600_000, null),
                createInstance("gray-fallback-service", "srv-base-az2", srvBaseAz2.port(), "zone-b", "baseline", 1.0, true, 3600_000, null)
        ));

        // 3. 基线隔离集群
        SERVICE_REGISTRY.put("baseline-isolation-service", List.of(
                createInstance("baseline-isolation-service", "srv-gray-az1", srvGrayAz1.port(), "zone-a", "v2", 1.0, true, 3600_000, null),
                createInstance("baseline-isolation-service", "srv-base-az1", srvBaseAz1.port(), "zone-a", "baseline", 1.0, true, 3600_000, null)
        ));

        // 4. 平滑启动预热集群 (已预热 mature 节点 vs 冷启动 2s cold 节点)
        DisposableServer srvWarmupMature = startServer("srv-warmup-mature");
        DisposableServer srvWarmupCold = startServer("srv-warmup-cold");
        SERVICE_REGISTRY.put("warmup-service", List.of(
                createInstance("warmup-service", "srv-warmup-mature", srvWarmupMature.port(), "zone-a", null, 1.0, true, 3600_000, null),
                createInstance("warmup-service", "srv-warmup-cold", srvWarmupCold.port(), "zone-a", null, 1.0, true, 2_000, "60s")
        ));

        // 5. 自适应资源过载集群 (健康节点 20% CPU vs 过载节点 95% CPU)
        DisposableServer srvHealthy = startServer("srv-healthy");
        DisposableServer srvOverloaded = startServer("srv-overloaded");
        SERVICE_REGISTRY.put("adaptive-overload-service", List.of(
                createInstanceWithCpu("adaptive-overload-service", "srv-healthy", srvHealthy.port(), "zone-a", 1.0, true, "0.20"),
                createInstanceWithCpu("adaptive-overload-service", "srv-overloaded", srvOverloaded.port(), "zone-a", 1.0, true, "0.95")
        ));

        // 6. 全集群过载集群 (两台节点均过载 95% CPU)
        DisposableServer srvAllOverload1 = startServer("srv-all-overload-1");
        DisposableServer srvAllOverload2 = startServer("srv-all-overload-2");
        SERVICE_REGISTRY.put("all-overloaded-service", List.of(
                createInstanceWithCpu("all-overloaded-service", "srv-all-overload-1", srvAllOverload1.port(), "zone-a", 1.0, true, "0.95"),
                createInstanceWithCpu("all-overloaded-service", "srv-all-overload-2", srvAllOverload2.port(), "zone-a", 1.0, true, "0.95")
        ));
    }

    @AfterAll
    static void tearDownMockClusters() {
        MOCK_SERVERS.values().forEach(DisposableServer::disposeNow);
        MOCK_SERVERS.clear();
        SERVICE_REGISTRY.clear();
    }

    private static DisposableServer startServer(String instanceId) {
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get("/instance-info", (req, res) ->
                        res.header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"code\":200,\"data\":{\"instanceId\":\"" + instanceId + "\"}}"))
                ))
                .bindNow();
        MOCK_SERVERS.put(instanceId, server);
        return server;
    }

    private static ServiceInstance createInstance(String serviceId, String id, int port, String zone, String tag, double weight,
                                                  boolean healthy, long uptimeMillis, String warmupDuration) {
        Map<String, String> meta = new HashMap<>();
        if (zone != null) {
            meta.put("zone", zone);
        }
        if (tag != null) {
            meta.put("tag", tag);
        }
        meta.put("nacos.weight", String.valueOf(weight));
        meta.put("nacos.healthy", String.valueOf(healthy));
        meta.put("startup-time", String.valueOf(System.currentTimeMillis() - uptimeMillis));
        if (warmupDuration != null) {
            meta.put("warmup-duration", warmupDuration);
        }
        return new DefaultServiceInstance(id, serviceId, "127.0.0.1", port, false, meta);
    }

    private static ServiceInstance createInstanceWithCpu(String serviceId, String id, int port, String zone, double weight,
                                                         boolean healthy, String cpuUsage) {
        Map<String, String> meta = new HashMap<>();
        if (zone != null) {
            meta.put("zone", zone);
        }
        meta.put("nacos.weight", String.valueOf(weight));
        meta.put("nacos.healthy", String.valueOf(healthy));
        meta.put("startup-time", String.valueOf(System.currentTimeMillis() - 3600_000));
        meta.put("cpu-usage", cpuUsage);
        return new DefaultServiceInstance(id, serviceId, "127.0.0.1", port, false, meta);
    }

    @Test
    @DisplayName("端到端断言：携带灰度标签的外部 HTTP 请求精准命中同机房灰度实例")
    void testHttpBoundaryGrayTagMatchesSameZoneCanaryInstance() {
        webTestClient.get()
                .uri("/mock/lb/gray-precision/instance-info")
                .header(GatewayConstants.X_TRAFFIC_TAG, "v2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.instanceId").isEqualTo("srv-gray-az1");
    }

    @Test
    @DisplayName("端到端断言：灰度节点全下线时平滑降级调度至同机房基线实例 (HTTP 200，杜绝 503)")
    void testHttpBoundaryGrayAllDownFallsBackToSameZoneBaseline() {
        webTestClient.get()
                .uri("/mock/lb/gray-fallback/instance-info")
                .header(GatewayConstants.X_TRAFFIC_TAG, "v2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.instanceId").isEqualTo("srv-base-az1");
    }

    @Test
    @DisplayName("端到端断言：普通未染色流量绝对隔离不落入金丝雀节点")
    void testHttpBoundaryUncoloredRequestNeverTouchesCanary() {
        for (int i = 0; i < 10; i++) {
            webTestClient.get()
                    .uri("/mock/lb/baseline-isolation/instance-info")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(200)
                    .jsonPath("$.data.instanceId").isEqualTo("srv-base-az1");
        }
    }

    @Test
    @DisplayName("端到端断言：冷启动新节点在平滑预热期内流量分配呈现阶梯升权分布")
    void testHttpBoundaryWarmupPreheatingDistribution() {
        DisposableServer srvWarmupMature = MOCK_SERVERS.get("srv-warmup-mature");
        DisposableServer srvWarmupCold = MOCK_SERVERS.get("srv-warmup-cold");
        SERVICE_REGISTRY.put("warmup-service", List.of(
                createInstance("warmup-service", "srv-warmup-mature", srvWarmupMature.port(), "zone-a", null, 1.0, true, 3600_000, null),
                createInstance("warmup-service", "srv-warmup-cold", srvWarmupCold.port(), "zone-a", null, 1.0, true, 500, "60s")
        ));

        AtomicInteger matureCount = new AtomicInteger(0);
        AtomicInteger coldCount = new AtomicInteger(0);

        for (int i = 0; i < 40; i++) {
            webTestClient.get()
                    .uri("/mock/lb/warmup/instance-info")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.data.instanceId").value(id -> {
                        if ("srv-warmup-mature".equals(id)) {
                            matureCount.incrementAndGet();
                        } else if ("srv-warmup-cold".equals(id)) {
                            coldCount.incrementAndGet();
                        }
                    });
        }

        // 严格断言：已预热节点获得显著高于冷启动节点的多数流量，冷启动节点保留保底底噪流量
        assertThat(matureCount.get()).isGreaterThan(coldCount.get());
        assertThat(coldCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("端到端断言：CPU 过载节点分得的流量显著低于健康节点")
    void testHttpBoundaryAdaptiveOverloadDistribution() {
        AtomicInteger healthyCount = new AtomicInteger(0);
        AtomicInteger overloadCount = new AtomicInteger(0);

        for (int i = 0; i < 40; i++) {
            webTestClient.get()
                    .uri("/mock/lb/adaptive-overload/instance-info")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.data.instanceId").value(id -> {
                        if ("srv-healthy".equals(id)) {
                            healthyCount.incrementAndGet();
                        } else if ("srv-overloaded".equals(id)) {
                            overloadCount.incrementAndGet();
                        }
                    });
        }

        assertThat(healthyCount.get()).isGreaterThan(overloadCount.get() * 2);
        assertThat(overloadCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("端到端断言：全集群过载时触发容灾兜底防线，流量平滑回退为均匀轮询")
    void testHttpBoundaryAllOverloadedClusterFallbackToEvenDistribution() {
        AtomicInteger server1Count = new AtomicInteger(0);
        AtomicInteger server2Count = new AtomicInteger(0);

        for (int i = 0; i < 40; i++) {
            webTestClient.get()
                    .uri("/mock/lb/all-overloaded/instance-info")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.data.instanceId").value(id -> {
                        if ("srv-all-overload-1".equals(id)) {
                            server1Count.incrementAndGet();
                        } else if ("srv-all-overload-2".equals(id)) {
                            server2Count.incrementAndGet();
                        }
                    });
        }

        // 全集群过载触发均匀轮询兜底，两者调用次数均衡（均在 35% ~ 65% 之间）
        assertThat(server1Count.get()).isBetween(14, 26);
        assertThat(server2Count.get()).isBetween(14, 26);
    }

    @Test
    @DisplayName("端到端断言：双轨动态路由原子热更 (Track A 与 Track B) 即时生效")
    void testHttpBoundaryDynamicRoutesHotReloadImmediateEffect() {
        // 1. 验证 Track A: 独立 Nacos 路由仓库动态更新
        NacosRouteDefinitionRepository repository = applicationContext.getBean(NacosRouteDefinitionRepository.class);
        RouteDefinition dynamicRouteA = new RouteDefinition();
        dynamicRouteA.setId("dynamic-track-a-route");
        dynamicRouteA.setUri(URI.create("forward:/mock/hello"));
        PredicateDefinition predicateA = new PredicateDefinition("Path=/mock/dynamic-track-a/test");
        dynamicRouteA.setPredicates(List.of(predicateA));

        repository.save(Mono.just(dynamicRouteA)).block();

        webTestClient.get()
                .uri("/mock/dynamic-track-a/test")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");

        // 2. 验证 Track B: 主配置动态路由热更通知
        TestPropertyValues.of(
                "spring.cloud.gateway.routes[0].id=mock-test-route",
                "spring.cloud.gateway.routes[0].uri=forward:/mock/hello",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/mock/test-baseline/**",
                "spring.cloud.gateway.routes[1].id=dynamic-track-b-route",
                "spring.cloud.gateway.routes[1].uri=forward:/mock/hello",
                "spring.cloud.gateway.routes[1].predicates[0]=Path=/mock/dynamic-track-b/test"
        ).applyTo(environment);

        applicationContext.publishEvent(new EnvironmentChangeEvent(
                Set.of("spring.cloud.gateway.routes[1].id", "spring.cloud.gateway.routes[1].uri")
        ));

        webTestClient.get()
                .uri("/mock/dynamic-track-b/test")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo("gateway-ok");
    }

    @TestConfiguration
    static class HttpBoundaryTestConfig {

        @Bean
        public RouteLocator customLbRouteLocator(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("lb-gray-precision", r -> r.path("/mock/lb/gray-precision/**")
                            .filters(f -> f.stripPrefix(3))
                            .uri("lb://gray-precision-service"))
                    .route("lb-gray-fallback", r -> r.path("/mock/lb/gray-fallback/**")
                            .filters(f -> f.stripPrefix(3))
                            .uri("lb://gray-fallback-service"))
                    .route("lb-baseline-isolation", r -> r.path("/mock/lb/baseline-isolation/**")
                            .filters(f -> f.stripPrefix(3))
                            .uri("lb://baseline-isolation-service"))
                    .route("lb-warmup", r -> r.path("/mock/lb/warmup/**")
                            .filters(f -> f.stripPrefix(3))
                            .uri("lb://warmup-service"))
                    .route("lb-adaptive-overload", r -> r.path("/mock/lb/adaptive-overload/**")
                            .filters(f -> f.stripPrefix(3))
                            .uri("lb://adaptive-overload-service"))
                    .route("lb-all-overloaded", r -> r.path("/mock/lb/all-overloaded/**")
                            .filters(f -> f.stripPrefix(3))
                            .uri("lb://all-overloaded-service"))
                    .build();
        }

        @Bean
        public ReactiveDiscoveryClient testReactiveDiscoveryClient() {
            return new ReactiveDiscoveryClient() {
                @Override
                public String description() {
                    return "Hermetic In-Memory Test Reactive Discovery Client";
                }

                @Override
                public Flux<ServiceInstance> getInstances(String serviceId) {
                    List<ServiceInstance> instances = SERVICE_REGISTRY.getOrDefault(serviceId, Collections.emptyList());
                    return Flux.fromIterable(instances);
                }

                @Override
                public Flux<String> getServices() {
                    return Flux.fromIterable(SERVICE_REGISTRY.keySet());
                }
            };
        }
    }
}
