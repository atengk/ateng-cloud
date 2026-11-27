package io.github.atengk.cloud.gateway.crypto.model;

/**
 * 网关混合解密算法入参载体
 *
 * @param cipherBytes  密文字节数组（含 AEAD Authentication Tag）
 * @param encryptedKey 使用服务端公钥加密的对称会话密钥 Base64 字符串
 * @param iv           12 字节 GCM 初始化向量 Base64 字符串
 * @param aad          可选 AEAD 关联认证数据（未开启 AAD 绑定时为 {@code null}）
 * @author Ateng
 * @since 2026-11-27
 */
public record GatewayCipherInput(
        byte[] cipherBytes,
        String encryptedKey,
        String iv,
        byte[] aad
) {
}
