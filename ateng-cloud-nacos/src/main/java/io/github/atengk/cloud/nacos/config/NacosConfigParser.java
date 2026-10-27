package io.github.atengk.cloud.nacos.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.nacos.code.NacosResultCode;
import io.github.atengk.cloud.nacos.constant.ConfigFormat;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.Properties;

/**
 * Nacos 多格式强类型配置解析与语法校验引擎
 *
 * <p>核心能力：
 * 1. <b>多格式泛型解析</b>：自适应反序列化 YAML、JSON、Properties 与纯文本为目标 POJO；
 * 2. <b>语法 Fail-Fast 预检</b>：推送配置前对 YAML、JSON 与 Properties 语法树进行有效性校验；
 * 3. <b>对象双向序列化</b>：支持将 Java POJO 或 Map 结构按指定格式序列化为规范文本。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosConfigParser {

    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;

    /**
     * 默认构造函数，初始化具备 Java 8 日期时间模块与容错特性的 Jackson 序列化引擎
     */
    public NacosConfigParser() {
        this(createDefaultJsonMapper(), createDefaultYamlMapper());
    }

    /**
     * 具备自定义 ObjectMapper 与 YAMLMapper 的构造函数
     *
     * @param jsonMapper JSON 序列化器
     * @param yamlMapper YAML 序列化器
     */
    public NacosConfigParser(ObjectMapper jsonMapper, YAMLMapper yamlMapper) {
        Assert.notNull(jsonMapper, "jsonMapper must not be null");
        Assert.notNull(yamlMapper, "yamlMapper must not be null");
        this.jsonMapper = jsonMapper;
        this.yamlMapper = yamlMapper;
    }

    /**
     * 将配置文本反序列化为指定类型的 POJO 对象
     *
     * @param content    配置文本内容
     * @param format     配置格式（为 null 时保底使用 TEXT 格式）
     * @param targetType 目标 POJO 类型
     * @param <T>        目标类型泛型
     * @return 反序列化后的对象实例，内容为空时返回 null
     * @throws BusinessException 当反序列化解析失败时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T parse(String content, ConfigFormat format, Class<T> targetType) {
        Assert.notNull(targetType, "targetType must not be null");
        if (!StringUtils.hasText(content)) {
            return null;
        }
        if (String.class.equals(targetType)) {
            return (T) content;
        }

        ConfigFormat targetFormat = format != null ? format : ConfigFormat.TEXT;
        try {
            switch (targetFormat) {
                case JSON -> {
                    return jsonMapper.readValue(content, targetType);
                }
                case YAML -> {
                    return yamlMapper.readValue(content, targetType);
                }
                case PROPERTIES -> {
                    Properties props = new Properties();
                    props.load(new StringReader(content));
                    if (Properties.class.equals(targetType)) {
                        return (T) props;
                    }
                    return jsonMapper.convertValue(props, targetType);
                }
                case TEXT -> {
                    if (Object.class.equals(targetType)) {
                        return (T) content;
                    }
                    return jsonMapper.convertValue(content, targetType);
                }
                default -> throw new IllegalArgumentException("Unsupported config format: " + targetFormat);
            }
        } catch (Exception ex) {
            throw new BusinessException(NacosResultCode.NACOS_CONFIG_PARSE_FAILED,
                    "解析 Nacos " + targetFormat + " 配置为 [" + targetType.getSimpleName() + "] 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 校验配置文本语法有效性
     *
     * @param content 配置文本内容
     * @param format  配置格式
     * @throws BusinessException 当语法损坏或格式非法时抛出
     */
    public void validate(String content, ConfigFormat format) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        ConfigFormat targetFormat = format != null ? format : ConfigFormat.TEXT;
        try {
            switch (targetFormat) {
                case JSON -> jsonMapper.readTree(content);
                case YAML -> yamlMapper.readTree(content);
                case PROPERTIES -> {
                    Properties props = new Properties();
                    props.load(new StringReader(content));
                }
                case TEXT -> {
                    // 纯文本无需特殊语法约束
                }
            }
        } catch (Exception ex) {
            throw new BusinessException(NacosResultCode.NACOS_CONFIG_VALIDATION_FAILED,
                    "Nacos " + targetFormat + " 配置语法校验失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 将配置对象序列化为指定格式的文本
     *
     * @param object 配置对象（POJO、Map 或基本类型）
     * @param format 目标配置格式
     * @return 序列化后的配置文本
     * @throws BusinessException 当序列化失败时抛出
     */
    public String serialize(Object object, ConfigFormat format) {
        if (object == null) {
            return "";
        }
        if (object instanceof String str) {
            return str;
        }
        ConfigFormat targetFormat = format != null ? format : ConfigFormat.TEXT;
        try {
            switch (targetFormat) {
                case JSON -> {
                    return jsonMapper.writeValueAsString(object);
                }
                case YAML -> {
                    return yamlMapper.writeValueAsString(object);
                }
                case PROPERTIES -> {
                    Properties props = new Properties();
                    if (object instanceof Properties p) {
                        props.putAll(p);
                    } else if (object instanceof Map<?, ?> map) {
                        map.forEach((k, v) -> {
                            if (k != null && v != null) {
                                props.put(String.valueOf(k), String.valueOf(v));
                            }
                        });
                    } else {
                        Map<?, ?> map = jsonMapper.convertValue(object, Map.class);
                        map.forEach((k, v) -> {
                            if (k != null && v != null) {
                                props.put(String.valueOf(k), String.valueOf(v));
                            }
                        });
                    }
                    StringWriter writer = new StringWriter();
                    props.store(writer, null);
                    return writer.toString();
                }
                case TEXT -> {
                    return String.valueOf(object);
                }
                default -> throw new IllegalArgumentException("Unsupported config format: " + targetFormat);
            }
        } catch (Exception ex) {
            throw new BusinessException(NacosResultCode.NACOS_CONFIG_PARSE_FAILED,
                    "序列化 Nacos 配置对象为 " + targetFormat + " 格式失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 获取内部 JSON 序列化器
     *
     * @return ObjectMapper 实例
     */
    public ObjectMapper getJsonMapper() {
        return jsonMapper;
    }

    /**
     * 获取内部 YAML 序列化器
     *
     * @return YAMLMapper 实例
     */
    public YAMLMapper getYamlMapper() {
        return yamlMapper;
    }

    private static ObjectMapper createDefaultJsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    private static YAMLMapper createDefaultYamlMapper() {
        YAMLMapper mapper = new YAMLMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }
}
