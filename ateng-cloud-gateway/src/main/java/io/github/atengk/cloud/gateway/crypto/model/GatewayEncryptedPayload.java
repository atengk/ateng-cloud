package io.github.atengk.cloud.gateway.crypto.model;

import java.io.Serializable;

/**
 * 网关传输层 JSON 密文信封载荷对象（格式：{@code {"cipherText":"..."}}）
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class GatewayEncryptedPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Base64 编码的对称加密密文字符串（含 AEAD 认证标签）
     */
    private String cipherText;

    public GatewayEncryptedPayload() {
    }

    public GatewayEncryptedPayload(String cipherText) {
        this.cipherText = cipherText;
    }

    public String getCipherText() {
        return cipherText;
    }

    public void setCipherText(String cipherText) {
        this.cipherText = cipherText;
    }
}
