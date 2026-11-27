package io.github.atengk.cloud.gateway.crypto.enums;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 网关边缘 API 混合加解密算法套件枚举
 *
 * @author Ateng
 * @since 2026-11-27
 */
public enum GatewayCryptoAlgorithm {

    /**
     * RSA-OAEP + AES-256-GCM 国际混合加密套件
     */
    RSA_AES_GCM,

    /**
     * 国密 SM2 + SM4-128-GCM 混合加密套件
     */
    SM2_SM4_GCM;

    /**
     * 从标头或配置字符串解析加解密算法枚举（忽略大小写并容忍中划线/下划线差异）
     *
     * @param code 算法编码文本
     * @return 解析成功的算法枚举，若未知或为空则返回 {@code null}
     */
    public static GatewayCryptoAlgorithm fromCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        String normalized = code.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_');
        for (GatewayCryptoAlgorithm algorithm : values()) {
            if (algorithm.name().equals(normalized)) {
                return algorithm;
            }
        }
        return null;
    }
}
