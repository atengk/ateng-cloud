package io.github.atengk.cloud.nacos.guard;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SingletonImmutabilityGuard} 业务单例不可变性架构守卫单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
class SingletonImmutabilityGuardTest {

    @Service
    @RefreshScope
    static class FaultyServiceBean {
    }

    @Repository
    @RefreshScope
    static class FaultyRepositoryBean {
    }

    @Component
    @RefreshScope
    static class FaultyComponentBean {
    }

    @Service
    static class ValidServiceBean {
    }

    @RefreshScope
    static class PlainRefreshScopeBean {
    }

    @Test
    @DisplayName("架构红线拦截：严禁在 @Service 业务单例 Bean 上标注 @RefreshScope")
    void testServiceWithRefreshScopeBlocked() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("faultyServiceBean", new AnnotatedGenericBeanDefinition(FaultyServiceBean.class));

        AtengNacosProperties properties = new AtengNacosProperties();
        SingletonImmutabilityGuard guard = new SingletonImmutabilityGuard(properties);

        assertThatThrownBy(() -> guard.postProcessBeanFactory(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bean Immutability Invariant")
                .hasMessageContaining("faultyServiceBean")
                .hasMessageContaining("@Service");
    }

    @Test
    @DisplayName("架构红线拦截：严禁在 @Repository 业务单例 Bean 上标注 @RefreshScope")
    void testRepositoryWithRefreshScopeBlocked() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("faultyRepositoryBean", new AnnotatedGenericBeanDefinition(FaultyRepositoryBean.class));

        AtengNacosProperties properties = new AtengNacosProperties();
        SingletonImmutabilityGuard guard = new SingletonImmutabilityGuard(properties);

        assertThatThrownBy(() -> guard.postProcessBeanFactory(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("faultyRepositoryBean")
                .hasMessageContaining("@Repository");
    }

    @Test
    @DisplayName("正常单例业务 Bean：未标注 @RefreshScope 正常平滑通过")
    void testValidServiceBeanPasses() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("validServiceBean", new AnnotatedGenericBeanDefinition(ValidServiceBean.class));

        AtengNacosProperties properties = new AtengNacosProperties();
        SingletonImmutabilityGuard guard = new SingletonImmutabilityGuard(properties);

        assertThatCode(() -> guard.postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("架构红线拦截：严禁在 @Component 业务单例 Bean 上标注 @RefreshScope")
    void testComponentWithRefreshScopeBlocked() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("faultyComponentBean", new AnnotatedGenericBeanDefinition(FaultyComponentBean.class));

        AtengNacosProperties properties = new AtengNacosProperties();
        SingletonImmutabilityGuard guard = new SingletonImmutabilityGuard(properties);

        assertThatThrownBy(() -> guard.postProcessBeanFactory(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("faultyComponentBean")
                .hasMessageContaining("@Component");
    }

    @Test
    @DisplayName("非业务构造型 Bean：无 @Component/@Service 构造型注记的类正常允许通过")
    void testPlainRefreshScopeBeanPasses() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("plainBean", new AnnotatedGenericBeanDefinition(PlainRefreshScopeBean.class));

        AtengNacosProperties properties = new AtengNacosProperties();
        SingletonImmutabilityGuard guard = new SingletonImmutabilityGuard(properties);

        assertThatCode(() -> guard.postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("配置禁用逃生舱：当 enforceBeanImmutability 为 false 时静默跳过")
    void testGuardDisabledPasses() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("faultyServiceBean", new AnnotatedGenericBeanDefinition(FaultyServiceBean.class));

        AtengNacosProperties properties = new AtengNacosProperties();
        properties.getConfig().setEnforceBeanImmutability(false);
        SingletonImmutabilityGuard guard = new SingletonImmutabilityGuard(properties);

        assertThatCode(() -> guard.postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("通过 Environment 构造支持配置禁用逃生舱")
    void testGuardWithEnvironmentDisabledPasses() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("faultyServiceBean", new AnnotatedGenericBeanDefinition(FaultyServiceBean.class));

        org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
        environment.setProperty("ateng.cloud.nacos.config.enforce-bean-immutability", "false");
        SingletonImmutabilityGuard guard = new SingletonImmutabilityGuard(environment);

        assertThatCode(() -> guard.postProcessBeanFactory(beanFactory)).doesNotThrowAnyException();
    }
}
