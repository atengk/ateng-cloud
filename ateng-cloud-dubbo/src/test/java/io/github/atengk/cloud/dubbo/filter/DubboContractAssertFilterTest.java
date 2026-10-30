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
        DubboContractAssertFilter filter = new DubboContractAssertFilter();
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
        DubboContractAssertFilter filter = new DubboContractAssertFilter();
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
        DubboContractAssertFilter filter = new DubboContractAssertFilter();
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
        DubboContractAssertFilter filter = new DubboContractAssertFilter();
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

        DubboContractAssertFilter filter = new DubboContractAssertFilter();
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
        DubboContractAssertFilter filter = new DubboContractAssertFilter();
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
        DubboContractAssertFilter filter = new DubboContractAssertFilter();
        Invoker<?> invoker = Mockito.mock(Invoker.class);
        RpcInvocation invocation = new RpcInvocation();
        when(invoker.invoke(invocation)).thenReturn(new AppResponse("ok"));

        org.apache.dubbo.rpc.Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("ok");
    }

    interface TestService {
        Result<String> queryUser();
    }
}
