package io.github.atengk.cloud.sample.dubbo.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dubbo 交易撮合与结算 Web 接入消费控制器端到端集成测试
 * 覆盖：单笔 POJO 强契约结算、Result 自动断言剥壳防呆、Server Stream 流式推送、4D 上下文/灰度染色穿透及参数校验
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public class DubboTradeConsumerControllerTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int dubboPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> dubboPort);
        registry.add("test.trade.dubbo.port", () -> dubboPort);
        registry.add("dubbo.test.reference.url", () -> "tri://127.0.0.1:" + dubboPort);
        registry.add("dubbo.consumer.direct-url", () -> "tri://127.0.0.1:" + dubboPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("验证 POST /settle/pojo 发起单笔原生 POJO 强契约结算成功")
    void testSettlePojo_Success() throws Exception {
        String requestJson = """
                {
                    "orderNo": "ORD_TRADE_20261103_001",
                    "accountId": 999001,
                    "amount": 258.50,
                    "tradeType": "PURCHASE"
                }
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/trade/settle/pojo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.orderNo", is("ORD_TRADE_20261103_001")))
                .andExpect(jsonPath("$.data.accountId", is("999001")))
                .andExpect(jsonPath("$.data.amount", is(258.50)))
                .andExpect(jsonPath("$.data.status", is("SUCCESS")))
                .andExpect(jsonPath("$.data.billingNo", notNullValue()))
                .andExpect(jsonPath("$.data.processNode", is("node-trade-cluster-01")));
    }

    @Test
    @DisplayName("验证 POST /settle/result 发起统一 Result<T> 强契约包装结算成功")
    void testSettleResult_Success() throws Exception {
        String requestJson = """
                {
                    "orderNo": "ORD_TRADE_20261103_002",
                    "accountId": 999002,
                    "amount": 88.00,
                    "tradeType": "PURCHASE"
                }
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/trade/settle/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.orderNo", is("ORD_TRADE_20261103_002")))
                .andExpect(jsonPath("$.data.amount", is(88.00)))
                .andExpect(jsonPath("$.data.status", is("SUCCESS")));
    }

    @Test
    @DisplayName("验证 POST /settle/failed 模拟非 200 结果触发 DubboContractAssertFilter 自动断言转译为业务异常")
    void testSettleFailedResult_ContractAssert() throws Exception {
        String requestJson = """
                {
                    "orderNo": "ORD_TRADE_FAIL_001",
                    "accountId": 999003,
                    "amount": 999999.00,
                    "tradeType": "PURCHASE"
                }
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/trade/settle/failed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(4002)))
                .andExpect(jsonPath("$.msg", containsString("账户结算余额不足")));
    }

    @Test
    @DisplayName("验证 GET /stream/bills Triple 原生 Server Stream 流式对账推送")
    void testStreamBillings_Success() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/trade/stream/bills")
                        .param("accountId", "999004")
                        .param("limit", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[0].status", is("SETTLED")))
                .andExpect(jsonPath("$.data[0].billingNo", notNullValue()));
    }

    @Test
    @DisplayName("验证 GET /context/probe 全链路四维上下文、Seata XID 与金丝雀灰度染色穿透")
    void testContextProbe_Propagation() throws Exception {
        mockMvc.perform(get("/sample/cloud/dubbo/trade/context/probe")
                        .header("X-User-Id", "user-trade-vip")
                        .header("X-Tenant-Id", "tenant-dubbo-trade")
                        .header("X-Traffic-Tag", "canary-v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.userId", is("user-trade-vip")))
                .andExpect(jsonPath("$.data.tenantId", is("tenant-dubbo-trade")))
                .andExpect(jsonPath("$.data.trafficTag", is("canary-v2")));
    }

    @Test
    @DisplayName("验证 POST /settle/pojo 入参不合规时 JSR-303 参数格式快速失败拦截")
    void testSettlePojo_ValidationFailed() throws Exception {
        String invalidJson = """
                {
                    "orderNo": "",
                    "accountId": null,
                    "amount": -10.00,
                    "tradeType": ""
                }
                """;

        mockMvc.perform(post("/sample/cloud/dubbo/trade/settle/pojo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.msg", notNullValue()));
    }
}
