package io.github.atengk.cloud.traffic.aspect;

import io.github.atengk.cloud.traffic.annotation.TrafficTag;
import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明式流量染色切面 TrafficTagAspect 单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class TrafficTagAspectTest {

    private MockEnvironment environment;
    private TrafficTagAspect aspect;

    @BeforeEach
    void setUp() {
        TrafficContextHolder.clear();
        environment = new MockEnvironment();
        environment.setProperty("canary.target.tag", "canary-from-prop");
        aspect = new TrafficTagAspect(environment);
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
        MDC.remove(TrafficConstants.MDC_TRAFFIC_TAG);
    }

    @Test
    @DisplayName("测试方法级 @TrafficTag 正常绑定与 finally 确定性清理")
    void testMethodLevelTrafficTagBindingAndCleanup() {
        TestService proxy = createProxy(new TestServiceImpl());

        assertThat(TrafficContextHolder.getTag()).isNull();
        String activeTag = proxy.executeWithTag();

        assertThat(activeTag).isEqualTo("method-canary");
        // 方法结束后验证上下文与 MDC 彻底恢复清空
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("测试类级 @TrafficTag 标注生效")
    void testClassLevelTrafficTagBinding() {
        ClassLevelTaggedService proxy = createProxy(new ClassLevelTaggedServiceImpl());

        assertThat(TrafficContextHolder.getTag()).isNull();
        String activeTag = proxy.sayHello();

        assertThat(activeTag).isEqualTo("class-canary");
        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();
    }

    @Test
    @DisplayName("测试方法级 @TrafficTag 优先级高于类级")
    void testMethodLevelOverridesClassLevel() {
        ClassLevelTaggedService proxy = createProxy(new ClassLevelTaggedServiceImpl());

        assertThat(TrafficContextHolder.getTag()).isNull();
        String activeTag = proxy.sayCustom();

        assertThat(activeTag).isEqualTo("method-override");
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("测试 ${...} 占位符解析")
    void testPlaceholderResolution() {
        TestService proxy = createProxy(new TestServiceImpl());

        String activeTag = proxy.executeWithPlaceholder();
        assertThat(activeTag).isEqualTo("canary-from-prop");
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("测试 override=false 且已有标签时遵循继承")
    void testOverrideFalseInheritsExistingTag() {
        TestService proxy = createProxy(new TestServiceImpl());

        TrafficContextHolder.setTag("caller-tag");
        String activeTag = proxy.executeWithOverrideFalse();

        // 应当继承 caller-tag 而非注解中的 override-false-tag
        assertThat(activeTag).isEqualTo("caller-tag");
        assertThat(TrafficContextHolder.getTag()).isEqualTo("caller-tag");
    }

    @Test
    @DisplayName("测试 override=false 且无标签时正常绑定新标签")
    void testOverrideFalseBindsWhenNoExistingTag() {
        TestService proxy = createProxy(new TestServiceImpl());

        assertThat(TrafficContextHolder.getTag()).isNull();
        String activeTag = proxy.executeWithOverrideFalse();

        assertThat(activeTag).isEqualTo("override-false-tag");
        assertThat(TrafficContextHolder.getTag()).isNull();
    }

    @Test
    @DisplayName("测试 override=true 强制覆盖上级标签并在 exit 时还原上级上下文")
    void testOverrideTrueReplacesAndRestores() {
        TestService proxy = createProxy(new TestServiceImpl());

        TrafficContextHolder.setTag("caller-tag");
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isEqualTo("caller-tag");

        String activeTag = proxy.executeWithTag();
        assertThat(activeTag).isEqualTo("method-canary");

        // 验证方法退出后 100% 还原为 caller-tag
        assertThat(TrafficContextHolder.getTag()).isEqualTo("caller-tag");
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isEqualTo("caller-tag");
    }

    @Test
    @DisplayName("测试被标注方法抛出异常时在 finally 中依然 100% 还原上下文")
    void testExceptionRestoresContextInFinally() {
        TestService proxy = createProxy(new TestServiceImpl());

        TrafficContextHolder.setTag("caller-tag");

        assertThatThrownBy(proxy::executeThrowingException)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated business error");

        // 校验异常后上级上下文依然完全还原
        assertThat(TrafficContextHolder.getTag()).isEqualTo("caller-tag");
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isEqualTo("caller-tag");
    }

    @Test
    @DisplayName("测试多层嵌套调用依次还原")
    void testNestedCallsRestoration() {
        NestedService proxy = createProxy(new NestedServiceImpl());

        assertThat(TrafficContextHolder.getTag()).isNull();
        proxy.outerCall();

        assertThat(TrafficContextHolder.getTag()).isNull();
        assertThat(MDC.get(TrafficConstants.MDC_TRAFFIC_TAG)).isNull();
    }

    @SuppressWarnings("unchecked")
    private <T> T createProxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }

    // ================= 测试桩类与接口 =================

    interface TestService {
        String executeWithTag();
        String executeWithPlaceholder();
        String executeWithOverrideFalse();
        void executeThrowingException();
    }

    static class TestServiceImpl implements TestService {

        @Override
        @TrafficTag("method-canary")
        public String executeWithTag() {
            return TrafficContextHolder.getTag();
        }

        @Override
        @TrafficTag("${canary.target.tag}")
        public String executeWithPlaceholder() {
            return TrafficContextHolder.getTag();
        }

        @Override
        @TrafficTag(tag = "override-false-tag", override = false)
        public String executeWithOverrideFalse() {
            return TrafficContextHolder.getTag();
        }

        @Override
        @TrafficTag("throwing-tag")
        public void executeThrowingException() {
            assertThat(TrafficContextHolder.getTag()).isEqualTo("throwing-tag");
            throw new IllegalStateException("Simulated business error");
        }
    }

    @TrafficTag("class-canary")
    interface ClassLevelTaggedService {
        String sayHello();
        String sayCustom();
    }

    @TrafficTag("class-canary")
    static class ClassLevelTaggedServiceImpl implements ClassLevelTaggedService {

        @Override
        public String sayHello() {
            return TrafficContextHolder.getTag();
        }

        @Override
        @TrafficTag("method-override")
        public String sayCustom() {
            return TrafficContextHolder.getTag();
        }
    }

    interface NestedService {
        void outerCall();
        void innerCall();
    }

    static class NestedServiceImpl implements NestedService {

        private NestedService self;

        public void setSelf(NestedService self) {
            this.self = self;
        }

        @Override
        @TrafficTag("outer-tag")
        public void outerCall() {
            assertThat(TrafficContextHolder.getTag()).isEqualTo("outer-tag");
            if (self != null) {
                self.innerCall();
            }
            assertThat(TrafficContextHolder.getTag()).isEqualTo("outer-tag");
        }

        @Override
        @TrafficTag("inner-tag")
        public void innerCall() {
            assertThat(TrafficContextHolder.getTag()).isEqualTo("inner-tag");
        }
    }
}
