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
import org.apache.dubbo.rpc.service.GenericService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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

    @Test
    @DisplayName("服务端入站: 精确匹配 excludeInterfaces 白名单接口时豁免 Same-Token 校验，非白名单接口仍阻断")
    void testProviderExcludeInterfacesExactMatch() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSameToken().setEnabled(true);
        properties.getSameToken().setCheck(true);
        properties.getSameToken().setExcludeInterfaces(List.of(OpenCallbackService.class.getName()));
        DubboPropertiesHolder.set(properties);

        Invoker<?> openInvoker = mock(Invoker.class);
        URL openUrl = URL.valueOf("tri://127.0.0.1:50051/" + OpenCallbackService.class.getName() + "?side=provider");
        when(openInvoker.getUrl()).thenReturn(openUrl);
        when(openInvoker.getInterface()).thenReturn((Class) OpenCallbackService.class);
        when(openInvoker.invoke(any())).thenReturn(new AppResponse("open-callback-ok"));

        RpcInvocation openInvocation = new RpcInvocation();
        openInvocation.setMethodName("notify");
        Result openResult = filter.invoke(openInvoker, openInvocation);
        assertThat(openResult.getValue()).isEqualTo("open-callback-ok");

        // 未命中白名单的接口即使在同一配置下也必须严格阻断
        Invoker<?> secretInvoker = mock(Invoker.class);
        URL secretUrl = URL.valueOf("tri://127.0.0.1:50051/" + DemoService.class.getName() + "?side=provider");
        when(secretInvoker.getUrl()).thenReturn(secretUrl);
        when(secretInvoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation secretInvocation = new RpcInvocation();
        secretInvocation.setMethodName("directAccess");

        assertThatThrownBy(() -> filter.invoke(secretInvoker, secretInvocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(CommonResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("服务端入站: 通配符匹配 excludeInterfaces 白名单模式时豁免 Same-Token 校验")
    void testProviderExcludeInterfacesWildcardMatch() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSameToken().setEnabled(true);
        properties.getSameToken().setCheck(true);
        properties.getSameToken().setExcludeInterfaces(List.of("*Open*Service"));
        DubboPropertiesHolder.set(properties);

        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/" + OpenCallbackService.class.getName() + "?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) OpenCallbackService.class);
        when(invoker.invoke(any())).thenReturn(new AppResponse("wildcard-pass"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("healthCheck");

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("wildcard-pass");
    }

    @Test
    @DisplayName("服务端入站: 过滤白名单空值与空白字符，且无法解析接口名时严禁被 *Service 通配符误放行")
    void testProviderExcludeInterfacesSanitizationAndFailClosedOnUnknownInterface() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSameToken().setEnabled(true);
        properties.getSameToken().setCheck(true);
        properties.getSameToken().setExcludeInterfaces(Arrays.asList(
                null,
                "   ",
                "\t",
                "  " + OpenCallbackService.class.getName() + "  ",
                "*Service"
        ));
        DubboPropertiesHolder.set(properties);

        assertThat(properties.getSameToken().getExcludeInterfaces())
                .containsExactly(OpenCallbackService.class.getName(), "*Service");
        assertThatThrownBy(() -> properties.getSameToken().getExcludeInterfaces().add("hack"))
                .isInstanceOf(UnsupportedOperationException.class);

        // 无法解析真实接口名时 Fail-Closed，绝不因回退名称匹配 "*Service" 绕过零信任校验
        Invoker<?> unknownInvoker = mock(Invoker.class);
        when(unknownInvoker.getUrl()).thenReturn(null);
        when(unknownInvoker.getInterface()).thenReturn(null);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("blindCall");

        assertThatThrownBy(() -> filter.invoke(unknownInvoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(CommonResultCode.UNAUTHORIZED.getCode()));

        // URL 缺失 serviceInterface 时同样 Fail-Closed
        Invoker<?> blankServiceUrlInvoker = mock(Invoker.class);
        URL blankServiceUrl = URL.valueOf("tri://127.0.0.1:50051?side=provider");
        when(blankServiceUrlInvoker.getUrl()).thenReturn(blankServiceUrl);
        when(blankServiceUrlInvoker.getInterface()).thenReturn(null);

        assertThatThrownBy(() -> filter.invoke(blankServiceUrlInvoker, invocation))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(CommonResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("服务端入站: GenericService 泛化提供者优先依据 URL serviceInterface 匹配白名单")
    void testProviderExcludeInterfacesGenericServiceUsesUrlServiceInterface() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSameToken().setEnabled(true);
        properties.getSameToken().setCheck(true);
        properties.getSameToken().setExcludeInterfaces(List.of(OpenCallbackService.class.getName()));
        DubboPropertiesHolder.set(properties);

        Invoker<?> genericInvoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/" + OpenCallbackService.class.getName() + "?side=provider");
        when(genericInvoker.getUrl()).thenReturn(url);
        when(genericInvoker.getInterface()).thenReturn((Class) GenericService.class);
        when(genericInvoker.invoke(any())).thenReturn(new AppResponse("generic-pass"));

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("$invoke");

        Result result = filter.invoke(genericInvoker, invocation);
        assertThat(result.getValue()).isEqualTo("generic-pass");
    }

    interface DemoService {
    }

    interface OpenCallbackService {
    }
}
