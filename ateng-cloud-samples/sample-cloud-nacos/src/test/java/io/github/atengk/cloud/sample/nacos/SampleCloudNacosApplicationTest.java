package io.github.atengk.cloud.sample.nacos;

import io.github.atengk.cloud.nacos.event.NacosConnectionEvent;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nacos 样例工程端到端实测（支持离线 Mock 与本地真实双模自适应）
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ateng.boot.encrypt.password=ateng-encrypt-pwd-123456"
})
@AutoConfigureMockMvc
@Import(SampleCloudNacosApplicationTest.OfflineMockConfig.class)
@DisplayName("Nacos 场景端到端双模自适应集成实测")
class SampleCloudNacosApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private NacosTemplate nacosTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired(required = false)
    private io.github.atengk.cloud.nacos.health.NacosHealthChecker healthChecker;

    /**
     * 离线 Mock/环境守卫测试配置
     * 关键守卫：标配 @Profile("!local")。当激活 local 时本配置自动失效，完全让位给真实中间件自动装配
     */
    @TestConfiguration
    @Profile("!local")
    static class OfflineMockConfig {
        @Bean
        public DiscoveryClient discoveryClient() {
            return new DiscoveryClient() {
                @Override
                public String description() {
                    return "OfflineMockDiscoveryClient";
                }

                @Override
                public List<ServiceInstance> getInstances(String serviceId) {
                    if ("order-service".equalsIgnoreCase(serviceId) || "sample-cloud-nacos".equalsIgnoreCase(serviceId)) {
                        // 1. 同机房可用区实例（与 sample-cloud-nacos 同在 beijing-az1，预热中：已启动 10s，预热 60s）
                        DefaultServiceInstance inst1 = new DefaultServiceInstance(
                                serviceId + "-1", serviceId, "10.0.1.10", 8080, false
                        );
                        inst1.getMetadata().put("zone", "beijing-az1");
                        inst1.getMetadata().put("startup-time", String.valueOf(System.currentTimeMillis() - 10_000L));
                        inst1.getMetadata().put("warmup-duration", "60s");

                        // 2. 同机房可用区实例（与 sample-cloud-nacos 同在 beijing-az1，已完全预热：已启动 90s）
                        DefaultServiceInstance inst2 = new DefaultServiceInstance(
                                serviceId + "-2", serviceId, "10.0.1.11", 8080, false
                        );
                        inst2.getMetadata().put("zone", "beijing-az1");
                        inst2.getMetadata().put("startup-time", String.valueOf(System.currentTimeMillis() - 90_000L));
                        inst2.getMetadata().put("warmup-duration", "60s");

                        // 3. 跨机房可用区实例（shanghai-az2）
                        DefaultServiceInstance inst3 = new DefaultServiceInstance(
                                serviceId + "-3", serviceId, "10.0.2.20", 8080, false
                        );
                        inst3.getMetadata().put("zone", "shanghai-az2");
                        inst3.getMetadata().put("startup-time", String.valueOf(System.currentTimeMillis() - 70_000L));
                        inst3.getMetadata().put("warmup-duration", "60s");

                        return List.of(inst1, inst2, inst3);
                    }
                    return Collections.emptyList();
                }

                @Override
                public List<String> getServices() {
                    return List.of("order-service", "sample-cloud-nacos");
                }
            };
        }

        @Bean
        public ReactiveDiscoveryClient reactiveDiscoveryClient(DiscoveryClient discoveryClient) {
            return new ReactiveDiscoveryClient() {
                @Override
                public String description() {
                    return "OfflineMockReactiveDiscoveryClient";
                }

                @Override
                public Flux<ServiceInstance> getInstances(String serviceId) {
                    return Flux.fromIterable(discoveryClient.getInstances(serviceId));
                }

                @Override
                public Flux<String> getServices() {
                    return Flux.fromIterable(discoveryClient.getServices());
                }
            };
        }
    }

    @AfterEach
    void tearDown() {
        // 确定性清理测试残留状态，确保实例恢复为上线状态并移除临时元数据
        if (nacosTemplate != null) {
            try {
                nacosTemplate.removeMetadata("canary-group");
                nacosTemplate.markOnline();
            } catch (Exception ignored) {
                // 离线环境或网络抖动保护
            }
        }
    }

    @Test
    @DisplayName("测试元数据感知接口端到端正常返回规范化元数据与 SPI 定制标签")
    void testGetMetadataEndpoint() throws Exception {
        mockMvc.perform(get("/api/nacos/metadata")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data['version']", is("1.0.0")))
                .andExpect(jsonPath("$.data['warmup-duration']", is("60s")))
                .andExpect(jsonPath("$.data['zone']", is("beijing-az1")))
                .andExpect(jsonPath("$.data['team']", is("core-platform")))
                .andExpect(jsonPath("$.data['framework']", is("ateng-cloud")))
                .andExpect(jsonPath("$.data['demo-module']", is("sample-cloud-nacos")))
                .andExpect(jsonPath("$.data['maintainer']", is("Ateng")))
                .andExpect(jsonPath("$.data['startup-time']", notNullValue()));
    }

    @Test
    @DisplayName("测试 Actuator nacosService 专属端点状态读取")
    void testGetActuatorNacosServiceEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/nacosService")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.serviceName", is("sample-cloud-nacos")))
                .andExpect(jsonPath("$.zone", is("beijing-az1")))
                .andExpect(jsonPath("$.metadata['version']", is("1.0.0")))
                .andExpect(jsonPath("$.warmupDuration", is("60s")))
                .andExpect(jsonPath("$.warmupUptimeSeconds", notNullValue()))
                .andExpect(jsonPath("$.warmupRemainingSeconds", notNullValue()))
                .andExpect(jsonPath("$.warmupCompleted", notNullValue()))
                .andExpect(jsonPath("$.config.enforceBeanImmutability", is(true)))
                .andExpect(jsonPath("$.config.listeners").isArray());
    }

    @Test
    @DisplayName("测试 Actuator 端点主动发起 offline 排空摘牌与 online 恢复上线流转")
    void testActuatorNacosServiceOfflineAndOnlineActions() throws Exception {
        // 1. 发起 offline 主动摘牌排空
        mockMvc.perform(post("/actuator/nacosService")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"offline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.action", is("offline")))
                .andExpect(jsonPath("$.state", is("OUT_OF_SERVICE")));

        // 2. 验证 Spring Boot 就绪探针感知下线状态切换为 OUT_OF_SERVICE (REFUSING_TRAFFIC)
        mockMvc.perform(get("/actuator/health/readiness")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.status", is("OUT_OF_SERVICE")));

        // 3. 发起 online 恢复挂牌上线
        mockMvc.perform(post("/actuator/nacosService")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"online\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.action", is("online")))
                .andExpect(jsonPath("$.state", is("UP")));

        // 4. 验证就绪探针恢复为 UP (ACCEPTING_TRAFFIC)
        mockMvc.perform(get("/actuator/health/readiness")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    @DisplayName("测试 NacosTemplate 运维接口查询与平滑自适应契约")
    void testNacosTemplateOpsEndpoints() throws Exception {
        // 1. 测试健康实例列表查询（双模自适应：离线空集合，在线实例列表）
        mockMvc.perform(get("/api/nacos/ops/instances?serviceName=sample-cloud-nacos")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data").isArray());

        // 2. 测试机房亲和筛选（双模自适应：离线空集合，在线机房列表）
        mockMvc.perform(get("/api/nacos/ops/same-zone?serviceName=sample-cloud-nacos")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data").isArray());

        // 3. 测试获取自身实例（双模自适应：离线为 null，在线返回自身节点）
        mockMvc.perform(get("/api/nacos/ops/me")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        // 4. 测试保护阈值探测
        mockMvc.perform(get("/api/nacos/ops/protection?serviceName=sample-cloud-nacos")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(false)));

        // 5. 测试动态元数据注册与移除
        mockMvc.perform(post("/api/nacos/ops/metadata?key=canary-group&value=canary-a")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        mockMvc.perform(delete("/api/nacos/ops/metadata?key=canary-group")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        // 6. 测试实例启用/禁用状态与权重调节（双模自适应：优先获取自身已注册实例节点）
        String opIp = "127.0.0.1";
        int opPort = 8080;
        if (nacosTemplate != null) {
            java.util.Optional<com.alibaba.nacos.api.naming.pojo.Instance> myInst = nacosTemplate.getMyInstance();
            if (myInst.isPresent()) {
                opIp = myInst.get().getIp();
                opPort = myInst.get().getPort();
            }
        }
        mockMvc.perform(post("/api/nacos/ops/instance/status")
                        .param("serviceName", "sample-cloud-nacos")
                        .param("ip", opIp)
                        .param("port", String.valueOf(opPort))
                        .param("enabled", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        mockMvc.perform(post("/api/nacos/ops/instance/weight")
                        .param("serviceName", "sample-cloud-nacos")
                        .param("ip", opIp)
                        .param("port", String.valueOf(opPort))
                        .param("weight", "1.0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        // 7. 测试动态上下线接口
        mockMvc.perform(post("/api/nacos/ops/offline")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        mockMvc.perform(post("/api/nacos/ops/online")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));
    }

    @Test
    @DisplayName("测试 ateng-boot-encrypt 密文透明解密与内存真明文/展示脱敏契约")
    void testConfigTransparentDecryptionAndInMemoryPlaintext() throws Exception {
        // 1. 读取展示视图：验证敏感字段已实施星号掩码脱敏
        mockMvc.perform(get("/api/nacos/config/current")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.title", is("Nacos Cloud Platform")))
                .andExpect(jsonPath("$.data.maskedSecretKey", is("******")))
                .andExpect(jsonPath("$.data.poolSize", is(20)));

        // 2. 读取业务真实字段：验证 ENC(...) 密文已由 ateng-boot-encrypt 透明解密且内存中为真实明文
        mockMvc.perform(get("/api/nacos/config/secret")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is("MyNacosSuperSecretPassword")));
    }

    @Test
    @DisplayName("测试模拟安全配置热更并触发 NacosConfigChangeListener SPI 与 Spring @EventListener")
    void testSimulateRefreshAndSpiAndEventListenerPropagation() throws Exception {
        // 1. 发起模拟热更
        mockMvc.perform(post("/api/nacos/config/simulate-refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\": [\"demo.config.title\", \"demo.config.secret-key\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.success", is(true)))
                .andExpect(jsonPath("$.data.changedKeysCount", is(2)))
                .andExpect(jsonPath("$.data.traceId", notNullValue()));

        // 2. 验证 SPI 与 Spring EventListener 均成功感知并处理
        mockMvc.perform(get("/api/nacos/config/listener-status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.spiChangeCount", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.eventListenerCount", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.lastSpiTraceId", notNullValue()))
                .andExpect(jsonPath("$.data.lastEventTraceId", notNullValue()));
    }

    @Test
    @DisplayName("测试参数校验防卫：热更变更键名列表为空时 Fail-Fast 阻断")
    void testSimulateRefreshValidationError() throws Exception {
        mockMvc.perform(post("/api/nacos/config/simulate-refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\": []}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("变更键名集合不可为空")));
    }

    @Test
    @DisplayName("测试编程式动态配置泛型强类型订阅、回调触发与 MDC 链路追踪传递")
    void testTypedConfigSubscriptionAndMdcTracing() throws Exception {
        // 1. 读取初始风控规则
        mockMvc.perform(get("/api/nacos/config/trade-rule")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.ruleName", notNullValue()))
                .andExpect(jsonPath("$.data.updateCount", is(0)));

        // 2. 模拟推送 TradeRuleConfig 泛型强类型配置（全真反序列化与 MDC traceId 穿透）
        String ruleJson = "{\"ruleName\": \"AntiFraud-HighRisk-Block\", \"maxLimit\": 200000, \"enabled\": true}";
        mockMvc.perform(post("/api/nacos/config/trade-rule/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.ruleName", is("AntiFraud-HighRisk-Block")))
                .andExpect(jsonPath("$.data.maxLimit", is(200000)))
                .andExpect(jsonPath("$.data.enabled", is(true)))
                .andExpect(jsonPath("$.data.updateCount", is(1)))
                .andExpect(jsonPath("$.data.traceId", notNullValue()))
                .andExpect(jsonPath("$.data.traceId", not(emptyOrNullString())));

        // 3. JSR-303 参数校验防卫：非法入参应被 Fail-Fast 拦截返回业务 400
        String invalidJson = "{\"ruleName\": \"\", \"maxLimit\": -100, \"enabled\": true}";
        mockMvc.perform(post("/api/nacos/config/trade-rule/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("测试 Nacos gRPC 长连接生命周期领域事件感知与状态透出")
    void testConnectionEventListening() throws Exception {
        // 发布模拟长连接断开领域事件
        eventPublisher.publishEvent(new NacosConnectionEvent(
                this,
                NacosConnectionEvent.ModuleType.CONFIG,
                NacosConnectionEvent.ConnectionStatus.DISCONNECTED,
                "127.0.0.1:8848",
                "sample-cloud-nacos"
        ));

        mockMvc.perform(get("/api/nacos/ops/connection-event")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.eventCount", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.moduleType", is("CONFIG")))
                .andExpect(jsonPath("$.data.status", is("DISCONNECTED")))
                .andExpect(jsonPath("$.data.serverAddress", is("127.0.0.1:8848")));
    }

    @Test
    @DisplayName("测试客户端负载均衡同机房亲和优先路由与加权平滑预热真实调度")
    void testLoadBalancerZoneAffinity() throws Exception {
        // 1. 弹性等待 Nacos 服务注册缓存收敛并断言同机房 Zone 亲和
        long deadline = System.currentTimeMillis() + 5000L;
        while (true) {
            try {
                mockMvc.perform(get("/api/nacos/loadbalancer/choose?serviceName=sample-cloud-nacos")
                                .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code", is(200)))
                        .andExpect(jsonPath("$.data.serviceName", is("sample-cloud-nacos")))
                        .andExpect(jsonPath("$.data.metadata.zone", is("beijing-az1")));
                break;
            } catch (AssertionError ex) {
                if (System.currentTimeMillis() >= deadline) {
                    throw ex;
                }
                Thread.sleep(300L);
            }
        }

        // 2. 模拟连续多次负载均衡调用，验证加权预热平滑调度 (已预热实例权重 > 预热中实例)
        java.util.Map<String, Integer> ipCounts = new java.util.HashMap<>();
        for (int i = 0; i < 20; i++) {
            String content = mockMvc.perform(get("/api/nacos/loadbalancer/choose?serviceName=order-service")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            if (content.contains("10.0.1.10")) {
                ipCounts.merge("10.0.1.10", 1, Integer::sum);
            } else if (content.contains("10.0.1.11")) {
                ipCounts.merge("10.0.1.11", 1, Integer::sum);
            }
        }
        if (ipCounts.containsKey("10.0.1.11") && ipCounts.containsKey("10.0.1.10")) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    ipCounts.get("10.0.1.11") >= ipCounts.get("10.0.1.10"),
                    "已预热完毕实例调度次数应高于或等于冷启动预热中实例"
            );
        }
    }

    @Test
    @DisplayName("测试 NacosMetricsManager 指标导出与 NacosHealthChecker 全景自检防御")
    void testNacosMetricsAndHealthDiagnostics() {
        // 1. 验证 Micrometer 度量标尺注册与数值有效性
        if (meterRegistry != null) {
            org.junit.jupiter.api.Assertions.assertNotNull(
                    meterRegistry.find("nacos.grpc.connection.status").gauge(),
                    "nacos.grpc.connection.status 指标标尺应注册"
            );
            org.junit.jupiter.api.Assertions.assertNotNull(
                    meterRegistry.find("nacos.instance.warmup.remaining.seconds").gauge(),
                    "nacos.instance.warmup.remaining.seconds 指标标尺应注册"
            );
        }

        // 2. 验证 NacosHealthChecker 核心深水区自检防御逻辑（K8s 停机排空安全预警与快照可写性探针）
        if (healthChecker != null) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> healthChecker.afterSingletonsInstantiated());
        }
    }

    @Test
    @DisplayName("测试基于节点系统负载指标的自适应动态加权端点演练与阻尼防抖")
    void testAdaptiveWeightCalculationEndpoint() throws Exception {
        // 1. 模拟健康负载节点 (8080 端口，CPU 40%)，权重无折损，保持 1.0
        mockMvc.perform(get("/api/nacos/loadbalancer/calculate-weight?currentWeight=1.0&cpuUsage=0.40&port=8080")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(1.0)));

        // 2. 模拟高负载过载节点 (8081 端口，CPU 95%)，权重被显著降权 (降至约 0.23)
        String respContent = mockMvc.perform(get("/api/nacos/loadbalancer/calculate-weight?currentWeight=1.0&cpuUsage=0.95&port=8081")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Number weightNumber = com.jayway.jsonpath.JsonPath.read(respContent, "$.data");
        double weight = weightNumber.doubleValue();
        org.junit.jupiter.api.Assertions.assertTrue(
                weight < 0.35 && weight >= 0.10,
                "高负载实例有效权重应被平滑降权至 0.10~0.35 之间，实际: " + weight
        );

        // 3. 验证阻尼防抖：对已评估为健康的 8080 节点在 5 秒采样窗口内突发指标变动，依然平稳复用缓存权重 1.0
        mockMvc.perform(get("/api/nacos/loadbalancer/calculate-weight?currentWeight=1.0&cpuUsage=0.95&port=8080")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(1.0)));
    }

    @Test
    @DisplayName("实例编程式订阅端到端：首帧快照即时派发、MDC 链路感知与离线自洽保底")
    void testInstanceSubscriptionEndToEnd() throws Exception {
        mockMvc.perform(get("/api/nacos/ops/instance-subscription")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.count", notNullValue()))
                .andExpect(jsonPath("$.data.instances", notNullValue()));
    }

    @Test
    @DisplayName("配置热更异常端到端：异常隔离屏障、SafeConfigRefreshFailureEvent 广播与 Actuator 状态联动")
    void testSafeConfigRefreshFailureEventAndActuatorLinkage() throws Exception {
        // 1. 触发模拟热更失败（携带 demo.config.simulate-failure 键名）
        // 验证框架异常隔离屏障生效：未击穿 Spring 容器抛出 500，依然受控响应 200
        mockMvc.perform(post("/api/nacos/config/simulate-refresh-failure")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.success", is(false)))
                .andExpect(jsonPath("$.data.changedKeysCount", is(1)))
                .andExpect(jsonPath("$.data.traceId", notNullValue()));

        // 2. 验证业务侧 DemoConfigRefreshFailureEventListener 成功捕获失败领域事件并完成告警联动
        mockMvc.perform(get("/api/nacos/config/refresh-failure-status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.failureCount", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.lastDataId", is("demo-failure.yml")))
                .andExpect(jsonPath("$.data.lastGroup", is("DEFAULT_GROUP")))
                .andExpect(jsonPath("$.data.lastChangedKeys[0]", is("demo.config.simulate-failure")))
                .andExpect(jsonPath("$.data.lastErrorMessage", org.hamcrest.Matchers.containsString("One or more NacosConfigChangeListener failed")))
                .andExpect(jsonPath("$.data.lastTraceId", notNullValue()));

        // 3. 验证 Actuator /actuator/nacosService 运维端点联动呈现失败详情
        mockMvc.perform(get("/actuator/nacosService")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRefresh.success", is(false)))
                .andExpect(jsonPath("$.lastRefresh.errorMessage", org.hamcrest.Matchers.containsString("One or more NacosConfigChangeListener failed")))
                .andExpect(jsonPath("$.lastRefresh.traceId", notNullValue()))
                .andExpect(jsonPath("$.lastRefresh.changedKeys[0]", is("demo.config.simulate-failure")))
                .andExpect(jsonPath("$.config.lastRefresh.success", is(false)))
                .andExpect(jsonPath("$.config.lastRefresh.errorMessage", org.hamcrest.Matchers.containsString("One or more NacosConfigChangeListener failed")));
    }
}
