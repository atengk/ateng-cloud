package io.github.atengk.cloud.sample.feign;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.RequestInterceptor;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import io.github.atengk.cloud.sample.feign.client.ContextFeignClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 出站全链路四维上下文透传、零信任 Same-Token 与日志脱敏端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(
        classes = SampleFeignApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "ateng.cloud.feign.logger-level=HEADERS",
                "logging.level.io.github.atengk.cloud.sample.feign.client.ContextFeignClient=DEBUG"
        }
)
@Import(ContextPropagationFeignClientTest.TestContextConfig.class)
@DisplayName("Feign 出站四维上下文透传与零信任 Same-Token 端到端集成测试")
class ContextPropagationFeignClientTest {

    private static final String TEST_SAME_TOKEN = "satoken-secret-token-abcdef123456";

    private static final int ALLOCATED_PORT = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> ALLOCATED_PORT);
        registry.add("test.server.port", () -> ALLOCATED_PORT);
    }

    @TestConfiguration
    static class TestContextConfig {
        @Bean
        public SameTokenProvider testSameTokenProvider() {
            return () -> TEST_SAME_TOKEN;
        }

        @Bean
        public RequestInterceptor subsequentObservingInterceptor() {
            class ObservingInterceptor implements RequestInterceptor, Ordered {
                @Override
                public void apply(feign.RequestTemplate template) {
                    var traceHeaders = template.headers().get(FeignHeaderConstants.TRACE_ID);
                    if (traceHeaders != null && !traceHeaders.isEmpty()) {
                        template.header("x-subsequent-observed-trace-id", traceHeaders.iterator().next());
                    }
                }

                @Override
                public int getOrder() {
                    return 0; // 优先级低于 HIGHEST_PRECEDENCE，验证执行时序后置
                }
            }
            return new ObservingInterceptor();
        }
    }

    @Autowired
    private ContextFeignClient contextFeignClient;

    @AfterEach
    void tearDown() {
        // 铁律：测试结束无条件清理线程上下文
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("测试全维度上下文 (四维上下文 + 流量染色 + Same-Token) 真实透传")
    void testPropagateFullContext() {
        MDC.put("traceId", "trace-e2e-8888");
        MDC.put("X-Traffic-Tag", "canary-v1");
        UserContextHolder.setUserId("user-cloud-100");
        TenantContextHolder.setTenantId("tenant-cloud-200");
        TenantContextHolder.setIgnoreTenant(true);

        Map<String, String> receivedHeaders = contextFeignClient.echoHeaders();

        assertThat(receivedHeaders).isNotNull();
        // 标头在 HTTP 规范下不区分大小写，Mock 控制器统一以小写 key 返回
        assertThat(receivedHeaders.get("x-trace-id")).isEqualTo("trace-e2e-8888");
        assertThat(receivedHeaders.get("x-user-id")).isEqualTo("user-cloud-100");
        assertThat(receivedHeaders.get("x-tenant-id")).isEqualTo("tenant-cloud-200");
        assertThat(receivedHeaders.get("x-ignore-tenant")).isEqualTo("true");
        assertThat(receivedHeaders.get("x-traffic-tag")).isEqualTo("canary-v1");
        assertThat(receivedHeaders.get("same-token")).isEqualTo(TEST_SAME_TOKEN);
    }

    @Test
    @DisplayName("测试空上下文透传保底 (绝不向 Header 注入 null 脏字面量)")
    void testPropagateEmptyContextDefensive() {
        // 上下文均未赋值
        Map<String, String> receivedHeaders = contextFeignClient.echoHeaders();

        assertThat(receivedHeaders).isNotNull();
        assertThat(receivedHeaders.get("x-trace-id")).isNull();
        assertThat(receivedHeaders.get("x-user-id")).isNull();
        assertThat(receivedHeaders.get("x-tenant-id")).isNull();
        assertThat(receivedHeaders.get("x-ignore-tenant")).isNull();
        assertThat(receivedHeaders.get("x-traffic-tag")).isNull();

        // 强断言：所有接收到的 Header 均不包含 "null" 字符串字面量
        for (Map.Entry<String, String> entry : receivedHeaders.entrySet()) {
            assertThat(entry.getValue()).isNotEqualToIgnoringCase("null");
        }
    }

    @Test
    @DisplayName("测试 ignoreTenant 为 false 时不传递标头")
    void testIgnoreTenantFalseNotPropagated() {
        TenantContextHolder.setIgnoreTenant(false);

        Map<String, String> receivedHeaders = contextFeignClient.echoHeaders();

        assertThat(receivedHeaders).isNotNull();
        assertThat(receivedHeaders.get("x-ignore-tenant")).isNull();
    }

    @Test
    @DisplayName("测试 Feign 日志门面机密标头星号脱敏 (不泄露明文)")
    void testSensitiveHeaderMaskingInLogs() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger clientLogger = context.getLogger(ContextFeignClient.class);
        clientLogger.setLevel(Level.DEBUG);

        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        clientLogger.addAppender(listAppender);

        try {
            // 发起请求，触发 HEADERS 日志打印
            contextFeignClient.echoHeaders();

            // 检查捕获的日志记录
            boolean foundMaskedHeader = false;
            for (ILoggingEvent event : listAppender.list) {
                String msg = event.getFormattedMessage();
                // 严密断言：日志中绝对严禁包含原始未脱敏明文
                assertThat(msg).doesNotContain("secret-token-abcdef");

                if (msg.contains("Same-Token: ") || msg.contains("same-token: ")) {
                    assertThat(msg).contains("******");
                    foundMaskedHeader = true;
                }
            }

            assertThat(foundMaskedHeader)
                    .as("Feign 请求日志必须打印脱敏后的 Same-Token 标头")
                    .isTrue();
        } finally {
            clientLogger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Test
    @DisplayName("测试后续低优先级拦截器可确定性读取第一顺位注入的上下文标头")
    void testSubsequentInterceptorObservesContextHeaders() {
        MDC.put("traceId", "trace-order-check-9999");
        UserContextHolder.setUserId("user-order-123");

        Map<String, String> receivedHeaders = contextFeignClient.echoHeaders();

        assertThat(receivedHeaders).isNotNull();
        assertThat(receivedHeaders.get("x-trace-id")).isEqualTo("trace-order-check-9999");
        assertThat(receivedHeaders.get("x-subsequent-observed-trace-id")).isEqualTo("trace-order-check-9999");
    }
}
