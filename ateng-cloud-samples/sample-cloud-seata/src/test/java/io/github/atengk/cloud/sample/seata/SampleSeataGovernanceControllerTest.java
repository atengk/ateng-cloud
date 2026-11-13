package io.github.atengk.cloud.sample.seata;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步线程池 XID 穿透与 @DS 假切源防卫 Controller 端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@AutoConfigureMockMvc
@DisplayName("异步 XID 穿透与 @DS 假切源防卫 Controller 端到端测试")
class SampleSeataGovernanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        DynamicDataSourceContextHolder.clear();
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
        MDC.remove(SeataConstants.MDC_KEY_XID);
    }

    @Test
    @DisplayName("测试受管异步线程池 XID 与 MDC XID Correlation 跨线程自动穿透及清理")
    void testAsyncXidPropagationViaController() throws Exception {
        String customXid = "127.0.0.1:8091:202611130001";

        mockMvc.perform(post("/sample/cloud/seata/async/propagate")
                        .param("xid", customXid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.masterXid").value(customXid))
                .andExpect(jsonPath("$.data.workerXid").value(customXid))
                .andExpect(jsonPath("$.data.workerMdcXid").value(customXid))
                .andExpect(jsonPath("$.data.propagated").value(true));

        assertThat(RootContext.getXID()).isNull();
        assertThat(MDC.get(SeataConstants.MDC_KEY_XID)).isNull();
    }

    @Test
    @DisplayName("测试活跃全局分布式事务下声明式 @DS(\"slave\") 跨库切源合法放行")
    void testAllowDatasourceSwitchInGlobalTransaction() throws Exception {
        String globalXid = "127.0.0.1:8091:202611130002";

        mockMvc.perform(post("/sample/cloud/seata/datasource-guard/allow-in-global-tx")
                        .param("xid", globalXid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.boundDatasource").value("master"))
                .andExpect(jsonPath("$.data.targetDatasource").value("slave"))
                .andExpect(jsonPath("$.data.xid").value(globalXid))
                .andExpect(jsonPath("$.data.allowed").value(true));
    }

    @Test
    @DisplayName("测试 Spring 单连接本地事务下声明式 @DS(\"slave\") 非法跨库假切源触发 5105 刚性阻断")
    void testBlockFalseDatasourceSwitchInLocalTransaction() throws Exception {
        mockMvc.perform(post("/sample/cloud/seata/datasource-guard/block-false-switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SeataResultCode.SEATA_FALSE_DATASOURCE_SWITCH.getCode()));
    }
}
