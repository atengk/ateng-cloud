package io.github.atengk.cloud.sentinel.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentinel Servlet 请求工具类单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SentinelServletUtilTest {

    @Test
    @DisplayName("测试 null 请求对象保底返回 unknown")
    void shouldReturnUnknownForNullRequest() {
        assertThat(SentinelServletUtil.resolveClientIp(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("测试 X-Forwarded-For 多 IP 逗号截取首个 IP")
    void shouldResolveFirstIpFromForwardedForHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");
        assertThat(SentinelServletUtil.resolveClientIp(request)).isEqualTo("203.0.113.195");
    }

    @Test
    @DisplayName("测试 X-Real-IP 优先级低于 X-Forwarded-For 但高于 RemoteAddr")
    void shouldResolveFromRealIpWhenForwardedForMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "198.51.100.10");
        request.setRemoteAddr("10.0.0.1");
        assertThat(SentinelServletUtil.resolveClientIp(request)).isEqualTo("198.51.100.10");
    }

    @Test
    @DisplayName("测试缺失代理标头时平滑回退至 RemoteAddr")
    void shouldFallbackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");
        assertThat(SentinelServletUtil.resolveClientIp(request)).isEqualTo("192.168.1.50");
    }

    @Test
    @DisplayName("测试 RemoteAddr 为空或空白时保底 unknown")
    void shouldReturnUnknownWhenRemoteAddrBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("");
        assertThat(SentinelServletUtil.resolveClientIp(request)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("测试工具类私有构造方法防御性反射调用")
    void shouldCoverPrivateConstructor() throws Exception {
        Constructor<SentinelServletUtil> constructor = SentinelServletUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SentinelServletUtil instance = constructor.newInstance();
        assertThat(instance).isNotNull();
    }
}
