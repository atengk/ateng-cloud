package io.github.atengk.cloud.gateway.spi;

/**
 * 开放接口数字签名应用密钥提供者 SPI 扩展点
 *
 * <p>业务系统可通过实现本接口并注入 Spring 容器，实现从数据库、配置中心或 KMS 中动态获取 AppId 对应的 Secret 密钥。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FunctionalInterface
public interface SignSecretProvider {

    /**
     * 根据开放应用标识 AppId 获取对应的签名 Secret
     *
     * @param appId 开放应用唯一标识
     * @return 签名 Secret 密钥明文 (若返回 null 或空字符串则表示 AppId 无效或不存在)
     */
    String getSecret(String appId);
}
