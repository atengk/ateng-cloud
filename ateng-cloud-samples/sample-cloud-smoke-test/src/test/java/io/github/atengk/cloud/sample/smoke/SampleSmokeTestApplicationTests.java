package io.github.atengk.cloud.sample.smoke;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 微服务全反应堆离线冒烟自检集成测试
 * <p>
 * 验证在无物理外部中间件（Nacos、Sentinel、Dubbo Registry）依赖下，ateng-cloud-all 能够 100% 离线自洽装配并响应 HTTP 探测。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest
@AutoConfigureMockMvc
class SampleSmokeTestApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("冒烟探针测试：调用 /sample/cloud/smoke/ping 返回 HTTP 200 与 pong 载荷")
    void testPingEndpoint() throws Exception {
        mockMvc.perform(get("/sample/cloud/smoke/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("pong"));
    }

    @Test
    @DisplayName("全家桶状态自检：调用 /sample/cloud/smoke/status 校验全套骨干组件装配就绪")
    void testSmokeStatusEndpoint() throws Exception {
        mockMvc.perform(get("/sample/cloud/smoke/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.framework").value("ateng-cloud-all"))
                .andExpect(jsonPath("$.data.components.nacos").value("LOADED"))
                .andExpect(jsonPath("$.data.components.feign").value("LOADED"))
                .andExpect(jsonPath("$.data.components.dubbo").value("LOADED"))
                .andExpect(jsonPath("$.data.components.sentinel").value("LOADED"))
                .andExpect(jsonPath("$.data.components.traffic").value("LOADED"));
    }
}
