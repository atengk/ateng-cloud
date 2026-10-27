package io.github.atengk.cloud.nacos.guard;

import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.type.MethodMetadata;

/**
 * 业务单例不可变性架构守卫 (Bean Immutability Guard)
 *
 * <p>微服务专属架构铁律：严禁在承载实际业务逻辑与事务的单例 Bean（{@code @Service}, {@code @Repository} 等）上标注 {@code @RefreshScope}。
 * 本守卫在 Spring 容器启动阶段执行全量扫描，凡发现业务单例挂载 {@code @RefreshScope} 立即抛出异常快速失败（Fail-Fast），
 * 强行杜绝 Bean 物理销毁导致事务断裂、在途请求熔断与分布式锁悬挂。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class SingletonImmutabilityGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(SingletonImmutabilityGuard.class);

    public static final String REFRESH_SCOPE_NAME = "refresh";
    public static final String REFRESH_SCOPE_ANNOTATION = "org.springframework.cloud.context.config.annotation.RefreshScope";

    private final AtengNacosProperties properties;
    private Environment environment;

    public SingletonImmutabilityGuard() {
        this.properties = null;
    }

    public SingletonImmutabilityGuard(AtengNacosProperties properties) {
        this.properties = properties;
    }

    public SingletonImmutabilityGuard(Environment environment) {
        this.properties = null;
        this.environment = environment;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        boolean enforce = true;
        if (properties != null && properties.getConfig() != null) {
            enforce = properties.getConfig().isEnforceBeanImmutability();
        } else if (environment != null) {
            Boolean flag = environment.getProperty("ateng.cloud.nacos.config.enforce-bean-immutability", Boolean.class);
            if (flag != null) {
                enforce = flag;
            }
        }
        if (!enforce) {
            return;
        }

        String[] beanNames = beanFactory.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
            if (isRefreshScoped(beanDefinition)) {
                checkBusinessSingletonViolation(beanName, beanDefinition);
            }
        }
    }

    private boolean isRefreshScoped(BeanDefinition bd) {
        if (REFRESH_SCOPE_NAME.equalsIgnoreCase(bd.getScope())) {
            return true;
        }
        if (bd instanceof AnnotatedBeanDefinition abd) {
            if (abd.getMetadata().hasAnnotation(REFRESH_SCOPE_ANNOTATION)) {
                return true;
            }
            MethodMetadata factoryMethodMetadata = abd.getFactoryMethodMetadata();
            if (factoryMethodMetadata != null && factoryMethodMetadata.isAnnotated(REFRESH_SCOPE_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }

    private void checkBusinessSingletonViolation(String beanName, BeanDefinition bd) {
        if (bd instanceof AnnotatedBeanDefinition abd) {
            boolean isService = abd.getMetadata().hasAnnotation("org.springframework.stereotype.Service");
            boolean isRepository = abd.getMetadata().hasAnnotation("org.springframework.stereotype.Repository");
            if (isService || isRepository) {
                String role = isService ? "@Service" : "@Repository";
                String errorMsg = String.format(
                        "【架构红线阻断 (Bean Immutability Invariant)】检测到单例业务 Bean '%s' 标注了 @RefreshScope 与 %s！" +
                        "严禁在承载实际业务逻辑与事务的单例 Bean 上使用 @RefreshScope，避免高并发下物理销毁导致在途事务断裂与锁悬挂。" +
                        "微服务配置热更必须严格收敛于 @ConfigurationProperties 纯属性 POJO 增量重绑。",
                        beanName, role);
                log.error(errorMsg);
                throw new IllegalStateException(errorMsg);
            }
        }
    }
}
