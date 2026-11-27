package io.github.atengk.cloud.gateway.crypto.model;

import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoResponseKeyMode;

import java.nio.charset.StandardCharsets;

/**
 * 单次网关请求加解密治理不可变上下文快照
 *
 * @param appId           解析后的应用标识
 * @param keyId           解析后的有效密钥版本号
 * @param algorithm       本次协商采用的混合加解密算法套件
 * @param responseKeyMode 出站响应密钥模式
 * @param bindAad         是否开启 AEAD 关联认证数据（AAD）防替换重放绑定
 * @author Ateng
 * @since 2026-11-27
 */
public record GatewayCryptoContext(
        String appId,
        String keyId,
        GatewayCryptoAlgorithm algorithm,
        GatewayCryptoResponseKeyMode responseKeyMode,
        boolean bindAad
) {

    /**
     * 构建标准 AEAD 关联认证数据（AAD）字节数组：{@code appId|keyId|algorithm}
     *
     * @return 开启 {@code bindAad} 时返回 UTF-8 编码字节数组；关闭时返回 {@code null}
     */
    public byte[] buildAadBytes() {
        if (!bindAad) {
            return null;
        }
        String resolvedAppId = appId != null ? appId : "";
        String resolvedKeyId = keyId != null ? keyId : "";
        String resolvedAlgorithm = algorithm != null ? algorithm.name() : "";
        return (resolvedAppId + "|" + resolvedKeyId + "|" + resolvedAlgorithm)
                .getBytes(StandardCharsets.UTF_8);
    }
}
