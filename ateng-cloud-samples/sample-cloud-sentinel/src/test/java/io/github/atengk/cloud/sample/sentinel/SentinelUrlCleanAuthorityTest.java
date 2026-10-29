package io.github.atengk.cloud.sample.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sentinel RESTful 路径清洗与来源授权端到端测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(classes = SampleSentinelApplication.class)
@AutoConfigureMockMvc
class SentinelUrlCleanAuthorityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(Collections.emptyList());
        AuthorityRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    @DisplayName("RESTful 路径收敛防爆：/users/1001 与 /users/1002 均被归一化为 /api/test/users/{id} 并命中统一流控")
    void testUrlCleanResourceAggregation() throws Exception {
        // 对归一化后的模板资源 /api/test/users/{id} 配置 QPS = 0 限流
        FlowRule rule = new FlowRule();
        rule.setResource("/api/test/users/{id}");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));

        // 请求具体 ID 1001 -> 触发 429
        MvcResult result1 = mockMvc.perform(get("/api/test/users/1001"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json1 = objectMapper.readTree(result1.getResponse().getContentAsString());
        assertThat(json1.get("code").asInt()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());

        // 请求不同 ID 1002 -> 同样命中 /api/test/users/{id} 触发 429
        MvcResult result2 = mockMvc.perform(get("/api/test/users/1002"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json2 = objectMapper.readTree(result2.getResponse().getContentAsString());
        assertThat(json2.get("code").asInt()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("多级调用源解析与白名单授权：受信任服务正常放行，非白名单服务返回 429 与 AUTHORITY_BLOCK")
    void testOriginAuthorityControl() throws Exception {
        // 配置白名单授权规则：仅允许 trusted-service 访问
        AuthorityRule rule = new AuthorityRule();
        rule.setResource("/api/test/admin/secure");
        rule.setStrategy(RuleConstant.AUTHORITY_WHITE);
        rule.setLimitApp("trusted-service");
        AuthorityRuleManager.loadRules(List.of(rule));

        // 携带白名单服务名标头 -> 200 放行
        MvcResult passResult = mockMvc.perform(get("/api/test/admin/secure")
                        .header("X-Origin-Service", "trusted-service"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode passJson = objectMapper.readTree(passResult.getResponse().getContentAsString());
        assertThat(passJson.get("code").asInt()).isEqualTo(200);
        assertThat(passJson.get("data").asText()).isEqualTo("admin-ok");

        // 携带未授权服务名标头 -> 429 阻断
        MvcResult blockResult = mockMvc.perform(get("/api/test/admin/secure")
                        .header("X-Origin-Service", "evil-service"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode blockJson = objectMapper.readTree(blockResult.getResponse().getContentAsString());
        assertThat(blockJson.get("code").asInt()).isEqualTo(SentinelResultCode.AUTHORITY_BLOCK.getCode());
        assertThat(blockJson.get("msg").asText()).isEqualTo(SentinelResultCode.AUTHORITY_BLOCK.getMsg());
    }
}
