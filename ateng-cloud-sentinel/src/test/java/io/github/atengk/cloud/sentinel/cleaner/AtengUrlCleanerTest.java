package io.github.atengk.cloud.sentinel.cleaner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AtengUrlCleaner} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengUrlCleanerTest {

    private AtengUrlCleaner cleaner;

    @BeforeEach
    void setUp() {
        this.cleaner = new AtengUrlCleaner();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
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
    @DisplayName("混合与已参数化路径：若包含占位符或为纯静态路径，保持原样")
    void testAlreadyTemplatedOrStaticPath() {
        assertThat(cleaner.clean("/api/users/{userId}")).isEqualTo("/api/users/{userId}");
        assertThat(cleaner.clean("/api/v1/health")).isEqualTo("/api/v1/health");
        assertThat(cleaner.clean("")).isEqualTo("");
        assertThat(cleaner.clean(null)).isNull();
    }
}
