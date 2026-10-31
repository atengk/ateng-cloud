package io.github.atengk.cloud.gateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 访问审计 Query 参数脱敏与旁路隔离工具类单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class GatewayQuerySanitizerTest {

    @Test
    @DisplayName("测试普通 Query 参数无敏感词原样保留")
    void testSanitizeNormalQueryNoSensitiveKeys() {
        String query = "name=alice&page=1&size=20&keyword=spring";
        String result = GatewayQuerySanitizer.sanitize(query);
        assertEquals(query, result);
    }

    @Test
    @DisplayName("测试内置 12 类高频敏感词脱敏（大小写不敏感匹配，原样保留键名）")
    void testSanitizeBuiltinSensitiveKeysCaseInsensitive() {
        String query = "password=p1&PWD=p2&token=t1&Secret=s1&KEY=k1&authorization=a1&Auth=a2&access_token=at1&REFRESH_TOKEN=rt1&mobile=13800000000&Phone=13900000000&IdCard=110101199003072394";
        String expected = "password=***&PWD=***&token=***&Secret=***&KEY=***&authorization=***&Auth=***&access_token=***&REFRESH_TOKEN=***&mobile=***&Phone=***&IdCard=***";
        String result = GatewayQuerySanitizer.sanitize(query);
        assertEquals(expected, result);
    }

    @Test
    @DisplayName("测试自定义脱敏参数列表 (mask-keys)")
    void testSanitizeCustomMaskKeys() {
        String query = "cvv=123&cardNumber=400012345678&username=john";
        String result = GatewayQuerySanitizer.sanitize(query, List.of("cvv", "CardNumber"));
        assertEquals("cvv=***&cardNumber=***&username=john", result);
    }

    @Test
    @DisplayName("测试空 Query 或 null 返回保底占位符 -")
    void testSanitizeEmptyOrNullQuery() {
        assertEquals("-", GatewayQuerySanitizer.sanitize(null));
        assertEquals("-", GatewayQuerySanitizer.sanitize(""));
    }

    @Test
    @DisplayName("测试无等号键脱敏 (如 password 或普通 flag)")
    void testSanitizeKeyWithoutValue() {
        assertEquals("password=***", GatewayQuerySanitizer.sanitize("password"));
        assertEquals("flag", GatewayQuerySanitizer.sanitize("flag"));
        assertEquals("debug&token=***", GatewayQuerySanitizer.sanitize("debug&token"));
    }

    @Test
    @DisplayName("测试空值参数脱敏")
    void testSanitizeEmptyValue() {
        assertEquals("password=***", GatewayQuerySanitizer.sanitize("password="));
        assertEquals("name=", GatewayQuerySanitizer.sanitize("name="));
    }

    @Test
    @DisplayName("测试同名参数多值重复脱敏")
    void testSanitizeMultipleValuesSameKey() {
        String query = "token=first&token=second&page=1";
        assertEquals("token=***&token=***&page=1", GatewayQuerySanitizer.sanitize(query));
    }

    @Test
    @DisplayName("测试超长 Query 超过 256 字符硬限制安全截断并追加 ...(truncated)")
    void testTruncateQueryLongerThan256() {
        // 构造一个长度为 300 字符的 Query 字符串
        String base = "param=abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 300) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(base);
        }
        String longQuery = sb.toString();
        assertTrue(longQuery.length() > 256);

        String result = GatewayQuerySanitizer.sanitize(longQuery);
        assertEquals(256 + GatewayQuerySanitizer.TRUNCATED_SUFFIX.length(), result.length());
        assertTrue(result.endsWith(GatewayQuerySanitizer.TRUNCATED_SUFFIX));
        assertEquals(longQuery.substring(0, 256), result.substring(0, 256));
    }

    @Test
    @DisplayName("测试恰好 256 字符的 Query 不被截断")
    void testExact256LengthNotTruncated() {
        // 构造恰好 256 字符的字符串 (使用非敏感键名 param)
        String prefix = "param=";
        String val = "a".repeat(256 - prefix.length());
        String query256 = prefix + val;
        assertEquals(256, query256.length());

        String result = GatewayQuerySanitizer.sanitize(query256);
        assertEquals(256, result.length());
        assertFalse(result.contains(GatewayQuerySanitizer.TRUNCATED_SUFFIX));
    }

    @Test
    @DisplayName("测试畸形百分号转义安全旁路隔离，降级输出 [malformed-query] 且永不抛出异常")
    void testMalformedPercentEncodingFallback() {
        // 非法末尾 %
        assertEquals(GatewayQuerySanitizer.MALFORMED_QUERY, GatewayQuerySanitizer.sanitize("foo=%"));
        // 非法单字符 %2
        assertEquals(GatewayQuerySanitizer.MALFORMED_QUERY, GatewayQuerySanitizer.sanitize("foo=%2"));
        // 非法十六进制字符 %ZZ
        assertEquals(GatewayQuerySanitizer.MALFORMED_QUERY, GatewayQuerySanitizer.sanitize("foo=%ZZ"));
        // 参数间混入非法 %
        assertEquals(GatewayQuerySanitizer.MALFORMED_QUERY, GatewayQuerySanitizer.sanitize("a=1&foo=%G1&b=2"));
    }

    @Test
    @DisplayName("测试包含非法控制字符旁路隔离")
    void testControlCharacterFallback() {
        assertEquals(GatewayQuerySanitizer.MALFORMED_QUERY, GatewayQuerySanitizer.sanitize("foo=bar\0baz"));
        assertEquals(GatewayQuerySanitizer.MALFORMED_QUERY, GatewayQuerySanitizer.sanitize("foo=bar\r\nbaz"));
    }

    @Test
    @DisplayName("测试合法 URL 编码正常保留且敏感参数依然脱敏")
    void testUrlEncodedCharactersPreserved() {
        String query = "keyword=spring%20boot&token=my-secret-token";
        String result = GatewayQuerySanitizer.sanitize(query);
        assertEquals("keyword=spring%20boot&token=***", result);
    }

    @Test
    @DisplayName("测试已 URL 编码的敏感键名被解码后精准匹配脱敏")
    void testDecodedKeyMatching() {
        // pass%77ord 解码为 password
        String query = "pass%77ord=secretValue";
        String result = GatewayQuerySanitizer.sanitize(query);
        assertEquals("pass%77ord=***", result);
    }
}
