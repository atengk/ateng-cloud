package io.github.atengk.cloud.dubbo.filter;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.same.SaSameTemplate;
import cn.dev33.satoken.same.SaSameUtil;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dubbo 零信任内网防穿透与 Same-Token 校验过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboSameTokenFilterTest {

    private final DubboSameTokenFilter filter = new DubboSameTokenFilter();

    @BeforeEach
    void setUp() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSameToken().setEnabled(true);
        properties.getSameToken().setCheck(true);
        DubboPropertiesHolder.set(properties);

        SaTokenConfig config = new SaTokenConfig();
        config.setCheckSameToken(true);
        SaManager.setConfig(config);
        SaManager.setSaSameTemplate(new SaSameTemplate());

        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
    }

    @AfterEach
    void tearDown() {
        DubboPropertiesHolder.reset();
        RpcContext.removeClientAttachment();
        RpcContext.removeServerAttachment();
    }

    @Test
    @DisplayName("消费端出站: 自动生成并注入 Same-Token 与 SA-SAME-TOKEN 受信凭据")
    void testConsumerInjectsSameToken() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryResource");
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result).isNotNull();

        String token = RpcContext.getClientAttachment().getAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY);
        assertThat(token).isNotBlank();
        assertThat(SaSameUtil.isValid(token)).isTrue();

        assertThat(RpcContext.getClientAttachment().getAttachment(DubboSameTokenFilter.SA_SAME_TOKEN_KEY))
                .isEqualTo(token);
    }

    @Test
    @DisplayName("消费端出站: 当调用方已显式指定 Same-Token 时不予覆盖")
    void testConsumerExplicitTokenPreserved() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryResource");
        invocation.setAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY, "explicit-custom-token");
        when(invoker.invoke(any())).thenReturn(new AppResponse("ok"));

        filter.invoke(invoker, invocation);

        assertThat(invocation.getAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY)).isEqualTo("explicit-custom-token");
    }

    @Test
    @DisplayName("服务端入站: 当携带有效 Same-Token 凭据时成功放行")
    void testProviderValidTokenPass() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("updateSecret");
        String validToken = SaSameUtil.getToken();
        invocation.setAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY, validToken);

        when(invoker.invoke(any())).thenReturn(new AppResponse("authorized"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("authorized");
    }

    @Test
    @DisplayName("服务端入站: 未携带凭据直连时 Fail-Fast 抛出 401 BusinessException")
    void testProviderMissingTokenFailFast() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("directAccess");

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.UNAUTHORIZED.getCode());
                    assertThat(be.getMessage()).contains("未携带合法 Same-Token 受信凭据");
                });
    }

    @Test
    @DisplayName("服务端入站: 携带伪造或无效凭据时拦截并阻断访问")
    void testProviderInvalidTokenFailFast() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("hackAccess");
        invocation.setAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY, "forged-invalid-token");

        assertThatThrownBy(() -> filter.invoke(invoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.UNAUTHORIZED.getCode());
                    assertThat(be.getMessage()).contains("Same-Token 凭据校验无效或已过期");
                });
    }

    @Test
    @DisplayName("当 check=false 时服务端关闭校验直接放行")
    void testProviderCheckDisabled() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSameToken().setEnabled(true);
        properties.getSameToken().setCheck(false);
        DubboPropertiesHolder.set(properties);

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("openAccess");
        when(invoker.invoke(any())).thenReturn(new AppResponse("open-pass"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("open-pass");
    }

    @Test
    @DisplayName("Injvm 本地内存调用协议豁免 Same-Token 校验")
    void testInjvmBypass() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("injvm://127.0.0.1/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("injvmCall");
        when(invoker.invoke(any())).thenReturn(new AppResponse("injvm-pass"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("injvm-pass");
    }

    @Test
    @DisplayName("配置属性禁用 Same-Token 特性时直接跳过")
    void testFeatureDisabled() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSameToken().setEnabled(false);
        DubboPropertiesHolder.set(properties);

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        when(invoker.invoke(any())).thenReturn(new AppResponse("bypass"));

        filter.invoke(invoker, invocation);
        assertThat(RpcContext.getClientAttachment().getAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY)).isNull();
    }

    interface DemoService {
    }
}
