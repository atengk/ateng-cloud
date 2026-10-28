package io.github.atengk.cloud.gateway.spi;

import java.time.Duration;

/**
 * 开放接口 API 签名防重放 Nonce 校验器 SPI
 *
 * <p>支持基于内存或分布式存储（如 Redis）校验请求随机数 Nonce 的唯一性，防御重放攻击。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public interface GatewayNonceValidator {

    /**
     * 校验并存储 Nonce
     *
     * @param nonce 随机数
     * @param ttl   有效存活时间（通常对齐时间戳允许的最大时钟偏差）
     * @return true 表示首次使用校验成功，false 表示已存在（检测到重放攻击）
     */
    boolean validateAndStore(String nonce, Duration ttl);
}
