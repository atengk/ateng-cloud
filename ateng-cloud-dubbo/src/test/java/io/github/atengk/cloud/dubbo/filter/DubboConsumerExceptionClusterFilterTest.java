package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.filter.ClusterFilter;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.service.GenericService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dubbo 消费端 ClusterFilter 原生 RpcException 按需因果转译单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
class DubboConsumerExceptionClusterFilterTest {

    private final DubboConsumerExceptionClusterFilter filter = new DubboConsumerExceptionClusterFilter();

    @BeforeEach
    void setUp() {
        DubboPropertiesHolder.reset();
    }

    @AfterEach
    void tearDown() {
        DubboPropertiesHolder.reset();
    }

    @Test
    @DisplayName("验证 ClusterFilter SPI 正确注册 consumerException 扩展")
    void testClusterFilterSpiRegistration() {
        ExtensionLoader<ClusterFilter> loader = ApplicationModel.defaultModel()
                .getDefaultModule()
                .getExtensionLoader(ClusterFilter.class);

        assertThat(loader.hasExtension("consumerException")).isTrue();
        assertThat(loader.getExtension("consumerException")).isInstanceOf(DubboConsumerExceptionClusterFilter.class);
    }

    @Test
    @DisplayName("默认关闭 translateConsumerRpcException=false 时原样透传原生 RpcException")
    void testDefaultPassthroughWhenTranslationDisabled() {
        Invoker<?> invoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService");
        RpcInvocation invocation = newInvocation("ping");
        RpcException timeoutEx = new RpcException(RpcException.TIMEOUT_EXCEPTION, "timeout");
        when(invoker.invoke(invocation)).thenThrow(timeoutEx);

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isSameAs(timeoutEx);
    }

    @Test
    @DisplayName("开启转译且未启用 mock 时，将 TIMEOUT_EXCEPTION 转译为 504 并保留原始 cause")
    void testTranslateTimeoutTo504WhenEnabled() {
        enableTranslation();
        Invoker<?> invoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService");
        RpcInvocation invocation = newInvocation("ping");
        RpcException timeoutEx = new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout");
        when(invoker.invoke(invocation)).thenThrow(timeoutEx);

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(504);
                    assertThat(be.getMessage()).contains("GATEWAY_TIMEOUT");
                    assertThat(be.getCause()).isSameAs(timeoutEx);
                });
    }

    @Test
    @DisplayName("开启转译且未启用 mock 时，将 NETWORK/FORBIDDEN/无可用提供者异常转译为 503 并保留 cause")
    void testTranslateUnavailableAndNetworkTo503() {
        enableTranslation();
        Invoker<?> invoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService");
        RpcInvocation invocation = newInvocation("ping");

        RpcException networkEx = new RpcException(RpcException.NETWORK_EXCEPTION, "upstream unavailable");
        doThrow(networkEx).when(invoker).invoke(invocation);
        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.SERVICE_UNAVAILABLE.getCode());
                    assertThat(be.getCause()).isSameAs(networkEx);
                });

        RpcException forbiddenEx = new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available");
        doThrow(forbiddenEx).when(invoker).invoke(invocation);
        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.SERVICE_UNAVAILABLE.getCode());
                    assertThat(be.getCause()).isSameAs(forbiddenEx);
                });

        RpcException noProviderMessageEx = new RpcException(
                "No provider available for the service DemoService under tag canary (tagforce=true)");
        doThrow(noProviderMessageEx).when(invoker).invoke(invocation);
        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.SERVICE_UNAVAILABLE.getCode());
                    assertThat(be.getCause()).isSameAs(noProviderMessageEx);
                });
    }

    @Test
    @DisplayName("开启转译且未启用 mock 时，将其他未知 RpcException 转译为 500 并保留 cause")
    void testTranslateUnknownRpcExceptionTo500() {
        enableTranslation();
        Invoker<?> invoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService");
        RpcInvocation invocation = newInvocation("ping");
        RpcException unknownEx = new RpcException(RpcException.SERIALIZATION_EXCEPTION, "codec failure");
        when(invoker.invoke(invocation)).thenThrow(unknownEx);

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
                    assertThat(be.getCause()).isSameAs(unknownEx);
                });
    }

    @Test
    @DisplayName("开启转译但 Invoker URL 配置有效 mock 参数时，无条件放行原生 RpcException 交由 MockClusterInvoker 降级")
    void testBypassTranslationWhenEffectiveMockConfigured() {
        enableTranslation();
        for (String url : new String[]{
                "tri://127.0.0.1:50051/DemoService?mock=true",
                "tri://127.0.0.1:50051/DemoService?mock=fail:return+null",
                "tri://127.0.0.1:50051/DemoService?ping.mock=true"
        }) {
            Invoker<?> invoker = mockConsumerInvoker(url);
            RpcInvocation invocation = newInvocation("ping");
            RpcException networkEx = new RpcException(RpcException.NETWORK_EXCEPTION, "connect failed");
            when(invoker.invoke(invocation)).thenThrow(networkEx);

            assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                    .as("url=%s", url)
                    .isSameAs(networkEx);
        }
    }

    @Test
    @DisplayName("Invoker URL 配置 mock=false/null/N/A 等无效占位符时，正常执行消费端 RpcException 转译")
    void testTranslateWhenMockParameterInactive() {
        enableTranslation();
        for (String url : new String[]{
                "tri://127.0.0.1:50051/DemoService?mock=false",
                "tri://127.0.0.1:50051/DemoService?mock=null",
                "tri://127.0.0.1:50051/DemoService?mock=N/A"
        }) {
            Invoker<?> invoker = mockConsumerInvoker(url);
            RpcInvocation invocation = newInvocation("ping");
            RpcException timeoutEx = new RpcException(RpcException.TIMEOUT_EXCEPTION, "timeout");
            when(invoker.invoke(invocation)).thenThrow(timeoutEx);

            assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                    .as("url=%s", url)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(504));
        }
    }

    @Test
    @DisplayName("Result 内携带 RpcException 或 onResponse 异步回调异常时同样执行按需因果转译")
    void testTranslateResultAndOnResponseRpcException() {
        enableTranslation();
        Invoker<?> invoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService");
        RpcInvocation invocation = newInvocation("ping");
        RpcException timeoutEx = new RpcException(RpcException.TIMEOUT_EXCEPTION, "async deadline exceeded");

        when(invoker.invoke(invocation)).thenReturn(AsyncRpcResult.newDefaultAsyncResult(timeoutEx, invocation));
        Result result = filter.invoke(invoker, invocation);
        assertThat(result.hasException()).isTrue();
        assertThat(result.getException()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) result.getException()).getCode()).isEqualTo(504);
        assertThat(result.getException().getCause()).isSameAs(timeoutEx);

        AppResponse appResponse = new AppResponse(new RpcException(RpcException.NETWORK_EXCEPTION, "socket closed"));
        filter.onResponse(appResponse, invoker, invocation);
        assertThat(appResponse.hasException()).isTrue();
        assertThat(appResponse.getException()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) appResponse.getException()).getCode())
                .isEqualTo(CommonResultCode.SERVICE_UNAVAILABLE.getCode());
    }

    @Test
    @DisplayName("保留内层 FailoverClusterInvoker 包装的 429 过载 BusinessException，不被降级覆盖为 500")
    void testPreserveOverload429WrappedByCluster() {
        enableTranslation();
        Invoker<?> invoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService");
        RpcInvocation invocation = newInvocation("ping");

        BusinessException overloadBe = new BusinessException(
                CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                "服务端并发线程池已耗尽",
                new RejectedExecutionException("pool exhausted")
        );
        RpcException clusterWrapped = new RpcException("Failed to invoke", overloadBe);
        when(invoker.invoke(invocation)).thenThrow(clusterWrapped);

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isSameAs(overloadBe);
    }

    @Test
    @DisplayName("流式调用与 Provider 侧调用豁免消费端 RpcException 转译")
    void testSkipStreamAndProviderSide() {
        enableTranslation();

        Invoker<?> consumerInvoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService");
        RpcInvocation streamInvocation = new RpcInvocation();
        streamInvocation.setMethodName("stream");
        streamInvocation.setParameterTypes(new Class<?>[]{StreamObserver.class});
        RpcException streamEx = new RpcException(RpcException.TIMEOUT_EXCEPTION, "stream timeout");
        when(consumerInvoker.invoke(streamInvocation)).thenThrow(streamEx);
        assertThatThrownBy(() -> filter.invoke(consumerInvoker, streamInvocation))
                .isSameAs(streamEx);

        Invoker<?> providerInvoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService?side=" + CommonConstants.PROVIDER_SIDE);
        RpcInvocation normalInvocation = newInvocation("ping");
        RpcException providerEx = new RpcException(RpcException.TIMEOUT_EXCEPTION, "provider timeout");
        when(providerInvoker.invoke(normalInvocation)).thenThrow(providerEx);
        assertThatThrownBy(() -> filter.invoke(providerInvoker, normalInvocation))
                .isSameAs(providerEx);
    }

    @Test
    @DisplayName("泛化调用豁免、无 cause 429 BusinessException 保留及 onResponse 边界空安全")
    void testSkipGenericInvocationAndBoundarySafeguards() {
        enableTranslation();

        Invoker<?> genericInvoker = mock(Invoker.class);
        when(genericInvoker.getInterface()).thenReturn((Class) GenericService.class);
        when(genericInvoker.getUrl()).thenReturn(URL.valueOf("tri://127.0.0.1:50051/GenericService"));
        RpcInvocation genericInvocation = newInvocation("$invoke");
        RpcException genericEx = new RpcException(RpcException.NETWORK_EXCEPTION, "generic network error");
        when(genericInvoker.invoke(genericInvocation)).thenThrow(genericEx);
        assertThatThrownBy(() -> filter.invoke(genericInvoker, genericInvocation))
                .isSameAs(genericEx);

        Invoker<?> consumerInvoker = mockConsumerInvoker("tri://127.0.0.1:50051/DemoService");
        RpcInvocation pingInvocation = newInvocation("ping");
        BusinessException overloadNoCause = new BusinessException(CommonResultCode.TOO_MANY_REQUESTS, "overload without inner cause");
        when(consumerInvoker.invoke(pingInvocation)).thenThrow(new RpcException("Failed to invoke", overloadNoCause));
        assertThatThrownBy(() -> filter.invoke(consumerInvoker, pingInvocation))
                .isSameAs(overloadNoCause);

        filter.onResponse(null, consumerInvoker, pingInvocation);
        AppResponse illegalStateResponse = new AppResponse(new IllegalStateException("non-rpc exception"));
        filter.onResponse(illegalStateResponse, consumerInvoker, pingInvocation);
        assertThat(illegalStateResponse.getException()).isInstanceOf(IllegalStateException.class);
    }

    private static void enableTranslation() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getException().setTranslateConsumerRpcException(true);
        DubboPropertiesHolder.set(properties);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Invoker<?> mockConsumerInvoker(String url) {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);
        when(invoker.getUrl()).thenReturn(URL.valueOf(url));
        return invoker;
    }

    private static RpcInvocation newInvocation(String methodName) {
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName(methodName);
        invocation.setParameterTypes(new Class<?>[0]);
        return invocation;
    }

    interface DemoService {
        String ping();
    }
}
