package io.github.atengk.cloud.dubbo.security;

import io.github.atengk.cloud.dubbo.autoconfigure.AtengDubboAutoConfiguration;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboSecurityProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboSerializeProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.utils.SerializeSecurityManager;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dubbo 序列化安全防护与自定义白名单扩展单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class DubboSerializeSecurityTest {

    @AfterEach
    void tearDown() {
        DubboPropertiesHolder.reset();
    }

    @Test
    @DisplayName("验证配置属性正确承载自定义白名单包列表")
    void testSerializePropertiesBinding() {
        AtengDubboProperties properties = new AtengDubboProperties();
        assertThat(properties.getSecurity()).isNotNull();
        assertThat(properties.getSecurity().getSerialize()).isNotNull();

        AtengDubboSerializeProperties serializeProps = properties.getSecurity().getSerialize();
        serializeProps.setAllowPackages(List.of("com.company.biz.dto", "com.company.biz.vo"));

        assertThat(serializeProps.getAllowPackages())
                .containsExactly("com.company.biz.dto", "com.company.biz.vo");
    }

    @Test
    @DisplayName("验证自动装配将框架基线包与自定义包前缀成功合并注册至 SerializeSecurityManager")
    void testSerializeSecurityManagerRegistration() throws Exception {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSecurity().getSerialize().setAllowPackages(List.of(
                "com.company.order.dto",
                "com.company.payment.vo"
        ));

        // 触发定制器执行反序列化白名单安全合并注册
        AtengDubboAutoConfiguration autoConfig = new AtengDubboAutoConfiguration(properties);
        autoConfig.atengDubboApplicationConfigCustomizer(properties).afterSingletonsInstantiated();

        SerializeSecurityManager securityManager = FrameworkModel.defaultModel()
                .getBeanFactory()
                .getBean(SerializeSecurityManager.class);
        assertThat(securityManager).isNotNull();

        // 验证 allowedPrefix 包含框架基线包与注册的自定义业务包
        Method method = SerializeSecurityManager.class.getDeclaredMethod("getAllowedPrefix");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> allowedPrefixes = (Set<String>) method.invoke(securityManager);

        assertThat(allowedPrefixes)
                .contains("io.github.atengk.", "com.company.order.dto", "com.company.payment.vo");
    }

    @Test
    @DisplayName("当未配置自定义白名单包时安全防御不抛异常且包含框架基线包")
    void testSerializeSecurityWithEmptyConfig() throws Exception {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSecurity().getSerialize().setAllowPackages(null);

        // 验证防御性逻辑不抛出 NPE 或异常，并确保基线包已注册
        AtengDubboAutoConfiguration autoConfig = new AtengDubboAutoConfiguration(properties);
        autoConfig.atengDubboApplicationConfigCustomizer(properties).afterSingletonsInstantiated();

        SerializeSecurityManager securityManager = FrameworkModel.defaultModel()
                .getBeanFactory()
                .getBean(SerializeSecurityManager.class);
        assertThat(securityManager).isNotNull();

        Method method = SerializeSecurityManager.class.getDeclaredMethod("getAllowedPrefix");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> allowedPrefixes = (Set<String>) method.invoke(securityManager);

        assertThat(allowedPrefixes).contains("io.github.atengk.");
    }
}
