package io.github.atengk.cloud.gateway.crypto.enums;

/**
 * 网关出站响应加密密钥协商模式枚举
 *
 * @author Ateng
 * @since 2026-11-27
 */
public enum GatewayCryptoResponseKeyMode {

    /**
     * 服务端使用静态配置或 KMS 提供的客户端公钥（RSA/SM2）封装新的响应对称会话密钥，
     * 适用于 B2B 开放平台或固定客户端公私钥托管场景
     */
    CLIENT_PUBLIC_KEY,

    /**
     * 复用当次入站请求解封出的临时对称会话密钥，为响应重新生成随机 12 字节 IV 加密，
     * 响应头不回传 {@code X-Encrypt-Key}，适用于无静态私钥托管能力的浏览器 SPA 前端
     */
    REQUEST_SESSION_KEY
}
