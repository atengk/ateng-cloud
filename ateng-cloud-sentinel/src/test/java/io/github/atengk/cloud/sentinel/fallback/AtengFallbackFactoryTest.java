package io.github.atengk.cloud.sentinel.fallback;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.List;

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
    }

    static class DefaultUserFallbackFactory extends AtengFallbackFactory<UserService> {
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
}
