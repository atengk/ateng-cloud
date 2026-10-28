package io.github.atengk.cloud.sample.feign;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.RetryableException;
import io.github.atengk.cloud.feign.retry.AtengFeignRetryer;
import io.github.atengk.cloud.sample.feign.client.BaselineFeignClient;
import io.github.atengk.cloud.sample.feign.client.CustomRetryFeignClient;
import io.github.atengk.cloud.sample.feign.client.IsolatedTimeoutFeignClient;
import io.github.atengk.cloud.sample.feign.client.RetryFeignClient;
import io.github.atengk.cloud.sample.feign.client.UnreachableFeignClient;
import io.github.atengk.cloud.sample.feign.controller.RetryMockController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 防雪崩弹性重试纪律与单 Client 配置物理隔离端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class RetryFeignClientTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int serverPort = TestSocketUtils.findAvailableTcpPort();
        int unreachablePort = TestSocketUtils.findAvailableTcpPort();
        registry.add("server.port", () -> serverPort);
        registry.add("test.server.port", () -> serverPort);
        registry.add("test.unreachable.port", () -> unreachablePort);
    }

    @Autowired
    private RetryFeignClient retryFeignClient;

    @Autowired
    private IsolatedTimeoutFeignClient isolatedTimeoutFeignClient;

    @Autowired
    private UnreachableFeignClient unreachableFeignClient;

    @Autowired
    private CustomRetryFeignClient customRetryFeignClient;

    @Autowired
    private BaselineFeignClient baselineFeignClient;

    @Autowired
    private RetryMockController retryMockController;

    @BeforeEach
    void setUp() {
        retryMockController.reset();
    }

    @Test
    @DisplayName("1. 强断言：POST 读超时服务端接收调用次数严格为 1，绝对禁止重试防雪崩与资损")
    void testPostReadTimeoutStrictlyNoRetry() {
        RetryableException ex = assertThrows(RetryableException.class, () -> retryFeignClient.postTimeout());
        assertNotNull(ex, "读超时必须抛出 RetryableException 阻断");
        assertEquals(1, retryMockController.getPostCallCount(),
                "POST 发生读超时绝对严禁重试，服务端接收调用次数必须严格为 1");
    }

    @Test
    @DisplayName("2. 强断言：GET 不可达端口弹性重试 1 次（总共 2 次后失败并抛出 RetryableException）")
    void testGetUnreachableElasticRetryOnce() {
        Logger logger = (Logger) LoggerFactory.getLogger(AtengFeignRetryer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            long start = System.currentTimeMillis();
            RetryableException ex = assertThrows(RetryableException.class, () -> unreachableFeignClient.getUnreachable());
            long elapsed = System.currentTimeMillis() - start;

            assertNotNull(ex, "不可达端口建连失败必须抛出 RetryableException 阻断");
            assertTrue(elapsed >= 40, "弹性重试必须经过退避休眠，总耗时应大于等于退避间隔");

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(m -> m.contains("unreachable-client") && m.contains("正在进行第 1/1 次弹性重试")),
                    "必须记录第 1/1 次弹性重试日志");
            assertTrue(messages.stream().anyMatch(m -> m.contains("unreachable-client") && m.contains("已达最大重试上限 (2/2)")),
                    "必须记录已达最大重试上限 (2/2) 阻断日志");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("3. 强断言：Client 级超时物理隔离生效（独立短超时客户端超时阻断，默认长超时客户端正常成功）")
    void testClientTimeoutIsolation() {
        // isolated-timeout-client 配置 socket-timeout: 300ms，调用耗时 800ms 的接口必触发读超时
        RetryableException ex = assertThrows(RetryableException.class, () -> isolatedTimeoutFeignClient.slow());
        assertNotNull(ex);

        // 全局默认客户端 baseline-client 拥有默认 5000ms 读超时，常规接口调用不受影响正常成功
        String echo = baselineFeignClient.echo("timeout-isolation");
        assertEquals("Echo: timeout-isolation", echo, "未覆盖超时的客户端平滑继承全局基线，正常通信成功");
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

            List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(m -> m.contains("custom-retry-client") && m.contains("正在进行第 1/2 次弹性重试")),
                    "必须按覆盖配置执行第 1/2 次弹性重试");
            assertTrue(messages.stream().anyMatch(m -> m.contains("custom-retry-client") && m.contains("正在进行第 2/2 次弹性重试")),
                    "必须按覆盖配置执行第 2/2 次弹性重试");
            assertTrue(messages.stream().anyMatch(m -> m.contains("custom-retry-client") && m.contains("已达最大重试上限 (3/3)")),
                    "必须按覆盖配置记录已达最大重试上限 (3/3) 阻断日志");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
