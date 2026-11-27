package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties.CryptoKeyVersionProperties;
import io.github.atengk.cloud.gateway.spi.GatewayCryptoKeyProvider;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 基于 {@link AtengGatewayCryptoProperties} 配置字典的多版本密钥提供者默认实现
 *
 * <p>严格校验密钥版本号存在性与启用状态：
 * <ul>
 *     <li>若配置了 {@code keys} 多版本映射：缺失对应 {@code keyId} 或 {@code enabled=false} 时 Fail-Closed 返回 HTTP 400；</li>
 *     <li>若未配置 {@code keys} 但客户端传入了与 {@code activeKeyId} 不一致的 {@code keyId}，同样返回 HTTP 400；</li>
 *     <li>按当前算法专属字段优先、通用字段兜底提取密钥；若服务端未配置对应密钥则返回 HTTP 500。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class PropertiesGatewayCryptoKeyProvider implements GatewayCryptoKeyProvider {

    private final AtengGatewayCryptoProperties properties;

    public PropertiesGatewayCryptoKeyProvider(AtengGatewayCryptoProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<String> getDecryptPrivateKey(String appId, String keyId, GatewayCryptoAlgorithm algorithm) {
        return resolveKey(keyId, algorithm, true);
    }

    @Override
    public Mono<String> getEncryptPublicKey(String appId, String keyId, GatewayCryptoAlgorithm algorithm) {
        return resolveKey(keyId, algorithm, false);
    }

    private Mono<String> resolveKey(String keyId, GatewayCryptoAlgorithm algorithm, boolean privateKey) {
        if (properties == null) {
            return Mono.error(new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "网关未装配 API 加解密配置属性"));
        }
        GatewayCryptoAlgorithm targetAlg = algorithm != null
                ? algorithm
                : (properties.getAlgorithm() != null ? properties.getAlgorithm() : GatewayCryptoAlgorithm.RSA_AES_GCM);

        String activeKeyId = StringUtils.hasText(properties.getActiveKeyId())
                ? properties.getActiveKeyId().trim()
                : "v1";
        boolean hasExplicitKeyId = StringUtils.hasText(keyId);
        String effectiveKeyId = hasExplicitKeyId ? keyId.trim() : activeKeyId;

        Map<String, CryptoKeyVersionProperties> versions = properties.getKeys();
        if (versions != null && !versions.isEmpty()) {
            CryptoKeyVersionProperties version = versions.get(effectiveKeyId);
            if (version == null) {
                return Mono.error(new BusinessException(
                        hasExplicitKeyId ? CommonResultCode.BAD_REQUEST : CommonResultCode.INTERNAL_SERVER_ERROR,
                        (hasExplicitKeyId ? "未知的加密密钥版本: " : "服务端默认激活密钥版本未在字典配置: ") + effectiveKeyId));
            }
            if (!version.isEnabled()) {
                return Mono.error(new BusinessException(
                        hasExplicitKeyId ? CommonResultCode.BAD_REQUEST : CommonResultCode.INTERNAL_SERVER_ERROR,
                        (hasExplicitKeyId ? "加密密钥版本已禁用: " : "服务端默认激活密钥版本处于禁用状态: ") + effectiveKeyId));
            }
            String resolved = resolveFromVersion(version, targetAlg, privateKey);
            if (!StringUtils.hasText(resolved)) {
                return Mono.error(missingKeyException(targetAlg, effectiveKeyId, privateKey));
            }
            return Mono.just(resolved.trim());
        }

        if (hasExplicitKeyId && !activeKeyId.equals(effectiveKeyId)) {
            return Mono.error(new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "未知的加密密钥版本: " + effectiveKeyId));
        }

        String resolved = resolveFromProperties(targetAlg, privateKey);
        if (!StringUtils.hasText(resolved)) {
            return Mono.error(missingKeyException(targetAlg, effectiveKeyId, privateKey));
        }
        return Mono.just(resolved.trim());
    }

    private String resolveFromVersion(CryptoKeyVersionProperties version,
                                      GatewayCryptoAlgorithm algorithm,
                                      boolean privateKey) {
        if (privateKey) {
            return selectByAlgorithm(
                    algorithm,
                    version.getRsaServerPrivateKey(),
                    version.getSm2ServerPrivateKey(),
                    version.getServerPrivateKey());
        }
        return selectByAlgorithm(
                algorithm,
                version.getRsaClientPublicKey(),
                version.getSm2ClientPublicKey(),
                version.getClientPublicKey());
    }

    private String resolveFromProperties(GatewayCryptoAlgorithm algorithm, boolean privateKey) {
        if (privateKey) {
            return selectByAlgorithm(
                    algorithm,
                    properties.getRsaServerPrivateKey(),
                    properties.getSm2ServerPrivateKey(),
                    properties.getServerPrivateKey());
        }
        return selectByAlgorithm(
                algorithm,
                properties.getRsaClientPublicKey(),
                properties.getSm2ClientPublicKey(),
                properties.getClientPublicKey());
    }

    private static String selectByAlgorithm(GatewayCryptoAlgorithm algorithm,
                                            String rsaSpecific,
                                            String sm2Specific,
                                            String fallbackGeneral) {
        if (algorithm == GatewayCryptoAlgorithm.SM2_SM4_GCM) {
            return StringUtils.hasText(sm2Specific) ? sm2Specific : fallbackGeneral;
        }
        return StringUtils.hasText(rsaSpecific) ? rsaSpecific : fallbackGeneral;
    }

    private static BusinessException missingKeyException(GatewayCryptoAlgorithm algorithm,
                                                         String keyId,
                                                         boolean privateKey) {
        String keyRole = privateKey ? "服务端解密私钥" : "客户端加密公钥";
        return new BusinessException(
                CommonResultCode.INTERNAL_SERVER_ERROR,
                "未配置算法 " + algorithm + " 版本 [" + keyId + "] 对应的" + keyRole);
    }
}
