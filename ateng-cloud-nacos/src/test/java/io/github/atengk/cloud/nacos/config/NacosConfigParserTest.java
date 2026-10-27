package io.github.atengk.cloud.nacos.config;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.nacos.code.NacosResultCode;
import io.github.atengk.cloud.nacos.constant.ConfigFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NacosConfigParser 多格式配置解析、校验与序列化单测
 *
 * @author Ateng
 * @since 2026-10-28
 */
class NacosConfigParserTest {

    private NacosConfigParser parser;

    @BeforeEach
    void setUp() {
        parser = new NacosConfigParser();
    }

    public static class SampleAppConfig {
        private String appName;
        private int port;
        private boolean active;

        public SampleAppConfig() {
        }

        public SampleAppConfig(String appName, int port, boolean active) {
            this.appName = appName;
            this.port = port;
            this.active = active;
        }

        public String getAppName() {
            return appName;
        }

        public void setAppName(String appName) {
            this.appName = appName;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }
    }

    @Test
    @DisplayName("测试 ConfigFormat 枚举与 Data ID 后缀推断")
    void testConfigFormatInference() {
        assertThat(ConfigFormat.fromDataId("order-service.yaml")).isEqualTo(ConfigFormat.YAML);
        assertThat(ConfigFormat.fromDataId("order-service.yml")).isEqualTo(ConfigFormat.YAML);
        assertThat(ConfigFormat.fromDataId("order-service.json")).isEqualTo(ConfigFormat.JSON);
        assertThat(ConfigFormat.fromDataId("order-service.properties")).isEqualTo(ConfigFormat.PROPERTIES);
        assertThat(ConfigFormat.fromDataId("order-service.txt")).isEqualTo(ConfigFormat.TEXT);
        assertThat(ConfigFormat.fromDataId("")).isEqualTo(ConfigFormat.TEXT);
        assertThat(ConfigFormat.fromDataId(null)).isEqualTo(ConfigFormat.TEXT);

        assertThat(ConfigFormat.fromType("yaml")).isEqualTo(ConfigFormat.YAML);
        assertThat(ConfigFormat.fromType("yml")).isEqualTo(ConfigFormat.YAML);
        assertThat(ConfigFormat.fromType("json")).isEqualTo(ConfigFormat.JSON);
        assertThat(ConfigFormat.fromType("properties")).isEqualTo(ConfigFormat.PROPERTIES);
        assertThat(ConfigFormat.fromType("text")).isEqualTo(ConfigFormat.TEXT);
        assertThat(ConfigFormat.fromType("unknown")).isEqualTo(ConfigFormat.TEXT);
        assertThat(ConfigFormat.fromType(null)).isEqualTo(ConfigFormat.TEXT);
    }

    @Test
    @DisplayName("测试 YAML 格式解析为 POJO 与 Map")
    void testParseYaml() {
        String yaml = """
                appName: order-service
                port: 8080
                active: true
                """;

        SampleAppConfig config = parser.parse(yaml, ConfigFormat.YAML, SampleAppConfig.class);
        assertThat(config).isNotNull();
        assertThat(config.getAppName()).isEqualTo("order-service");
        assertThat(config.getPort()).isEqualTo(8080);
        assertThat(config.isActive()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> map = parser.parse(yaml, ConfigFormat.YAML, Map.class);
        assertThat(map).containsEntry("appName", "order-service");
        assertThat(map).containsEntry("port", 8080);
    }

    @Test
    @DisplayName("测试 JSON 格式解析为 POJO 与 Map")
    void testParseJson() {
        String json = """
                {"appName": "payment-service", "port": 8081, "active": false}
                """;

        SampleAppConfig config = parser.parse(json, ConfigFormat.JSON, SampleAppConfig.class);
        assertThat(config).isNotNull();
        assertThat(config.getAppName()).isEqualTo("payment-service");
        assertThat(config.getPort()).isEqualTo(8081);
        assertThat(config.isActive()).isFalse();

        @SuppressWarnings("unchecked")
        Map<String, Object> map = parser.parse(json, ConfigFormat.JSON, Map.class);
        assertThat(map).containsEntry("appName", "payment-service");
    }

    @Test
    @DisplayName("测试 Properties 格式解析为 Properties 对象与 POJO")
    void testParseProperties() {
        String propContent = """
                appName=user-service
                port=8082
                active=true
                """;

        Properties props = parser.parse(propContent, ConfigFormat.PROPERTIES, Properties.class);
        assertThat(props).isNotNull();
        assertThat(props.getProperty("appName")).isEqualTo("user-service");
        assertThat(props.getProperty("port")).isEqualTo("8082");

        SampleAppConfig config = parser.parse(propContent, ConfigFormat.PROPERTIES, SampleAppConfig.class);
        assertThat(config).isNotNull();
        assertThat(config.getAppName()).isEqualTo("user-service");
        assertThat(config.getPort()).isEqualTo(8082);
        assertThat(config.isActive()).isTrue();
    }

    @Test
    @DisplayName("测试纯文本与基本类型解析")
    void testParseText() {
        assertThat(parser.parse("hello nacos", ConfigFormat.TEXT, String.class)).isEqualTo("hello nacos");
        assertThat(parser.parse("12345", ConfigFormat.TEXT, Integer.class)).isEqualTo(12345);
        assertThat(parser.parse(null, ConfigFormat.TEXT, String.class)).isNull();
        assertThat(parser.parse("   ", ConfigFormat.TEXT, String.class)).isNull();
    }

    @Test
    @DisplayName("测试损坏语法解析抛出受管业务异常")
    void testParseCorruptedSyntaxThrowsException() {
        String brokenYaml = "appName: [unclosed list";
        assertThatThrownBy(() -> parser.parse(brokenYaml, ConfigFormat.YAML, SampleAppConfig.class))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(NacosResultCode.NACOS_CONFIG_PARSE_FAILED.getCode());
                });

        String brokenJson = "{\"appName\": unquoted";
        assertThatThrownBy(() -> parser.parse(brokenJson, ConfigFormat.JSON, SampleAppConfig.class))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(NacosResultCode.NACOS_CONFIG_PARSE_FAILED.getCode());
                });
    }

    @Test
    @DisplayName("测试语法校验 validate 正常与损坏分支")
    void testValidate() {
        // 正常情况
        parser.validate("key: value", ConfigFormat.YAML);
        parser.validate("{\"key\": \"value\"}", ConfigFormat.JSON);
        parser.validate("key=value", ConfigFormat.PROPERTIES);
        parser.validate("plain text content", ConfigFormat.TEXT);
        parser.validate(null, ConfigFormat.YAML);
        parser.validate("  ", ConfigFormat.JSON);

        // 损坏的 YAML 语法
        assertThatThrownBy(() -> parser.validate("key: [bad array", ConfigFormat.YAML))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(NacosResultCode.NACOS_CONFIG_VALIDATION_FAILED.getCode());
                });

        // 损坏的 JSON 语法
        assertThatThrownBy(() -> parser.validate("{broken json", ConfigFormat.JSON))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(NacosResultCode.NACOS_CONFIG_VALIDATION_FAILED.getCode());
                });
    }

    @Test
    @DisplayName("测试对象序列化为 YAML、JSON 与 Properties")
    void testSerialize() {
        SampleAppConfig config = new SampleAppConfig("inventory-service", 8083, true);

        String yaml = parser.serialize(config, ConfigFormat.YAML);
        assertThat(yaml).contains("appName: \"inventory-service\"").contains("port: 8083");

        String json = parser.serialize(config, ConfigFormat.JSON);
        assertThat(json).contains("\"appName\":\"inventory-service\"").contains("\"port\":8083");

        String props = parser.serialize(config, ConfigFormat.PROPERTIES);
        assertThat(props).contains("appName=inventory-service").contains("port=8083");

        assertThat(parser.serialize(null, ConfigFormat.YAML)).isEmpty();
        assertThat(parser.serialize("raw string", ConfigFormat.YAML)).isEqualTo("raw string");
    }
}
