package io.github.atengk.cloud.gateway.spi;

import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import reactor.core.publisher.Mono;

/**
 * 网关开放接口响应式多算法签名密钥提供者 SPI 契约
 *
 * <p>全面采用非阻塞 {@link Mono} 返回契约，支持按应用标识 {@code appId} 与算法动态从配置、响应式 Redis、
 * R2DBC 或远端 KMS 检索对称密钥或非对称公私钥，严守 WebFlux EventLoop 零阻塞红线。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public interface GatewaySignKeyProvider {

    /**
     * 获取指定应用的对称签名密钥（HMAC-SHA256）
     *
     * @param appId 应用/商户唯一标识
     * @return 对称密钥明文 Mono（不存在时返回空 Mono）
     */
    Mono<String> getSecretKey(String appId);

    /**
     * 获取指定应用的验签公钥（RSA X.509 或 SM2 Hex/Base64/PEM）
     *
     * @param appId 应用/商户唯一标识
     * @return 客户端公钥 Mono（不存在时返回空 Mono）
     */
    Mono<String> getPublicKey(String appId);

    /**
     * 获取指定应用的服务端出站加签私钥（RSA PKCS#8 或 SM2 Hex/Base64/PEM）
     *
     * @param appId 应用/商户唯一标识
     * @return 服务端私钥 Mono（不存在时返回空 Mono）
     */
    Mono<String> getPrivateKey(String appId);

    /**
     * 按生效算法获取入站验签密钥（非对称算法分派至公钥，对称算法分派至对称密钥）
     *
     * @param appId     应用/商户唯一标识
     * @param algorithm 当前生效的签名算法
     * @return 验签密钥 Mono
     */
    default Mono<String> getVerifyKey(String appId, GatewaySignAlgorithm algorithm) {
        return (algorithm == GatewaySignAlgorithm.RSA || algorithm == GatewaySignAlgorithm.SM2)
                ? getPublicKey(appId)
                : getSecretKey(appId);
    }

    /**
     * 按生效算法获取出站响应加签密钥（非对称算法分派至私钥，对称算法分派至对称密钥）
     *
     * @param appId     应用/商户唯一标识
     * @param algorithm 当前生效的签名算法
     * @return 加签密钥 Mono
     */
    default Mono<String> getSignKey(String appId, GatewaySignAlgorithm algorithm) {
        return (algorithm == GatewaySignAlgorithm.RSA || algorithm == GatewaySignAlgorithm.SM2)
                ? getPrivateKey(appId)
                : getSecretKey(appId);
    }
}
