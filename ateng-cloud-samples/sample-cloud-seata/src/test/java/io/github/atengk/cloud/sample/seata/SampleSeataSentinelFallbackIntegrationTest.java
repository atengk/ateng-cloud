package io.github.atengk.cloud.sample.seata;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sample.seata.service.OrderService;
import io.github.atengk.cloud.sample.seata.service.StorageService;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sentinel 熔断降级与 Seata 全局事务回滚防卫对抗性端到端集成实测
 * <p>
 * 规约对抗防卫：
 * 1. 活跃全局事务下对抗用例：下游跨微服务调用触发熔断降级时，Fallback 逻辑若返回 Mock 假数据将诱骗事务协调器误判提交引发单边脏写；
 *    SeataFallbackDefense 强制阻断抛出 SEATA_DEGRADE_BLOCKED(5104) 异常，驱动全链路原子回滚，强断言本地写操作 100% 回滚；
 * 2. 普通无事务对抗用例：在无全局事务上下文中，下游熔断降级正常平滑放行，允许本地单体业务安全提交。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = SampleSeataApplication.class)
@DisplayName("Sentinel 降级回滚强制防卫对抗性集成测试")
class SampleSeataSentinelFallbackIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private StorageService storageService;

    private static final String USER_ID = "sentinel_user_001";
    private static final String COMMODITY_CODE = "product_sentinel_001";
    private static final String TEST_XID = "192.168.1.77:8091:202610317777";

    @BeforeEach
    void setUp() {
        storageService.initStock(COMMODITY_CODE, 100);
        orderService.cleanOrders();
        RootContext.unbind();
    }

    @AfterEach
    void tearDown() {
        RootContext.unbind();
        MDC.remove(SeataConstants.MDC_KEY_XID);
    }

    @Test
    @DisplayName("对抗性用例：活跃全局事务下，下游触发熔断降级时强制阻断并全链路回滚")
    void shouldRollbackTransactionWhenDownstreamFallbackTriggeredInActiveTx() {
        // 绑定全局分布式事务 XID
        RootContext.bind(TEST_XID);

        RuntimeException downstreamCause = new RuntimeException("Sentinel FlowException: 触发集群流控降级");

        // 下单购买 5 件商品，下游调用抛出异常触发降级
        assertThatThrownBy(() -> orderService.createOrderWithDownstream(USER_ID, COMMODITY_CODE, 5, () -> {
            throw downstreamCause;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("下游外部账户扣费服务")
                .hasMessageContaining(TEST_XID)
                .hasCause(downstreamCause)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_DEGRADE_BLOCKED.getCode());
                });

        // 关键强断言：事务原子回滚，订单未落库！
        assertThat(orderService.getOrderCount(USER_ID)).isZero();

        // 关键强断言：本地已执行的库存扣减操作完全回滚，库存维持初始 100 件（绝不发生单边脏扣为 95）！
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(100);
    }

    @Test
    @DisplayName("对比用例：无全局事务普通调用下，下游触发熔断降级正常平滑放行并提交")
    void shouldAllowFallbackPeacefullyWhenNoActiveTx() {
        // 确保无事务上下文
        assertThat(RootContext.getXID()).isNull();

        RuntimeException downstreamCause = new RuntimeException("Sentinel CircuitBreaker Degraded");

        // 下单购买 5 件商品，下游触发降级
        Long orderId = orderService.createOrderWithDownstream(USER_ID, COMMODITY_CODE, 5, () -> {
            throw downstreamCause;
        });

        // 强断言：无事务时降级防卫静默放行，本地下单与扣减正常提交
        assertThat(orderId).isPositive();
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(1);
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(95);
    }

    @Test
    @DisplayName("正向用例：活跃全局事务下，下游正常调用全量提交")
    void shouldCommitNormallyWhenDownstreamSucceedsInActiveTx() {
        RootContext.bind(TEST_XID);

        Long orderId = orderService.createOrderWithDownstream(USER_ID, COMMODITY_CODE, 3, () -> {
            // 下游远程调用执行成功
        });

        assertThat(orderId).isPositive();
        assertThat(orderService.getOrderCount(USER_ID)).isEqualTo(1);
        assertThat(storageService.getStock(COMMODITY_CODE)).isEqualTo(97);
    }
}
