package io.github.atengk.cloud.gateway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.cloud.gateway.autoconfigure.AtengGatewayAutoConfiguration;
import io.github.atengk.cloud.gateway.autoconfigure.AtengGatewaySignRedisAutoConfiguration;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.filter.GatewaySignGuardFilter;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.ReactiveGatewayNonceValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 响应式 Redis Nonce 防重放校验器与四阶自动装配优先级测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("响应式 Redis Nonce 校验器与四阶装配测试")
class RedisReactiveGatewayNonceValidatorTest {

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengGatewaySignRedisAutoConfiguration.class,
                    AtengGatewayAutoConfiguration.class
            ))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues(
                    "ateng.cloud.gateway.sign.enabled=true",
                    "ateng.cloud.gateway.security.enabled=false",
                    "ateng.cloud.gateway.dynamic-route.enabled=false",
                    "ateng.cloud.gateway.traffic.enabled=false",
                    "ateng.cloud.gateway.rate-limiter.enabled=false",
                    "ateng.cloud.gateway.openapi.enabled=false"
            );

    @Test
    @DisplayName("Redis 原子 setIfAbsent 键按 appId 隔离且 TTL 为 2 倍阈值，重放返回 false")
    @SuppressWarnings("unchecked")
    void testRedisAtomicSetIfAbsentAndAppIsolation() {
        ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = Mockito.mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("ateng:gateway:sign:nonce:appA:nonce-1"), eq("1"), eq(Duration.ofMinutes(10))))
                .thenReturn(Mono.just(true), Mono.just(false));
        when(valueOps.setIfAbsent(eq("ateng:gateway:sign:nonce:appB:nonce-1"), eq("1"), eq(Duration.ofMinutes(10))))
                .thenReturn(Mono.just(true));

        RedisReactiveGatewayNonceValidator validator = new RedisReactiveGatewayNonceValidator(
                redisTemplate, "ateng:gateway:sign:nonce:", false, null);

        assertTrue(Boolean.TRUE.equals(validator.validateAndStore("appA", "nonce-1", Duration.ofMinutes(5)).block()));
        assertFalse(Boolean.TRUE.equals(validator.validateAndStore("appA", "nonce-1", Duration.ofMinutes(5)).block()));
        assertTrue(Boolean.TRUE.equals(validator.validateAndStore("appB", "nonce-1", Duration.ofMinutes(5)).block()));
    }

    @Test
    @DisplayName("Redis 异常默认 Fail-Closed 返回 503，开启 nonceFailOpen=true 时降级有界内存防重放")
    @SuppressWarnings("unchecked")
    void testRedisErrorFailClosedAndFailOpenFallback() throws Exception {
        ReactiveStringRedisTemplate redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = Mockito.mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new RuntimeException("redis down")));

        // 1. 默认 Fail-Closed -> 抛出 503 BusinessException，过滤器返回 503
        RedisReactiveGatewayNonceValidator failClosedValidator = new RedisReactiveGatewayNonceValidator(
                redisTemplate, "ateng:gateway:sign:nonce:", false, null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> failClosedValidator.validateAndStore("appA", "n1", Duration.ofMinutes(5)).block());
        assertEquals(503, ex.getCode());

        AtengGatewaySignProperties props = new AtengGatewaySignProperties();
        props.setEnabled(true);
        props.setSecretKey("sec-1");
        GatewaySignGuardFilter failClosedFilter = new GatewaySignGuardFilter(
                props, new PropertiesGatewaySignKeyProvider(props), failClosedValidator, List.of(), new ObjectMapper());
        String ts = String.valueOf(System.currentTimeMillis());
        String canonical = "appId=appA&nonce=n-503&timestamp=" + ts;
        MockServerWebExchange webEx = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/open/ping")
                        .header(GatewayConstants.X_APP_ID, "appA")
                        .header(GatewayConstants.X_TIMESTAMP, ts)
                        .header(GatewayConstants.X_NONCE, "n-503")
                        .header(GatewayConstants.X_SIGNATURE, hmacBase64(canonical, "sec-1"))
                        .build()
        );
        failClosedFilter.filter(webEx, e -> Mono.empty()).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, webEx.getResponse().getStatusCode());

        // 2. 开启 failOpen 且不手动传 fallbackValidator (null) -> 自动内置有界内存降级且二次重放仍拦截
        RedisReactiveGatewayNonceValidator failOpenValidator = new RedisReactiveGatewayNonceValidator(
                redisTemplate, "ateng:gateway:sign:nonce:", true, null);
        assertTrue(Boolean.TRUE.equals(failOpenValidator.validateAndStore("appA", "n-fb", Duration.ofMinutes(5)).block()));
        assertFalse(Boolean.TRUE.equals(failOpenValidator.validateAndStore("appA", "n-fb", Duration.ofMinutes(5)).block()));
    }

    @Test
    @DisplayName("四阶自动装配优先级：自定义响应式 > 自定义旧版同步 > Redis > 无 Redis 内存回退")
    void testFourTierAutoConfigurationPrecedence() {
        // Tier 4：无 Redis 类路径时安全回退至 InMemoryReactiveGatewayNonceValidator
        contextRunner
                .withClassLoader(new FilteredClassLoader(ReactiveStringRedisTemplate.class))
                .run(ctx -> {
                    ReactiveGatewayNonceValidator bean = ctx.getBean(ReactiveGatewayNonceValidator.class);
                    assertInstanceOf(InMemoryReactiveGatewayNonceValidator.class, bean);
                });

        // Tier 3：存在 ReactiveStringRedisTemplate 时自动装配 RedisReactiveGatewayNonceValidator
        ReactiveStringRedisTemplate mockRedis = Mockito.mock(ReactiveStringRedisTemplate.class);
        contextRunner
                .withBean(ReactiveStringRedisTemplate.class, () -> mockRedis)
                .run(ctx -> {
                    ReactiveGatewayNonceValidator bean = ctx.getBean(ReactiveGatewayNonceValidator.class);
                    assertInstanceOf(RedisReactiveGatewayNonceValidator.class, bean);
                });

        // Tier 2：即使存在 Redis，自定义旧版 GatewayNonceValidator 优先包装适配
        GatewayNonceValidator customLegacy = (nonce, ttl) -> true;
        contextRunner
                .withBean(ReactiveStringRedisTemplate.class, () -> mockRedis)
                .withBean(GatewayNonceValidator.class, () -> customLegacy)
                .run(ctx -> {
                    ReactiveGatewayNonceValidator bean = ctx.getBean(ReactiveGatewayNonceValidator.class);
                    assertInstanceOf(LegacyGatewayNonceValidatorAdapter.class, bean);
                });

        // Tier 1：用户自定义 ReactiveGatewayNonceValidator 最高优先
        ReactiveGatewayNonceValidator customReactive = (appId, nonce, ttl) -> Mono.just(true);
        contextRunner
                .withBean(ReactiveStringRedisTemplate.class, () -> mockRedis)
                .withBean(GatewayNonceValidator.class, () -> customLegacy)
                .withBean(ReactiveGatewayNonceValidator.class, () -> customReactive)
                .run(ctx -> {
                    ReactiveGatewayNonceValidator bean = ctx.getBean(ReactiveGatewayNonceValidator.class);
                    assertEquals(customReactive, bean);
                });
    }

    private String hmacBase64(String data, String secret) {
        return HmacUtil.hmacSha256Base64(data, secret);
    }
}
