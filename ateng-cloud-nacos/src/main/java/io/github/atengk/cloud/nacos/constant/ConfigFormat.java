package io.github.atengk.cloud.nacos.constant;

/**
 * Nacos 动态配置格式枚举与 Data ID 后缀推断规范
 *
 * <p>支持标准 YAML、JSON、Properties 与纯文本格式的枚举定义及 Data ID 自动推断。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public enum ConfigFormat {

    /**
     * YAML 格式 (.yaml, .yml)
     */
    YAML("yaml"),

    /**
     * JSON 格式 (.json)
     */
    JSON("json"),

    /**
     * Properties 属性格式 (.properties)
     */
    PROPERTIES("properties"),

    /**
     * 纯文本格式 (.txt, .text 等)
     */
    TEXT("text");

    private final String value;

    ConfigFormat(String value) {
        this.value = value;
    }

    /**
     * 获取 Nacos 配置类型字面量
     *
     * @return 类型字面量（如 yaml, json, properties, text）
     */
    public String getValue() {
        return value;
    }

    /**
     * 根据 Data ID 扩展名自动推断配置格式
     *
     * @param dataId 配置集唯一标识
     * @return 匹配的配置格式，若无法推断则保底返回 {@link #TEXT}
     */
    public static ConfigFormat fromDataId(String dataId) {
        if (dataId == null || dataId.isBlank()) {
            return TEXT;
        }
        String lower = dataId.trim().toLowerCase();
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return YAML;
        }
        if (lower.endsWith(".json")) {
            return JSON;
        }
        if (lower.endsWith(".properties")) {
            return PROPERTIES;
        }
        return TEXT;
    }

    /**
     * 根据类型字符串安全解析配置格式
     *
     * @param type 类型字符串（如 yaml, json, properties, text）
     * @return 对应的配置格式，若无法识别则返回 {@link #TEXT}
     */
    public static ConfigFormat fromType(String type) {
        if (type == null || type.isBlank()) {
            return TEXT;
        }
        String lower = type.trim().toLowerCase();
        for (ConfigFormat format : values()) {
            if (format.value.equals(lower)) {
                return format;
            }
        }
        if ("yml".equals(lower)) {
            return YAML;
        }
        return TEXT;
    }
}
