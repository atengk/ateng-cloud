package io.github.atengk.cloud.sample.seata;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.sample.seata.model.dto.PurchaseOrderDTO;
import io.github.atengk.cloud.sample.seata.service.AccountService;
import io.github.atengk.cloud.sample.seata.service.OrderService;
import io.github.atengk.cloud.sample.seata.service.StorageService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AT 模式采购提单控制器端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@AutoConfigureMockMvc
@DisplayName("AT 模式采购提单 Controller 端到端场景测试")
class SampleSeataPurchaseOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private AccountService accountService;

    private static final String USER_ID = "user_purchase_001";
    private static final String COMMODITY_CODE = "product_purchase_001";

    @BeforeEach
    void setUp() {
        storageService.initStock(COMMODITY_CODE, 100);
        accountService.initAccount(USER_ID, 1000);
        orderService.cleanOrders();
    }

    @AfterEach
    void tearDown() {
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
        MDC.clear();
        orderService.cleanOrders();
    }

    @Test
    @DisplayName("测试采购提单正常提交：扣减库存与余额并生成订单")
    void testSubmitPurchaseOrder_Success() throws Exception {
        PurchaseOrderDTO dto = new PurchaseOrderDTO(USER_ID, COMMODITY_CODE, 2);

        mockMvc.perform(post("/sample/cloud/seata/purchase/submit")
                        .header("TX_XID", "127.0.0.1:8091:202611030001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.commodityCode").value(COMMODITY_CODE))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.orderMoney").value(200))
                .andExpect(jsonPath("$.data.stockRemaining").value(98))
                .andExpect(jsonPath("$.data.balanceRemaining").value(800))
                .andExpect(jsonPath("$.data.status").value("COMMITTED"));

        // 强断言底层 H2 数据库真实落库
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(98);
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(800);
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("测试采购提单模拟异常回滚：两阶段原子回滚，无脏数据落库")
    void testSubmitPurchaseOrder_Rollback() throws Exception {
        PurchaseOrderDTO dto = new PurchaseOrderDTO(USER_ID, COMMODITY_CODE, 2);

        mockMvc.perform(post("/sample/cloud/seata/purchase/submit-rollback")
                        .header("TX_XID", "127.0.0.1:8091:202611030002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5101));

        // 强断言底层数据零污染（完全恢复原状）
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(100);
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(0);
    }

    @Test
    @DisplayName("测试余额不足时自动回滚：库存恢复原状")
    void testSubmitPurchaseOrder_InsufficientBalance_Rollback() throws Exception {
        // 用户仅有 100 元，采购 5 件（需 500 元）
        accountService.initAccount(USER_ID, 100);
        PurchaseOrderDTO dto = new PurchaseOrderDTO(USER_ID, COMMODITY_CODE, 5);

        mockMvc.perform(post("/sample/cloud/seata/purchase/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));

        // 强断言库存未被单边扣减
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(100);
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(100);
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(0);
    }

    @Test
    @DisplayName("测试查询库存与余额接口")
    void testGetStockAndBalance() throws Exception {
        mockMvc.perform(get("/sample/cloud/seata/purchase/stock-and-balance")
                        .param("userId", USER_ID)
                        .param("commodityCode", COMMODITY_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.commodityCode").value(COMMODITY_CODE))
                .andExpect(jsonPath("$.data.stock").value(100))
                .andExpect(jsonPath("$.data.balance").value(1000));
    }

    @Test
    @DisplayName("测试入参 JSR-303 参数校验拦截")
    void testSubmitPurchaseOrder_ValidationFailure() throws Exception {
        // 订购数量为 0，且商品编码为空
        PurchaseOrderDTO invalidDto = new PurchaseOrderDTO(USER_ID, "", 0);

        mockMvc.perform(post("/sample/cloud/seata/purchase/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }
}
