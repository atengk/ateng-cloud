package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.gateway.crypto.enums.GatewayCryptoAlgorithm;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewayCryptoProperties.CryptoKeyVersionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PropertiesGatewayCryptoKeyProvider} 多版本密钥解析与禁用防线单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
class PropertiesGatewayCryptoKeyProviderTest {

    @Test
    @DisplayName("未配置 keys 时按算法专属字段优先、通用字段兜底解析密钥")
    void shouldResolveTopLevelAlgorithmSpecificAndFallback() {
        AtengGatewayCryptoProperties props = new AtengGatewayCryptoProperties();
        props.setActiveKeyId("v1");
        props.setServerPrivateKey("general-priv");
        props.setClientPublicKey("general-pub");
        props.setSm2ServerPrivateKey("sm2-priv");

        PropertiesGatewayCryptoKeyProvider provider = new PropertiesGatewayCryptoKeyProvider(props);

        assertEquals("sm2-priv",
                provider.getDecryptPrivateKey("app", null, GatewayCryptoAlgorithm.SM2_SM4_GCM).block());
        assertEquals("general-priv",
                provider.getDecryptPrivateKey("app", "v1", GatewayCryptoAlgorithm.RSA_AES_GCM).block());
        assertEquals("general-pub",
                provider.getEncryptPublicKey("app", null, GatewayCryptoAlgorithm.SM2_SM4_GCM).block());
    }

    @Test
    @DisplayName("多版本 keys 轮转：命中对应版本返回对应密钥，默认回退 activeKeyId")
    void shouldResolveVersionedKeysWithActiveFallback() {
        AtengGatewayCryptoProperties props = new AtengGatewayCryptoProperties();
        props.setActiveKeyId("v2");

        CryptoKeyVersionProperties v1 = new CryptoKeyVersionProperties();
        v1.setEnabled(true);
        v1.setRsaServerPrivateKey("v1-rsa-priv");
        v1.setRsaClientPublicKey("v1-rsa-pub");

        CryptoKeyVersionProperties v2 = new CryptoKeyVersionProperties();
        v2.setEnabled(true);
        v2.setRsaServerPrivateKey("v2-rsa-priv");
        v2.setRsaClientPublicKey("v2-rsa-pub");

        props.getKeys().put("v1", v1);
        props.getKeys().put("v2", v2);

        PropertiesGatewayCryptoKeyProvider provider = new PropertiesGatewayCryptoKeyProvider(props);

        assertEquals("v1-rsa-priv",
                provider.getDecryptPrivateKey("app", "v1", GatewayCryptoAlgorithm.RSA_AES_GCM).block());
        assertEquals("v2-rsa-pub",
                provider.getEncryptPublicKey("app", null, GatewayCryptoAlgorithm.RSA_AES_GCM).block());
    }

    @Test
    @DisplayName("未知 keyId 或已禁用 keyId 严格 Fail-Closed 抛出 HTTP 400 BusinessException")
    void shouldRejectUnknownOrDisabledKeyIdWith400() {
        AtengGatewayCryptoProperties props = new AtengGatewayCryptoProperties();
        props.setActiveKeyId("v2");

        CryptoKeyVersionProperties v1Disabled = new CryptoKeyVersionProperties();
        v1Disabled.setEnabled(false);
        v1Disabled.setServerPrivateKey("v1-disabled-priv");
        props.getKeys().put("v1", v1Disabled);

        CryptoKeyVersionProperties v2 = new CryptoKeyVersionProperties();
        v2.setEnabled(true);
        v2.setServerPrivateKey("v2-priv");
        props.getKeys().put("v2", v2);

        PropertiesGatewayCryptoKeyProvider provider = new PropertiesGatewayCryptoKeyProvider(props);

        BusinessException disabledEx = assertThrows(BusinessException.class,
                () -> provider.getDecryptPrivateKey("app", "v1", GatewayCryptoAlgorithm.RSA_AES_GCM).block());
        assertEquals(400, disabledEx.getCode());

        BusinessException unknownEx = assertThrows(BusinessException.class,
                () -> provider.getDecryptPrivateKey("app", "v999", GatewayCryptoAlgorithm.RSA_AES_GCM).block());
        assertEquals(400, unknownEx.getCode());

        // 客户端未传 X-Key-Id 时回退 activeKeyId，若服务端激活版本缺失或禁用应返回 500 服务端配置错误
        props.setActiveKeyId("v1");
        BusinessException activeDisabledEx = assertThrows(BusinessException.class,
                () -> provider.getDecryptPrivateKey("app", null, GatewayCryptoAlgorithm.RSA_AES_GCM).block());
        assertEquals(500, activeDisabledEx.getCode());
    }

    @Test
    @DisplayName("未配置密钥或版本存在但缺失具体密钥字段时抛出 HTTP 500 BusinessException")
    void shouldFailWith500WhenConfiguredVersionMissesKeyMaterial() {
        AtengGatewayCryptoProperties props = new AtengGatewayCryptoProperties();
        props.setActiveKeyId("v1");
        CryptoKeyVersionProperties v1 = new CryptoKeyVersionProperties();
        v1.setEnabled(true);
        props.getKeys().put("v1", v1);

        PropertiesGatewayCryptoKeyProvider provider = new PropertiesGatewayCryptoKeyProvider(props);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.getDecryptPrivateKey("app", "v1", GatewayCryptoAlgorithm.SM2_SM4_GCM).block());
        assertEquals(500, ex.getCode());
    }
}
