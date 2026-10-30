package io.github.atengk.cloud.feign.retry;

import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import feign.Target;
import io.github.atengk.cloud.feign.properties.AtengFeignClientProperties;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import io.github.atengk.cloud.feign.properties.AtengFeignRetryProperties;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AtengFeignRetryer 单元测试
 * 对抗性验证写请求禁重试、读超时禁重试、建连超时弹性重试与单 Client 配置隔离
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignRetryerUnitTest {

    private Request createMockRequest(Request.HttpMethod method, String clientName) {
        RequestTemplate template = new RequestTemplate();
        if (clientName != null) {
            template.feignTarget(new Target.HardCodedTarget<>(Object.class, clientName, "http://" + clientName));
        }
        return Request.create(
                method,
                "http://" + (clientName != null ? clientName : "mock-service") + "/test",
                Collections.emptyMap(),
                new byte[0],
                StandardCharsets.UTF_8,
                template
        );
    }

    private RetryableException createRetryableException(Request.HttpMethod method, Throwable cause, String clientName) {
        Request request = createMockRequest(method, clientName);
        return new RetryableException(-1, "Request failed", method, cause, (Long) null, request);
    }

    @Test
    @DisplayName("1. 验证 POST 写请求发生读超时（SocketTimeoutException）绝对严禁重试")
    void testPostReadTimeoutStrictlyNoRetry() {
        AtengFeignRetryer retryer = new AtengFeignRetryer(10, 50, 2);
        RetryableException ex = createRetryableException(
                Request.HttpMethod.POST,
                new SocketTimeoutException("Read timed out"),
                "order-service"
        );

        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(ex));
        assertEquals(1, retryer.getAttempt(), "写请求严禁任何重试，尝试次数必须停留在 1");
    }

    @Test
    @DisplayName("2. 验证 PUT/PATCH/DELETE 等写请求即便发生建连超时也严禁重试")
    void testWriteRequestsStrictlyNoRetry() {
        AtengFeignRetryer retryer = new AtengFeignRetryer(10, 50, 2);

        for (Request.HttpMethod method : new Request.HttpMethod[]{
                Request.HttpMethod.PUT,
                Request.HttpMethod.PATCH,
                Request.HttpMethod.DELETE
        }) {
            RetryableException ex = createRetryableException(
                    method,
                    new ConnectTimeoutException("Connect to host failed"),
                    "order-service"
            );
            assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(ex));
            assertEquals(1, retryer.getAttempt());
        }
    }

    @Test
    @DisplayName("3. 验证 GET 读请求发生读超时（SocketTimeoutException）同样严禁重试防雪崩")
    void testGetReadTimeoutStrictlyNoRetry() {
        AtengFeignRetryer retryer = new AtengFeignRetryer(10, 50, 2);
        RetryableException ex = createRetryableException(
                Request.HttpMethod.GET,
                new SocketTimeoutException("Read timed out"),
                "user-service"
        );

        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(ex));
        assertEquals(1, retryer.getAttempt(), "读超时坚决不重试以防对端满载演变为重试风暴");
    }

    @Test
    @DisplayName("4. 验证 GET 读请求在网络建连超时（ConnectTimeoutException）时允许弹性重试 1 次")
    void testGetConnectTimeoutElasticRetryOnce() {
        AtengFeignRetryer retryer = new AtengFeignRetryer(10, 50, 2);
        RetryableException ex = createRetryableException(
                Request.HttpMethod.GET,
                new ConnectTimeoutException("Connect to host failed: connect timed out"),
                "user-service"
        );

        // 第一次失败：允许重试，不抛出异常，attempt 累加至 2
        assertDoesNotThrow(() -> retryer.continueOrPropagate(ex));
        assertEquals(2, retryer.getAttempt());

        // 第二次失败：达到最大请求次数 2，抛出异常阻断
        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(ex));
        assertEquals(3, retryer.getAttempt());
    }

    @Test
    @DisplayName("5. 验证 GET 读请求在网络端口不可达（ConnectException）时允许弹性重试 1 次")
    void testGetConnectExceptionElasticRetryOnce() {
        AtengFeignRetryer retryer = new AtengFeignRetryer(10, 50, 2);
        RetryableException ex = createRetryableException(
                Request.HttpMethod.GET,
                new ConnectException("Connection refused"),
                "user-service"
        );

        // 第一次失败：允许重试
        assertDoesNotThrow(() -> retryer.continueOrPropagate(ex));
        assertEquals(2, retryer.getAttempt());

        // 第二次失败：阻断抛出
        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(ex));
    }

    @Test
    @DisplayName("6. 验证单 Client 独立配置覆盖全局重试参数（maxAttempts = 3）")
    void testPerClientMaxAttemptsOverride() {
        AtengFeignProperties properties = new AtengFeignProperties();
        properties.getRetry().setMaxAttempts(2); // 全局默认 2

        AtengFeignClientProperties specialClientProps = new AtengFeignClientProperties();
        AtengFeignRetryProperties retryOverride = new AtengFeignRetryProperties();
        retryOverride.setMaxAttempts(3); // 特殊 Client 覆盖为 3
        retryOverride.setPeriod(Duration.ofMillis(10));
        retryOverride.setMaxPeriod(Duration.ofMillis(50));
        specialClientProps.setRetry(retryOverride);

        properties.getClients().put("special-service", specialClientProps);

        AtengFeignRetryer retryer = new AtengFeignRetryer(properties);
        RetryableException ex = createRetryableException(
                Request.HttpMethod.GET,
                new ConnectTimeoutException("Connect timed out"),
                "special-service"
        );

        // 初始失败 (attempt 1 -> 2)
        assertDoesNotThrow(() -> retryer.continueOrPropagate(ex));
        assertEquals(2, retryer.getAttempt());

        // 第一次重试后再次失败 (attempt 2 -> 3)
        assertDoesNotThrow(() -> retryer.continueOrPropagate(ex));
        assertEquals(3, retryer.getAttempt());

        // 第二次重试后再次失败 (attempt 3 >= 3 -> 抛出异常)
        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(ex));
    }

    @Test
    @DisplayName("7. 验证单 Client 独立关闭重试（enabled = false）时直接阻断")
    void testPerClientRetryDisabled() {
        AtengFeignProperties properties = new AtengFeignProperties();
        AtengFeignClientProperties disabledClientProps = new AtengFeignClientProperties();
        AtengFeignRetryProperties retryOverride = new AtengFeignRetryProperties();
        retryOverride.setEnabled(false);
        disabledClientProps.setRetry(retryOverride);

        properties.getClients().put("no-retry-service", disabledClientProps);

        AtengFeignRetryer retryer = new AtengFeignRetryer(properties);
        RetryableException ex = createRetryableException(
                Request.HttpMethod.GET,
                new ConnectTimeoutException("Connect timed out"),
                "no-retry-service"
        );

        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(ex));
        assertEquals(1, retryer.getAttempt());
    }

    @Test
    @DisplayName("8. 验证 clone 机制重置 attempt 状态为 1 且参数独立副本")
    void testCloneIndependence() {
        AtengFeignRetryer retryer = new AtengFeignRetryer(10, 50, 2);
        RetryableException ex = createRetryableException(
                Request.HttpMethod.GET,
                new ConnectTimeoutException("Connect timed out"),
                "user-service"
        );

        assertDoesNotThrow(() -> retryer.continueOrPropagate(ex));
        assertEquals(2, retryer.getAttempt());

        AtengFeignRetryer cloned = (AtengFeignRetryer) retryer.clone();
        assertEquals(1, cloned.getAttempt(), "克隆出来的重试器必须是初始 attempt=1 的全新调用副本");
        assertEquals(retryer.getMaxAttempts(), cloned.getMaxAttempts());
    }

    @org.springframework.cloud.openfeign.FeignClient(name = "shared-service", contextId = "contextIdClient")
    interface ContextIdTestClient {
    }

    @Test
    @DisplayName("9. 验证基于 @FeignClient(contextId = ...) 优先匹配单 Client 重试配置")
    void testContextIdClientRetryOverride() {
        AtengFeignProperties properties = new AtengFeignProperties();
        AtengFeignClientProperties clientProps = new AtengFeignClientProperties();
        AtengFeignRetryProperties retryOverride = new AtengFeignRetryProperties();
        retryOverride.setMaxAttempts(4);
        retryOverride.setPeriod(Duration.ofMillis(5));
        retryOverride.setMaxPeriod(Duration.ofMillis(20));
        clientProps.setRetry(retryOverride);

        properties.getClients().put("contextIdClient", clientProps);

        RequestTemplate template = new RequestTemplate();
        template.feignTarget(new Target.HardCodedTarget<>(ContextIdTestClient.class, "shared-service", "http://shared-service"));
        Request request = Request.create(Request.HttpMethod.GET, "/test", Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, template);
        RetryableException ex = new RetryableException(-1, "Connect failed", Request.HttpMethod.GET, new ConnectTimeoutException("Connect timed out"), (Long) null, request);

        AtengFeignRetryer retryer = new AtengFeignRetryer(properties);
        assertDoesNotThrow(() -> retryer.continueOrPropagate(ex)); // 1 -> 2
        assertEquals(2, retryer.getAttempt());
        assertDoesNotThrow(() -> retryer.continueOrPropagate(ex)); // 2 -> 3
        assertEquals(3, retryer.getAttempt());
        assertDoesNotThrow(() -> retryer.continueOrPropagate(ex)); // 3 -> 4
        assertEquals(4, retryer.getAttempt());
        assertThrows(RetryableException.class, () -> retryer.continueOrPropagate(ex)); // 4 >= 4 throws, attempt becomes 5
        assertEquals(5, retryer.getAttempt());
    }

    @Test
    @DisplayName("10. 验证 calculateSleepInterval 采用带保底时间窗的 Full Jitter 随机抖动算法")
    void testFullJitterBackoffCalculation() {
        AtengFeignRetryer retryer = new AtengFeignRetryer(100, 1000, 3);

        long minObserved = Long.MAX_VALUE;
        long maxObserved = Long.MIN_VALUE;
        java.util.Set<Long> uniqueIntervals = new java.util.HashSet<>();

        // attempt 2: upper = min(1000, 100 * 1.5^1) = 150, floor = min(100/2, 20) = 20
        for (int i = 0; i < 100; i++) {
            long interval = retryer.calculateSleepInterval(100, 1000, 2);
            assertTrue(interval >= 20, "休眠间隔必须大于等于保底下限 20ms，当前: " + interval);
            assertTrue(interval <= 150, "休眠间隔必须小于等于上限 150ms，当前: " + interval);
            minObserved = Math.min(minObserved, interval);
            maxObserved = Math.max(maxObserved, interval);
            uniqueIntervals.add(interval);
        }

        // 验证确实存在随机离散性（100 次抽样应至少产生 10 种以上不同延迟，而非固定值）
        assertTrue(uniqueIntervals.size() > 10, "Full Jitter 必须具备充分的离散度以打散惊群");
        assertTrue(minObserved < maxObserved, "最大观测值必须严格大于最小观测值");
    }
}
