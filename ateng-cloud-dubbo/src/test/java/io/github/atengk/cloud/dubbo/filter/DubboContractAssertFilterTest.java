package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.dubbo.properties.AtengDubboContractProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboResultUnwrapProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.service.GenericService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Dubbo 消费端双模契约校验过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboContractAssertFilterTest {

    private final DubboContractAssertFilter filter = new DubboContractAssertFilter();

    @AfterEach
    void tearDown() {
        DubboPropertiesHolder.reset();
    }

    @Test
    @DisplayName("验证 SPI 注册表中 contractAssert 生效且已清理废弃的 resultUnwrap")
    void testFilterSpiRegistration() {
        ExtensionLoader<Filter> loader = ApplicationModel.defaultModel()
                .getDefaultModule()
                .getExtensionLoader(Filter.class);

        assertThat(loader.hasExtension("contractAssert")).isTrue();
        Filter filter = loader.getExtension("contractAssert");
        assertThat(filter).isInstanceOf(DubboContractAssertFilter.class);

        // resultUnwrap 已从 SPI 注册表中安全移除
        assertThat(loader.hasExtension("resultUnwrap")).isFalse();
    }

    @Test
    @DisplayName("验证废弃类 DubboResultUnwrapFilter 与 AtengDubboResultUnwrapProperties 的向后兼容性")
    void testDeprecatedClassesBackwardCompatibility() {
        DubboResultUnwrapFilter deprecatedFilter = new DubboResultUnwrapFilter();
        assertThat(deprecatedFilter).isInstanceOf(DubboContractAssertFilter.class);

        AtengDubboResultUnwrapProperties deprecatedProps = new AtengDubboResultUnwrapProperties();
        assertThat(deprecatedProps).isInstanceOf(AtengDubboContractProperties.class);
        assertThat(deprecatedProps.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("当返回成功 Result 时原样放行并不设异常")
    void testOnResponseSuccessResult() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) TestService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");

        AppResponse response = new AppResponse(Result.success("user-data"));
        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isFalse();
        assertThat(response.getValue()).isInstanceOf(Result.class);
        Result<?> result = (Result<?>) response.getValue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo("user-data");
    }

    @Test
    @DisplayName("当返回业务失败 Result 时自动转译为受管 BusinessException")
    void testOnResponseFailedResult() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) TestService.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");

        AppResponse response = new AppResponse(Result.fail(4001, "用户不存在"));
        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isTrue();
        assertThat(response.getException()).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) response.getException();
        assertThat(be.getCode()).isEqualTo(4001);
        assertThat(be.getMessage()).isEqualTo("用户不存在");
        assertThat(response.getValue()).isNull();
    }

    @Test
    @DisplayName("当返回普通 POJO 时直接原样放行")
    void testOnResponsePojo() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        RpcInvocation invocation = new RpcInvocation();

        AppResponse response = new AppResponse("direct-pojo-string");
        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isFalse();
        assertThat(response.getValue()).isEqualTo("direct-pojo-string");
    }

    @Test
    @DisplayName("流式调用 (含 StreamObserver 参数) 豁免契约校验转换")
    void testStreamInvocationExemption() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("streamCall");
        invocation.setParameterTypes(new Class<?>[]{String.class, StreamObserver.class});

        AppResponse response = new AppResponse(Result.fail(500, "流式内部错误"));
        filter.onResponse(response, invoker, invocation);

        // 流式调用豁免转译，保留原始返回值
        assertThat(response.hasException()).isFalse();
        assertThat(response.getValue()).isInstanceOf(Result.class);
    }

    @Test
    @DisplayName("当配置属性禁用契约校验时，不进行自动转译")
    void testDisabledContractProperties() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getContract().setEnabled(false);
        DubboPropertiesHolder.set(properties);

        Invoker<?> invoker = Mockito.mock(Invoker.class);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");

        AppResponse response = new AppResponse(Result.fail(4004, "未找到"));
        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isFalse();
        assertThat(response.getValue()).isInstanceOf(Result.class);
    }

    @Test
    @DisplayName("当响应已存在异常时不重复转译处理")
    void testHasExceptionExemption() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        RpcInvocation invocation = new RpcInvocation();

        AppResponse response = new AppResponse();
        response.setException(new RuntimeException("existing-exception"));
        filter.onResponse(response, invoker, invocation);

        assertThat(response.hasException()).isTrue();
        assertThat(response.getException()).hasMessage("existing-exception");
    }

    @Test
    @DisplayName("验证 filter.invoke 调用 invoker.invoke")
    void testFilterInvoke() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        RpcInvocation invocation = new RpcInvocation();
        when(invoker.invoke(invocation)).thenReturn(new AppResponse("ok"));

        org.apache.dubbo.rpc.Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("ok");
    }

    @Test
    @DisplayName("当方法声明返回非 void 基本类型且对端返回 null 时，前置拦截并转译为 BusinessException 防拆箱 NPE")
    void testPrimitiveNullReturnIntercepted() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) TestService.class);

        for (String methodName : new String[]{"countLong", "countInt", "checkBoolean", "calcDouble"}) {
            RpcInvocation invocation = new RpcInvocation();
            invocation.setMethodName(methodName);
            invocation.setParameterTypes(new Class<?>[0]);

            AppResponse response = new AppResponse((Object) null);
            filter.onResponse(response, invoker, invocation);

            assertThat(response.hasException()).as("method=%s", methodName).isTrue();
            assertThat(response.getException()).isInstanceOf(BusinessException.class);
            BusinessException be = (BusinessException) response.getException();
            assertThat(be.getCode()).isEqualTo(io.github.atengk.boot.core.code.CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
            assertThat(response.getValue()).isNull();
        }
    }

    @Test
    @DisplayName("当方法声明返回 void 或普通 POJO 且返回值为 null 时，不触发基本类型空返回拦截")
    void testVoidAndPojoNullReturnAllowed() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) TestService.class);

        RpcInvocation voidInvocation = new RpcInvocation();
        voidInvocation.setMethodName("doVoid");
        voidInvocation.setParameterTypes(new Class<?>[0]);
        AppResponse voidResponse = new AppResponse((Object) null);
        filter.onResponse(voidResponse, invoker, voidInvocation);
        assertThat(voidResponse.hasException()).isFalse();

        RpcInvocation pojoInvocation = new RpcInvocation();
        pojoInvocation.setMethodName("findPojo");
        pojoInvocation.setParameterTypes(new Class<?>[0]);
        AppResponse pojoResponse = new AppResponse((Object) null);
        filter.onResponse(pojoResponse, invoker, pojoInvocation);
        assertThat(pojoResponse.hasException()).isFalse();
    }

    @Test
    @DisplayName("当方法声明返回 Result<?> 且返回 null 时，默认 assertNonNullResult=true 断言拦截；关闭后放行 null")
    void testResultNullReturnAssertionAndToggle() {
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) TestService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryUser");
        invocation.setParameterTypes(new Class<?>[0]);

        // 1. 默认开启 assertNonNullResult = true：拦截 null Result<?>
        AppResponse defaultResponse = new AppResponse((Object) null);
        filter.onResponse(defaultResponse, invoker, invocation);
        assertThat(defaultResponse.hasException()).isTrue();
        assertThat(defaultResponse.getException()).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) defaultResponse.getException();
        assertThat(be.getCode()).isEqualTo(io.github.atengk.boot.core.code.CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(be.getMessage()).contains("Result<?>");

        // 2. 显式关闭 assertNonNullResult = false：允许 null 透传
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getContract().setAssertNonNullResult(false);
        DubboPropertiesHolder.set(properties);

        AppResponse allowedResponse = new AppResponse((Object) null);
        filter.onResponse(allowedResponse, invoker, invocation);
        assertThat(allowedResponse.hasException()).isFalse();
        assertThat(allowedResponse.getValue()).isNull();
    }

    @Test
    @DisplayName("当 contract.enabled=false 时，基本类型与 Result<?> 空返回均不触发拦截")
    void testDisabledContractSkipsNullGuards() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getContract().setEnabled(false);
        DubboPropertiesHolder.set(properties);

        Invoker<?> invoker = Mockito.mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) TestService.class);

        RpcInvocation primitiveInvocation = new RpcInvocation();
        primitiveInvocation.setMethodName("countLong");
        primitiveInvocation.setParameterTypes(new Class<?>[0]);
        AppResponse primitiveResponse = new AppResponse((Object) null);
        filter.onResponse(primitiveResponse, invoker, primitiveInvocation);
        assertThat(primitiveResponse.hasException()).isFalse();

        RpcInvocation resultInvocation = new RpcInvocation();
        resultInvocation.setMethodName("queryUser");
        resultInvocation.setParameterTypes(new Class<?>[0]);
        AppResponse resultResponse = new AppResponse((Object) null);
        filter.onResponse(resultResponse, invoker, resultInvocation);
        assertThat(resultResponse.hasException()).isFalse();
    }

    @Test
    @DisplayName("GenericService 与 $invoke 泛化调用返回 null 或非 200 Result 时豁免拦截")
    void testGenericServiceExemptionForNullAndFailedResult() {
        Invoker<?> genericInvoker = Mockito.mock(Invoker.class);
        when(genericInvoker.getInterface()).thenReturn((Class) GenericService.class);

        RpcInvocation nullInvocation = new RpcInvocation();
        nullInvocation.setMethodName("$invoke");
        nullInvocation.setParameterTypes(new Class<?>[]{String.class, String[].class, Object[].class});
        AppResponse nullResponse = new AppResponse((Object) null);
        filter.onResponse(nullResponse, genericInvoker, nullInvocation);
        assertThat(nullResponse.hasException()).isFalse();
        assertThat(nullResponse.getValue()).isNull();

        AppResponse failResultResponse = new AppResponse(Result.fail(4001, "generic fail payload"));
        filter.onResponse(failResultResponse, genericInvoker, nullInvocation);
        assertThat(failResultResponse.hasException()).isFalse();
        assertThat(failResultResponse.getValue()).isInstanceOf(Result.class);
    }

    interface TestService {
        Result<String> queryUser();

        long countLong();

        int countInt();

        boolean checkBoolean();

        double calcDouble();

        void doVoid();

        String findPojo();
    }
}
