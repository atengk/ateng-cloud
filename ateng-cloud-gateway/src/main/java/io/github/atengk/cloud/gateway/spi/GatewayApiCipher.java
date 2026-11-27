package io.github.atengk.cloud.gateway.spi;

import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;

/**
 * 网关边缘 API 混合加解密算法执行引擎 SPI
 *
 * <p>封装“非对称密钥信封（RSA-OAEP / SM2） + 对称 AEAD 载荷（AES-256-GCM / SM4-128-GCM）”混合密码协议。
 * 同时支持 {@code CLIENT_PUBLIC_KEY} 静态公钥回传模式与 {@code REQUEST_SESSION_KEY} 单次请求会话密钥复用模式。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public interface GatewayApiCipher {

    /**
     * 判断当前引擎是否支持指定的混合加密算法套件
     *
     * @param algorithm 混合加密算法套件枚举
     * @return 支持返回 {@code true}
     */
    boolean supports(GatewayCryptoAlgorithm algorithm);

    /**
     * 使用客户端非对称公钥对响应明文执行完整混合加密
     *
     * @param plainBytes      原始响应明文字节
     * @param clientPublicKey 客户端非对称公钥（Base64/Hex/PEM）
     * @param aad             可选 AEAD 关联认证数据（未开启 AAD 时传 {@code null}）
     * @return 密文字节、封装后的会话密钥 Base64 与随机 12 字节 IV Base64
     */
    GatewayCipherResult encrypt(byte[] plainBytes, String clientPublicKey, byte[] aad);

    /**
     * 使用服务端非对称私钥解封会话密钥并解密入站请求密文
     *
     * @param input            密文输入参数载体
     * @param serverPrivateKey 服务端非对称私钥（Base64/Hex/PEM）
     * @return 解密后的明文字节数组
     */
    byte[] decrypt(GatewayCipherInput input, String serverPrivateKey);

    /**
     * 仅解封客户端传入的对称会话密钥信封（用于 {@code REQUEST_SESSION_KEY} 握手及密钥复用途径）
     *
     * @param encryptedKey     客户端公钥封装后的会话密钥 Base64 字符串
     * @param serverPrivateKey 服务端非对称私钥
     * @return 原始对称会话密钥字节数组（调用方有义务在使用完毕后安全清零）
     */
    byte[] unwrapSessionKey(String encryptedKey, String serverPrivateKey);

    /**
     * 使用已解封的对称会话密钥直接解密密文字节
     *
     * @param cipherBytes 密文字节数组
     * @param sessionKey  原始对称会话密钥字节数组
     * @param iv          12 字节 GCM 初始化向量 Base64 字符串
     * @param aad         可选 AEAD 关联认证数据
     * @return 解密后的明文字节数组
     */
    byte[] decryptWithSessionKey(byte[] cipherBytes, byte[] sessionKey, String iv, byte[] aad);

    /**
     * 复用既有对称会话密钥对响应明文执行 AEAD 加密（每次强制生成全新随机 12 字节 IV，返回 {@code encryptedKey = null}）
     *
     * @param plainBytes 原始响应明文字节数组
     * @param sessionKey 当前请求解封出的对称会话密钥字节数组
     * @param aad        可选 AEAD 关联认证数据
     * @return 密文字节与新随机 12 字节 IV Base64（{@code encryptedKey} 恒为 {@code null}）
     */
    GatewayCipherResult encryptWithSessionKey(byte[] plainBytes, byte[] sessionKey, byte[] aad);
}
