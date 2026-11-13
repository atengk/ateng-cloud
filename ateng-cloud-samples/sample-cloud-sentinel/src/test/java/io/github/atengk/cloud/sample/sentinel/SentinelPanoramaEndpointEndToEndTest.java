package io.github.atengk.cloud.sample.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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
 * Sentinel 全景自省诊断端点端到端集成测试 (/actuator/sentinel-panorama)
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = SampleSentinelApplication.class)
@AutoConfigureMockMvc
class SentinelPanoramaEndpointEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SentinelBlockCounter blockCounter;

    @BeforeEach
    @AfterEach
    void cleanup() {
        FlowRuleManager.loadRules(Collections.emptyList());
        DegradeRuleManager.loadRules(Collections.emptyList());
        ParamFlowRuleManager.loadRules(Collections.emptyList());
        SystemRuleManager.loadRules(Collections.emptyList());
        AuthorityRuleManager.loadRules(Collections.emptyList());
        blockCounter.reset();
        MDC.clear();
    }

    @Test
    @DisplayName("端到端验证 /actuator/sentinel-panorama 返回五维结构化诊断报文")
    void testPanoramaEndpointReturnsStructuredReport() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/actuator/sentinel-panorama"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(mvcResult.getResponse().getContentAsString());

        // 1. 基础概览
        assertThat(root.get("status").asText()).isEqualTo("UP");
        assertThat(root.get("appName").asText()).isEqualTo("sample-cloud-sentinel");
        assertThat(root.get("sentinelVersion").asText()).isNotEmpty();
        assertThat(root.get("timestamp").asLong()).isGreaterThan(0);

        // 2. Web MVC 防卫配置快照
        JsonNode webMvc = root.get("webMvcConfig");
        assertThat(webMvc).isNotNull();
        assertThat(webMvc.get("urlClean").get("enabled").asBoolean()).isTrue();
        assertThat(webMvc.get("urlClean").get("hexReplacement").asText()).isEqualTo("{id}");
        assertThat(webMvc.get("urlClean").get("customPatternCount").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(webMvc.get("urlClean").get("excludePatterns").toString()).contains("/actuator/**", "/v3/api-docs/**");
        assertThat(webMvc.get("originParser").get("originHeader").asText()).isEqualTo("X-Origin-Service");
        assertThat(webMvc.get("originParser").get("gatewayHeader").asText()).isEqualTo("X-Gateway-From");
        assertThat(webMvc.get("originParser").get("enableClientIpFallback").asBoolean()).isTrue();
        assertThat(webMvc.get("originParser").get("enableTenantFallback").asBoolean()).isFalse();
        assertThat(webMvc.get("originParser").get("tenantPrefix").asText()).isEqualTo("tenant:");
        assertThat(webMvc.get("blockHandler").get("httpStatusAlign").asBoolean()).isTrue();
        assertThat(webMvc.get("blockHandler").get("retryAfterSeconds").asInt()).isZero();
        assertThat(webMvc.get("blockAdvice").get("retryAfterSeconds").asInt()).isZero();

        // 3. 内存规则快照
        JsonNode inMemory = root.get("inMemoryRules");
        assertThat(inMemory).isNotNull();
        assertThat(inMemory.get("totalRuleCount").asInt()).isGreaterThanOrEqualTo(0);

        // 4. 阻断事件统计
        JsonNode metrics = root.get("blockMetrics");
        assertThat(metrics).isNotNull();
        assertThat(metrics.get("blockedTotal").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.get("totalBlocked").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("端到端验证发生流控阻断后，自省端点中的内存规则与阻断计数器联动实时递增")
    void testPanoramaEndpointReflectsRulesAndBlockMetricsIncrement() throws Exception {
        // 1. 注入内存限流规则：针对 /sample/cloud/sentinel/seckill/vip-channel 设置 QPS = 0
        FlowRule rule = new FlowRule();
        rule.setResource("/sample/cloud/sentinel/seckill/vip-channel");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));

        // 2. 模拟发起业务请求 -> 触发 429 阻断
        MvcResult blockedResult = mockMvc.perform(get("/sample/cloud/sentinel/seckill/vip-channel")
                        .header("X-Origin-Service", "smoke-caller"))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        String responseTraceId = blockedResult.getResponse().getHeader("X-Trace-Id");
        assertThat(responseTraceId).isNotBlank();

        // 3. 访问诊断端点 -> 验证内存规则与阻断统计均准确记录
        MvcResult endpointResult = mockMvc.perform(get("/actuator/sentinel-panorama"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(endpointResult.getResponse().getContentAsString());

        // 强断言：生效规则准确识别
        JsonNode inMemory = root.get("inMemoryRules");
        assertThat(inMemory.get("flowRuleCount").asInt()).isEqualTo(1);
        assertThat(inMemory.get("flowRules").get(0).get("resource").asText()).isEqualTo("/sample/cloud/sentinel/seckill/vip-channel");

        // 强断言：阻断统计计数器已原子递增并捕获最近一次事件（含 traceId 与 origin）
        JsonNode metrics = root.get("blockMetrics");
        assertThat(metrics.get("blockedTotal").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.get("totalBlocked").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.get("flowCount").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode lastEvent = metrics.get("lastBlockEvent");
        assertThat(lastEvent).isNotNull();
        assertThat(lastEvent.get("resource").asText()).isEqualTo("/sample/cloud/sentinel/seckill/vip-channel");
        assertThat(lastEvent.get("ruleType").asText()).isEqualTo("FLOW");
        assertThat(lastEvent.get("traceId").asText()).isEqualTo(responseTraceId);
        assertThat(lastEvent.get("origin").asText()).isEqualTo("smoke-caller");
    }
}
