package io.github.atengk.cloud.gateway.sign.signer;

import io.github.atengk.boot.core.util.Sm2Util;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import io.github.atengk.cloud.gateway.spi.GatewayApiSigner;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 基于纯 JDK 17 原生国密 SM2 (GB/T 32918.2-2016) / SM3 的网关非对称签名器
 *
 * <p>底层椭圆曲线运算、密钥解析与签验直接复用 {@link Sm2Util}，兼容 ASN.1 DER 与 Raw 64B 签名格式，零 Bouncy Castle 依赖。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class Sm2GatewaySigner implements GatewayApiSigner {

    private final byte[] userId;

    /**
     * 使用国标默认用户标识 {@code 1234567812345678} 构造签名器
     */
    public Sm2GatewaySigner() {
        this(Sm2Util.defaultUserId());
    }

    /**
     * 使用自定义用户标识字节数组构造签名器
     *
     * @param userId 签名者用户标识字节数组（为空时回退国标默认值）
     */
    public Sm2GatewaySigner(byte[] userId) {
        this.userId = (userId != null && userId.length > 0) ? userId.clone() : Sm2Util.defaultUserId();
    }

    /**
     * 使用自定义用户标识字符串构造签名器
     *
     * @param userId 签名者用户标识字符串（为空时回退国标默认值）
     */
    public Sm2GatewaySigner(String userId) {
        this(StringUtils.hasText(userId) ? userId.getBytes(StandardCharsets.UTF_8) : Sm2Util.defaultUserId());
    }

    @Override
    public boolean supports(GatewaySignAlgorithm algorithm) {
        return algorithm == GatewaySignAlgorithm.SM2;
    }

    @Override
    public String sign(String data, String signKey) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signKey)) {
            throw new IllegalArgumentException("待签名数据与 SM2 私钥不能为空");
        }
        try {
            return Sm2Util.signBase64(data, signKey, this.userId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("SM2 数据加签失败", e);
        }
    }

    @Override
    public boolean verify(String data, String signature, String verifyKey) {
        if (!StringUtils.hasText(data) || !StringUtils.hasText(signature) || !StringUtils.hasText(verifyKey)) {
            return false;
        }
        return Sm2Util.verifyBase64(data, signature.trim(), verifyKey, this.userId);
    }
}
