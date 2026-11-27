package io.github.atengk.cloud.gateway.crypto.cipher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;
import io.github.atengk.cloud.gateway.spi.GatewayApiCipher;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * RSA-OAEP-SHA256 + AES-256-GCM 国际混合加解密执行引擎
 *
 * <p>基于 JDK 17 原生 JCA 密码服务（零额外三方密码库依赖），通过 {@code RsaUtil} 与 {@code CryptoUtil}
 * 分别完成会话密钥非对称封装与 AEAD 对称载荷加解密，并在生成/解封后第一时间擦除临时对称密钥明文。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class RsaAesGcmGatewayCipher implements GatewayApiCipher {

    private static final int AES_KEY_BITS = 256;

    @Override
    public boolean supports(GatewayCryptoAlgorithm algorithm) {
        return algorithm == GatewayCryptoAlgorithm.RSA_AES_GCM;
    }

    @Override
    public GatewayCipherResult encrypt(byte[] plainBytes, String clientPublicKey, byte[] aad) {
        String resolvedPub = GatewayCipherSupport.requireClientPublicKey(clientPublicKey);
        byte[] sessionKey = CryptoUtil.generateKey(AES_KEY_BITS);
        try {
            PublicKey publicKey = RsaUtil.parsePublicKey(resolvedPub);
            byte[] iv = CryptoUtil.generateGcmIv();
            byte[] data = plainBytes != null ? plainBytes : new byte[0];
            byte[] cipherBytes = aad != null
                    ? CryptoUtil.encryptGcm(data, sessionKey, iv, aad)
                    : CryptoUtil.encryptGcm(data, sessionKey, iv);
            byte[] encryptedSessionKey = RsaUtil.encryptOaep(sessionKey, publicKey);
            return new GatewayCipherResult(
                    cipherBytes,
                    Base64.getEncoder().encodeToString(encryptedSessionKey),
                    Base64.getEncoder().encodeToString(iv)
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RSA-AES-GCM 出站响应混合加密失败: " + ex.getMessage(),
                    ex);
        } finally {
            GatewayCipherSupport.wipe(sessionKey);
        }
    }

    @Override
    public byte[] decrypt(GatewayCipherInput input, String serverPrivateKey) {
        GatewayCipherInput validInput = GatewayCipherSupport.requireCipherInput(input);
        byte[] sessionKey = unwrapSessionKey(validInput.encryptedKey(), serverPrivateKey);
        try {
            return decryptWithSessionKey(validInput.cipherBytes(), sessionKey, validInput.iv(), validInput.aad());
        } finally {
            GatewayCipherSupport.wipe(sessionKey);
        }
    }

    @Override
    public byte[] unwrapSessionKey(String encryptedKey, String serverPrivateKey) {
        String resolvedPriv = GatewayCipherSupport.requireServerPrivateKey(serverPrivateKey);
        PrivateKey privateKey;
        try {
            privateKey = RsaUtil.parsePrivateKey(resolvedPriv);
        } catch (Exception ex) {
            throw new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "RSA 服务端解密私钥解析失败",
                    ex);
        }

        byte[] wrappedKey = GatewayCipherSupport.decodeBase64Strict(encryptedKey, "X-Encrypt-Key");
        byte[] sessionKey = null;
        try {
            sessionKey = RsaUtil.decryptOaep(wrappedKey, privateKey);
            if (!isValidAesKeyLength(sessionKey)) {
                GatewayCipherSupport.wipe(sessionKey);
                throw new BusinessException(
                        CommonResultCode.BAD_REQUEST,
                        "解封后的 AES 会话密钥长度非法");
            }
            return sessionKey;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            GatewayCipherSupport.wipe(sessionKey);
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "RSA 会话密钥信封解封失败",
                    ex);
        }
    }

    @Override
    public byte[] decryptWithSessionKey(byte[] cipherBytes, byte[] sessionKey, String iv, byte[] aad) {
        if (cipherBytes == null || cipherBytes.length == 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求加密密文内容为空");
        }
        if (!isValidAesKeyLength(sessionKey)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "AES 会话密钥长度非法");
        }
        byte[] ivBytes = GatewayCipherSupport.decodeGcmIvStrict(iv);
        try {
            return aad != null
                    ? CryptoUtil.decryptGcm(cipherBytes, sessionKey, ivBytes, aad)
                    : CryptoUtil.decryptGcm(cipherBytes, sessionKey, ivBytes);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "AES-GCM 请求密文解密或完整性校验失败",
                    ex);
        }
    }

    @Override
    public GatewayCipherResult encryptWithSessionKey(byte[] plainBytes, byte[] sessionKey, byte[] aad) {
        if (!isValidAesKeyLength(sessionKey)) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "无效的 AES 响应会话密钥");
        }
        try {
            byte[] iv = CryptoUtil.generateGcmIv();
            byte[] data = plainBytes != null ? plainBytes : new byte[0];
            byte[] cipherBytes = aad != null
                    ? CryptoUtil.encryptGcm(data, sessionKey, iv, aad)
                    : CryptoUtil.encryptGcm(data, sessionKey, iv);
            return new GatewayCipherResult(
                    cipherBytes,
                    null,
                    Base64.getEncoder().encodeToString(iv)
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "AES-GCM 复用会话密钥响应加密失败: " + ex.getMessage(),
                    ex);
        }
    }

    private static boolean isValidAesKeyLength(byte[] key) {
        return key != null && (key.length == 16 || key.length == 24 || key.length == 32);
    }
}
