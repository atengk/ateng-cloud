package io.github.atengk.cloud.gateway.sign.signer;

import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import io.github.atengk.cloud.gateway.spi.GatewayApiSigner;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 基于纯 JDK 原生 HMAC-SHA256 的网关对称签名器
 *
 * <p>委托 {@link HmacUtil} 实现常量时间签名比对，验签同时兼容 Base64 与十六进制（大小写不敏感）格式。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class HmacSha256GatewaySigner implements GatewayApiSigner {

    @Override
    public boolean supports(GatewaySignAlgorithm algorithm) {
        return algorithm == GatewaySignAlgorithm.HMAC_SHA256;
    }

    @Override
    public String sign(String data, String signKey) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signKey)) {
            throw new IllegalArgumentException("待签名数据与对称密钥不能为空");
        }
        return HmacUtil.hmacSha256Base64(data, signKey);
    }

    @Override
    public boolean verify(String data, String signature, String verifyKey) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signature) || !StringUtils.hasText(verifyKey)) {
            return false;
        }
        try {
            byte[] expectedBytes = HmacUtil.hmacSha256(
                    data.getBytes(StandardCharsets.UTF_8),
                    verifyKey.getBytes(StandardCharsets.UTF_8)
            );
            String cleanSignature = signature.trim();
            String expectedBase64 = Base64.getEncoder().encodeToString(expectedBytes);
            if (HmacUtil.verifyBase64(expectedBase64, cleanSignature)) {
                return true;
            }
            String expectedHex = HexFormat.of().formatHex(expectedBytes);
            return HmacUtil.verifyHex(expectedHex, cleanSignature);
        } catch (Exception e) {
            return false;
        }
    }
}
