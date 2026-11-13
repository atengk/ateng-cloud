package io.github.atengk.cloud.sample.seata;

import io.github.atengk.cloud.sample.seata.service.OrderService;
import io.github.atengk.cloud.sample.seata.service.StorageService;
import io.github.atengk.cloud.seata.code.SeataResultCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局排他锁与 Sentinel 降级防卫 Controller 端到端场景测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@AutoConfigureMockMvc
@DisplayName("全局锁与降级防卫 Controller 端到端场景测试")
class SampleSeataLockAndFallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StorageService storageService;

    @Autowired
    private OrderService orderService;

    private static final String COMMODITY_CODE = "product_lock_test_001";
    private static final String USER_ID = "user_lock_test_001";

    @BeforeEach
    void setUp() {
        storageService.initStock(COMMODITY_CODE, 50);
        orderService.cleanOrders();
    }

    @AfterEach
    void tearDown() {
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
        if (RootContext.requireGlobalLock()) {
            RootContext.unbindGlobalLockFlag();
        }
        MDC.clear();
        orderService.cleanOrders();
    }

    @Test
    @DisplayName("测试声明式 @GlobalLock 校验商品库存")
    void testDeclarativeGlobalLockCheck() throws Exception {
        // 50 件库存，校验 20 件充足
        mockMvc.perform(get("/sample/cloud/seata/lock/declarative-check")
                        .param("commodityCode", COMMODITY_CODE)
                        .param("count", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 校验 100 件不足
        mockMvc.perform(get("/sample/cloud/seata/lock/declarative-check")
                        .param("commodityCode", COMMODITY_CODE)
                        .param("count", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(false));
    }

    @Test
    @DisplayName("测试编程式 SeataGlobalLockTemplate#executeWithRetry 执行库存安全扣减与库存不足 Fail-Fast 断言")
    void testProgrammaticGlobalLockUpdate() throws Exception {
        mockMvc.perform(post("/sample/cloud/seata/lock/programmatic-update")
                        .param("commodityCode", COMMODITY_CODE)
                        .param("count", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 强断言数据库扣减 10 件，剩余 40
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(40);

        // 库存不足时 BusinessAssert 业务异常透传且库存维持 40
        mockMvc.perform(post("/sample/cloud/seata/lock/programmatic-update")
                        .param("commodityCode", COMMODITY_CODE)
                        .param("count", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));

        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(40);
    }

    @Test
    @DisplayName("测试活跃全局事务下，下游触发熔断降级时强制抛出 5104 阻断假提交")
    void testFallbackWithActiveTransaction_AbortsFalseCommit() throws Exception {
        storageService.initStock(COMMODITY_CODE, 100);

        mockMvc.perform(post("/sample/cloud/seata/fallback/with-active-tx")
                        .param("userId", USER_ID)
                        .param("commodityCode", COMMODITY_CODE)
                        .param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SeataResultCode.SEATA_DEGRADE_BLOCKED.getCode()));

        // 关键断言：活跃事务下下游降级被强制抛错，本地事务 100% 回滚，库存维持 100，订单数为 0
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(100);
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(0);
    }

    @Test
    @DisplayName("测试普通无全局事务下，下游触发熔断降级时平滑放行返回保底结果")
    void testFallbackWithoutTransaction_SmoothDegradation() throws Exception {
        storageService.initStock(COMMODITY_CODE, 100);

        mockMvc.perform(post("/sample/cloud/seata/fallback/without-tx")
                        .param("userId", USER_ID)
                        .param("commodityCode", COMMODITY_CODE)
                        .param("count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("平滑降级执行成功")));

        // 关键断言：无全局事务时允许单体业务安全提交，本地库存扣减为 95，订单记录存在
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(95);
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(1);
    }
}
