package io.github.atengk.cloud.gateway.filter.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 弹性防雪崩安全重试过滤器工厂单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("防雪崩安全重试过滤器工厂单元测试")
class AtengRetryGatewayFilterFactoryTest {

    private final AtengRetryGatewayFilterFactory factory = new AtengRetryGatewayFilterFactory();

    @Test
    @DisplayName("非幂等写操作 (POST/PUT/PATCH/DELETE) 发生建连异常时铁律零重试 (执行次数=1)")
    void testWriteMethodZeroRetryOnConnectException() {
        RetryGatewayFilterFactory.RetryConfig config = new RetryGatewayFilterFactory.RetryConfig();
        config.setRetries(3); // 模拟用户误配置为 3 次
        GatewayFilter filter = factory.apply(config);

        for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            AtomicInteger callCount = new AtomicInteger(0);
            MockServerHttpRequest request = MockServerHttpRequest.method(method, "/api/resource").build();
            MockServerWebExchange exchange = MockServerWebExchange.from(request);

            GatewayFilterChain chain = ex -> Mono.defer(() -> {
                callCount.incrementAndGet();
                return Mono.error(new ConnectException("Connection refused"));
            });

            Exception thrown = assertThrows(Exception.class, () -> filter.filter(exchange, chain).block());
            assertEquals("Connection refused", getRootCauseMessage(thrown));
            // 铁律强断言：非幂等写操作仅执行 1 次，绝对零重试
            assertEquals(1, callCount.get(), "写操作 " + method + " 绝对禁止任何重试");
        }
    }

    @Test
    @DisplayName("纯读操作 (GET) 发生建连异常时允许弹性重试 1 次 (执行次数=2)")
    void testReadMethodRetriesOnceOnConnectException() {
        RetryGatewayFilterFactory.RetryConfig config = new RetryGatewayFilterFactory.RetryConfig();
        config.setRetries(3); // 用户配置了 3 次，框架应收敛为至多 1 次重试 (总执行 2 次)
        GatewayFilter filter = factory.apply(config);

        AtomicInteger callCount = new AtomicInteger(0);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/resource").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.defer(() -> {
            callCount.incrementAndGet();
            return Mono.error(new ConnectException("Connection refused"));
        });

        Exception thrown = assertThrows(Exception.class, () -> filter.filter(exchange, chain).block());
        assertEquals("Connection refused", getRootCauseMessage(thrown));
        // 读操作发生建连异常，弹性重试 1 次 (总计执行 2 次)
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("纯读操作 (GET) 发生读取超时 (SocketTimeoutException) 时防雪崩零重试 (执行次数=1)")
    void testReadMethodZeroRetryOnSocketTimeoutException() {
        RetryGatewayFilterFactory.RetryConfig config = new RetryGatewayFilterFactory.RetryConfig();
        config.setRetries(3);
        GatewayFilter filter = factory.apply(config);

        AtomicInteger callCount = new AtomicInteger(0);
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/resource").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> Mono.defer(() -> {
            callCount.incrementAndGet();
            return Mono.error(new SocketTimeoutException("Read timed out"));
        });

        Exception thrown = assertThrows(Exception.class, () -> filter.filter(exchange, chain).block());
        assertEquals("Read timed out", getRootCauseMessage(thrown));
        // 读超时坚决不重试，防止下游雪崩
        assertEquals(1, callCount.get());
    }

    private String getRootCauseMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
