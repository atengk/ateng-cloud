package io.github.atengk.cloud.gateway.spi;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 响应式非阻塞开放接口 Nonce 防重放校验器 SPI 契约
 *
 * <p>依托 Project Reactor {@link Mono} 返回契约，允许在不阻塞 Netty EventLoop 线程的前提下
 * 无缝对接响应式 Redis 等分布式原子存储实施防重放拦截。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
@FunctionalInterface
public interface ReactiveGatewayNonceValidator {

    /**
     * 非阻塞原子校验并持久化请求 Nonce
     *
     * @param appId 开放应用唯一标识（用于多应用键空间隔离）
     * @param nonce 请求随机数
     * @param ttl   有效时效窗口（通常为签名时间戳允许偏差）
     * @return {@code Mono.just(true)} 表示首次合法使用并已占位；{@code Mono.just(false)} 表示重放或超限拒绝
     */
    Mono<Boolean> validateAndStore(String appId, String nonce, Duration ttl);
}
