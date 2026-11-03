package io.github.atengk.cloud.sample.sentinel;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.sentinel.client.RemotePaymentClient;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentPayDTO;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentResultVO;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feign 客户端受管降级工厂端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@SpringBootTest
class SentinelFeignFallbackTest {

    @Autowired
    private RemotePaymentClient remotePaymentClient;

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
    }
}
