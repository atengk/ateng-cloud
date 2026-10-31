package io.github.atengk.cloud.seata.fallback;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Seata 分布式事务熔断降级防卫机制与断言单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class SeataFallbackDefenseTest {

    private static final String TEST_XID = "192.168.1.100:8091:20261031001";

    @BeforeEach
    @AfterEach
    void cleanup() {
        RootContext.unbind();
    }

    @Test
    @DisplayName("测试状态探测：无事务返回 false，绑定 XID 返回 true")
    void testHasActiveTransaction() {
        assertThat(SeataFallbackDefense.hasActiveTransaction()).isFalse();
        assertThat(SeataAssert.hasActiveTransaction()).isFalse();

        RootContext.bind(TEST_XID);
        assertThat(SeataFallbackDefense.hasActiveTransaction()).isTrue();
        assertThat(SeataAssert.hasActiveTransaction()).isTrue();

        RootContext.unbind();
        assertThat(SeataFallbackDefense.hasActiveTransaction()).isFalse();
        assertThat(SeataAssert.hasActiveTransaction()).isFalse();
    }

    @Test
    @DisplayName("测试无事务调用时静默放行，允许普通下游降级")
    void testNoActiveTransactionDoesNotThrow() {
        assertThatCode(() -> {
            SeataFallbackDefense.failOnActiveTransaction();
            SeataFallbackDefense.failOnActiveTransaction("扣减库存");
            SeataFallbackDefense.failOnActiveTransaction(new RuntimeException("RPC Timeout"));
            SeataFallbackDefense.failOnActiveTransaction("扣减库存", new RuntimeException("RPC Timeout"));

            SeataAssert.failOnActiveTransaction();
            SeataAssert.failOnActiveTransaction("扣减库存");
            SeataAssert.failOnActiveTransaction(new RuntimeException("RPC Timeout"));
            SeataAssert.failOnActiveTransaction("扣减库存", new RuntimeException("RPC Timeout"));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("测试活跃事务下无参防卫拦截，抛出 SEATA_DEGRADE_BLOCKED 异常并包含 XID")
    void testActiveTransactionThrowsDegradeBlocked() {
        RootContext.bind(TEST_XID);

        assertThatThrownBy(SeataFallbackDefense::failOnActiveTransaction)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TEST_XID)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_DEGRADE_BLOCKED.getCode());
                    assertThat(be.getCause()).isNull();
                });
    }

    @Test
    @DisplayName("测试活跃事务下包含动作描述的防卫拦截")
    void testActiveTransactionWithActionDesc() {
        RootContext.bind(TEST_XID);
        String action = "扣减商品库存";

        assertThatThrownBy(() -> SeataFallbackDefense.failOnActiveTransaction(action))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(action)
                .hasMessageContaining(TEST_XID)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(5104);
                });
    }

    @Test
    @DisplayName("测试活跃事务下保留原始根因因果链")
    void testActiveTransactionWithCause() {
        RootContext.bind(TEST_XID);
        IllegalStateException rootCause = new IllegalStateException("Sentinel Flow Limit");

        assertThatThrownBy(() -> SeataFallbackDefense.failOnActiveTransaction(rootCause))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TEST_XID)
                .hasCause(rootCause)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(SeataResultCode.SEATA_DEGRADE_BLOCKED.getCode());
                });
    }

    @Test
    @DisplayName("测试活跃事务下组合动作描述与因果链防卫拦截")
    void testActiveTransactionWithActionDescAndCause() {
        RootContext.bind(TEST_XID);
        String action = "冻结账户资产";
        IllegalStateException rootCause = new IllegalStateException("CircuitBreaker Degraded");

        assertThatThrownBy(() -> SeataFallbackDefense.failOnActiveTransaction(action, rootCause))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(action)
                .hasMessageContaining(TEST_XID)
                .hasCause(rootCause)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(5104);
                });
    }

    @Test
    @DisplayName("测试 SeataAssert 门面语义化断言完整委托")
    void testSeataAssertDelegation() {
        RootContext.bind(TEST_XID);
        String action = "创建物流运单";
        RuntimeException rootCause = new RuntimeException("SocketTimeout");

        assertThatThrownBy(SeataAssert::failOnActiveTransaction)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(TEST_XID);

        assertThatThrownBy(() -> SeataAssert.failOnActiveTransaction(action))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(action);

        assertThatThrownBy(() -> SeataAssert.failOnActiveTransaction(rootCause))
                .isInstanceOf(BusinessException.class)
                .hasCause(rootCause);

        assertThatThrownBy(() -> SeataAssert.failOnActiveTransaction(action, rootCause))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(action)
                .hasCause(rootCause);
    }

    @Test
    @DisplayName("测试 SeataResultCode 枚举定义规范")
    void testSeataResultCode() {
        assertThat(SeataResultCode.SEATA_DEGRADE_BLOCKED.getCode()).isEqualTo(5104);
        assertThat(SeataResultCode.SEATA_DEGRADE_BLOCKED.getMsg()).isEqualTo("下游调用触发熔断降级，全局事务强制阻断回滚");
    }
}
