package io.github.atengk.cloud.sample.seata;

import feign.RequestTemplate;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import io.github.atengk.cloud.seata.interceptor.SeataFeignContextInterceptor;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP Feign 跨微服务 XID 真实穿透与提供端对称绑定/解绑集成实测
 * <p>
 * 覆盖：
 * 1. Feign 客户端请求发起端：检测 RootContext XID 并自动透明注入 HTTP TX_XID 请求头；
 * 2. Web MVC 服务端入站：SeataWebHandlerInterceptor 拦截请求，对称提取 XID 绑定至 RootContext，并同步透传至 SLF4J MDC xid；
 * 3. 确定性生命周期：请求完成后 afterCompletion 对称解绑 RootContext 并恢复 MDC，杜绝线程污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = {SampleSeataApplication.class, SampleSeataFeignMdcIntegrationTest.TestEchoController.class})
@AutoConfigureMockMvc
@DisplayName("Feign 跨微服务 XID 穿透与 MDC 黄金链路端到端集成测试")
class SampleSeataFeignMdcIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private SeataFeignContextInterceptor feignContextInterceptor;

    private static final String TEST_XID = "192.168.1.88:8091:202610318888";

    @BeforeEach
    @AfterEach
    void cleanup() {
        RootContext.unbind();
        MDC.remove(SeataConstants.MDC_KEY_XID);
    }

    @Test
    @DisplayName("测试 Feign 客户端请求拦截器：活跃事务下自动注入 TX_XID 请求头")
    void shouldInjectTxXidHeaderOnFeignRequest() {
        assertThat(feignContextInterceptor).isNotNull();

        RootContext.bind(TEST_XID);
        RequestTemplate template = new RequestTemplate();
        feignContextInterceptor.apply(template);

        Map<String, Collection<String>> headers = template.headers();
        assertThat(headers).containsKey(SeataConstants.TX_XID);
        assertThat(headers.get(SeataConstants.TX_XID)).contains(TEST_XID);

        // 无事务时不得注入 TX_XID 请求头
        RootContext.unbind();
        RequestTemplate templateNoTx = new RequestTemplate();
        feignContextInterceptor.apply(templateNoTx);
        assertThat(templateNoTx.headers()).doesNotContainKey(SeataConstants.TX_XID);
    }

    @Test
    @DisplayName("测试 WebMVC 服务端入站：TX_XID 自动绑定 RootContext 并在 Controller 与 MDC 中同步生效")
    void shouldPropagateXidAndMdcInServerPipeline() throws Exception {
        mockMvc.perform(get("/test/feign/tx-echo")
                        .header(SeataConstants.TX_XID, TEST_XID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootContextXid").value(TEST_XID))
                .andExpect(jsonPath("$.mdcXid").value(TEST_XID));

        // 强断言：请求完成后服务端对称释放 RootContext 与 MDC，防止线程池污染
        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();
    }

    @Test
    @DisplayName("测试 WebMVC 服务端无事务调用时：正常放行且 MDC 为空")
    void shouldHandleNonTransactionalRequestNormally() throws Exception {
        mockMvc.perform(get("/test/feign/tx-echo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootContextXid").isEmpty())
                .andExpect(jsonPath("$.mdcXid").isEmpty());

        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();
    }

    @RestController
    @RequestMapping("/test/feign")
    static class TestEchoController {

        @GetMapping("/tx-echo")
        public Map<String, String> echoTxContext() {
            Map<String, String> result = new HashMap<>();
            result.put("rootContextXid", RootContext.getXID() != null ? RootContext.getXID() : "");
            result.put("mdcXid", MDC.get(SeataConstants.MDC_KEY_XID) != null ? MDC.get(SeataConstants.MDC_KEY_XID) : "");
            return result;
        }
    }
}
