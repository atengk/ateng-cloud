package io.github.atengk.cloud.traffic.exception;

import io.github.atengk.cloud.traffic.code.TrafficResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流量灰度路由异常单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficRoutingExceptionTest {

    @Test
    @DisplayName("测试 TrafficRoutingException 错误码与元数据封装")
    void testExceptionCreation() {
        TrafficRoutingException ex = new TrafficRoutingException("order-service", "canary-v2");

        assertThat(ex.getCode()).isEqualTo(TrafficResultCode.STRICT_LANE_BLOCK.getCode());
        assertThat(ex.getResultCode()).isEqualTo(TrafficResultCode.STRICT_LANE_BLOCK);
        assertThat(ex.getServiceId()).isEqualTo("order-service");
        assertThat(ex.getTag()).isEqualTo("canary-v2");
        assertThat(ex.getMessage()).contains("order-service", "canary-v2", "strict-lane");
    }

    @Test
    @DisplayName("测试自定义结果码与消息构造")
    void testCustomResultCodeException() {
        TrafficRoutingException ex = new TrafficRoutingException(
                TrafficResultCode.TRAFFIC_ROUTING_ERROR,
                "pay-service",
                "beta",
                "自定义路由失败说明"
        );

        assertThat(ex.getCode()).isEqualTo(TrafficResultCode.TRAFFIC_ROUTING_ERROR.getCode());
        assertThat(ex.getServiceId()).isEqualTo("pay-service");
        assertThat(ex.getTag()).isEqualTo("beta");
        assertThat(ex.getMessage()).isEqualTo("自定义路由失败说明");
    }
}
