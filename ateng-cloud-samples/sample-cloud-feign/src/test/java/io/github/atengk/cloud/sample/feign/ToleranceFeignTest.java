package io.github.atengk.cloud.sample.feign;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.FeignException;
import feign.RetryableException;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.feign.retry.AtengFeignRetryer;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import io.github.atengk.cloud.sample.feign.client.CustomRetryFeignClient;
import io.github.atengk.cloud.sample.feign.client.IsolatedTimeoutFeignClient;
import io.github.atengk.cloud.sample.feign.client.OrderRemoteFeignClient;
import io.github.atengk.cloud.sample.feign.client.ProductFeignClient;
import io.github.atengk.cloud.sample.feign.client.SlowCallFeignClient;
import io.github.atengk.cloud.sample.feign.client.UnreachableFeignClient;
import io.github.atengk.cloud.sample.feign.controller.FeignToleranceController;
import io.github.atengk.cloud.sample.feign.controller.RemoteToleranceProviderController;
import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import io.github.atengk.cloud.sample.feign.model.ToleranceResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 微服务容灾、防雪崩重试、因果异常转译与上下文透传端到端测试
 * 覆盖：写请求读超时绝不重试不变量、建连握手弹性重试 1 次、单 Client 重试覆盖、单 Client 超时隔离、10MB 响应防爆、四维上下文与 Same-Token 穿透
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(
        classes = SampleFeignApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "ateng.cloud.feign.logger-level=HEADERS"
        }
)
@Import(ToleranceFeignTest.TestContextConfig.class)
@DirtiesContext
public class ToleranceFeignTest {

    private static final String TEST_SAME_TOKEN = "satoken-secret-token-abcdef123456";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int port = findAvailablePort();
        registry.add("server.port", () -> port);
        registry.add("test.server.port", () -> port);
        registry.add("test.unreachable.port", () -> 59999);
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int localPort = socket.getLocalPort();
            if (localPort < 10000) {
                return 20000 + (int) (Math.random() * 30000);
            }
            return localPort;
        } catch (Exception e) {
            return 20000 + (int) (Math.random() * 30000);
        }
    }

    @TestConfiguration
    static class TestContextConfig {
        @Bean
        public SameTokenProvider testSameTokenProvider() {
            return () -> TEST_SAME_TOKEN;
        }
    }

    @Autowired
    private OrderRemoteFeignClient orderRemoteFeignClient;

    @Autowired
    private IsolatedTimeoutFeignClient isolatedTimeoutFeignClient;

    @Autowired
    private UnreachableFeignClient unreachableFeignClient;

    @Autowired
    private CustomRetryFeignClient customRetryFeignClient;

    @Autowired
    private ProductFeignClient productFeignClient;

    @Autowired
    private RemoteToleranceProviderController remoteToleranceProviderController;

    @Autowired
    private FeignToleranceController feignToleranceController;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        try {
            productFeignClient.getProductById(1L);
        } catch (Exception ignored) {
        }
        remoteToleranceProviderController.reset();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. 强断言：POST 读超时服务端接收调用次数严格为 1，绝对禁止重试防雪崩与资损")
    void testPostReadTimeoutStrictlyNoRetry() {
        assertThrows(RetryableException.class, () -> orderRemoteFeignClient.postTimeout());
        assertEquals(1, remoteToleranceProviderController.getPostCallCount(),
                "非幂等写接口（POST）读超时绝对禁止重试，下游接收到的请求次数严格必须为 1");
    }

    @Test
    @DisplayName("2. 强断言：GET 请求读取超时（SocketTimeoutException）同样不发起重试，防止下游服务雪崩")
    void testGetSlowReadTimeoutNoRetry() {
        assertThrows(RetryableException.class, () -> orderRemoteFeignClient.getTimeout());
        assertEquals(1, remoteToleranceProviderController.getGetSlowCallCount(),
                "已建立连接但服务端慢响应引发读超时，绝不发起重试，防止打崩下游慢服务");
    }

    @Test
    @DisplayName("3. 强断言：GET 请求建连握手失败（ConnectTimeoutException），执行 1 次带随机抖动的弹性重试")
    void testConnectTimeoutElasticRetryOnce() {
        Logger logger = (Logger) LoggerFactory.getLogger(AtengFeignRetryer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            RetryableException ex = assertThrows(RetryableException.class, () -> unreachableFeignClient.getUnreachable());
            assertNotNull(ex);

            boolean retryLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("正在进行第 1/1 次弹性重试"));
            assertTrue(retryLogged, "必须记录第 1/1 次弹性重试告警日志");

            boolean maxAttemptsLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("已达最大重试上限 (2/2)"));
            assertTrue(maxAttemptsLogged, "必须记录达到最大重试上限 2/2 的告警日志");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("4. 强断言：单 Client 级独立重试参数覆盖生效（按配置重试 2 次，总共 3 次后失败）")
    void testClientRetryOverride() {
        Logger logger = (Logger) LoggerFactory.getLogger(AtengFeignRetryer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            RetryableException ex = assertThrows(RetryableException.class, () -> customRetryFeignClient.getUnreachable());
            assertNotNull(ex);

            long retryCount = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("正在进行第")
                            && event.getFormattedMessage().contains("custom-retry-client"))
                    .count();
            assertEquals(2, retryCount, "针对 custom-retry-client 必须精准重试 2 次");

            boolean maxAttemptsLogged = appender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("已达最大重试上限 (3/3)")
                            && event.getFormattedMessage().contains("custom-retry-client"));
            assertTrue(maxAttemptsLogged, "必须记录 custom-retry-client 达到最大重试上限 3/3 日志");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("5. 强断言：Client 级超时物理隔离生效（独立短超时客户端超时阻断，默认长超时客户端正常成功）")
    void testClientTimeoutIsolation() {
        // isolated-timeout-client 配置 socket-timeout: 300ms，调用耗时 800ms 的接口必触发读超时
        RetryableException ex = assertThrows(RetryableException.class, () -> isolatedTimeoutFeignClient.slow());
        assertNotNull(ex);

        // 全局默认客户端 product-client 拥有默认 5000ms 读超时，常规接口调用不受影响正常成功
        ProductDetailVO product = productFeignClient.getProductById(1L);
        assertNotNull(product, "未覆盖超时的客户端平滑继承全局基线，正常通信成功");
        assertEquals(1L, product.getId());
    }

    @Test
    @DisplayName("6. 强断言：10MB 响应载荷有界读取硬防爆熔断，超限立即抛出 BusinessException 守护 JVM 堆内存")
    void testPayloadGuard10MB() {
        BusinessException ex = assertThrows(BusinessException.class, () -> orderRemoteFeignClient.largePayload());
        assertNotNull(ex);
        assertTrue(ex.getMsg().contains("超出") || ex.getMsg().contains("防爆"),
                "超大报文异常信息需明确提示响应超出防爆限制: " + ex.getMsg());
    }

    @Test
    @DisplayName("7. 强断言：下游业务错误码因果解码（Causal Error Decoding），转译为受管 BusinessException")
    void testCausalErrorDecoding() {
        BusinessException ex = assertThrows(BusinessException.class, () -> orderRemoteFeignClient.getBusinessError());
        assertEquals(6001, ex.getCode(), "转译后的业务错误码必须与服务端完全一致");
        assertTrue(ex.getMsg().contains("下游支付通道结算失败"), "错误描述文案必须完整保留");
    }

    @Test
    @DisplayName("8. 强断言：下游崩溃返回 HTTP 500 时转译为 BusinessException 并完整保留原始 cause 链")
    void testHttp500CausalTranslation() {
        BusinessException ex = assertThrows(BusinessException.class, () -> orderRemoteFeignClient.getRaw500());
        assertNotNull(ex.getCause(), "底层因果链 cause 严禁被吞咽丢弃");
        assertTrue(ex.getCause() instanceof FeignException, "因果链 root cause 必须保留原始 FeignException");
    }

    @Test
    @DisplayName("9. 强断言：出站全维度上下文 (四维上下文 + 流量染色 + Same-Token) 真实透传")
    void testPropagateFullContextAndSameToken() {
        MDC.put("traceId", "trace-e2e-8888");
        MDC.put("X-Traffic-Tag", "canary-v1");
        UserContextHolder.setUserId("user-cloud-100");
        TenantContextHolder.setTenantId("tenant-cloud-200");
        TenantContextHolder.setIgnoreTenant(true);

        Map<String, String> receivedHeaders = orderRemoteFeignClient.echoHeaders();

        assertThat(receivedHeaders).isNotNull();
        assertThat(receivedHeaders.get("x-trace-id")).isEqualTo("trace-e2e-8888");
        assertThat(receivedHeaders.get("x-user-id")).isEqualTo("user-cloud-100");
        assertThat(receivedHeaders.get("x-tenant-id")).isEqualTo("tenant-cloud-200");
        assertThat(receivedHeaders.get("x-ignore-tenant")).isEqualTo("true");
        assertThat(receivedHeaders.get("x-traffic-tag")).isEqualTo("canary-v1");
        assertThat(receivedHeaders.get("same-token")).isEqualTo(TEST_SAME_TOKEN);
    }

    @Test
    @DisplayName("10. 验证 FeignToleranceController 对外接入控制器端到端调测联动")
    void testToleranceControllerEndpoints() {
        // 写请求超时禁重试
        Result<ToleranceResultVO> writeResult = feignToleranceController.testWriteTimeout();
        assertTrue(writeResult.isSuccess());
        assertEquals(1, writeResult.getData().getAttempts());

        // 建连弹性重试
        Result<ToleranceResultVO> connectResult = feignToleranceController.testConnectTimeout();
        assertTrue(connectResult.isSuccess());
        assertEquals(2, connectResult.getData().getAttempts());

        // 自定义重试覆盖
        Result<ToleranceResultVO> customResult = feignToleranceController.testCustomRetry();
        assertTrue(customResult.isSuccess());
        assertEquals(3, customResult.getData().getAttempts());

        // 业务错误因果转译
        Result<ToleranceResultVO> errorResult = feignToleranceController.testBusinessError();
        assertTrue(errorResult.isSuccess());
        assertEquals(6001, errorResult.getData().getErrorCode());

        // 10MB 超大报文防爆
        Result<ToleranceResultVO> payloadResult = feignToleranceController.testPayloadOverflow();
        assertTrue(payloadResult.isSuccess());

        // 四维上下文传播
        Result<Map<String, String>> headersResult = feignToleranceController.testHeaders();
        assertTrue(headersResult.isSuccess());
    }

    @Test
    @DisplayName("11. 验证新增容灾体验端点与真实 HTTP 1:1 路由可达性（含慢调用主动告警）")
    void testAllToleranceDocumentedHttpRoutes() {
        // 1. 控制器新增体验端点直调断言
        Result<ToleranceResultVO> getTimeoutRes = feignToleranceController.testGetTimeout();
        assertTrue(getTimeoutRes.isSuccess());
        assertEquals(1, getTimeoutRes.getData().getAttempts());
        assertEquals(504, getTimeoutRes.getData().getErrorCode());

        Result<ToleranceResultVO> isolatedRes = feignToleranceController.testIsolatedTimeout();
        assertTrue(isolatedRes.isSuccess());
        assertEquals(504, isolatedRes.getData().getErrorCode());
        assertTrue(isolatedRes.getData().getDurationMs() < 700, "单 Client 300ms 超时隔离必须显著快于对端 800ms 耗时");

        Result<ToleranceResultVO> http500Res = feignToleranceController.testHttp500();
        assertTrue(http500Res.isSuccess());
        assertEquals(500, http500Res.getData().getErrorCode());
        assertNotNull(http500Res.getData().getRootCause());

        Logger slowLogger = (Logger) LoggerFactory.getLogger(SlowCallFeignClient.class);
        ListAppender<ILoggingEvent> slowAppender = new ListAppender<>();
        slowAppender.start();
        slowLogger.addAppender(slowAppender);
        try {
            Result<ToleranceResultVO> slowCallRes = feignToleranceController.testSlowCall();
            assertTrue(slowCallRes.isSuccess());
            assertEquals(200, slowCallRes.getData().getErrorCode());
            boolean slowWarnLogged = slowAppender.list.stream()
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("Feign 慢调用告警: client=slow-call-client"));
            assertTrue(slowWarnLogged, "调用耗时超过 100ms 阈值时必须输出结构化慢调用 WARN 告警");
        } finally {
            slowLogger.detachAppender(slowAppender);
        }

        // 2. 全量容灾体验 HTTP 路由 1:1 可达性验证
        ResponseEntity<String> writeHttp = restTemplate.postForEntity("/sample/cloud/feign/tolerance/write-timeout", null, String.class);
        assertEquals(HttpStatus.OK, writeHttp.getStatusCode());
        assertTrue(writeHttp.getBody() != null && writeHttp.getBody().contains("POST-Write-Timeout-Defense"));

        ResponseEntity<String> getTimeoutHttp = restTemplate.getForEntity("/sample/cloud/feign/tolerance/get-timeout", String.class);
        assertEquals(HttpStatus.OK, getTimeoutHttp.getStatusCode());
        assertTrue(getTimeoutHttp.getBody() != null && getTimeoutHttp.getBody().contains("GET-Read-Timeout-No-Retry"));

        ResponseEntity<String> connectHttp = restTemplate.getForEntity("/sample/cloud/feign/tolerance/connect-timeout", String.class);
        assertEquals(HttpStatus.OK, connectHttp.getStatusCode());
        assertTrue(connectHttp.getBody() != null && connectHttp.getBody().contains("Connect-Timeout-FullJitter-Retry"));

        ResponseEntity<String> customRetryHttp = restTemplate.getForEntity("/sample/cloud/feign/tolerance/custom-retry", String.class);
        assertEquals(HttpStatus.OK, customRetryHttp.getStatusCode());
        assertTrue(customRetryHttp.getBody() != null && customRetryHttp.getBody().contains("Per-Client-Custom-Retry-Override"));

        ResponseEntity<String> isolatedHttp = restTemplate.getForEntity("/sample/cloud/feign/tolerance/isolated-timeout", String.class);
        assertEquals(HttpStatus.OK, isolatedHttp.getStatusCode());
        assertTrue(isolatedHttp.getBody() != null && isolatedHttp.getBody().contains("Per-Client-Timeout-Isolation"));

        ResponseEntity<String> bizErrorHttp = restTemplate.getForEntity("/sample/cloud/feign/tolerance/business-error", String.class);
        assertEquals(HttpStatus.OK, bizErrorHttp.getStatusCode());
        assertTrue(bizErrorHttp.getBody() != null && bizErrorHttp.getBody().contains("Causal-Business-Error-Decoding"));

        ResponseEntity<String> http500 = restTemplate.getForEntity("/sample/cloud/feign/tolerance/http-500", String.class);
        assertEquals(HttpStatus.OK, http500.getStatusCode());
        assertTrue(http500.getBody() != null && http500.getBody().contains("HTTP-500-Causal-Translation"));

        ResponseEntity<String> payloadHttp = restTemplate.getForEntity("/sample/cloud/feign/tolerance/payload-overflow", String.class);
        assertEquals(HttpStatus.OK, payloadHttp.getStatusCode());
        assertTrue(payloadHttp.getBody() != null && payloadHttp.getBody().contains("Payload-Guard-10MB-Overflow"));

        ResponseEntity<String> slowCallHttp = restTemplate.getForEntity("/sample/cloud/feign/tolerance/slow-call", String.class);
        assertEquals(HttpStatus.OK, slowCallHttp.getStatusCode());
        assertTrue(slowCallHttp.getBody() != null && slowCallHttp.getBody().contains("Slow-Call-Threshold-Detection"));

        ResponseEntity<String> headersHttp = restTemplate.getForEntity("/sample/cloud/feign/tolerance/headers", String.class);
        assertEquals(HttpStatus.OK, headersHttp.getStatusCode());
        assertTrue(headersHttp.getBody() != null && headersHttp.getBody().contains("same-token"));
    }
}
