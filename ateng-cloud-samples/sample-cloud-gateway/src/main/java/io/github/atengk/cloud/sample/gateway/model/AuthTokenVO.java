package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 集中认证登录令牌契约视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record AuthTokenVO(
        String tokenName,
        String tokenValue,
        String loginId,
        String tenantId,
        long tokenTimeout
) implements Serializable {
}
