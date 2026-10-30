package io.github.atengk.cloud.dubbo.security;

import io.github.atengk.cloud.dubbo.autoconfigure.AtengDubboAutoConfiguration;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboSecurityProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboSerializeProperties;
import org.apache.dubbo.common.utils.SerializeSecurityManager;
import org.apache.dubbo.rpc.model.FrameworkModel;
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
    @DisplayName("验证自动装配将配置的自定义包前缀成功注册至 SerializeSecurityManager")
    void testSerializeSecurityManagerRegistration() throws Exception {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSecurity().getSerialize().setAllowPackages(List.of(
                "com.company.order.dto",
                "com.company.payment.vo"
        ));

        // 初始化 AtengDubboAutoConfiguration 触发注册
        new AtengDubboAutoConfiguration(properties);

        SerializeSecurityManager securityManager = FrameworkModel.defaultModel()
                .getBeanFactory()
                .getBean(SerializeSecurityManager.class);
        assertThat(securityManager).isNotNull();

        // 验证 allowedPrefix 包含注册的自定义包
        Method method = SerializeSecurityManager.class.getDeclaredMethod("getAllowedPrefix");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> allowedPrefixes = (Set<String>) method.invoke(securityManager);

        assertThat(allowedPrefixes)
                .contains("com.company.order.dto", "com.company.payment.vo");
    }

    @Test
    @DisplayName("当未配置自定义白名单包时安全防御不抛异常")
    void testSerializeSecurityWithEmptyConfig() {
        AtengDubboProperties properties = new AtengDubboProperties();
        properties.getSecurity().getSerialize().setAllowPackages(null);

        // 验证防御性逻辑不抛出 NPE 或异常
        new AtengDubboAutoConfiguration(properties);
    }
}
