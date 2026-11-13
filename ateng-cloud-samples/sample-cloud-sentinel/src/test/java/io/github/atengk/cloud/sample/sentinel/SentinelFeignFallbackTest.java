package io.github.atengk.cloud.sample.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.sentinel.client.PaymentFallbackFactory;
import io.github.atengk.cloud.sample.sentinel.client.RemotePaymentClient;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentPayDTO;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentResultVO;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feign 客户端受管降级工厂端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest
@AutoConfigureMockMvc
class SentinelFeignFallbackTest {

    @Autowired
    private RemotePaymentClient remotePaymentClient;

    @Autowired
    private PaymentFallbackFactory paymentFallbackFactory;

    @Autowired
    private SentinelBlockCounter blockCounter;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        FlowRuleManager.loadRules(Collections.emptyList());
        blockCounter.reset();
    }

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(Collections.emptyList());
        blockCounter.reset();
    }

    @Test
    @DisplayName("默认受管降级：调用故障节点抛出语义化 BusinessException(4202 DEGRADE)")
    void testDefaultFeignFallbackThrowsBusinessException() {
        PaymentPayDTO payDTO = new PaymentPayDTO("ORD-TEST-1001", new BigDecimal("199.00"), "ALIPAY");
        assertThatThrownBy(() -> remotePaymentClient.payOrder(payDTO, false, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResultCode()).isEqualTo(SentinelResultCode.DEGRADE);
                    assertThat(be.getCode()).isEqualTo(4202);
                    assertThat(be.getCause()).isNotNull();
                    assertThat(be.getMessage()).contains("RemotePaymentClient#payOrder");
                });
    }

    @Test
    @DisplayName("柔性受管降级：调用故障节点返回自定义 Mock 保底数据")
    void testCustomFeignFallbackReturnsMockData() {
        Result<PaymentResultVO> result = remotePaymentClient.queryPaymentStatus("PAY-8888", false);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getPayNo()).isEqualTo("PAY-8888");
        assertThat(result.getData().getFallback()).isTrue();
        assertThat(result.getData().getStatus()).isEqualTo("DEGRADED");
        assertThat(blockCounter.getTotalBlocked()).isEqualTo(1);
    }

    @Test
    @DisplayName("端到端防重计数与 HTTP 503 对齐：Controller 调用 Feign 触发降级时，仅累计 1 次阻断且响应 503")
    void testFeignFallbackThroughControllerAlignsHttp503AndCountsOnce() throws Exception {
        PaymentPayDTO payDTO = new PaymentPayDTO("ORD-TEST-1002", new BigDecimal("88.00"), "WECHAT");
        MvcResult mvcResult = mockMvc.perform(post("/sample/cloud/sentinel/payment/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payDTO)))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(mvcResult.getResponse().getHeader("X-Sentinel-Block-Rule")).isEqualTo("DEGRADE");
        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.DEGRADE.getCode());
        assertThat(blockCounter.getTotalBlocked()).isEqualTo(1);
        assertThat(blockCounter.getLastBlockEvent()).isNotNull();
        assertThat(blockCounter.getLastBlockEvent().resource()).isEqualTo("RemotePaymentClient#payOrder");
    }

    @Test
    @DisplayName("端到端客户端流控：Feign 出站触发 FlowException 时返回 HTTP 429 与 4201，且优先记录规则资源名")
    void testFeignClientFlowBlockAlignsHttp429AndRecordsRuleResource() throws Exception {
        String feignResource = "POST:http://localhost:8080/sample/cloud/sentinel/provider/payment/pay";
        FlowRule flowRule = new FlowRule(feignResource);
        flowRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        flowRule.setCount(0);
        flowRule.setLimitApp("default");
        FlowRuleManager.loadRules(List.of(flowRule));

        PaymentPayDTO payDTO = new PaymentPayDTO("ORD-TEST-1003", new BigDecimal("66.00"), "ALIPAY");
        MvcResult mvcResult = mockMvc.perform(post("/sample/cloud/sentinel/payment/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payDTO)))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(mvcResult.getResponse().getHeader("X-Sentinel-Block-Rule")).isEqualTo("FLOW");
        JsonNode json = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        assertThat(json.get("code").asInt()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
        assertThat(blockCounter.getTotalBlocked()).isEqualTo(1);
        assertThat(blockCounter.getFlowCount()).isEqualTo(1);
        assertThat(blockCounter.getLastBlockEvent()).isNotNull();
        assertThat(blockCounter.getLastBlockEvent().resource()).isEqualTo(feignResource);
    }

    @Test
    @DisplayName("下游普通业务异常原样透传：PaymentFallbackFactory 不覆写普通 BusinessException 且不触发阻断计数")
    void testFeignOrdinaryBusinessExceptionPassthroughWithoutCounting() {
        BusinessException downstreamBiz = new BusinessException(10001, "账户余额不足");
        RemotePaymentClient fallbackProxy = paymentFallbackFactory.create(downstreamBiz);
        PaymentPayDTO payDTO = new PaymentPayDTO("ORD-TEST-1004", new BigDecimal("50.00"), "WECHAT");

        assertThatThrownBy(() -> fallbackProxy.payOrder(payDTO, false, false))
                .isSameAs(downstreamBiz);
        assertThat(blockCounter.getTotalBlocked()).isZero();
    }
}
