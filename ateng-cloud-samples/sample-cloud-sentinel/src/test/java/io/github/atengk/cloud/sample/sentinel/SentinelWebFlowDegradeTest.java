package io.github.atengk.cloud.sample.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sample.sentinel.model.seckill.SecKillOrderDTO;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sentinel Web 接口流控与降级端到端自验测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(classes = SampleSentinelApplication.class)
@AutoConfigureMockMvc
class SentinelWebFlowDegradeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AtengSentinelProperties sentinelProperties;

    @Autowired
    private SentinelBlockCounter blockCounter;

    @AfterEach
    void tearDown() {
        // 测试后确定性清理，杜绝规则与线程污染
        FlowRuleManager.loadRules(Collections.emptyList());
        DegradeRuleManager.loadRules(Collections.emptyList());
        blockCounter.reset();
        MDC.clear();
        sentinelProperties.getBlockHandler().setRetryAfter(Duration.ZERO);
    }

    @Test
    @DisplayName("端到端无规则：秒杀提单接口正常返回 200 与订单业务数据")
    void testNormalRequestWithoutRules() throws Exception {
        SecKillOrderDTO orderDTO = new SecKillOrderDTO(1001L, 2001L, 2);
        MvcResult mvcResult = mockMvc.perform(post("/sample/cloud/sentinel/seckill/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderDTO)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(200);
        assertThat(json.get("data").get("orderNo").asText()).startsWith("SK-");
        assertThat(json.get("data").get("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("端到端流量控制：秒杀提单触发 QPS 限流时返回 HTTP 429 与 FLOW_LIMIT (4201)")
    void testFlowLimitBlock() throws Exception {
        // 配置流控规则：QPS 上限为 0，必定触发拦截
        FlowRule rule = new FlowRule();
        rule.setResource("/sample/cloud/sentinel/seckill/order");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));

        SecKillOrderDTO orderDTO = new SecKillOrderDTO(1001L, 2001L, 2);
        MvcResult mvcResult = mockMvc.perform(post("/sample/cloud/sentinel/seckill/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderDTO)))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
        assertThat(json.get("msg").asText()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getMsg());
    }

    @Test
    @DisplayName("端到端熔断降级：触发降级熔断窗口时返回 HTTP 503 与 DEGRADE (4202)")
    void testDegradeBlock() throws Exception {
        // 配置异常数降级规则：异常数大于 0 次即熔断 10 秒
        DegradeRule rule = new DegradeRule();
        rule.setResource("/sample/cloud/sentinel/payment/circuit-breaker");
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT);
        rule.setCount(0);
        rule.setTimeWindow(10);
        rule.setMinRequestAmount(1);
        DegradeRuleManager.loadRules(List.of(rule));

        // 第 1 次请求：触发业务异常，计入熔断统计
        try {
            mockMvc.perform(get("/sample/cloud/sentinel/payment/circuit-breaker?fail=true"));
        } catch (Exception ignored) {
            // 忽略业务异常
        }

        // 第 2 次请求：处于熔断窗口期内，被 DegradeRule 阻断，返回 HTTP 503
        MvcResult mvcResult = mockMvc.perform(get("/sample/cloud/sentinel/payment/circuit-breaker"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(mvcResult.getResponse().getHeader("X-Sentinel-Block-Rule")).isEqualTo("DEGRADE");
        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.DEGRADE.getCode());
        assertThat(json.get("msg").asText()).isEqualTo(SentinelResultCode.DEGRADE.getMsg());
    }

    @Test
    @DisplayName("端到端阻断响应头与快照溯源：验证 X-Sentinel-Block-Rule、Retry-After 与 traceId/origin")
    void testBlockHeadersRetryAfterAndSnapshotTraceOrigin() throws Exception {
        sentinelProperties.getBlockHandler().setRetryAfter(Duration.ofSeconds(20));
        FlowRule rule = new FlowRule();
        rule.setResource("/sample/cloud/sentinel/seckill/order");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        rule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(rule));

        SecKillOrderDTO orderDTO = new SecKillOrderDTO(1001L, 2001L, 2);
        MvcResult mvcResult = mockMvc.perform(post("/sample/cloud/sentinel/seckill/order")
                        .header("X-Origin-Service", "mobile-bff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderDTO)))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(mvcResult.getResponse().getHeader("X-Sentinel-Block-Rule")).isEqualTo("FLOW");
        assertThat(mvcResult.getResponse().getHeader("Retry-After")).isEqualTo("20");
        String responseTraceId = mvcResult.getResponse().getHeader("X-Trace-Id");
        assertThat(responseTraceId).isNotBlank();
        assertThat(blockCounter.getLastBlockEvent()).isNotNull();
        assertThat(blockCounter.getLastBlockEvent().traceId()).isEqualTo(responseTraceId);
        assertThat(blockCounter.getLastBlockEvent().origin()).isEqualTo("mobile-bff");
    }
}
