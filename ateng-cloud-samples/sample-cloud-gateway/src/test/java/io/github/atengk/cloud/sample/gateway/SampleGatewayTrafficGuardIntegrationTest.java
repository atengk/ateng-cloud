package io.github.atengk.cloud.sample.gateway;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.sample.gateway.controller.MockTestController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 网关流量防卫与防雪崩端到端集成测试 (100% 离线自洽)
 *
 * <p>全面覆盖以下生产高并发流量防波堤语义：
 * <ul>
 *     <li>Netty 2MB 载荷硬防爆：请求报文超限直接拦截并返回标准 HTTP 413 Result；</li>
 *     <li>原生流式零缓冲转发：SSE (text/event-stream) 随到随推，保持打字机低延迟体验；</li>
 *     <li>分布式限流标准 429 契约转译：附加 Retry-After 标头与标准 Result 结构；</li>
 *     <li>写请求零重试防雪崩铁律：POST 写操作底层建连失败绝对禁止重试 (执行次数=1)；</li>
 *     <li>纯读请求建连失败弹性重试：GET 读操作发生建连异常弹性重试 1 次并成功恢复 (执行次数=2)。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "15000")
public class SampleGatewayTrafficGuardIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MockTestController mockTestController;

    @BeforeEach
    void setUp() {
        mockTestController.resetRetryCounters();
    }

    @Test
    @DisplayName("测试 Netty 2MB 载荷硬限制：超大报文 (>2MB) 直接熔断拦截为 HTTP 413 Result")
    void testPayloadBombBlockedWith413() {
        // 构造 2.5MB 超限载荷 (超过网关 2MB 硬限制)
        byte[] oversizedPayload = new byte[2500000];

        webTestClient.post()
                .uri("/api/mock/upload-echo")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(oversizedPayload)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(413)
                .jsonPath("$.msg").value(containsString("2MB"))
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试 SSE 流式协议原生零缓冲透传 (text/event-stream 逐条推送)")
    void testSseStreamingPassthrough() {
        var eventFlux = webTestClient.get()
                .uri("/api/mock/sse")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .getResponseBody();

        List<ServerSentEvent<String>> events = eventFlux.take(3).collectList().block();
        assertNotNull(events, "SSE 响应列表不可为空");
        assertEquals(3, events.size());
        assertEquals("chunk-0", events.get(0).data());
        assertEquals("chunk-1", events.get(1).data());
        assertEquals("chunk-2", events.get(2).data());
    }

    @Test
    @DisplayName("测试限流触发 HTTP 429 状态标准化转译与 Retry-After 标头附加")
    void testRateLimitResponseTranslation() {
        webTestClient.get()
                .uri("/api/mock/simulate-rate-limit")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectHeader().exists(GatewayConstants.X_TRACE_ID)
                .expectBody()
                .jsonPath("$.code").isEqualTo(429)
                .jsonPath("$.msg").isEqualTo("当前访问过于频繁，请稍后重试")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    @DisplayName("测试防雪崩重试铁律：POST 写请求建连失败绝对零重试 (执行次数=1)")
    void testWriteMethodZeroRetryOnFailure() {
        webTestClient.post()
                .uri("/api/mock/retry-counter")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.code").isEqualTo(503);

        // 强断言：非幂等写操作绝对禁止重试，执行次数严密锁定为 1
        assertEquals(1, mockTestController.getPostRetryCount(), "非幂等写请求绝对禁止重试");
    }

    @Test
    @DisplayName("测试防雪崩重试机制：GET 读请求建连失败弹性重试 1 次恢复成功 (执行次数=2)")
    void testReadMethodElasticRetrySuccess() {
        webTestClient.get()
                .uri("/api/mock/retry-counter")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data").isEqualTo(2);

        // 强断言：读操作建连异常后重试 1 次成功恢复，总执行次数为 2
        assertEquals(2, mockTestController.getGetRetryCount(), "读请求建连失败应弹性重试 1 次");
    }
}
