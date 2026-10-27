package io.github.atengk.cloud.nacos.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SafeConfigRefreshEvent} 强类型事件单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class SafeConfigRefreshEventTest {

    @Test
    @DisplayName("事件属性与便捷查询契约：前缀匹配、包含断言与不可变保护")
    void testEventPropertiesAndPrefixMatching() {
        ConfigChangeItem item1 = new ConfigChangeItem("sample.datasource.url", "jdbc:mysql://old", "jdbc:mysql://new", ConfigChangeType.MODIFIED);
        ConfigChangeItem item2 = new ConfigChangeItem("sample.datasource.pool-size", 10, 20, ConfigChangeType.MODIFIED);
        ConfigChangeItem item3 = new ConfigChangeItem("sample.feature.enabled", null, true, ConfigChangeType.ADDED);
        ConfigChangeItem item4 = new ConfigChangeItem("sample.legacy.key", "old", null, ConfigChangeType.DELETED);

        Map<String, ConfigChangeItem> details = Map.of(
                item1.getKey(), item1,
                item2.getKey(), item2,
                item3.getKey(), item3,
                item4.getKey(), item4
        );
        Set<String> keys = details.keySet();

        SafeConfigRefreshEvent event = new SafeConfigRefreshEvent(
                this, "order-service.yml", "DEFAULT_GROUP", keys, details, "trace-abc-123");

        assertThat(event.getDataId()).isEqualTo("order-service.yml");
        assertThat(event.getGroup()).isEqualTo("DEFAULT_GROUP");
        assertThat(event.getTraceId()).isEqualTo("trace-abc-123");
        assertThat(event.getChangedKeys()).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(event.getChangeDetails()).isEqualTo(details);

        // 单键查询
        assertThat(event.containsKey("sample.datasource.url")).isTrue();
        assertThat(event.containsKey("non.existent.key")).isFalse();
        assertThat(event.getChange("sample.datasource.url")).isEqualTo(item1);
        assertThat(event.getChange("non.existent")).isNull();

        // 前缀匹配
        assertThat(event.containsPrefix("sample.datasource")).isTrue();
        assertThat(event.containsPrefix("sample.feature")).isTrue();
        assertThat(event.containsPrefix("sample.legacy")).isTrue();
        assertThat(event.containsPrefix("sample.other")).isFalse();
        assertThat(event.containsPrefix("")).isTrue();
        assertThat(event.containsPrefix(null)).isTrue();

        // 验证不可变集合保护
        assertThat(event.getChangedKeys()).isUnmodifiable();
        assertThat(event.getChangeDetails()).isUnmodifiable();
    }

    @Test
    @DisplayName("空属性边界测试：null 集合安全兜底为空集合")
    void testNullSafeCollections() {
        SafeConfigRefreshEvent event = new SafeConfigRefreshEvent(this, null, null, null, null, null);

        assertThat(event.getDataId()).isNull();
        assertThat(event.getGroup()).isNull();
        assertThat(event.getTraceId()).isEmpty();
        assertThat(event.getChangedKeys()).isEmpty();
        assertThat(event.getChangeDetails()).isEmpty();
        assertThat(event.containsPrefix("any")).isFalse();
        assertThat(event.containsKey("any")).isFalse();
    }

    @Test
    @DisplayName("ConfigChangeItem 值对象等价性与字符串表示")
    void testConfigChangeItemEquivalence() {
        ConfigChangeItem item1 = new ConfigChangeItem("key1", "old", "new", ConfigChangeType.MODIFIED);
        ConfigChangeItem item2 = new ConfigChangeItem("key1", "old", "new", ConfigChangeType.MODIFIED);
        ConfigChangeItem item3 = new ConfigChangeItem("key1", "old", "different", ConfigChangeType.MODIFIED);

        assertThat(item1).isEqualTo(item2);
        assertThat(item1.hashCode()).isEqualTo(item2.hashCode());
        assertThat(item1).isNotEqualTo(item3);
        assertThat(item1.toString()).contains("key1", "old", "new", "MODIFIED");
    }

    @Test
    @DisplayName("脱敏屏障：敏感属性在 toString 与 getMaskedChangeDetails 中掩码，内存真明文保持不变")
    void testSensitiveDesensitizationAndInMemoryPlaintextInvariant() {
        ConfigChangeItem pwdItem = new ConfigChangeItem("spring.datasource.password", "OldSecret@123", "NewSecret@456", ConfigChangeType.MODIFIED);
        ConfigChangeItem portItem = new ConfigChangeItem("server.port", "8080", "8081", ConfigChangeType.MODIFIED);

        // 1. 验证内存真明文不变量
        assertThat(pwdItem.isSensitive()).isTrue();
        assertThat(pwdItem.getOldValue()).isEqualTo("OldSecret@123");
        assertThat(pwdItem.getNewValue()).isEqualTo("NewSecret@456");

        // 2. 验证脱敏获取方法返回星号掩码
        assertThat(pwdItem.getMaskedOldValue()).isEqualTo("******");
        assertThat(pwdItem.getMaskedNewValue()).isEqualTo("******");

        // 3. 验证 ConfigChangeItem.toString() 自动脱敏
        assertThat(pwdItem.toString()).doesNotContain("OldSecret@123");
        assertThat(pwdItem.toString()).doesNotContain("NewSecret@456");
        assertThat(pwdItem.toString()).contains("******");

        // 4. 验证非敏感项在 toString 中维持正常明文
        assertThat(portItem.isSensitive()).isFalse();
        assertThat(portItem.toString()).contains("8080", "8081");

        // 5. 验证 SafeConfigRefreshEvent.toString() 与 getMaskedChangeDetails()
        Map<String, ConfigChangeItem> details = Map.of(
                pwdItem.getKey(), pwdItem,
                portItem.getKey(), portItem
        );
        SafeConfigRefreshEvent event = new SafeConfigRefreshEvent(
                this, "sample.yml", "DEFAULT_GROUP", details.keySet(), details, "trace-test-mask");

        String eventStr = event.toString();
        assertThat(eventStr).doesNotContain("OldSecret@123");
        assertThat(eventStr).doesNotContain("NewSecret@456");
        assertThat(eventStr).contains("******");

        Map<String, ConfigChangeItem> maskedDetails = event.getMaskedChangeDetails();
        assertThat(maskedDetails.get("spring.datasource.password").getOldValue()).isEqualTo("******");
        assertThat(maskedDetails.get("spring.datasource.password").getNewValue()).isEqualTo("******");
        assertThat(maskedDetails.get("server.port").getOldValue()).isEqualTo("8080");
        assertThat(maskedDetails.get("server.port").getNewValue()).isEqualTo("8081");
    }
}
