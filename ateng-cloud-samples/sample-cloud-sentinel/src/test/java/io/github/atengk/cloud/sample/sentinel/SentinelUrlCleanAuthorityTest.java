package io.github.atengk.cloud.sample.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.cleaner.AtengUrlCleaner;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.parser.AtengRequestOriginParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
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

    @Autowired
    private AtengUrlCleaner urlCleaner;

    @Autowired
    private AtengRequestOriginParser originParser;

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

    @Test
    @DisplayName("白名单路径排除实测：/actuator/** 与 /v3/api-docs/** 绕过 Sentinel 资源创建，同时常规业务路径正常归一化")
    void testUrlCleanExcludePatternsAndNormalization() {
        // 白名单路径返回空字符串，Sentinel 框架将跳过资源 Entry 创建
        assertThat(urlCleaner.clean("/actuator/health")).isEmpty();
        assertThat(urlCleaner.clean("/actuator/sentinel-panorama")).isEmpty();
        assertThat(urlCleaner.clean("/v3/api-docs/swagger-config")).isEmpty();

        // 常规业务 RESTful 数字 ID 与 UUID 均被模板化
        assertThat(urlCleaner.clean("/api/users/10086")).isEqualTo("/api/users/{id}");
        assertThat(urlCleaner.clean("/api/orders/e0b11568-1cdb-4028-a400-9892ee510fa6")).isEqualTo("/api/orders/{uuid}");
    }

    @Test
    @DisplayName("请求来源解析实测：Header 优先级契约 (X-Origin-Service > X-Gateway-From > 客户端 IP)")
    void testOriginParserHeaderPrecedenceAndFallback() {
        // 场景 1: 仅携带网关专有标头，识别为网关来源
        MockHttpServletRequest requestWithGateway = new MockHttpServletRequest();
        requestWithGateway.addHeader("X-Gateway-From", "edge-gateway");
        assertThat(originParser.parseOrigin(requestWithGateway)).isEqualTo("edge-gateway");

        // 场景 2: 携带微服务调用方服务标头，识别为服务来源
        MockHttpServletRequest requestWithService = new MockHttpServletRequest();
        requestWithService.addHeader("X-Origin-Service", "internal-order");
        assertThat(originParser.parseOrigin(requestWithService)).isEqualTo("internal-order");

        // 场景 3: 无标头时，回退提取真实客户端 IP
        MockHttpServletRequest requestFallbackIp = new MockHttpServletRequest();
        requestFallbackIp.addHeader("X-Forwarded-For", "203.0.113.195, 10.0.0.1");
        assertThat(originParser.parseOrigin(requestFallbackIp)).isEqualTo("203.0.113.195");
    }
}
