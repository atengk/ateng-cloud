package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 当前登录用户信息视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public record UserInfoVO(
        String userId,
        String tenantId,
        String status
) implements Serializable {
}
