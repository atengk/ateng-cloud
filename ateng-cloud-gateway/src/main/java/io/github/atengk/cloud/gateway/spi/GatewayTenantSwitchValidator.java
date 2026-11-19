package io.github.atengk.cloud.gateway.spi;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关平台超级管理员切换目标租户响应式校验扩展 SPI
 *
 * <p>当平台超级管理员携带 {@code X-Tenant-Id} 请求头尝试临时切换目标租户时，
 * 底座完成格式合法性防卫后将回调本 SPI（支持非阻塞查询 Redis / 租户状态缓存等），
 * 返回 {@code true} 放行切换，返回 {@code false} 或空序列则阻断并返回 HTTP 403。</p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@FunctionalInterface
public interface GatewayTenantSwitchValidator {

    /**
     * 校验当前平台超管是否允许切换至目标租户
     *
     * @param exchange        当前网关响应式请求上下文
     * @param targetTenantId  校验通过格式防卫的目标租户 ID
     * @param sessionTenantId 登录会话本身归属的平台租户 ID（可能为 {@code null}）
     * @param userId          当前登录用户 ID（可能为 {@code null}）
     * @return {@code Mono.just(true)} 表示允许切换，其余情况一律拒绝
     */
    Mono<Boolean> validate(ServerWebExchange exchange, String targetTenantId, String sessionTenantId, String userId);
}
