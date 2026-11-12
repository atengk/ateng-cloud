package io.github.atengk.cloud.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 限流 429 统一转译过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("限流 429 统一转译过滤器单元测试")
class GatewayRateLimitResponseFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayRateLimitResponseFilter filter = new GatewayRateLimitResponseFilter(objectMapper);

    @Test
    @DisplayName("当底层限流触发 429 状态并 setComplete 时自动转译为统一 Result JSON")
    void testRateLimitResponseFormattingOnSetComplete() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test")
                .header(GatewayConstants.X_TRACE_ID, "trace-429-test")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_TRACE_ID, "trace-429-test");

        // 模拟下游 RequestRateLimiter 设置 429 并完成响应
        GatewayFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return ex.getResponse().setComplete();
        };

        filter.filter(exchange, chain).block();

        MockServerHttpResponse response = exchange.getResponse();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().getContentType().toString());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals("trace-429-test", response.getHeaders().getFirst(GatewayConstants.X_TRACE_ID));

        String body = response.getBodyAsString().block();
        assertNotNull(body);
        JsonNode jsonNode = objectMapper.readTree(body);
        assertEquals(429, jsonNode.get("code").asInt());
        assertEquals("当前访问过于频繁，请稍后重试", jsonNode.get("msg").asText());
        assertEquals("trace-429-test", jsonNode.get("traceId").asText());
    }

    @Test
    @DisplayName("正常 200 请求不受影响直接放行")
    void testNormalResponseNotAffected() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            byte[] bytes = "normal-data".getBytes(StandardCharsets.UTF_8);
            return ex.getResponse().writeWith(Mono.just(ex.getResponse().bufferFactory().wrap(bytes)));
        };

        filter.filter(exchange, chain).block();

        MockServerHttpResponse response = exchange.getResponse();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("normal-data", response.getBodyAsString().block());
    }

    @Test
    @DisplayName("当下游写出携带池化 DataBuffer 的 429 响应并被转译时，原缓冲区引用计数确定性归零释放")
    void testRateLimitWriteWithReleasesOriginalPooledDataBuffer() throws Exception {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/seckill")
                .header(GatewayConstants.X_TRACE_ID, "trace-429-buffer")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(GatewayConstants.ATTR_TRACE_ID, "trace-429-buffer");

        NettyDataBufferFactory pooledFactory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        ByteBuf nativeBuf = PooledByteBufAllocator.DEFAULT.buffer();
        nativeBuf.writeBytes("raw-rate-limit-body".getBytes(StandardCharsets.UTF_8));
        NettyDataBuffer pooledBuffer = pooledFactory.wrap(nativeBuf);
        assertEquals(1, pooledBuffer.getNativeBuffer().refCnt());

        GatewayFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return ex.getResponse().writeWith(Mono.just(pooledBuffer));
        };

        try {
            filter.filter(exchange, chain).block();

            assertEquals(0, pooledBuffer.getNativeBuffer().refCnt(),
                    "改写 429 响应前必须确定性排空并释放原始池化 DataBuffer");
            MockServerHttpResponse response = exchange.getResponse();
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
            assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            String body = response.getBodyAsString().block();
            assertNotNull(body);
            JsonNode jsonNode = objectMapper.readTree(body);
            assertEquals(429, jsonNode.get("code").asInt());
            assertEquals("trace-429-buffer", jsonNode.get("traceId").asText());
        } finally {
            if (pooledBuffer.getNativeBuffer().refCnt() > 0) {
                pooledBuffer.getNativeBuffer().release(pooledBuffer.getNativeBuffer().refCnt());
            }
        }
    }
}
