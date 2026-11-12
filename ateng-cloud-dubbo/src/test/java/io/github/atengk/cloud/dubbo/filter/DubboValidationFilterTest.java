package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.validation.ValidList;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    @DisplayName("Provider 端入站: List 集合中任一元素违反 JSR-303 约束时立即短路拦截并返回 BAD_REQUEST BusinessException")
    void testProviderListElementInvalidFailFast() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("batchCreateUsers");
        List<ValidUserDto> batch = Arrays.asList(
                new ValidUserDto("alice", 25),
                null,
                new ValidUserDto("", 12)
        );
        invocation.setArguments(new Object[]{batch});

        Result result = filter.invoke(invoker, invocation);

        verify(invoker, never()).invoke(any());
        assertThat(result.hasException()).isTrue();
        assertThat(result.getException()).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) result.getException();
        assertThat(be.getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
        assertThat(be.getMessage()).contains("参数校验失败").contains("[2]");
    }

    @Test
    @DisplayName("Provider 端入站: 合法 ValidList 与数组批量入参正常放行穿透")
    void testProviderValidBatchListAndArrayPasses() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);
        when(invoker.invoke(any())).thenReturn(new AppResponse("batch-ok"));

        ValidList<ValidUserDto> validList = ValidList.of(
                new ValidUserDto("alice", 25),
                new ValidUserDto("bob", 30)
        );
        RpcInvocation listInvocation = new RpcInvocation();
        listInvocation.setMethodName("batchCreateUsers");
        listInvocation.setArguments(new Object[]{validList});

        assertThat(filter.invoke(invoker, listInvocation).getValue()).isEqualTo("batch-ok");

        ValidUserDto[] arrayArgs = new ValidUserDto[]{
                new ValidUserDto("charlie", 22),
                null,
                new ValidUserDto("david", 40)
        };
        RpcInvocation arrayInvocation = new RpcInvocation();
        arrayInvocation.setMethodName("batchCreateUsersArray");
        arrayInvocation.setArguments(new Object[]{arrayArgs});

        assertThat(filter.invoke(invoker, arrayInvocation).getValue()).isEqualTo("batch-ok");
    }

    @Test
    @DisplayName("Provider 端入站: ValidList、Set、Map 值、Object[] 数组与嵌套容器非法元素均执行级联短路拦截")
    void testProviderContainerVariantsInvalidFailFast() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);

        // 1. ValidList 非法元素
        ValidList<ValidUserDto> invalidValidList = ValidList.of(
                new ValidUserDto("alice", 25),
                new ValidUserDto("bob", 15)
        );
        Result validListResult = filter.invoke(invoker, buildInvocation("batchCreateUsers", new Object[]{invalidValidList}));
        assertThat(validListResult.getException()).isInstanceOf(BusinessException.class);
        BusinessException validListBe = (BusinessException) validListResult.getException();
        assertThat(validListBe.getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
        assertThat(validListBe.getMessage()).contains("[1].age");

        // 2. Set 容器非法元素
        Set<ValidUserDto> invalidSet = new LinkedHashSet<>(Arrays.asList(
                new ValidUserDto("alice", 25),
                new ValidUserDto(" ", 20)
        ));
        Result setResult = filter.invoke(invoker, buildInvocation("batchCreateUsersSet", new Object[]{invalidSet}));
        assertThat(setResult.getException()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) setResult.getException()).getMessage()).contains("[1].name");

        // 3. Map 值集合非法元素
        Map<String, ValidUserDto> invalidMap = new LinkedHashMap<>();
        invalidMap.put("normal", new ValidUserDto("alice", 25));
        invalidMap.put("empty", null);
        invalidMap.put("vip", new ValidUserDto("", 25));
        Result mapResult = filter.invoke(invoker, buildInvocation("batchCreateUsersMap", new Object[]{invalidMap}));
        assertThat(mapResult.getException()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) mapResult.getException()).getMessage()).contains("[vip].name");

        // 4. Object[] 对象数组非法元素
        ValidUserDto[] invalidArray = new ValidUserDto[]{
                new ValidUserDto("alice", 25),
                new ValidUserDto("bob", 12)
        };
        Result arrayResult = filter.invoke(invoker, buildInvocation("batchCreateUsersArray", new Object[]{invalidArray}));
        assertThat(arrayResult.getException()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) arrayResult.getException()).getMessage()).contains("[1].age");

        // 5. 嵌套集合 List<List<ValidUserDto>> 非法元素
        List<List<ValidUserDto>> nestedList = List.of(
                List.of(new ValidUserDto("alice", 25)),
                List.of(new ValidUserDto("", 25))
        );
        Result nestedResult = filter.invoke(invoker, buildInvocation("batchCreateNested", new Object[]{nestedList}));
        assertThat(nestedResult.getException()).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) nestedResult.getException()).getMessage()).contains("[1][0].name");

        verify(invoker, never()).invoke(any());
    }

    @Test
    @DisplayName("Provider 端入站: 原生数组、纯标量容器、空容器及自引用环路集合安全跳过不死循环")
    void testProviderSkipsPrimitiveArraysScalarContainersAndCyclicContainers() {
        Invoker<?> invoker = mock(Invoker.class);
        URL url = URL.valueOf("tri://127.0.0.1:50051/io.github.atengk.cloud.dubbo.api.DemoService?side=provider");
        when(invoker.getUrl()).thenReturn(url);
        when(invoker.getInterface()).thenReturn((Class) DemoService.class);
        when(invoker.invoke(any())).thenReturn(new AppResponse("safe-ok"));

        List<Object> cyclic = new ArrayList<>();
        cyclic.add(new ValidUserDto("alice", 25));
        cyclic.add(cyclic);

        RpcInvocation invocation = buildInvocation("mixedScalars", new Object[]{
                new int[]{1, 2, 3},
                List.of("a", "b"),
                Collections.emptyMap(),
                cyclic
        });

        Result result = filter.invoke(invoker, invocation);
        assertThat(result.hasException()).isFalse();
        assertThat(result.getValue()).isEqualTo("safe-ok");
    }

    private RpcInvocation buildInvocation(String methodName, Object[] args) {
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName(methodName);
        invocation.setArguments(args);
        return invocation;
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
