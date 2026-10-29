package io.github.atengk.cloud.sample.sentinel;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sample.sentinel.client.DemoCustomFeignClient;
import io.github.atengk.cloud.sample.sentinel.client.DemoDefaultFeignClient;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
    private DemoDefaultFeignClient demoDefaultFeignClient;

    @Autowired
    private DemoCustomFeignClient demoCustomFeignClient;

    @Test
    @DisplayName("默认受管降级：调用故障节点抛出语义化 BusinessException(4202 DEGRADE)")
    void testDefaultFeignFallbackThrowsBusinessException() {
        assertThatThrownBy(() -> demoDefaultFeignClient.getRemoteUser(1001L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResultCode()).isEqualTo(SentinelResultCode.DEGRADE);
                    assertThat(be.getCode()).isEqualTo(4202);
                    assertThat(be.getCause()).isNotNull();
                    assertThat(be.getMessage()).contains("DemoDefaultFeignClient#getRemoteUser");
                });
    }

    @Test
    @DisplayName("柔性受管降级：调用故障节点返回自定义 Mock 保底数据")
    void testCustomFeignFallbackReturnsMockData() {
        String result = demoCustomFeignClient.getRemoteOrder(8888L);
        assertThat(result).isEqualTo("MockOrderFallback-8888");
    }
}
