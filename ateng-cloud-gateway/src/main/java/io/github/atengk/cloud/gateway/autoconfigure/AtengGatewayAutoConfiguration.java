package io.github.atengk.cloud.gateway.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.handler.GatewayGlobalExceptionHandler;
import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * ateng-cloud 响应式网关自动装配核心配置类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(AtengGatewayProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengGatewayAutoConfiguration {

    /**
     * 注册全局响应式统一异常处理器
     *
     * @param objectMapper Jackson 对象映射器
     * @return GatewayGlobalExceptionHandler
     */
    @Bean
    @ConditionalOnMissingBean
    public GatewayGlobalExceptionHandler gatewayGlobalExceptionHandler(ObjectMapper objectMapper) {
        return new GatewayGlobalExceptionHandler(objectMapper);
    }

    /**
     * 定制 WebFlux 编解码器最大直接内存上限 (载荷防爆硬防线)
     *
     * @param properties 网关配置属性
     * @return CodecCustomizer
     */
    @Bean
    @ConditionalOnMissingBean
    public CodecCustomizer atengGatewayCodecCustomizer(AtengGatewayProperties properties) {
        return configurer -> configurer.defaultCodecs()
                .maxInMemorySize((int) properties.getMaxInMemorySize().toBytes());
    }
}
