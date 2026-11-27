package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.cloud.gateway.spi.ReactiveGatewayNonceValidator;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * 基于有界内存滑动窗口的响应式 Nonce 防重放校验器默认实现
 *
 * <p>内部复用 {@link InMemoryGatewayNonceValidator} 维持容量上限与 Fail-Closed 保护，
 * 并按 {@code appId:nonce} 维度执行隔离存储。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class InMemoryReactiveGatewayNonceValidator implements ReactiveGatewayNonceValidator {

    private final InMemoryGatewayNonceValidator delegate;

    public InMemoryReactiveGatewayNonceValidator() {
        this(InMemoryGatewayNonceValidator.DEFAULT_MAX_CAPACITY);
    }

    public InMemoryReactiveGatewayNonceValidator(int maxCapacity) {
        this.delegate = new InMemoryGatewayNonceValidator(maxCapacity);
    }

    public InMemoryReactiveGatewayNonceValidator(InMemoryGatewayNonceValidator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "InMemoryGatewayNonceValidator 不能为空");
    }

    @Override
    public Mono<Boolean> validateAndStore(String appId, String nonce, Duration ttl) {
        if (!StringUtils.hasText(nonce)) {
            return Mono.just(false);
        }
        String safeAppId = StringUtils.hasText(appId) ? appId.trim() : "default";
        String namespacedKey = safeAppId + ":" + nonce.trim();
        return Mono.fromSupplier(() -> delegate.validateAndStore(namespacedKey, ttl));
    }
}
