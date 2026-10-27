package io.github.atengk.cloud.sample.nacos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nacos 样例工程端到端离线自测
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SampleCloudNacosApplicationTest {

    @Autowired
    private MockMvc mockMvc;

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
                .andExpect(jsonPath("$.data['demo-module']", is("sample-cloud-nacos")))
                .andExpect(jsonPath("$.data['maintainer']", is("Ateng")))
                .andExpect(jsonPath("$.data['startup-time']", notNullValue()));
    }

    @Test
    @DisplayName("测试 Actuator nacos-service 专属端点状态读取")
    void testGetActuatorNacosServiceEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/nacos-service")
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
        mockMvc.perform(post("/actuator/nacos-service")
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
        mockMvc.perform(post("/actuator/nacos-service")
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
}
