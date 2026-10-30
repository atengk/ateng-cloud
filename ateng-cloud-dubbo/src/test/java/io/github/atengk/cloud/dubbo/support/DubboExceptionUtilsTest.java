package io.github.atengk.cloud.dubbo.support;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dubbo 异常工具类白盒边界单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboExceptionUtilsTest {

    @Test
    @DisplayName("isOverloadException: 覆盖各类直接与嵌套的线程池耗尽拒绝异常")
    void testIsOverloadException() {
        assertThat(DubboExceptionUtils.isOverloadException(null)).isFalse();
        assertThat(DubboExceptionUtils.isOverloadException(new RuntimeException("normal exception"))).isFalse();

        // 1. 直接与嵌套的 RejectedExecutionException
        assertThat(DubboExceptionUtils.isOverloadException(new RejectedExecutionException("pool is full"))).isTrue();
        assertThat(DubboExceptionUtils.isOverloadException(new RuntimeException(new RejectedExecutionException()))).isTrue();
        assertThat(DubboExceptionUtils.isOverloadException(new RuntimeException(new IllegalStateException(new RejectedExecutionException())))).isTrue();

        // 2. 包含特定关键词的异常信息 (大小写自适应)
        assertThat(DubboExceptionUtils.isOverloadException(new RpcException("Thread pool is EXHAUSTED!"))).isTrue();
        assertThat(DubboExceptionUtils.isOverloadException(new RpcException("Server threadpool is exhausted"))).isTrue();
        assertThat(DubboExceptionUtils.isOverloadException(new RuntimeException("Provider threads can not be used anymore"))).isTrue();
    }

    @Test
    @DisplayName("findConstraintViolationException: 沿异常调用栈深度探测参数校验异常")
    void testFindConstraintViolationException() {
        assertThat(DubboExceptionUtils.findConstraintViolationException(null)).isNull();
        assertThat(DubboExceptionUtils.findConstraintViolationException(new IllegalArgumentException("invalid"))).isNull();

        ConstraintViolationException cve = new ConstraintViolationException("validation failed", Collections.emptySet());
        assertThat(DubboExceptionUtils.findConstraintViolationException(cve)).isSameAs(cve);

        RuntimeException wrapped = new RuntimeException("wrapper", new IllegalStateException("cause", cve));
        assertThat(DubboExceptionUtils.findConstraintViolationException(wrapped)).isSameAs(cve);
    }

    @Test
    @DisplayName("formatConstraintViolations: 格式化参数校验违规集合为友好提示")
    void testFormatConstraintViolations() {
        assertThat(DubboExceptionUtils.formatConstraintViolations(null)).isEqualTo("参数校验失败");
        assertThat(DubboExceptionUtils.formatConstraintViolations(Collections.emptySet())).isEqualTo("参数校验失败");

        ConstraintViolation<?> violation1 = mock(ConstraintViolation.class);
        Path path1 = mock(Path.class);
        when(path1.toString()).thenReturn("createUser.request.username");
        when(violation1.getPropertyPath()).thenReturn(path1);
        when(violation1.getMessage()).thenReturn("不能为空");

        ConstraintViolation<?> violation2 = mock(ConstraintViolation.class);
        Path path2 = mock(Path.class);
        when(path2.toString()).thenReturn("age");
        when(violation2.getPropertyPath()).thenReturn(path2);
        when(violation2.getMessage()).thenReturn("必须大于等于 18");

        Set<ConstraintViolation<?>> violations = new LinkedHashSet<>();
        violations.add(violation1);
        violations.add(violation2);

        String formatted = DubboExceptionUtils.formatConstraintViolations(violations);
        assertThat(formatted).contains("参数校验失败: ");
        assertThat(formatted).contains("username 不能为空");
        assertThat(formatted).contains("age 必须大于等于 18");
    }

    @Test
    @DisplayName("isStreamObserver: 验证流式通信观察者类型探测")
    void testIsStreamObserver() {
        assertThat(DubboExceptionUtils.isStreamObserver(null)).isFalse();
        assertThat(DubboExceptionUtils.isStreamObserver(String.class)).isFalse();
        assertThat(DubboExceptionUtils.isStreamObserver(StreamObserver.class)).isTrue();
    }

    @Test
    @DisplayName("isStreamInvocation: 验证 RPC 调用中流式入参与返回类型探测")
    void testIsStreamInvocation() {
        assertThat(DubboExceptionUtils.isStreamInvocation(null)).isFalse();

        // 1. 普通非流式调用
        RpcInvocation normalInvocation = new RpcInvocation();
        normalInvocation.setParameterTypes(new Class<?>[]{String.class, Long.class});
        normalInvocation.setReturnType(String.class);
        assertThat(DubboExceptionUtils.isStreamInvocation(normalInvocation)).isFalse();

        // 2. 入参含有 StreamObserver 的调用
        RpcInvocation paramStreamInvocation = new RpcInvocation();
        paramStreamInvocation.setParameterTypes(new Class<?>[]{String.class, StreamObserver.class});
        paramStreamInvocation.setReturnType(void.class);
        assertThat(DubboExceptionUtils.isStreamInvocation(paramStreamInvocation)).isTrue();

        // 3. 返回值含有 StreamObserver 的调用 (通过 Invoker 与服务接口定位方法返回类型)
        Invoker<?> invoker = mock(Invoker.class);
        org.apache.dubbo.common.URL url = org.apache.dubbo.common.URL.valueOf("tri://127.0.0.1/" + StreamDemoService.class.getName());
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation returnStreamInvocation = new RpcInvocation();
        returnStreamInvocation.setInvoker(invoker);
        returnStreamInvocation.setMethodName("serverStream");
        returnStreamInvocation.setParameterTypes(new Class<?>[]{String.class});
        assertThat(DubboExceptionUtils.isStreamInvocation(returnStreamInvocation)).isTrue();
    }

    interface StreamDemoService {
        StreamObserver<String> serverStream(String req);
    }
}
