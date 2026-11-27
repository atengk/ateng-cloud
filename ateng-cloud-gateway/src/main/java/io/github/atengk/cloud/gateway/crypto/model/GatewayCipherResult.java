package io.github.atengk.cloud.gateway.crypto.model;

/**
 * 网关混合加密算法输出结果载体
 *
 * @param cipherBytes  密文字节数组（含 AEAD Authentication Tag）
 * @param encryptedKey 封装后的对称会话密钥 Base64 字符串；在 {@code REQUEST_SESSION_KEY} 模式下为 {@code null}
 * @param iv           随机生成的 12 字节 GCM 初始化向量 Base64 字符串
 * @author Ateng
 * @since 2026-11-27
 */
public record GatewayCipherResult(
        byte[] cipherBytes,
        String encryptedKey,
        String iv
) {
}
