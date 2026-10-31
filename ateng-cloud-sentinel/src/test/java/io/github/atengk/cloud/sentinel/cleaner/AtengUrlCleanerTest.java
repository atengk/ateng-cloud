package io.github.atengk.cloud.sentinel.cleaner;

import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengUrlCleaner} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengUrlCleanerTest {

    private AtengSentinelProperties properties;
    private AtengUrlCleaner cleaner;

    @BeforeEach
    void setUp() {
        this.properties = new AtengSentinelProperties();
        this.cleaner = new AtengUrlCleaner(properties);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("排除白名单：默认包含的 /actuator/**、Swagger、文档与图标路径返回空字符串以跳过资源创建")
    void testDefaultExcludePatterns() {
        assertThat(cleaner.clean("/actuator/health")).isEmpty();
        assertThat(cleaner.clean("/actuator/metrics/jvm.memory.used")).isEmpty();
        assertThat(cleaner.clean("/v3/api-docs/swagger-config")).isEmpty();
        assertThat(cleaner.clean("/swagger-ui/index.html")).isEmpty();
        assertThat(cleaner.clean("/doc.html")).isEmpty();
        assertThat(cleaner.clean("/favicon.ico")).isEmpty();
        assertThat(cleaner.clean("/webjars/js/bundle.js")).isEmpty();
    }

    @Test
    @DisplayName("自定义排除白名单：自定义追加 Ant 路径模式并返回空字符串")
    void testCustomExcludePatterns() {
        properties.getUrlClean().setExcludePatterns(List.of("/static/**", "/healthz"));
        AtengUrlCleaner customCleaner = new AtengUrlCleaner(properties);

        assertThat(customCleaner.clean("/static/images/logo.png")).isEmpty();
        assertThat(customCleaner.clean("/healthz")).isEmpty();
        assertThat(customCleaner.clean("/api/orders/1001")).isEqualTo("/api/orders/{id}");
    }

    @Test
    @DisplayName("Spring MVC 模板优先：请求属性存在 BEST_MATCHING_PATTERN 时直接提取模板")
    void testSpringMvcBestMatchingPatternPriority() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/1001");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders/{id}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        String cleaned = cleaner.clean("/api/v1/orders/1001");
        assertThat(cleaned).isEqualTo("/api/v1/orders/{id}");
    }

    @Test
    @DisplayName("数值 ID 正则收敛：未挂载模板时，自动将单段或多段数值 ID 替换为 /{id}")
    void testNumericIdRegexReplacement() {
        assertThat(cleaner.clean("/api/users/12345")).isEqualTo("/api/users/{id}");
        assertThat(cleaner.clean("/api/users/12345/orders/67890")).isEqualTo("/api/users/{id}/orders/{id}");
        assertThat(cleaner.clean("/12345")).isEqualTo("/{id}");
    }

    @Test
    @DisplayName("UUID 正则收敛：未挂载模板时，自动将标准 UUID 替换为 /{uuid}")
    void testUuidRegexReplacement() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertThat(cleaner.clean("/api/files/" + uuid)).isEqualTo("/api/files/{uuid}");
        assertThat(cleaner.clean("/api/users/" + uuid + "/avatar")).isEqualTo("/api/users/{uuid}/avatar");
    }

    @Test
    @DisplayName("动态占位符归一化：自定义配置占位符（带或不带括号）均自动规范化为 /{token}")
    void testCustomTokenNormalization() {
        properties.getUrlClean().setNumberReplacement("pk");
        properties.getUrlClean().setUuidReplacement("{custom_uuid}");
        AtengUrlCleaner customCleaner = new AtengUrlCleaner(properties);

        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertThat(customCleaner.clean("/api/users/9988")).isEqualTo("/api/users/{pk}");
        assertThat(customCleaner.clean("/api/files/" + uuid)).isEqualTo("/api/files/{custom_uuid}");
    }

    @Test
    @DisplayName("混合与已参数化路径：若包含占位符或为纯静态路径，保持原样")
    void testAlreadyTemplatedOrStaticPath() {
        assertThat(cleaner.clean("/api/users/{userId}")).isEqualTo("/api/users/{userId}");
        assertThat(cleaner.clean("/api/v1/health")).isEqualTo("/api/v1/health");
        assertThat(cleaner.clean("")).isEqualTo("");
        assertThat(cleaner.clean(null)).isNull();
    }
}
