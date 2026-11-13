package io.github.atengk.cloud.sentinel.util;

import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

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

    @Test
    @DisplayName("测试 SentinelResultCode 与规则类型、HTTP 状态码及等价阻断异常双向映射")
    void shouldMapResultCodeAndCreateSyntheticBlockExceptions() {
        assertThat(SentinelServletUtil.determineRuleType(SentinelResultCode.FLOW_LIMIT)).isEqualTo("FLOW");
        assertThat(SentinelServletUtil.determineRuleType(SentinelResultCode.DEGRADE)).isEqualTo("DEGRADE");
        assertThat(SentinelServletUtil.determineRuleType(SentinelResultCode.PARAM_FLOW_LIMIT)).isEqualTo("PARAM_FLOW");
        assertThat(SentinelServletUtil.determineRuleType(SentinelResultCode.SYSTEM_BLOCK)).isEqualTo("SYSTEM");
        assertThat(SentinelServletUtil.determineRuleType(SentinelResultCode.AUTHORITY_BLOCK)).isEqualTo("AUTHORITY");
        assertThat(SentinelServletUtil.determineRuleType((SentinelResultCode) null)).isEqualTo("UNKNOWN");

        assertThat(SentinelServletUtil.determineHttpStatus(SentinelResultCode.DEGRADE))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(SentinelServletUtil.determineHttpStatus(SentinelResultCode.FLOW_LIMIT))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(SentinelServletUtil.createBlockExceptionForResultCode(SentinelResultCode.FLOW_LIMIT, "r", "m"))
                .isInstanceOf(FlowException.class);
        assertThat(SentinelServletUtil.createBlockExceptionForResultCode(SentinelResultCode.DEGRADE, "r", "m"))
                .isInstanceOf(DegradeException.class);
        assertThat(SentinelServletUtil.createBlockExceptionForResultCode(SentinelResultCode.PARAM_FLOW_LIMIT, "r", "m"))
                .isInstanceOf(ParamFlowException.class);
        assertThat(SentinelServletUtil.createBlockExceptionForResultCode(SentinelResultCode.SYSTEM_BLOCK, "r", "m"))
                .isInstanceOf(SystemBlockException.class);
        assertThat(SentinelServletUtil.createBlockExceptionForResultCode(SentinelResultCode.AUTHORITY_BLOCK, "r", "m"))
                .isInstanceOf(AuthorityException.class);
    }

    @Test
    @DisplayName("测试 BusinessException 提取 SentinelResultCode 与因果链循环防死锁解包")
    void shouldResolveSentinelResultCodeAndSafelyWalkCyclicChain() {
        assertThat(SentinelServletUtil.resolveSentinelResultCode(null)).isNull();
        assertThat(SentinelServletUtil.resolveSentinelResultCode(new BusinessException(10001, "biz"))).isNull();
        assertThat(SentinelServletUtil.resolveSentinelResultCode(
                new BusinessException(SentinelResultCode.PARAM_FLOW_LIMIT)))
                .isEqualTo(SentinelResultCode.PARAM_FLOW_LIMIT);
        assertThat(SentinelServletUtil.resolveSentinelResultCode(new BusinessException(4202, "manual degrade")))
                .isEqualTo(SentinelResultCode.DEGRADE);

        FlowException flow = new FlowException("nested-flow");
        InvocationTargetException wrapped = new InvocationTargetException(new UndeclaredThrowableException(flow));
        assertThat(SentinelServletUtil.extractBlockException(wrapped)).isSameAs(flow);

        // 构造带循环引用的异常链，验证 findInCausalChain 不陷入死循环
        RuntimeException nodeA = new RuntimeException("A");
        RuntimeException nodeB = new RuntimeException("B", nodeA);
        nodeA.initCause(nodeB);
        assertThat(SentinelServletUtil.extractBlockException(nodeA)).isNull();
    }
}
