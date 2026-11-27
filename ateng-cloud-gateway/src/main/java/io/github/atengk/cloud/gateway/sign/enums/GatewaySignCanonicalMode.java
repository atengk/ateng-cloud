package io.github.atengk.cloud.gateway.sign.enums;

/**
 * 网关开放接口 API 签名待签串规范模式枚举
 *
 * @author Ateng
 * @since 2026-11-27
 */
public enum GatewaySignCanonicalMode {

    /**
     * 兼容旧版拼串协议（默认，保持既有接入方 100% 向后兼容）：
     * {@code appId=...[&bodyHash=...]&nonce=...&timestamp=...[&query]}
     */
    LEGACY,

    /**
     * 统一规范待签串协议（100% 对齐 {@code ateng-boot-web} {@code SignCanonicalUtil}）：
     * {@code Method\nURI\nTimestamp\nNonce\nSortedQueryString\nBodySha256}
     */
    STANDARD
}
