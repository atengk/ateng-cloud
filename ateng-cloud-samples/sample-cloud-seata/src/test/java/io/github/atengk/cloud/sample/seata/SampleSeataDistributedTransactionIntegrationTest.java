package io.github.atengk.cloud.sample.seata;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sample.seata.service.AccountService;
import io.github.atengk.cloud.sample.seata.service.OrderService;
import io.github.atengk.cloud.sample.seata.service.StorageService;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AT 模式端到端分布式事务自验集成测试
 * <p>
 * 覆盖经典“Order 订单 ➔ Storage 库存 ➔ Account 余额”三节点拓扑：
 * 1. 正向用例：下单成功，订单、库存、账户数据一致提交；
 * 2. 逆向用例：余额不足或业务断言抛出 BusinessException，全链路自动原子回滚。
 * 100% 纯内存 H2 数据库离线自洽运行。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@DisplayName("AT 模式三节点分布式事务端到端集成测试")
class SampleSeataDistributedTransactionIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private AccountService accountService;

    private static final String USER_ID = "user_test_001";
    private static final String COMMODITY_CODE = "product_test_001";

    @BeforeEach
    void setUp() {
        // 模拟分布式事务 XID 绑定
        RootContext.bind("127.0.0.1:8091:998877665544332211");

        // 初始化数据：库存 100 件，账户余额 1000 元，清空历史订单
        storageService.initStock(COMMODITY_CODE, 100);
        accountService.initAccount(USER_ID, 1000);
        orderService.cleanOrders();
    }

    @AfterEach
    void tearDown() {
        // 彻底清理上下文，杜绝线程污染
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
    }

    @Test
    @DisplayName("正向场景：下单成功，订单生成、库存扣减、账户扣款全量一致提交")
    void shouldCommitDistributedTransactionSuccessfully() {
        // 下单购买 2 件，单价 100，总计 200 元
        Long orderId = orderService.createOrder(USER_ID, COMMODITY_CODE, 2);

        // 强断言：订单生成
        assertThat(orderId).isNotNull();
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(1);

        // 强断言：库存由 100 扣减为 98
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(98);

        // 强断言：账户余额由 1000 扣减为 800
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(800);
    }

    @Test
    @DisplayName("逆向场景：余额不足抛出受管 BusinessException，全链路自动原子回滚")
    void shouldRollbackDistributedTransactionOnBusinessException() {
        // 用户仅有 1000 元，尝试下单购买 15 件（金额 1500 元），触发账户余额不足异常
        assertThatThrownBy(() -> orderService.createOrder(USER_ID, COMMODITY_CODE, 15))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("余额不足");

        // 关键强断言：事务原子回滚，订单不得落库
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(0);

        // 关键强断言：库存不得单边扣减，必须恢复为初始 100 件
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(100);

        // 关键强断言：账户余额未扣减，保持初始 1000 元
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);
    }

    @Test
    @DisplayName("逆向场景：库存不足抛出受管 BusinessException，全链路自动原子回滚")
    void shouldRollbackDistributedTransactionOnStockInsufficient() {
        // 库存仅有 100 件，尝试下单购买 120 件，触发库存不足异常
        assertThatThrownBy(() -> orderService.createOrder(USER_ID, COMMODITY_CODE, 120))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        // 关键强断言：订单未生成
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(0);

        // 关键强断言：库存保持初始 100 件
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(100);

        // 关键强断言：账户余额未扣减，保持初始 1000 元
        assertThat(accountService.getBalance(USER_ID)).isEqualTo(1000);
    }
}
