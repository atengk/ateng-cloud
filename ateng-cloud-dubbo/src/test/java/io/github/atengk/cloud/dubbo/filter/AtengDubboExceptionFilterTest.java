package io.github.atengk.cloud.dubbo.filter;

import com.thirdparty.mock.VendorDriverException;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.overload.DubboOverloadListener;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.service.GenericService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dubbo 服务端因果异常保真与反序列化防御过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengDubboExceptionFilterTest {

    private final AtengDubboExceptionFilter filter = new AtengDubboExceptionFilter();

    @BeforeEach
    void setUp() {
        DubboPropertiesHolder.set(new AtengDubboProperties());
        DubboOverloadListener.reset();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        DubboPropertiesHolder.reset();
        DubboOverloadListener.reset();
    }

    @Test
    @DisplayName("受管 BusinessException 白名单直接放行，完整保留错误码与因果链")
    void testBusinessExceptionPassthrough() {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("query");

        BusinessException original = new BusinessException(4002, "订单状态无效");
        AppResponse response = new AppResponse(original);

        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isTrue();
        assertThat(response.getException()).isSameAs(original);
    }

    @Test
    @DisplayName("JSR-303 参数校验异常自动转译为受管 BusinessException(BAD_REQUEST)")
    void testConstraintViolationExceptionTransformation() {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("query");

        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("username");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("不能为空");

        ConstraintViolationException cve = new ConstraintViolationException(Set.of(violation));
        AppResponse response = new AppResponse(cve);

        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isTrue();
        assertThat(response.getException()).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) response.getException();
        assertThat(be.getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
        assertThat(be.getMessage()).contains("username 不能为空");
        assertThat(be.getCause()).isSameAs(cve);
    }

    @Test
    @DisplayName("服务端线程池耗尽异常自动转译为 TOO_MANY_REQUESTS 并递增过载统计指标")
    void testOverloadExceptionTransformation() {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("query");

        long countBefore = DubboOverloadListener.getRejectedCount();
        RejectedExecutionException ree = new RejectedExecutionException("thread pool is exhausted");
        AppResponse response = new AppResponse(ree);

        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isTrue();
        assertThat(response.getException()).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) response.getException();
        assertThat(be.getCode()).isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());
        assertThat(be.getMessage()).contains("服务端并发线程池已耗尽");
        assertThat(be.getCause()).isSameAs(ree);

        assertThat(DubboOverloadListener.getRejectedCount()).isEqualTo(countBefore + 1);
    }

    @Test
    @DisplayName("JDK 标准受检异常与 throws 声明异常直接放行")
    void testCheckedAndDeclaredExceptionPassthrough() throws Exception {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("callWithChecked");
        invocation.setParameterTypes(new Class<?>[0]);

        // 1. 受检异常 (Checked Exception)
        IOException ioException = new IOException("disk full");
        AppResponse response1 = new AppResponse(ioException);
        filter.onResponse(response1, invoker, invocation);
        assertThat(response1.getException()).isSameAs(ioException);

        // 2. 方法签名 throws 声明的自定义受检异常
        SampleCustomCheckedException customChecked = new SampleCustomCheckedException("custom checked");
        AppResponse response2 = new AppResponse(customChecked);
        filter.onResponse(response2, invoker, invocation);
        assertThat(response2.getException()).isSameAs(customChecked);
    }

    @Test
    @DisplayName("JDK 核心基础异常与 Dubbo RpcException 直接放行")
    void testJdkCoreAndRpcExceptionPassthrough() {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("query");

        // 1. JDK 基础异常 (如 IllegalArgumentException)
        IllegalArgumentException iae = new IllegalArgumentException("illegal arg");
        AppResponse response1 = new AppResponse(iae);
        filter.onResponse(response1, invoker, invocation);
        assertThat(response1.getException()).isSameAs(iae);

        // 2. RpcException
        RpcException rpcException = new RpcException("network timeout");
        AppResponse response2 = new AppResponse(rpcException);
        filter.onResponse(response2, invoker, invocation);
        assertThat(response2.getException()).isSameAs(rpcException);
    }

    @Test
    @DisplayName("未受检的第三方底层私有异常安全转译为 BusinessException 并保留原始因果链")
    void testUnknownThirdPartyExceptionTransformation() {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("query");
        invocation.setParameterTypes(new Class<?>[0]);

        // 第三方私有非 JDK 未受检异常
        ThirdPartyDriverRuntimeException driverEx = new ThirdPartyDriverRuntimeException("db driver crashed");
        AppResponse response = new AppResponse(driverEx);

        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isTrue();
        assertThat(response.getException()).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) response.getException();
        assertThat(be.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(be.getMessage()).contains("RPC 底层调用未知异常: db driver crashed");
        assertThat(be.getCause()).isSameAs(driverEx);
    }

    @Test
    @DisplayName("GenericService 泛化调用豁免异常转译")
    void testGenericServiceExemption() {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) GenericService.class);
        RpcInvocation invocation = new RpcInvocation();

        ThirdPartyDriverRuntimeException driverEx = new ThirdPartyDriverRuntimeException("db driver crashed");
        AppResponse response = new AppResponse(driverEx);

        filter.onResponse(response, invoker, invocation);
        assertThat(response.getException()).isSameAs(driverEx);
    }

    @Test
    @DisplayName("配置属性禁用异常过滤器时直接短路")
    void testDisabledExceptionProperties() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getException().setEnabled(false);
        DubboPropertiesHolder.set(properties);

        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();

        ThirdPartyDriverRuntimeException driverEx = new ThirdPartyDriverRuntimeException("raw error");
        AppResponse response = new AppResponse(driverEx);

        filter.onResponse(response, invoker, invocation);
        assertThat(response.getException()).isSameAs(driverEx);
    }

    @Test
    @DisplayName("默认开启 sanitizeUntrustedCause 时自动剥离非白名单第三方异常 cause 并回填 traceId")
    void testUntrustedThirdPartyCauseSanitizedByDefaultAndTraceIdBound() {
        MDC.put("traceId", "dubbo-provider-trace-101");
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("query");
        invocation.setParameterTypes(new Class<?>[0]);

        // 1. 第三方非白名单包异常：默认剥离 cause 防止消费端反序列化 ClassNotFoundException / 安全校验拦截
        VendorDriverException vendorEx = new VendorDriverException("mysql deadlock");
        AppResponse response1 = new AppResponse(vendorEx);
        filter.onResponse(response1, invoker, invocation);

        BusinessException be1 = (BusinessException) response1.getException();
        assertThat(be1.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(be1.getCause()).isNull();
        assertThat(be1.getMessage()).contains("mysql deadlock").contains("VendorDriverException");
        assertThat(be1.getTraceId()).isEqualTo("dubbo-provider-trace-101");

        // 2. 过载异常嵌套非白名单 cause 时同样安全剥离，防止嵌套 cause 导致反序列化失败
        RejectedExecutionException nestedOverload = new RejectedExecutionException("thread pool is exhausted", vendorEx);
        AppResponse response2 = new AppResponse(nestedOverload);
        filter.onResponse(response2, invoker, invocation);

        BusinessException be2 = (BusinessException) response2.getException();
        assertThat(be2.getCode()).isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());
        assertThat(be2.getCause()).isNull();
        assertThat(be2.getTraceId()).isEqualTo("dubbo-provider-trace-101");

        // 3. 受管 BusinessException 若 traceId 为空则自动从 MDC 回填；若已有 traceId 则保留原值
        BusinessException bizNoTrace = new BusinessException(4003, "库存锁定失败");
        AppResponse response3 = new AppResponse(bizNoTrace);
        filter.onResponse(response3, invoker, invocation);
        assertThat(bizNoTrace.getTraceId()).isEqualTo("dubbo-provider-trace-101");

        BusinessException bizWithTrace = new BusinessException(4003, "库存锁定失败").withTraceId("existing-trace-999");
        AppResponse response4 = new AppResponse(bizWithTrace);
        filter.onResponse(response4, invoker, invocation);
        assertThat(bizWithTrace.getTraceId()).isEqualTo("existing-trace-999");

        // 4. 受管 BusinessException 携带非白名单 cause 时同样安全剥离并保留 resultCode / code / traceId
        BusinessException bizWithVendorCause = new BusinessException(
                CommonResultCode.CONFLICT, "唯一键冲突", new RuntimeException("wrapper", vendorEx));
        AppResponse response5 = new AppResponse(bizWithVendorCause);
        filter.onResponse(response5, invoker, invocation);
        BusinessException sanitizedBiz = (BusinessException) response5.getException();
        assertThat(sanitizedBiz.getResultCode()).isEqualTo(CommonResultCode.CONFLICT);
        assertThat(sanitizedBiz.getCode()).isEqualTo(CommonResultCode.CONFLICT.getCode());
        assertThat(sanitizedBiz.getCause()).isNull();
        assertThat(sanitizedBiz.getSuppressed()).isEmpty();
        assertThat(sanitizedBiz.getMessage()).contains("唯一键冲突").contains("VendorDriverException");
        assertThat(sanitizedBiz.getTraceId()).isEqualTo("dubbo-provider-trace-101");

        // 5. JDK 异常内部嵌套非白名单 cause 时同样在出口安全转译剥离并保留全栈日志
        IllegalStateException jdkWrappingVendor = new IllegalStateException("wrapped jdk error", vendorEx);
        AppResponse response6 = new AppResponse(jdkWrappingVendor);
        filter.onResponse(response6, invoker, invocation);
        BusinessException sanitizedJdk = (BusinessException) response6.getException();
        assertThat(sanitizedJdk.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(sanitizedJdk.getCause()).isNull();
        assertThat(sanitizedJdk.getMessage()).contains("wrapped jdk error").contains("VendorDriverException");
        assertThat(sanitizedJdk.getTraceId()).isEqualTo("dubbo-provider-trace-101");

        // 6. Dubbo RpcException 嵌套非白名单 cause 时同样安全剥离；纯 RpcException 则保持放行
        RpcException rpcWrappingVendor = new RpcException("rpc wrapped vendor", vendorEx);
        AppResponse response7 = new AppResponse(rpcWrappingVendor);
        filter.onResponse(response7, invoker, invocation);
        BusinessException sanitizedRpc = (BusinessException) response7.getException();
        assertThat(sanitizedRpc.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(sanitizedRpc.getCause()).isNull();
        assertThat(sanitizedRpc.getMessage()).contains("VendorDriverException");
    }

    @Test
    @DisplayName("关闭 sanitizeUntrustedCause 或将第三方包加入 trustedCausePackages 时保留原始 cause")
    void testPreserveUntrustedCauseWhenDisabledOrTrustedPackageConfigured() {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) SampleService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("query");
        invocation.setParameterTypes(new Class<?>[0]);
        invocation.setAttachment(MessageHeaderConstants.TRACE_ID, "attachment-trace-202");

        // 1. 关闭 sanitizeUntrustedCause
        AtengDubboProperties disabledSanitizeProps = new AtengDubboProperties();
        disabledSanitizeProps.getException().setSanitizeUntrustedCause(false);
        DubboPropertiesHolder.set(disabledSanitizeProps);

        VendorDriverException vendorEx = new VendorDriverException("raw vendor cause");
        AppResponse response1 = new AppResponse(vendorEx);
        filter.onResponse(response1, invoker, invocation);

        BusinessException be1 = (BusinessException) response1.getException();
        assertThat(be1.getCause()).isSameAs(vendorEx);
        assertThat(be1.getTraceId()).isEqualTo("attachment-trace-202");

        // 2. 开启 sanitizeUntrustedCause 但将 com.thirdparty.mock 加入可信包前缀白名单
        AtengDubboProperties trustedPkgProps = new AtengDubboProperties();
        trustedPkgProps.getException().setSanitizeUntrustedCause(true);
        trustedPkgProps.getException().setTrustedCausePackages(List.of("com.thirdparty.mock"));
        DubboPropertiesHolder.set(trustedPkgProps);

        AppResponse response2 = new AppResponse(vendorEx);
        filter.onResponse(response2, invoker, invocation);

        BusinessException be2 = (BusinessException) response2.getException();
        assertThat(be2.getCause()).isSameAs(vendorEx);

        // 3. 加入 trustedCausePackages 时受管 BusinessException 也原样保留其 cause
        BusinessException trustedBiz = new BusinessException(4009, "透传第三方因果", vendorEx);
        AppResponse response3 = new AppResponse(trustedBiz);
        filter.onResponse(response3, invoker, invocation);
        assertThat(response3.getException()).isSameAs(trustedBiz);
        assertThat(response3.getException().getCause()).isSameAs(vendorEx);
    }

    interface SampleService {
        void query();

        void callWithChecked() throws IOException, SampleCustomCheckedException;
    }

    static class SampleCustomCheckedException extends Exception {
        SampleCustomCheckedException(String msg) {
            super(msg);
        }
    }

    // 模拟非 JDK、非受检第三方私有底层异常
    static class ThirdPartyDriverRuntimeException extends RuntimeException {
        ThirdPartyDriverRuntimeException(String msg) {
            super(msg);
        }
    }
}
