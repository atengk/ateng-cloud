package io.github.atengk.cloud.gateway.crypto.cipher;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.cloud.gateway.crypto.model.GatewayCipherInput;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Base64;

/**
 * 网关混合加解密算法公共辅助工具（包内共享防重复与统一 Fail-Fast 校验）
 *
 * @author Ateng
 * @since 2026-11-27
 */
final class GatewayCipherSupport {

    private GatewayCipherSupport() {
    }

    /**
     * 严格解码客户端 Base64 文本（空值或非法格式抛出 HTTP 400 BusinessException）
     */
    static byte[] decodeBase64Strict(String encoded, String fieldName) {
        if (!StringUtils.hasText(encoded)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "缺少必要加密参数: " + fieldName);
        }
        try {
            return Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "加密参数 Base64 格式非法: " + fieldName,
                    ex);
        }
    }

    /**
     * 严格解码并校验 12 字节 GCM 初始化向量（IV）
     */
    static byte[] decodeGcmIvStrict(String ivBase64) {
        byte[] iv = decodeBase64Strict(ivBase64, "X-Encrypt-Iv");
        if (iv.length != CryptoUtil.DEFAULT_GCM_IV_SIZE) {
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "加密向量长度非法，预期 12 字节，实际: " + iv.length);
        }
        return iv;
    }

    /**
     * 前置校验服务端私钥文本非空
     */
    static String requireServerPrivateKey(String serverPrivateKey) {
        if (!StringUtils.hasText(serverPrivateKey)) {
            throw new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "未配置有效的服务端解密私钥");
        }
        return serverPrivateKey.trim();
    }

    /**
     * 前置校验客户端公钥文本非空
     */
    static String requireClientPublicKey(String clientPublicKey) {
        if (!StringUtils.hasText(clientPublicKey)) {
            throw new BusinessException(
                    CommonResultCode.INTERNAL_SERVER_ERROR,
                    "未配置有效的客户端加密公钥");
        }
        return clientPublicKey.trim();
    }

    /**
     * 前置校验密文输入参数载体完整性
     */
    static GatewayCipherInput requireCipherInput(GatewayCipherInput input) {
        if (input == null || input.cipherBytes() == null || input.cipherBytes().length == 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求加密密文内容为空");
        }
        return input;
    }

    /**
     * 安全擦除敏感对称会话密钥字节数组内容
     */
    static void wipe(byte[] secretBytes) {
        if (secretBytes != null && secretBytes.length > 0) {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }
}
