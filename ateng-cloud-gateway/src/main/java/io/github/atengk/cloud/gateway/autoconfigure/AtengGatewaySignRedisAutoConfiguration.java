package io.github.atengk.cloud.gateway.autoconfigure;

import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.provider.InMemoryGatewayNonceValidator;
import io.github.atengk.cloud.gateway.provider.RedisReactiveGatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.ReactiveGatewayNonceValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 网关开放接口响应式 Redis Nonce 防重放自动装配类
 *
 * <p>严格隔离可选依赖 {@link ReactiveStringRedisTemplate}，在 RedisReactiveAutoConfiguration 之后、
 * {@link AtengGatewayAutoConfiguration} 之前装配：仅当容器存在 {@link ReactiveStringRedisTemplate}
 * 且用户未显式提供自定义 {@link ReactiveGatewayNonceValidator} 或 {@link GatewayNonceValidator} Bean 时生效。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
        before = AtengGatewayAutoConfiguration.class
)
@ConditionalOnClass({GlobalFilter.class, ReactiveStringRedisTemplate.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@EnableConfigurationProperties(AtengGatewayProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengGatewaySignRedisAutoConfiguration {

    /**
     * 条件装配基于响应式 Redis 的非阻塞 Nonce 防重放校验器
     *
     * @param redisTemplate 响应式 Redis 字符串模板
     * @param properties    网关配置属性
     * @return RedisReactiveGatewayNonceValidator
     */
    @Bean
    @ConditionalOnBean(ReactiveStringRedisTemplate.class)
    @ConditionalOnMissingBean({ReactiveGatewayNonceValidator.class, GatewayNonceValidator.class})
    @ConditionalOnProperty(prefix = "ateng.cloud.gateway.sign", name = "enabled", havingValue = "true", matchIfMissing = false)
    public ReactiveGatewayNonceValidator redisReactiveGatewayNonceValidator(
            ReactiveStringRedisTemplate redisTemplate,
            AtengGatewayProperties properties) {
        AtengGatewaySignProperties signProps = properties != null && properties.getSign() != null
                ? properties.getSign()
                : new AtengGatewaySignProperties();
        GatewayNonceValidator fallbackValidator = signProps.isNonceFailOpen()
                ? new InMemoryGatewayNonceValidator(signProps.getNonceMaxCapacity())
                : null;
        return new RedisReactiveGatewayNonceValidator(
                redisTemplate,
                signProps.getNoncePrefix(),
                signProps.isNonceFailOpen(),
                fallbackValidator
        );
    }
}
