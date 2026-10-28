package io.github.atengk.cloud.feign.logger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
