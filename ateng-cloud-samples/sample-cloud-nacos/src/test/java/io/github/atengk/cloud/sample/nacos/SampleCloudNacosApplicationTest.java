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
}
