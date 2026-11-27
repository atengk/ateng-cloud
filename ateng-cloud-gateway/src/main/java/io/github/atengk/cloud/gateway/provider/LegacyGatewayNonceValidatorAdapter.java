package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.cloud.gateway.spi.GatewayNonceValidator;
import io.github.atengk.cloud.gateway.spi.ReactiveGatewayNonceValidator;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Objects;

/**
 * 旧版同步 {@link GatewayNonceValidator} 向 {@link ReactiveGatewayNonceValidator} 的兼容适配器
 *
 * <p>保留原始 {@code nonce} 传参契约并将同步 SPI 调度至有界弹性调度器（{@link Schedulers#boundedElastic()}），
 * 防止自定义阻塞式 {@link GatewayNonceValidator} 实现占满 Netty EventLoop 线程。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class LegacyGatewayNonceValidatorAdapter implements ReactiveGatewayNonceValidator {

    private final GatewayNonceValidator delegate;

    public LegacyGatewayNonceValidatorAdapter(GatewayNonceValidator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "GatewayNonceValidator 不能为空");
    }

    @Override
    public Mono<Boolean> validateAndStore(String appId, String nonce, Duration ttl) {
        if (!StringUtils.hasText(nonce)) {
            return Mono.just(false);
        }
        String trimmedNonce = nonce.trim();
        return Mono.fromCallable(() -> delegate.validateAndStore(trimmedNonce, ttl))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
