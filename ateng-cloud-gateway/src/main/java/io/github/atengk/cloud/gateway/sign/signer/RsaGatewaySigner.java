package io.github.atengk.cloud.gateway.sign.signer;

import io.github.atengk.boot.core.util.RsaUtil;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import io.github.atengk.cloud.gateway.spi.GatewayApiSigner;
import org.springframework.util.StringUtils;

/**
 * 基于纯 JDK 17 原生 RSA ({@code SHA256withRSA}) 的网关非对称签名器
 *
 * <p>底层密码学原语直接复用 {@link RsaUtil}，零第三方加密库依赖。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class RsaGatewaySigner implements GatewayApiSigner {

    @Override
    public boolean supports(GatewaySignAlgorithm algorithm) {
        return algorithm == GatewaySignAlgorithm.RSA;
    }

    @Override
    public String sign(String data, String signKey) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signKey)) {
            throw new IllegalArgumentException("待签名数据与 RSA 私钥不能为空");
        }
        try {
            return RsaUtil.signSha256Base64(data, signKey);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("RSA 数据加签失败", e);
        }
    }

    @Override
    public boolean verify(String data, String signature, String verifyKey) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signature) || !StringUtils.hasText(verifyKey)) {
            return false;
        }
        return RsaUtil.verifySha256Base64(data, signature.trim(), verifyKey);
    }
}
