package io.github.atengk.cloud.sample.seata;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sample.seata.model.dto.TccDebitDTO;
import io.github.atengk.cloud.sample.seata.service.AccountService;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.tcc.TccFenceBarrier;
import io.github.atengk.cloud.seata.tcc.TccFenceRecord;
import io.github.atengk.cloud.seata.tcc.TccFenceStatus;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TCC 业务防卫屏障 Controller 端到端场景测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@AutoConfigureMockMvc
@DisplayName("TCC 防卫屏障 Controller 端到端场景测试")
class SampleSeataTccFenceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TccFenceBarrier tccFenceBarrier;

    private static final String USER_ID = "tcc_ctrl_user_001";
    private static final String XID = "127.0.0.1:8091:202611038888";

    @BeforeEach
    void setUp() {
        accountService.initAccount(USER_ID, 1000);
    }

    @AfterEach
    void tearDown() {
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
        MDC.clear();
    }

    @Test
    @DisplayName("测试 TCC 一阶段 Try 准备与二阶段 Confirm 提交")
    void testPrepareAndCommit() throws Exception {
        Long branchId = 3001L;
        TccDebitDTO dto = new TccDebitDTO(XID, branchId, USER_ID, 200);

        // 1. Try: 预扣 200
        mockMvc.perform(post("/sample/cloud/seata/tcc/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("TRIED"))
                .andExpect(jsonPath("$.data.balanceRemaining").value(800));

        assertThat(accountService.getBalance(USER_ID)).isEqualTo(800);

        // 2. Confirm: 确认
        mockMvc.perform(post("/sample/cloud/seata/tcc/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.balanceRemaining").value(800));

        TccFenceRecord record = tccFenceBarrier.getRepository().findByXidAndBranchId(XID, branchId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TccFenceStatus.COMMITTED);
    }

    @Test
    @DisplayName("测试 TCC 一阶段 Try 与二阶段 Cancel 回滚补偿")
    void testPrepareAndRollback() throws Exception {
        Long branchId = 3002L;
        TccDebitDTO dto = new TccDebitDTO(XID, branchId, USER_ID, 300);

        // 1. Try: 预扣 300
        mockMvc.perform(post("/sample/cloud/seata/tcc/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("TRIED"))
                .andExpect(jsonPath("$.data.balanceRemaining").value(700));

        // 2. Cancel: 补偿回退
        mockMvc.perform(post("/sample/cloud/seata/tcc/rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("ROLLBACKED"))
                .andExpect(jsonPath("$.data.balanceRemaining").value(1000));

        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);
        TccFenceRecord record = tccFenceBarrier.getRepository().findByXidAndBranchId(XID, branchId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TccFenceStatus.ROLLBACKED);
    }

    @Test
    @DisplayName("测试空回滚防卫：未执行 Try 直接 Cancel 自动插入 SUSPENDED 标记并不执行退款")
    void testEmptyRollback() throws Exception {
        Long branchId = 3003L;
        TccDebitDTO dto = new TccDebitDTO(XID, branchId, USER_ID, 400);

        mockMvc.perform(post("/sample/cloud/seata/tcc/empty-rollback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        // 强断言余额未被虚增
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);
        TccFenceRecord record = tccFenceBarrier.getRepository().findByXidAndBranchId(XID, branchId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(TccFenceStatus.SUSPENDED);
    }

    @Test
    @DisplayName("测试业务悬挂防卫：Cancel 先行到达后，延迟到达的 Try 被刚性拦截拒执行并抛出 5110")
    void testHangingDefense() throws Exception {
        Long branchId = 3004L;
        TccDebitDTO dto = new TccDebitDTO(XID, branchId, USER_ID, 500);

        mockMvc.perform(post("/sample/cloud/seata/tcc/hanging-defense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SeataResultCode.TCC_FENCE_SUSPENDED.getCode()));

        // 强断言延迟到达的 Try 未扣款，余额保持 1000
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);
    }

    @Test
    @DisplayName("测试二阶段重复提交幂等防卫：重复 Confirm 不产生重复扣费")
    void testDuplicateCommit() throws Exception {
        Long branchId = 3005L;
        TccDebitDTO dto = new TccDebitDTO(XID, branchId, USER_ID, 100);

        mockMvc.perform(post("/sample/cloud/seata/tcc/duplicate-commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.balanceRemaining").value(900));

        // 强断言余额只扣了一次 100，剩余 900
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(900);
    }

    @Test
    @DisplayName("测试 JSR-303 参数校验拦截")
    void testValidationFailure() throws Exception {
        TccDebitDTO invalidDto = new TccDebitDTO("", 0);

        mockMvc.perform(post("/sample/cloud/seata/tcc/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }
}
