package io.github.atengk.cloud.gateway.crypto.cipher;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RsaAesGcmGatewayCipher} RSA-OAEP + AES-256-GCM 混合加解密单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
class RsaAesGcmGatewayCipherTest {

    private final RsaAesGcmGatewayCipher cipher = new RsaAesGcmGatewayCipher();

    @Test
    @DisplayName("RSA-AES-GCM 标准混合加解密与 AAD 绑定验证闭环")
    void shouldEncryptAndDecryptWithOptionalAad() {
        assertTrue(cipher.supports(GatewayCryptoAlgorithm.RSA_AES_GCM));

        KeyPair keyPair = RsaUtil.generateKeyPair();
        String pubBase64 = RsaUtil.encodePublicKeyBase64(keyPair.getPublic());
        String privBase64 = RsaUtil.encodePrivateKeyBase64(keyPair.getPrivate());

        byte[] plain = "{\"orderNo\":\"ORD-1001\",\"amount\":99.5}".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "merchant-a|v1|RSA_AES_GCM".getBytes(StandardCharsets.UTF_8);

        GatewayCipherResult encrypted = cipher.encrypt(plain, pubBase64, aad);
        byte[] decrypted = cipher.decrypt(
                new GatewayCipherInput(encrypted.cipherBytes(), encrypted.encryptedKey(), encrypted.iv(), aad),
                privBase64);

        assertArrayEquals(plain, decrypted);

        // 篡改 AAD 应触发 AEAD 标签验签失败并返回 400
        byte[] tamperedAad = "merchant-b|v1|RSA_AES_GCM".getBytes(StandardCharsets.UTF_8);
        BusinessException aadEx = assertThrows(BusinessException.class, () -> cipher.decrypt(
                new GatewayCipherInput(encrypted.cipherBytes(), encrypted.encryptedKey(), encrypted.iv(), tamperedAad),
                privBase64));
        assertEquals(400, aadEx.getCode());
    }

    @Test
    @DisplayName("篡改密文或 GCM Tag、错误私钥或非法 IV 均 Fail-Closed 抛出 HTTP 400")
    void shouldRejectTamperedCipherWrongKeyOrInvalidIvWith400() {
        KeyPair serverKp = RsaUtil.generateKeyPair();
        KeyPair wrongKp = RsaUtil.generateKeyPair();
        String serverPub = RsaUtil.encodePublicKeyBase64(serverKp.getPublic());
        String serverPriv = RsaUtil.encodePrivateKeyBase64(serverKp.getPrivate());
        String wrongPriv = RsaUtil.encodePrivateKeyBase64(wrongKp.getPrivate());

        byte[] plain = "{\"msg\":\"sensitive\"}".getBytes(StandardCharsets.UTF_8);
        GatewayCipherResult encrypted = cipher.encrypt(plain, serverPub, null);

        byte[] tamperedCipher = encrypted.cipherBytes().clone();
        tamperedCipher[tamperedCipher.length - 1] ^= 0x01;

        BusinessException tagEx = assertThrows(BusinessException.class, () -> cipher.decrypt(
                new GatewayCipherInput(tamperedCipher, encrypted.encryptedKey(), encrypted.iv(), null),
                serverPriv));
        assertEquals(400, tagEx.getCode());

        BusinessException wrongKeyEx = assertThrows(BusinessException.class, () -> cipher.decrypt(
                new GatewayCipherInput(encrypted.cipherBytes(), encrypted.encryptedKey(), encrypted.iv(), null),
                wrongPriv));
        assertEquals(400, wrongKeyEx.getCode());

        String shortIv = Base64.getEncoder().encodeToString(new byte[8]);
        BusinessException ivEx = assertThrows(BusinessException.class, () -> cipher.decrypt(
                new GatewayCipherInput(encrypted.cipherBytes(), encrypted.encryptedKey(), shortIv, null),
                serverPriv));
        assertEquals(400, ivEx.getCode());
    }

    @Test
    @DisplayName("REQUEST_SESSION_KEY 复用解封会话密钥并生成全新随机 IV 加密响应，encryptedKey 为 null")
    void shouldReuseUnwrappedSessionKeyWithFreshRandomIv() {
        KeyPair keyPair = RsaUtil.generateKeyPair();
        PublicKey serverPub = keyPair.getPublic();
        String serverPriv = RsaUtil.encodePrivateKeyBase64(keyPair.getPrivate());

        byte[] clientSessionKey = CryptoUtil.generateKey(256);
        byte[] clientIv = CryptoUtil.generateGcmIv();
        byte[] reqPlain = "{\"query\":\"balance\"}".getBytes(StandardCharsets.UTF_8);
        byte[] reqCipher = CryptoUtil.encryptGcm(reqPlain, clientSessionKey, clientIv);
        String encKeyBase64 = Base64.getEncoder().encodeToString(RsaUtil.encryptOaep(clientSessionKey, serverPub));
        String reqIvBase64 = Base64.getEncoder().encodeToString(clientIv);

        byte[] unwrappedKey = cipher.unwrapSessionKey(encKeyBase64, serverPriv);
        byte[] decryptedReq = cipher.decryptWithSessionKey(reqCipher, unwrappedKey, reqIvBase64, null);
        assertArrayEquals(reqPlain, decryptedReq);

        byte[] respPlain = "{\"balance\":1888}".getBytes(StandardCharsets.UTF_8);
        GatewayCipherResult respResult = cipher.encryptWithSessionKey(respPlain, unwrappedKey, null);
        assertNull(respResult.encryptedKey());
        assertNotEquals(reqIvBase64, respResult.iv());

        byte[] clientDecryptedResp = CryptoUtil.decryptGcm(
                respResult.cipherBytes(),
                clientSessionKey,
                Base64.getDecoder().decode(respResult.iv()));
        assertArrayEquals(respPlain, clientDecryptedResp);
    }
}
