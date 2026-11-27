package io.github.atengk.cloud.gateway.crypto.cipher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherResult;
import io.github.atengk.cloud.gateway.spi.GatewayApiCipher;

import java.math.BigInteger;
import java.util.Base64;

/**
 * 国密 SM2 + SM4-128-GCM 混合加解密执行引擎
 *
 * <p>依托 {@code ateng-boot-core} 纯 JDK 17 内置的国密椭圆曲线加密（{@code Sm2Util}）与
 * 轻量级 SM4-GCM 认证加密（{@code CryptoUtil#encryptSm4Gcm}/{@code decryptSm4Gcm}），
 * 零外部 BouncyCastle 依赖完成等保国密信道防护。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class Sm2Sm4GcmGatewayCipher implements GatewayApiCipher {

    @Override
    public boolean supports(GatewayCryptoAlgorithm algorithm) {
        return algorithm == GatewayCryptoAlgorithm.SM2_SM4_GCM;
    }

    @Override
    public GatewayCipherResult encrypt(byte[] plainBytes, String clientPublicKey, byte[] aad) {
        String resolvedPub = GatewayCipherSupport.requireClientPublicKey(clientPublicKey);
        byte[] sessionKey = CryptoUtil.generateSm4Key();
        try {
            Sm2Util.Sm2Point publicKey = Sm2Util.parsePublicKey(resolvedPub);
            byte[] iv = CryptoUtil.generateGcmIv();
            byte[] data = plainBytes != null ? plainBytes : new byte[0];
            byte[] cipherBytes = aad != null
                    ? CryptoUtil.encryptSm4Gcm(data, sessionKey, iv, aad)
                    : CryptoUtil.encryptSm4Gcm(data, sessionKey, iv);
            byte[] encryptedSessionKey = Sm2Util.encrypt(sessionKey, publicKey);
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
                    "SM2-SM4-GCM 出站响应混合加密失败: " + ex.getMessage(),
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
        BigInteger privateKey;
        try {
            privateKey = Sm2Util.parsePrivateKey(resolvedPriv);
        } catch (Exception ex) {
            throw new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "SM2 服务端解密私钥解析失败",
                    ex);
        }

        byte[] wrappedKey = GatewayCipherSupport.decodeBase64Strict(encryptedKey, "X-Encrypt-Key");
        byte[] sessionKey = null;
        try {
            sessionKey = Sm2Util.decrypt(wrappedKey, privateKey);
            if (!isValidSm4KeyLength(sessionKey)) {
                GatewayCipherSupport.wipe(sessionKey);
                throw new BusinessException(
                        CommonResultCode.BAD_REQUEST,
                        "解封后的 SM4 会话密钥长度非法，预期 16 字节");
            }
            return sessionKey;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            GatewayCipherSupport.wipe(sessionKey);
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "SM2 会话密钥信封解封失败",
                    ex);
        }
    }

    @Override
    public byte[] decryptWithSessionKey(byte[] cipherBytes, byte[] sessionKey, String iv, byte[] aad) {
        if (cipherBytes == null || cipherBytes.length == 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求加密密文内容为空");
        }
        if (!isValidSm4KeyLength(sessionKey)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "SM4 会话密钥长度非法");
        }
        byte[] ivBytes = GatewayCipherSupport.decodeGcmIvStrict(iv);
        try {
            return aad != null
                    ? CryptoUtil.decryptSm4Gcm(cipherBytes, sessionKey, ivBytes, aad)
                    : CryptoUtil.decryptSm4Gcm(cipherBytes, sessionKey, ivBytes);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "SM4-GCM 请求密文解密或完整性校验失败",
                    ex);
        }
    }

    @Override
    public GatewayCipherResult encryptWithSessionKey(byte[] plainBytes, byte[] sessionKey, byte[] aad) {
        if (!isValidSm4KeyLength(sessionKey)) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "无效的 SM4 响应会话密钥");
        }
        try {
            byte[] iv = CryptoUtil.generateGcmIv();
            byte[] data = plainBytes != null ? plainBytes : new byte[0];
            byte[] cipherBytes = aad != null
                    ? CryptoUtil.encryptSm4Gcm(data, sessionKey, iv, aad)
                    : CryptoUtil.encryptSm4Gcm(data, sessionKey, iv);
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
                    "SM4-GCM 复用会话密钥响应加密失败: " + ex.getMessage(),
                    ex);
        }
    }

    private static boolean isValidSm4KeyLength(byte[] key) {
        return key != null && key.length == CryptoUtil.SM4_KEY_SIZE;
    }
}
