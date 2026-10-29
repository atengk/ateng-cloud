package io.github.atengk.cloud.seata.interceptor;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web 入站 XID 生命周期拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SeataWebHandlerInterceptorTest {

    private final SeataWebHandlerInterceptor interceptor = new SeataWebHandlerInterceptor();

    @BeforeEach
    @AfterEach
    void cleanContext() {
        RootContext.unbind();
    }

    @Test
    @DisplayName("测试入站请求携带 TX_XID 时成功绑定并在请求完成后安全解绑")
    void testPreHandleAndAfterCompletionLifecycle() {
        String testXid = "192.168.1.100:8091:88888888";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SeataConstants.TX_XID_HEADER, testXid);
        request.setRequestURI("/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handleResult = interceptor.preHandle(request, response, new Object());
        assertThat(handleResult).isTrue();
        assertThat(RootContext.getXID()).isEqualTo(testXid);
        assertThat(request.getAttribute(SeataWebHandlerInterceptor.BIND_XID_ATTRIBUTE)).isEqualTo(testXid);

        // 模拟请求生命周期结束
        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(RootContext.getXID()).isNull();
        assertThat(request.getAttribute(SeataWebHandlerInterceptor.BIND_XID_ATTRIBUTE)).isNull();
    }

    @Test
    @DisplayName("测试入站请求无 TX_XID 时不改变当前上下文且不执行解绑")
    void testNoXidInRequestHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handleResult = interceptor.preHandle(request, response, new Object());
        assertThat(handleResult).isTrue();
        assertThat(RootContext.getXID()).isNull();
        assertThat(request.getAttribute(SeataWebHandlerInterceptor.BIND_XID_ATTRIBUTE)).isNull();

        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(RootContext.getXID()).isNull();
    }

    @Test
    @DisplayName("测试当前线程已存在不同 XID 时跳过重新绑定且请求结束不误解绑原事务")
    void testExistingDifferentXidNotOverridden() {
        String existingXid = "192.168.1.100:8091:11111111";
        String incomingXid = "192.168.1.100:8091:22222222";
        RootContext.bind(existingXid);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SeataConstants.TX_XID_HEADER, incomingXid);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean handleResult = interceptor.preHandle(request, response, new Object());
        assertThat(handleResult).isTrue();
        // 维持原事务 XID 不变
        assertThat(RootContext.getXID()).isEqualTo(existingXid);
        assertThat(request.getAttribute(SeataWebHandlerInterceptor.BIND_XID_ATTRIBUTE)).isNull();

        // 请求结束不应解绑原事务
        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(RootContext.getXID()).isEqualTo(existingXid);
    }

    @Test
    @DisplayName("测试入站绑定后若当前 XID 发生漂移，afterCompletion 依然通过 finally 保证安全解绑与属性清理")
    void testAfterCompletionDeterministicCleanupWhenXidDrifted() {
        String boundXid = "192.168.1.100:8091:33333333";
        String driftedXid = "192.168.1.100:8091:44444444";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SeataWebHandlerInterceptor.BIND_XID_ATTRIBUTE, boundXid);
        RootContext.bind(driftedXid);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        assertThat(RootContext.getXID()).isNull();
        assertThat(request.getAttribute(SeataWebHandlerInterceptor.BIND_XID_ATTRIBUTE)).isNull();
    }
}
