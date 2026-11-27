package io.github.atengk.cloud.gateway.sign.enums;

/**
 * 网关开放接口数字签名与验签算法枚举
 *
 * @author Ateng
 * @since 2026-11-27
 */
public enum GatewaySignAlgorithm {

    /**
     * HMAC-SHA256 对称哈希签名算法（默认，极速高效）
     */
    HMAC_SHA256,

    /**
     * RSA (SHA256withRSA) 非对称数字签名算法
     */
    RSA,

    /**
     * 国密 SM2 (GB/T 32918.2-2016) / SM3 非对称数字签名算法
     */
    SM2
}
