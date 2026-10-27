package io.github.atengk.cloud.sample.nacos;

import io.github.atengk.cloud.nacos.template.NacosTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
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
@SpringBootTest
@AutoConfigureMockMvc
@Import(SampleCloudNacosApplicationTest.OfflineMockConfig.class)
@DisplayName("Nacos 场景端到端双模自适应集成实测")
class SampleCloudNacosApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private NacosTemplate nacosTemplate;

    /**
     * 离线 Mock/环境守卫测试配置
     * 关键守卫：标配 @Profile("!local")。当激活 local 时本配置自动失效，完全让位给真实中间件自动装配
     */
    @TestConfiguration
    @Profile("!local")
    static class OfflineMockConfig {
        // 离线守卫扩展配置：在 !local 模式下保持离线自洽，零外部网络依赖
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
                .andExpect(jsonPath("$.metadata['version']", is("1.0.0")));
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

        // 6. 测试实例启用/禁用状态与权重调节
        mockMvc.perform(post("/api/nacos/ops/instance/status")
                        .param("serviceName", "sample-cloud-nacos")
                        .param("ip", "127.0.0.1")
                        .param("port", "8080")
                        .param("enabled", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        mockMvc.perform(post("/api/nacos/ops/instance/weight")
                        .param("serviceName", "sample-cloud-nacos")
                        .param("ip", "127.0.0.1")
                        .param("port", "8080")
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
}
