package io.github.atengk.cloud.feign.logger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import io.github.atengk.cloud.feign.properties.AtengFeignClientProperties;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FeignClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 安全脱敏日志门面单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("Feign 安全脱敏日志门面单元测试")
class AtengFeignLoggerTest {

    private final AtengFeignLogger logger = new AtengFeignLogger();

    @Test
    @DisplayName("测试敏感标头识别规则 (不区分大小写)")
    void testIsSensitiveHeader() {
        assertThat(logger.isSensitiveHeader("Same-Token")).isTrue();
        assertThat(logger.isSensitiveHeader("same-token")).isTrue();
        assertThat(logger.isSensitiveHeader("SAME-TOKEN")).isTrue();
        assertThat(logger.isSensitiveHeader("Authorization")).isTrue();
        assertThat(logger.isSensitiveHeader("Proxy-Authorization")).isTrue();
        assertThat(logger.isSensitiveHeader("Cookie")).isTrue();
        assertThat(logger.isSensitiveHeader("Set-Cookie")).isTrue();
        assertThat(logger.isSensitiveHeader("token")).isTrue();
        assertThat(logger.isSensitiveHeader("satoken")).isTrue();
        assertThat(logger.isSensitiveHeader("X-Token")).isTrue();
        assertThat(logger.isSensitiveHeader("X-Auth-Token")).isTrue();

        // 非敏感标头
        assertThat(logger.isSensitiveHeader("Content-Type")).isFalse();
        assertThat(logger.isSensitiveHeader("X-Trace-Id")).isFalse();
        assertThat(logger.isSensitiveHeader("X-User-Id")).isFalse();
        assertThat(logger.isSensitiveHeader("X-Tenant-Id")).isFalse();
        assertThat(logger.isSensitiveHeader(null)).isFalse();
        assertThat(logger.isSensitiveHeader("")).isFalse();
    }

    @Test
    @DisplayName("测试敏感标头掩码脱敏效果")
    void testMaskHeader() {
        // 长敏感字符串
        String maskedToken = logger.maskHeader("Same-Token", "satoken-secret-token-abcdef123456");
        assertThat(maskedToken)
                .startsWith("sa")
                .endsWith("56")
                .contains("******")
                .doesNotContain("secret-token-abcdef");

        // 短敏感字符串 (<= 6)
        assertThat(logger.maskHeader("Authorization", "123456")).isEqualTo("******");
        assertThat(logger.maskHeader("Authorization", "123")).isEqualTo("******");

        // 空值或空白字符
        assertThat(logger.maskHeader("Cookie", null)).isEqualTo("******");
        assertThat(logger.maskHeader("Cookie", "   ")).isEqualTo("******");
    }

    @Test
    @DisplayName("测试键值对标头日志参数脱敏")
    void testSanitizeArgs() {
        Object[] args = new Object[]{"Same-Token", "my-super-secret-key-123456"};
        Object[] sanitized = logger.sanitizeArgs("%s: %s", args);

        assertThat(sanitized[0]).isEqualTo("Same-Token");
        assertThat(sanitized[1].toString())
                .startsWith("my")
                .endsWith("56")
                .contains("******")
                .doesNotContain("super-secret-key");

        // 非标头格式保持原样
        Object[] nonHeaderArgs = new Object[]{"POST", "http://example.com", "HTTP/1.1"};
        Object[] nonHeaderSanitized = logger.sanitizeArgs("---> %s %s %s", nonHeaderArgs);
        assertThat(nonHeaderSanitized).containsExactly("POST", "http://example.com", "HTTP/1.1");
    }

    @FeignClient(name = "shared-slow-service", contextId = "fastOrderClient")
    interface FastOrderClient {
    }

    @FeignClient(name = "shared-slow-service", contextId = "batchExportClient")
    interface BatchExportClient {
    }

    private Response createResponse(Class<?> targetType, String targetName, int status, Map<String, Collection<String>> headers) {
        RequestTemplate template = new RequestTemplate();
        if (targetType != null) {
            template.feignTarget(new Target.HardCodedTarget<>(targetType, targetName, "http://" + targetName));
        }
        Request request = Request.create(Request.HttpMethod.GET, "/api/slow", Collections.emptyMap(), null, StandardCharsets.UTF_8, template);
        return Response.builder()
                .status(status)
                .reason("OK")
                .request(request)
                .headers(headers != null ? headers : Collections.emptyMap())
                .body("{\"code\":200}", StandardCharsets.UTF_8)
                .build();
    }

    @Test
    @DisplayName("测试默认 2s 慢调用告警无视 INFO/WARN 级别主动触发且临界值严格生效")
    void testGlobalSlowCallAlertIgnoresDebugRequirement() throws Exception {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger("test.slow.global." + System.nanoTime());
        Level originalLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        try {
            AtengFeignProperties properties = new AtengFeignProperties();
            assertThat(properties.getSlowCallThreshold()).isEqualTo(Duration.ofSeconds(2));
            AtengFeignLogger feignLogger = new AtengFeignLogger(logbackLogger, properties);

            Response response = createResponse(null, "order-service", 200, Collections.emptyMap());

            // 1999ms 未达 2s 阈值，且处于 INFO 级别，不产生任何 WARN 或 DEBUG 日志
            Response fastResult = feignLogger.logAndRebufferResponse("OrderClient#get(Long)", feign.Logger.Level.BASIC, response, 1999L);
            assertThat(fastResult).isSameAs(response);
            assertThat(appender.list).isEmpty();

            // 2000ms 恰好达到默认 2s 阈值，即便处于 WARN 级别也要输出包含方法标识、状态码、耗时与阈值的 WARN 告警
            logbackLogger.setLevel(Level.WARN);
            Response slowResult = feignLogger.logAndRebufferResponse("OrderClient#get(Long)", feign.Logger.Level.BASIC, response, 2000L);
            assertThat(slowResult).isSameAs(response);
            assertThat(appender.list).hasSize(1);

            ILoggingEvent warnEvent = appender.list.get(0);
            assertThat(warnEvent.getLevel()).isEqualTo(Level.WARN);
            assertThat(warnEvent.getFormattedMessage())
                    .contains("OrderClient#get(Long)")
                    .contains("200")
                    .contains("2000ms");
        } finally {
            logbackLogger.detachAppender(appender);
            logbackLogger.setLevel(originalLevel);
        }
    }

    @Test
    @DisplayName("测试单客户端 contextId 优先与服务名回退覆盖 slow-call-threshold，支持 0/负值关闭")
    void testPerClientSlowCallThresholdAndDisable() throws Exception {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger("test.slow.client." + System.nanoTime());
        Level originalLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        try {
            AtengFeignProperties properties = new AtengFeignProperties();
            properties.setSlowCallThreshold(Duration.ofSeconds(5));

            AtengFeignClientProperties serviceProps = new AtengFeignClientProperties();
            serviceProps.setSlowCallThreshold(Duration.ofMillis(800));
            properties.getClients().put("shared-slow-service", serviceProps);

            AtengFeignClientProperties fastProps = new AtengFeignClientProperties();
            fastProps.setSlowCallThreshold(Duration.ofMillis(200));
            properties.getClients().put("fastOrderClient", fastProps);

            AtengFeignLogger feignLogger = new AtengFeignLogger(logbackLogger, properties);

            // 1. fastOrderClient 命中 contextId 覆盖阈值 200ms：250ms 触发 WARN
            Response fastClientResp = createResponse(FastOrderClient.class, "shared-slow-service", 200, Collections.emptyMap());
            feignLogger.logAndRebufferResponse("FastOrderClient#query()", feign.Logger.Level.BASIC, fastClientResp, 199L);
            assertThat(appender.list).isEmpty();
            feignLogger.logAndRebufferResponse("FastOrderClient#query()", feign.Logger.Level.BASIC, fastClientResp, 250L);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("fastOrderClient")
                    .contains("FastOrderClient#query()")
                    .contains("250ms")
                    .contains("200ms");

            // 2. batchExportClient 未配 contextId，回退服务名 800ms：500ms 不告警，850ms 告警
            appender.list.clear();
            Response batchClientResp = createResponse(BatchExportClient.class, "shared-slow-service", 503, Collections.emptyMap());
            feignLogger.logAndRebufferResponse("BatchExportClient#export()", feign.Logger.Level.BASIC, batchClientResp, 500L);
            assertThat(appender.list).isEmpty();
            feignLogger.logAndRebufferResponse("BatchExportClient#export()", feign.Logger.Level.BASIC, batchClientResp, 850L);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("503")
                    .contains("850ms")
                    .contains("800ms");

            // 3. 设单客户端为 0ms 与全局设为负数时，均彻底关闭告警
            appender.list.clear();
            fastProps.setSlowCallThreshold(Duration.ZERO);
            feignLogger.logAndRebufferResponse("FastOrderClient#query()", feign.Logger.Level.BASIC, fastClientResp, 9999L);
            assertThat(appender.list).isEmpty();

            properties.setSlowCallThreshold(Duration.ofSeconds(-1));
            serviceProps.setSlowCallThreshold(null);
            feignLogger.logAndRebufferResponse("BatchExportClient#export()", feign.Logger.Level.BASIC, batchClientResp, 9999L);
            assertThat(appender.list).isEmpty();
        } finally {
            logbackLogger.detachAppender(appender);
            logbackLogger.setLevel(originalLevel);
        }
    }

    @Test
    @DisplayName("测试开启 DEBUG 级别时慢调用 WARN 告警与安全脱敏通信日志协同输出，且 LoggerFactory 透传 clientType")
    void testSlowCallAlertAlongsideDebugMaskedLoggingAndFactory() throws Exception {
        AtengFeignProperties properties = new AtengFeignProperties();
        properties.setSlowCallThreshold(Duration.ofMillis(100));
        AtengFeignLoggerFactory factory = new AtengFeignLoggerFactory(properties);
        feign.Logger created = factory.create(FastOrderClient.class);
        assertThat(created).isInstanceOf(AtengFeignLogger.class);

        Logger classLogger = (Logger) LoggerFactory.getLogger(FastOrderClient.class);
        Level originalLevel = classLogger.getLevel();
        classLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        classLogger.addAppender(appender);

        try {
            // 构造不含 RequestTemplate 的响应，验证 factory 传入的 FastOrderClient 类型同样可工作，且敏感标头仍被掩码
            Request request = Request.create(Request.HttpMethod.GET, "/api/headers", Collections.emptyMap(), null, StandardCharsets.UTF_8);
            Response response = Response.builder()
                    .status(200)
                    .reason("OK")
                    .request(request)
                    .headers(Map.of("Same-Token", List.of("satoken-secret-token-abcdef123456")))
                    .body("{}", StandardCharsets.UTF_8)
                    .build();

            ((AtengFeignLogger) created).logAndRebufferResponse("FastOrderClient#ping()", feign.Logger.Level.HEADERS, response, 150L);

            assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("fastOrderClient")
                    && e.getFormattedMessage().contains("150ms"));
            assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.DEBUG
                    && e.getFormattedMessage().toLowerCase().contains("same-token:")
                    && e.getFormattedMessage().contains("sa******56")
                    && !e.getFormattedMessage().contains("secret-token-abcdef"));
        } finally {
            classLogger.detachAppender(appender);
            classLogger.setLevel(originalLevel);
        }
    }

    @Test
    @DisplayName("测试 Response 缺失 @FeignClient 注解类型元数据时，Factory 传入的 clientType contextId 仍优先于目标服务名")
    void testContextIdTakesPrecedenceOverTargetNameWhenResponseLacksAnnotatedType() throws Exception {
        AtengFeignProperties properties = new AtengFeignProperties();
        properties.setSlowCallThreshold(Duration.ofSeconds(5));

        AtengFeignClientProperties serviceProps = new AtengFeignClientProperties();
        serviceProps.setSlowCallThreshold(Duration.ofMillis(800));
        properties.getClients().put("shared-slow-service", serviceProps);

        AtengFeignClientProperties fastProps = new AtengFeignClientProperties();
        fastProps.setSlowCallThreshold(Duration.ofMillis(200));
        properties.getClients().put("fastOrderClient", fastProps);

        Logger classLogger = (Logger) LoggerFactory.getLogger("test.slow.precedence." + System.nanoTime());
        Level originalLevel = classLogger.getLevel();
        classLogger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        classLogger.addAppender(appender);

        try {
            AtengFeignLogger feignLogger = new AtengFeignLogger(classLogger, FastOrderClient.class, properties);
            Response responseWithoutAnnotatedType = createResponse(Object.class, "shared-slow-service", 200, Collections.emptyMap());

            feignLogger.logAndRebufferResponse("FastOrderClient#query()", feign.Logger.Level.BASIC, responseWithoutAnnotatedType, 250L);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("fastOrderClient")
                    .contains("250ms")
                    .contains("200ms");
        } finally {
            classLogger.detachAppender(appender);
            classLogger.setLevel(originalLevel);
        }
    }
}
