package io.github.atengk.cloud.gateway.spi;

import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;

/**
 * 网关开放接口数字签名与验签算法 SPI 契约
 *
 * <p>支持基于多算法（HMAC-SHA256、RSA、SM2）扩展网关入站验签与出站响应加签能力。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public interface GatewayApiSigner {

    /**
     * 判断当前签名器是否支持指定的签名算法
     *
     * @param algorithm 签名算法枚举
     * @return true 表示支持
     */
    boolean supports(GatewaySignAlgorithm algorithm);

    /**
     * 对规范化待签串生成数字签名
     *
     * @param data    待签名规范化字符串
     * @param signKey 私钥（非对称算法）或对称密钥
     * @return 签名字符串（Base64 或算法标准格式）
     */
    String sign(String data, String signKey);

    /**
     * 校验数字签名合法性
     *
     * @param data      原始待签名规范化字符串
     * @param signature 待校验的签名字符串
     * @param verifyKey 公钥（非对称算法）或对称密钥
     * @return true 表示签名有效；false 表示不匹配或输入非法
     */
    boolean verify(String data, String signature, String verifyKey);
}
