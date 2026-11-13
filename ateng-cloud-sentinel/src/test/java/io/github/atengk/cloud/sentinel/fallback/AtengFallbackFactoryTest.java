package io.github.atengk.cloud.sentinel.fallback;

import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AtengFallbackFactory} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class AtengFallbackFactoryTest {

    interface UserService {
        String getUserName(Long id);
        int getUserAge(Long id);
        void notifyUser(Long id);
        List<String> listRoles(Long id);
        Collection<String> collectPermissions(Long id);
        Iterable<String> iterateTags(Long id);
        Set<String> listGroupSet(Long id);
        SortedSet<String> listSortedSet(Long id);
        NavigableSet<String> listNavigableSet(Long id);
        Map<String, Object> getUserAttributes(Long id);
        SortedMap<String, Object> getSortedAttributes(Long id);
        NavigableMap<String, Object> getNavigableAttributes(Long id);
        Optional<String> findNickname(Long id);

        default String greetDefault(String name) {
            return "Hello " + name;
        }
    }

    static class DefaultUserFallbackFactory extends AtengFallbackFactory<UserService> {
    }

    static class NullReturningUserFallbackFactory extends AtengFallbackFactory<UserService> {
        @Override
        protected Object handleFallback(Method method, Object[] args, Throwable cause) {
            return null;
        }
    }

    static class CustomUserFallbackFactory extends AtengFallbackFactory<UserService> {
        @Override
        protected Object handleFallback(Method method, Object[] args, Throwable cause) throws Throwable {
            if ("getUserName".equals(method.getName())) {
                return "MockUser";
            }
            if ("listRoles".equals(method.getName())) {
                return Collections.singletonList("ROLE_GUEST");
            }
            if ("getUserAge".equals(method.getName())) {
                return 18;
            }
            if ("notifyUser".equals(method.getName())) {
                return null;
            }
            return super.handleFallback(method, args, cause);
        }
    }

    @Test
    @DisplayName("默认模式：调用接口方法时抛出携带原始因果链的 BusinessException(4202 DEGRADE)")
    void testDefaultFallbackThrowsBusinessException() {
        DefaultUserFallbackFactory factory = new DefaultUserFallbackFactory();
        DegradeException originalCause = new DegradeException("circuit breaker open");

        UserService client = factory.create(originalCause);

        assertThat(client).isNotNull();
        assertThatThrownBy(() -> client.getUserName(1001L))
                .isInstanceOf(BusinessException.class)
                .hasCause(originalCause)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResultCode()).isEqualTo(SentinelResultCode.DEGRADE);
                    assertThat(be.getCode()).isEqualTo(4202);
                    assertThat(be.getMessage()).contains("UserService#getUserName");
                });
    }

    @Test
    @DisplayName("默认模式：原始网络超时异常 SocketTimeoutException 完整保留在 cause 中")
    void testNetworkTimeoutCausePreserved() {
        DefaultUserFallbackFactory factory = new DefaultUserFallbackFactory();
        SocketTimeoutException timeoutException = new SocketTimeoutException("Read timed out");

        UserService client = factory.create(timeoutException);

        assertThatThrownBy(() -> client.getUserAge(1001L))
                .isInstanceOf(BusinessException.class)
                .hasCause(timeoutException);
    }

    @Test
    @DisplayName("Object 基础方法安全调用，不触发熔断降级")
    void testObjectMethodsSafe() {
        DefaultUserFallbackFactory factory = new DefaultUserFallbackFactory();
        UserService client = factory.create(new RuntimeException("test"));

        assertThat(client.toString()).isEqualTo("AtengFallbackProxy<" + UserService.class.getName() + ">");
        assertThat(client.hashCode()).isNotZero();
        assertThat(client.equals(client)).isTrue();
        assertThat(client.equals(null)).isFalse();
    }

    @Test
    @DisplayName("柔性降级：重写 handleFallback 返回自定义保底数据与集合")
    void testCustomFallbackReturnsMockData() {
        CustomUserFallbackFactory factory = new CustomUserFallbackFactory();
        UserService client = factory.create(new DegradeException("degraded"));

        assertThat(client.getUserName(1001L)).isEqualTo("MockUser");
        assertThat(client.listRoles(1001L)).containsExactly("ROLE_GUEST");
        assertThat(client.getUserAge(1001L)).isEqualTo(18);
        client.notifyUser(1001L); // void 返回正常退出
    }

    @Test
    @DisplayName("防御性检查：目标非接口类型抛出异常")
    void testNonInterfaceTargetThrows() {
        assertThatThrownBy(() -> new AtengFallbackFactory<String>(String.class) {})
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be an interface");
    }

    @Test
    @DisplayName("下游普通 BusinessException 原样透传：不覆写为 DEGRADE 且不触发阻断计数")
    void testOrdinaryBusinessExceptionPassthrough() {
        DefaultUserFallbackFactory factory = new DefaultUserFallbackFactory();
        SentinelBlockCounter counter = new SentinelBlockCounter();
        factory.setBlockCounter(counter);

        BusinessException downstreamBiz = new BusinessException(10001, "账户余额不足");
        UserService client = factory.create(downstreamBiz);

        assertThatThrownBy(() -> client.getUserName(1001L))
                .isSameAs(downstreamBiz);
        assertThat(counter.getTotalBlocked()).isZero();
    }

    @Test
    @DisplayName("因果链 BlockException 精准映射：5 种规则分别映射为对应 SentinelResultCode 并源头计数")
    void testBlockExceptionPreciseMappingAndCounter() {
        DefaultUserFallbackFactory factory = new DefaultUserFallbackFactory();
        SentinelBlockCounter counter = new SentinelBlockCounter();
        factory.setBlockCounter(counter);

        assertMappedCode(factory, new FlowException("flow"), SentinelResultCode.FLOW_LIMIT);
        assertMappedCode(factory, new DegradeException("degrade"), SentinelResultCode.DEGRADE);
        assertMappedCode(factory, new ParamFlowException("res", "p1"), SentinelResultCode.PARAM_FLOW_LIMIT);
        assertMappedCode(factory, new SystemBlockException("res", "load"), SentinelResultCode.SYSTEM_BLOCK);
        assertMappedCode(factory, new AuthorityException("res"), SentinelResultCode.AUTHORITY_BLOCK);

        // 反射/代理嵌套包装的 BlockException 同样精准识别
        InvocationTargetException wrapped = new InvocationTargetException(
                new UndeclaredThrowableException(new FlowException("wrapped-flow")));
        assertMappedCode(factory, wrapped, SentinelResultCode.FLOW_LIMIT);

        assertThat(counter.getTotalBlocked()).isEqualTo(6);
        assertThat(counter.getFlowCount()).isEqualTo(2);
        assertThat(counter.getDegradeCount()).isEqualTo(1);
        assertThat(counter.getParamFlowCount()).isEqualTo(1);
        assertThat(counter.getSystemCount()).isEqualTo(1);
        assertThat(counter.getAuthorityCount()).isEqualTo(1);
    }

    private void assertMappedCode(DefaultUserFallbackFactory factory, Throwable cause, SentinelResultCode expected) {
        UserService client = factory.create(cause);
        assertThatThrownBy(() -> client.getUserName(1L))
                .isInstanceOf(BusinessException.class)
                .hasCause(cause)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getResultCode()).isEqualTo(expected);
                    assertThat(be.getCode()).isEqualTo(expected.getCode());
                });
    }

    @Test
    @DisplayName("接口 default 方法直接放行执行，不触发降级逻辑也不计入阻断计数")
    void testInterfaceDefaultMethodPassthrough() {
        DefaultUserFallbackFactory factory = new DefaultUserFallbackFactory();
        SentinelBlockCounter counter = new SentinelBlockCounter();
        factory.setBlockCounter(counter);
        UserService client = factory.create(new SocketTimeoutException("timeout"));

        assertThat(client.greetDefault("Ateng")).isEqualTo("Hello Ateng");
        assertThat(counter.getTotalBlocked()).isZero();
    }

    @Test
    @DisplayName("空安全容器兜底：handleFallback 返回 null 时自动兜底空 List/Collection/Iterable/Set/Map/Optional")
    void testNullSafeContainerDefaults() {
        NullReturningUserFallbackFactory factory = new NullReturningUserFallbackFactory();
        SentinelBlockCounter counter = new SentinelBlockCounter();
        factory.setBlockCounter(counter);
        UserService client = factory.create(new SocketTimeoutException("timeout"));

        assertThat(client.listRoles(1L)).isEmpty();
        assertThat(client.collectPermissions(1L)).isEmpty();
        assertThat(client.iterateTags(1L)).isEmpty();
        assertThat(client.listGroupSet(1L)).isEmpty();
        assertThat(client.listSortedSet(1L)).isEmpty();
        assertThat(client.listNavigableSet(1L)).isEmpty();
        assertThat(client.getUserAttributes(1L)).isEmpty();
        assertThat(client.getSortedAttributes(1L)).isEmpty();
        assertThat(client.getNavigableAttributes(1L)).isEmpty();
        assertThat(client.findNickname(1L)).isEmpty();
        assertThat(client.getUserAge(1L)).isZero();
        assertThat(counter.getTotalBlocked()).isEqualTo(11);
    }

    @Test
    @DisplayName("兼容契约与边界防御：保留 getTargetType、支持 null cause 与已含 SentinelResultCode 的下游异常按原规则计数")
    void testCompatibilityAndNullCauseAndSentinelBusinessCause() {
        DefaultUserFallbackFactory factory = new DefaultUserFallbackFactory();
        assertThat(factory.getTargetType()).isEqualTo(UserService.class);

        SentinelBlockCounter counter = new SentinelBlockCounter();
        factory.setBlockCounter(counter);

        UserService nullCauseClient = factory.create(null);
        assertThatThrownBy(() -> nullCauseClient.getUserName(1L))
                .isInstanceOf(BusinessException.class)
                .hasCause(null)
                .satisfies(ex -> assertThat(((BusinessException) ex).getResultCode()).isEqualTo(SentinelResultCode.DEGRADE));
        assertThat(counter.getDegradeCount()).isEqualTo(1);

        BusinessException downstreamFlowBiz = new BusinessException(SentinelResultCode.FLOW_LIMIT, "downstream-flow");
        UserService flowBizClient = factory.create(downstreamFlowBiz);
        assertThatThrownBy(() -> flowBizClient.getUserName(1L))
                .isInstanceOf(BusinessException.class)
                .isInstanceOf(SentinelBlockTracked.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getResultCode()).isEqualTo(SentinelResultCode.FLOW_LIMIT);
                    assertThat(((SentinelBlockTracked) ex).isSourceCounted()).isTrue();
                });
        assertThat(counter.getFlowCount()).isEqualTo(1);
        assertThat(counter.getTotalBlocked()).isEqualTo(2);
    }
}
