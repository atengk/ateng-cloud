package io.github.atengk.cloud.gateway.crypto.cipher;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Sm2Sm4GcmGatewayCipher} 国密 SM2 + SM4-128-GCM 混合加解密单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
class Sm2Sm4GcmGatewayCipherTest {

    private final Sm2Sm4GcmGatewayCipher cipher = new Sm2Sm4GcmGatewayCipher();

    @Test
    @DisplayName("SM2-SM4-GCM 国密混合加解密与 AAD 认证防替换闭环")
    void shouldEncryptAndDecryptWithSm2AndSm4Gcm() {
        assertTrue(cipher.supports(GatewayCryptoAlgorithm.SM2_SM4_GCM));

        Sm2Util.Sm2KeyPair keyPair = Sm2Util.generateKeyPair();
        byte[] plain = "{\"idCard\":\"11010119900101001X\"}".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "gov-app|v2|SM2_SM4_GCM".getBytes(StandardCharsets.UTF_8);

        GatewayCipherResult encrypted = cipher.encrypt(plain, keyPair.publicKeyBase64(), aad);
        byte[] decrypted = cipher.decrypt(
                new GatewayCipherInput(encrypted.cipherBytes(), encrypted.encryptedKey(), encrypted.iv(), aad),
                keyPair.privateKeyBase64());

        assertArrayEquals(plain, decrypted);

        // 篡改密文 Tag 触发 HTTP 400
        byte[] tamperedCipher = encrypted.cipherBytes().clone();
        tamperedCipher[tamperedCipher.length - 1] ^= 0x7F;
        BusinessException ex = assertThrows(BusinessException.class, () -> cipher.decrypt(
                new GatewayCipherInput(tamperedCipher, encrypted.encryptedKey(), encrypted.iv(), aad),
                keyPair.privateKeyBase64()));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("SM2 复用 SM4 会话密钥加密响应：生成新随机 IV 且不回传 X-Encrypt-Key")
    void shouldReuseSm4SessionKeyWithFreshIv() {
        Sm2Util.Sm2KeyPair serverKp = Sm2Util.generateKeyPair();

        byte[] sm4Key = CryptoUtil.generateSm4Key();
        byte[] reqIv = CryptoUtil.generateGcmIv();
        byte[] reqPlain = "{\"action\":\"queryProfile\"}".getBytes(StandardCharsets.UTF_8);
        byte[] reqCipher = CryptoUtil.encryptSm4Gcm(reqPlain, sm4Key, reqIv);
        String encKeyBase64 = Base64.getEncoder().encodeToString(Sm2Util.encrypt(sm4Key, serverKp.publicKey()));
        String reqIvBase64 = Base64.getEncoder().encodeToString(reqIv);

        byte[] unwrapped = cipher.unwrapSessionKey(encKeyBase64, serverKp.privateKeyBase64());
        byte[] decryptedReq = cipher.decryptWithSessionKey(reqCipher, unwrapped, reqIvBase64, null);
        assertArrayEquals(reqPlain, decryptedReq);

        byte[] respPlain = "{\"user\":\"Ateng\"}".getBytes(StandardCharsets.UTF_8);
        GatewayCipherResult respResult = cipher.encryptWithSessionKey(respPlain, unwrapped, null);
        assertNull(respResult.encryptedKey());
        assertNotEquals(reqIvBase64, respResult.iv());

        byte[] clientDecrypted = CryptoUtil.decryptSm4Gcm(
                respResult.cipherBytes(),
                sm4Key,
                Base64.getDecoder().decode(respResult.iv()));
        assertArrayEquals(respPlain, clientDecrypted);
    }

    @Test
    @DisplayName("SM2 服务端私钥配置非法返回 HTTP 500，客户端非 16 字节会话密钥返回 HTTP 400")
    void shouldDistinguishServerKeyError500AndClientSessionKeyError400() {
        Sm2Util.Sm2KeyPair serverKp = Sm2Util.generateKeyPair();
        byte[] invalidLengthKey = new byte[32];
        String badWrapped = Base64.getEncoder().encodeToString(Sm2Util.encrypt(invalidLengthKey, serverKp.publicKey()));

        BusinessException clientEx = assertThrows(BusinessException.class,
                () -> cipher.unwrapSessionKey(badWrapped, serverKp.privateKeyBase64()));
        assertEquals(400, clientEx.getCode());

        BusinessException serverEx = assertThrows(BusinessException.class,
                () -> cipher.unwrapSessionKey(badWrapped, "invalid-sm2-private-key"));
        assertEquals(500, serverEx.getCode());
    }
}
