package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayAccessLogProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayOpenApiProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityHeadersProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySecurityProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTenantProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import io.github.atengk.cloud.gateway.spi.IpFilterProvider;
import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.Ordered;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关内置 13 个全局过滤器与 WebFilter 执行顺序单调无碰撞校验单测
 *
 * @author Ateng
 * @since 2026-11-12
 */
@DisplayName("网关全局过滤器单调 Order 校验测试")
class GatewayFilterOrderTest {

    @Test
    @DisplayName("13 个内置过滤器 Order 严格单调递增且零碰撞")
    void testAllBuiltInFilterOrdersStrictlyMonotonic() {
        ObjectMapper objectMapper = new ObjectMapper();
        AtengGatewaySecurityProperties securityProperties = new AtengGatewaySecurityProperties();

        GatewayRateLimitResponseFilter rateLimitResponseFilter = new GatewayRateLimitResponseFilter(objectMapper);
        List<Ordered> filtersInExecutionOrder = List.of(
                new GatewaySecurityHeadersFilter(new AtengGatewaySecurityHeadersProperties()),
                new GatewayPathValidationFilter(),
                new GatewayTraceFilter(),
                new GatewayHeaderSanitizeFilter(securityProperties),
                new GatewayClientIpFilter(securityProperties),
                new GatewayIpFilter(Mockito.mock(IpFilterProvider.class)),
                new OpenApiGuardFilter(new AtengGatewayOpenApiProperties(), objectMapper),
                new GatewaySignGuardFilter(new AtengGatewaySignProperties(), Mockito.mock(SignSecretProvider.class), objectMapper),
                new SaTokenGatewayFilter(securityProperties),
                new GatewayTenantFilter(new AtengGatewayTenantProperties()),
                new GatewayTrafficTagFilter(new AtengGatewayTrafficProperties()),
                rateLimitResponseFilter,
                new GatewayAccessLogFilter(new AtengGatewayAccessLogProperties())
        );

        assertEquals(13, filtersInExecutionOrder.size());

        Set<Integer> distinctOrders = new HashSet<>();
        for (int i = 0; i < filtersInExecutionOrder.size(); i++) {
            Ordered current = filtersInExecutionOrder.get(i);
            int currentOrder = current.getOrder();
            assertTrue(distinctOrders.add(currentOrder),
                    "检测到过滤器 Order 碰撞: " + current.getClass().getSimpleName() + " order=" + currentOrder);

            if (i > 0) {
                Ordered previous = filtersInExecutionOrder.get(i - 1);
                assertTrue(previous.getOrder() < currentOrder,
                        () -> String.format("过滤器顺序非严格单调递增: %s(%d) 应小于 %s(%d)",
                                previous.getClass().getSimpleName(), previous.getOrder(),
                                current.getClass().getSimpleName(), currentOrder));
            }
        }

        assertEquals(GatewayConstants.ORDER_RATE_LIMIT_RESPONSE, rateLimitResponseFilter.getOrder(),
                "GatewayRateLimitResponseFilter 应收敛至 ORDER_RATE_LIMIT_RESPONSE (-20)");
    }
}
