package io.github.atengk.cloud.nacos.mask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfigMasker} 配置属性敏感脱敏屏障单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class ConfigMaskerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "spring.datasource.password",
            "server.ssl.key-store-password",
            "ateng.cloud.nacos.secret-key",
            "sa-token.jwt-secret-key",
            "security.oauth2.client.client-secret",
            "app.auth.token",
            "wechat.pay.private-key",
            "alipay.privateKey",
            "redis.password",
            "my.credential",
            "passphrase",
            "db.pwd"
    })
    @DisplayName("敏感特征键命中判定为敏感")
    void testSensitiveKeyMatching(String sensitiveKey) {
        assertThat(ConfigMasker.isSensitive(sensitiveKey)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "server.port",
            "spring.application.name",
            "ateng.cloud.nacos.zone",
            "ateng.cloud.nacos.cluster",
            "spring.cloud.nacos.discovery.enabled",
            "logging.level.root",
            "management.endpoints.web.exposure.include"
    })
    @DisplayName("非敏感特征键判定为非敏感")
    void testNonSensitiveKeyMatching(String nonSensitiveKey) {
        assertThat(ConfigMasker.isSensitive(nonSensitiveKey)).isFalse();
    }

    @Test
    @DisplayName("敏感特征值实施星号脱敏掩码")
    void testMaskSensitiveValue() {
        String masked = ConfigMasker.mask("spring.datasource.password", "Root@123456");
        assertThat(masked).isEqualTo("******");
    }

    @Test
    @DisplayName("非敏感特征值保持原始明文")
    void testMaskNonSensitiveValue() {
        String val = ConfigMasker.mask("server.port", "8080");
        assertThat(val).isEqualTo("8080");
    }

    @Test
    @DisplayName("null 或空值脱敏安全防御")
    void testMaskNullOrEmpty() {
        assertThat(ConfigMasker.mask("spring.datasource.password", null)).isNull();
        assertThat(ConfigMasker.mask("spring.datasource.password", "")).isEmpty();
        assertThat(ConfigMasker.mask(null, "someValue")).isEqualTo("someValue");
        assertThat(ConfigMasker.isSensitive(null)).isFalse();
    }
}
