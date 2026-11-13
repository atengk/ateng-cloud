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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("尾部斜杠归一化：非根路径自动截除尾部斜杠，根路径保持不变")
    void testTrailingSlashNormalization() {
        assertThat(cleaner.clean("/")).isEqualTo("/");
        assertThat(cleaner.clean("/api/v1/health/")).isEqualTo("/api/v1/health");
        assertThat(cleaner.clean("/api/orders/1001/")).isEqualTo("/api/orders/{id}");
        assertThat(cleaner.clean("/actuator/health/")).isEmpty();
    }

    @Test
    @DisplayName("MVC 路由模板防串扰：仅当前请求路径匹配且非 /** /* 通配符时才采纳模板，并对模板校验白名单")
    void testSpringMvcPatternIsolationWildcardAndExclude() {
        // 1. 跨路径防串扰：当前请求是 /api/v1/orders/1001，但直接清洗 /api/v2/users/2002 不得被串扰为 /api/v1/orders/{id}
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders/1001");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders/{id}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(cleaner.clean("/api/v2/users/2002")).isEqualTo("/api/v2/users/{id}");
        assertThat(cleaner.clean("/api/v1/orders/1001/")).isEqualTo("/api/v1/orders/{id}");

        // 2. 过滤 /** 和 /* 泛化通配符模板，回退正则收敛
        MockHttpServletRequest wildcardRequest = new MockHttpServletRequest("GET", "/api/orders/9527");
        wildcardRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/**");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(wildcardRequest));
        assertThat(cleaner.clean("/api/orders/9527")).isEqualTo("/api/orders/{id}");

        wildcardRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/*");
        assertThat(cleaner.clean("/api/orders/9527")).isEqualTo("/api/orders/{id}");

        // 3. 模板本身命中 excludePatterns 白名单时返回空字符串
        properties.getUrlClean().setExcludePatterns(List.of("/internal/**"));
        AtengUrlCleaner excludeCleaner = new AtengUrlCleaner(properties);
        MockHttpServletRequest internalRequest = new MockHttpServletRequest("GET", "/internal/tasks/1001");
        internalRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/internal/tasks/{id}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(internalRequest));
        assertThat(excludeCleaner.clean("/internal/tasks/1001")).isEmpty();
    }

    @Test
    @DisplayName("24/32 位十六进制串收敛：自动归一化 Mongo ObjectId 与无连字符 MD5/UUID，不误伤正常英文路径")
    void testHexIdReplacement() {
        String objectId24 = "507f1f77bcf86cd799439011";
        String md5Hex32 = "550e8400e29b41d4a716446655440000";

        assertThat(cleaner.clean("/api/docs/" + objectId24)).isEqualTo("/api/docs/{id}");
        assertThat(cleaner.clean("/api/tokens/" + md5Hex32 + "/verify")).isEqualTo("/api/tokens/{id}/verify");
        // 非 24/32 位的正常英文单词不被误伤
        assertThat(cleaner.clean("/api/cafe/deadbeef")).isEqualTo("/api/cafe/deadbeef");

        properties.getUrlClean().setHexReplacement("hexId");
        AtengUrlCleaner customHexCleaner = new AtengUrlCleaner(properties);
        assertThat(customHexCleaner.clean("/api/docs/" + objectId24)).isEqualTo("/api/docs/{hexId}");
    }

    @Test
    @DisplayName("自定义正则清洗：按序执行 custom-patterns 收敛带业务前缀的单号路径")
    void testCustomPatternsOrderedReplacement() {
        AtengSentinelProperties.UrlCleanProperties.CustomPattern payPattern =
                new AtengSentinelProperties.UrlCleanProperties.CustomPattern("/PAY-[A-Za-z0-9-]+(?=/|$)", "/{payNo}");
        AtengSentinelProperties.UrlCleanProperties.CustomPattern orderPattern =
                new AtengSentinelProperties.UrlCleanProperties.CustomPattern("/ORD_[0-9_]+(?=/|$)", "/{orderNo}");
        properties.getUrlClean().setCustomPatterns(List.of(payPattern, orderPattern));

        AtengUrlCleaner customCleaner = new AtengUrlCleaner(properties);
        assertThat(customCleaner.clean("/sample/cloud/sentinel/payment/status/PAY-1001"))
                .isEqualTo("/sample/cloud/sentinel/payment/status/{payNo}");
        assertThat(customCleaner.clean("/api/orders/ORD_2026_01/items/99"))
                .isEqualTo("/api/orders/{orderNo}/items/{id}");
    }

    @Test
    @DisplayName("对抗性边界测试：非法正则与非法分组引用快速失败、含花括号防爆旁路封堵与字面量转义")
    void testCustomPatternsAdversarialAndQuoteReplacement() {
        // 1. 非法正则语法构造期 Fail-Fast
        properties.getUrlClean().setCustomPatterns(List.of(
                new AtengSentinelProperties.UrlCleanProperties.CustomPattern("[unclosed-regex", "/{bad}")
        ));
        assertThatThrownBy(() -> new AtengUrlCleaner(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid custom url-clean regex pattern");

        // 2. 非法分组引用与悬空转义构造期 Fail-Fast
        properties.getUrlClean().setCustomPatterns(List.of(
                new AtengSentinelProperties.UrlCleanProperties.CustomPattern("/v/\\d+", "/$9")
        ));
        assertThatThrownBy(() -> new AtengUrlCleaner(properties))
                .isInstanceOf(IllegalArgumentException.class);

        properties.getUrlClean().setCustomPatterns(List.of(
                new AtengSentinelProperties.UrlCleanProperties.CustomPattern("/v/\\d+", "/bad\\")
        ));
        assertThatThrownBy(() -> new AtengUrlCleaner(properties))
                .isInstanceOf(IllegalArgumentException.class);

        // 3. 空条目/空白 pattern 跳过、replacement 为 null 兜底，且允许有效捕获组引用
        AtengSentinelProperties validProps = new AtengSentinelProperties();
        AtengSentinelProperties.UrlCleanProperties.CustomPattern validCapture =
                new AtengSentinelProperties.UrlCleanProperties.CustomPattern("/v(\\d+)/PAY-[0-9]+", "/v$1/{pay}");
        AtengSentinelProperties.UrlCleanProperties.CustomPattern nullReplacement =
                new AtengSentinelProperties.UrlCleanProperties.CustomPattern("/temp", null);
        AtengSentinelProperties.UrlCleanProperties.CustomPattern blankPattern =
                new AtengSentinelProperties.UrlCleanProperties.CustomPattern("   ", "/ignored");
        validProps.getUrlClean().setCustomPatterns(java.util.Arrays.asList(null, blankPattern, validCapture, nullReplacement));
        validProps.getUrlClean().setHexReplacement("$hex");

        AtengUrlCleaner safeCleaner = new AtengUrlCleaner(validProps);
        assertThat(safeCleaner.clean("/v2/PAY-1001")).isEqualTo("/v2/{pay}");
        assertThat(safeCleaner.clean("/temp/items/100")).isEqualTo("/items/{id}");

        // 4. 含 {x} 混合路径仍被内置正则收敛 (不旁路防爆) 且 $ 占位符不会触发 IllegalArgumentException
        assertThat(safeCleaner.clean("/api/orders/12345/{x}")).isEqualTo("/api/orders/{id}/{x}");
        assertThat(safeCleaner.clean("/api/docs/507f1f77bcf86cd799439011")).isEqualTo("/api/docs/{$hex}");
    }
}
