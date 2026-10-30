package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dubbo 服务端参数格式快速失败与异常转译过滤器单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboValidationFilterTest {

    private final DubboValidationFilter filter = new DubboValidationFilter();

    @BeforeEach
    void setUp() {
        DubboPropertiesHolder.set(new AtengDubboProperties());
    }

    @AfterEach
    void tearDown() {
        DubboPropertiesHolder.reset();
    }

    @Test
    @DisplayName("Provider 端入站: 合法参数正常通过快速失败校验防卫")
    void testProviderValidParamPasses() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("createUser");
        ValidUserDto validUser = new ValidUserDto("alice", 25);
        invocation.setArguments(new Object[]{validUser});

        when(invoker.invoke(any())).thenReturn(new AppResponse("created"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("created");
    }

    @Test
    @DisplayName("Provider 端入站: 非法参数触发 Fail-Fast 拦截并返回 BAD_REQUEST BusinessException")
    void testProviderInvalidParamFailFast() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("createUser");
        // name 为空且 age < 18
        ValidUserDto invalidUser = new ValidUserDto("", 10);
        invocation.setArguments(new Object[]{invalidUser});

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.hasException()).isTrue();
        assertThat(result.getException()).isInstanceOf(BusinessException.class);

        BusinessException be = (BusinessException) result.getException();
        assertThat(be.getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
        assertThat(be.getMessage()).contains("参数校验失败");
    }

    @Test
    @DisplayName("跳过基础类型与 java.lang/java.util 类型的参数校验")
    void testSkipPrimitiveAndJdkTypes() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("queryById");
        invocation.setArguments(new Object[]{1001L, "admin-query"});

        when(invoker.invoke(any())).thenReturn(new AppResponse("query-ok"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("query-ok");
    }

    @Test
    @DisplayName("Provider 端 onResponse 拦截 ConstraintViolationException 并转译为 BusinessException")
    void testOnResponseConstraintViolationException() {
        Invoker<?> invoker = mock(Invoker.class);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("createUser");

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
    }

    @Test
    @DisplayName("Consumer 消费端出站时跳过 Provider 参数校验防卫")
    void testConsumerSideSkipsValidation() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=consumer");
        when(invoker.getUrl()).thenReturn(url);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("createUser");
        ValidUserDto invalidUser = new ValidUserDto("", 10);
        invocation.setArguments(new Object[]{invalidUser});

        when(invoker.invoke(any())).thenReturn(new AppResponse("consumer-ok"));

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.getValue()).isEqualTo("consumer-ok");
    }

    interface DemoService {
        void createUser(ValidUserDto dto);

        void queryById(Long id, String tag);
    }

    static class ValidUserDto {
        @NotBlank(message = "用户名不能为空")
        private String name;

        @Min(value = 18, message = "年龄不能小于 18")
        private Integer age;

        public ValidUserDto(String name, Integer age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public Integer getAge() {
            return age;
        }
    }
}
