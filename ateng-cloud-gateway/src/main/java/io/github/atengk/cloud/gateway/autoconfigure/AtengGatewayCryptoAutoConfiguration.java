package io.github.atengk.cloud.gateway.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.cloud.gateway.crypto.cipher.RsaAesGcmGatewayCipher;
import io.github.atengk.cloud.gateway.crypto.cipher.Sm2Sm4GcmGatewayCipher;
import io.github.atengk.cloud.gateway.filter.GatewayCryptoFilter;
import io.github.atengk.cloud.gateway.properties.AtengGatewayProperties;
import io.github.atengk.cloud.gateway.provider.PropertiesGatewayCryptoKeyProvider;
import io.github.atengk.cloud.gateway.spi.GatewayApiCipher;
import io.github.atengk.cloud.gateway.spi.GatewayCryptoKeyProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;

/**
 * 网关边缘 API 报文混合加解密防窃听自动装配配置类
 *
 * <p>仅当总控开关与 {@code ateng.cloud.gateway.crypto.enabled=true} 同时开启时激活装配，
 * 默认关闭保持既有接入项目 100% 零侵入向后兼容。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
@AutoConfiguration
@AutoConfigureAfter(AtengGatewayAutoConfiguration.class)
@ConditionalOnClass(GlobalFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBean(AtengGatewayProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.gateway.crypto", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AtengGatewayCryptoAutoConfiguration {

    /**
     * 注册 RSA-OAEP + AES-256-GCM 混合加解密算法执行引擎
     *
     * @return RsaAesGcmGatewayCipher
     */
    @Bean
    @ConditionalOnMissingBean(RsaAesGcmGatewayCipher.class)
    public RsaAesGcmGatewayCipher rsaAesGcmGatewayCipher() {
        return new RsaAesGcmGatewayCipher();
    }

    /**
     * 注册国密 SM2 + SM4-128-GCM 混合加解密算法执行引擎
     *
     * @return Sm2Sm4GcmGatewayCipher
     */
    @Bean
    @ConditionalOnMissingBean(Sm2Sm4GcmGatewayCipher.class)
    public Sm2Sm4GcmGatewayCipher sm2Sm4GcmGatewayCipher() {
        return new Sm2Sm4GcmGatewayCipher();
    }

    /**
     * 注册基于配置属性与多版本字典的加解密密钥提供者默认实现
     *
     * @param properties 网关总控配置属性
     * @return GatewayCryptoKeyProvider
     */
    @Bean
    @ConditionalOnMissingBean(GatewayCryptoKeyProvider.class)
    public GatewayCryptoKeyProvider gatewayCryptoKeyProvider(AtengGatewayProperties properties) {
        return new PropertiesGatewayCryptoKeyProvider(properties.getCrypto());
    }

    /**
     * 注册边缘 API 响应式混合加解密全局过滤器（ORDER_CRYPTO = -95）
     *
     * @param properties      网关总控配置属性
     * @param keyProvider     加解密密钥提供者
     * @param ciphersProvider 算法执行引擎列表提供者
     * @param objectMapper    Jackson 对象映射器
     * @return GatewayCryptoFilter
     */
    @Bean
    @ConditionalOnMissingBean
    public GatewayCryptoFilter gatewayCryptoFilter(
            AtengGatewayProperties properties,
            GatewayCryptoKeyProvider keyProvider,
            ObjectProvider<GatewayApiCipher> ciphersProvider,
            ObjectMapper objectMapper) {
        return new GatewayCryptoFilter(
                properties.getCrypto(),
                keyProvider,
                ciphersProvider.orderedStream().toList(),
                objectMapper
        );
    }
}
