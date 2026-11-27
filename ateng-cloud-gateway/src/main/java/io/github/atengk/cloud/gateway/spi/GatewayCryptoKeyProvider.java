package io.github.atengk.cloud.gateway.spi;

import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import reactor.core.publisher.Mono;

/**
 * 响应式网关边缘 API 混合加解密密钥提供者 SPI
 *
 * <p>支持外部扩展实现（如基于 Nacos 配置中心、KMS 或 Redis 的异步非阻塞动态密钥获取与多版本轮转）。
 * 严禁在异步响应链中直接执行同步阻塞操作。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public interface GatewayCryptoKeyProvider {

    /**
     * 异步获取用于入站解密与会话密钥解封的服务端非对称私钥
     *
     * @param appId     应用标识
     * @param keyId     密钥版本号（允许为空，空时回退至当前激活版本）
     * @param algorithm 混合加解密算法套件
     * @return 编码后的服务端私钥字符串（如 Base64/Hex/PEM）；若未知或禁用版本则返回含 {@code BusinessException} 的错误信号
     */
    Mono<String> getDecryptPrivateKey(String appId, String keyId, GatewayCryptoAlgorithm algorithm);

    /**
     * 异步获取用于出站响应加密的客户端非对称公钥
     *
     * @param appId     应用标识
     * @param keyId     密钥版本号（允许为空，空时回退至当前激活版本）
     * @param algorithm 混合加解密算法套件
     * @return 编码后的客户端公钥字符串；若未知或禁用版本则返回含 {@code BusinessException} 的错误信号
     */
    Mono<String> getEncryptPublicKey(String appId, String keyId, GatewayCryptoAlgorithm algorithm);
}
