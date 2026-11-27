package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties;
import io.github.atengk.cloud.gateway.properties.AtengGatewaySignProperties.SignAppProperties;
import io.github.atengk.cloud.gateway.sign.enums.GatewaySignAlgorithm;
import io.github.atengk.cloud.gateway.spi.GatewaySignKeyProvider;
import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 基于配置属性与旧版 {@link SignSecretProvider} 桥接的响应式签名密钥提供者默认实现
 *
 * <p>优先从 {@code ateng.cloud.gateway.sign.apps.<appId>.*} 解析独立应用密钥，
 * 单应用配置场景或出站共享私钥可平滑回退至顶层属性；回退调用同步 {@link SignSecretProvider} SPI 时
 * 自动卸载至有界弹性调度器，严守 Netty EventLoop 零阻塞红线。</p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class PropertiesGatewaySignKeyProvider implements GatewaySignKeyProvider {

    private final AtengGatewaySignProperties properties;
    private final SignSecretProvider legacySecretProvider;

    public PropertiesGatewaySignKeyProvider(AtengGatewaySignProperties properties) {
        this(properties, (SignSecretProvider) null);
    }

    public PropertiesGatewaySignKeyProvider(AtengGatewaySignProperties properties,
                                            ObjectProvider<SignSecretProvider> legacySecretProvider) {
        this(properties, legacySecretProvider != null ? legacySecretProvider.getIfAvailable() : null);
    }

    public PropertiesGatewaySignKeyProvider(AtengGatewaySignProperties properties,
                                            SignSecretProvider legacySecretProvider) {
        this.properties = properties != null ? properties : new AtengGatewaySignProperties();
        this.legacySecretProvider = legacySecretProvider;
    }

    @Override
    public Mono<String> getSecretKey(String appId) {
        String normalizedAppId = trimOrNull(appId);
        if (normalizedAppId == null) {
            return Mono.empty();
        }
        String configuredSecret = resolveConfiguredSecret(normalizedAppId);
        if (configuredSecret != null) {
            return Mono.just(configuredSecret);
        }
        if (legacySecretProvider == null) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> trimOrNull(legacySecretProvider.getSecret(normalizedAppId)))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(StringUtils::hasText);
    }

    @Override
    public Mono<String> getPublicKey(String appId) {
        return Mono.justOrEmpty(resolveAsymmetricKeySync(appId, null, false));
    }

    @Override
    public Mono<String> getPrivateKey(String appId) {
        return Mono.justOrEmpty(resolveAsymmetricKeySync(appId, null, true));
    }

    @Override
    public Mono<String> getVerifyKey(String appId, GatewaySignAlgorithm algorithm) {
        if (!StringUtils.hasText(appId) || algorithm == null) {
            return Mono.empty();
        }
        if (algorithm == GatewaySignAlgorithm.HMAC_SHA256) {
            return getSecretKey(appId);
        }
        return Mono.justOrEmpty(resolveAsymmetricKeySync(appId, algorithm, false));
    }

    @Override
    public Mono<String> getSignKey(String appId, GatewaySignAlgorithm algorithm) {
        if (!StringUtils.hasText(appId) || algorithm == null) {
            return Mono.empty();
        }
        if (algorithm == GatewaySignAlgorithm.HMAC_SHA256) {
            return getSecretKey(appId);
        }
        return Mono.justOrEmpty(resolveAsymmetricKeySync(appId, algorithm, true));
    }

    private String resolveConfiguredSecret(String normalizedAppId) {
        SignAppProperties appProps = resolveAppProperties(normalizedAppId);
        if (appProps != null && StringUtils.hasText(appProps.getSecretKey())) {
            return appProps.getSecretKey().trim();
        }
        if (canFallbackTopLevel(appProps, normalizedAppId) && StringUtils.hasText(properties.getSecretKey())) {
            return properties.getSecretKey().trim();
        }
        return null;
    }

    private String resolveAsymmetricKeySync(String appId, GatewaySignAlgorithm algorithm, boolean isPrivate) {
        String normalizedAppId = trimOrNull(appId);
        if (normalizedAppId == null) {
            return null;
        }
        SignAppProperties appProps = resolveAppProperties(normalizedAppId);
        if (appProps != null) {
            String appKey = extractKey(
                    algorithm, isPrivate,
                    appProps.getRsaPublicKey(), appProps.getRsaPrivateKey(),
                    appProps.getSm2PublicKey(), appProps.getSm2PrivateKey(),
                    appProps.getPublicKey(), appProps.getPrivateKey()
            );
            if (appKey != null) {
                return appKey;
            }
            if (!isPrivate && !isConfiguredDefaultApp(normalizedAppId)) {
                return null;
            }
        }
        if ((isPrivate && appProps != null) || canFallbackTopLevel(appProps, normalizedAppId)) {
            return extractKey(
                    algorithm, isPrivate,
                    properties.getRsaPublicKey(), properties.getRsaPrivateKey(),
                    properties.getSm2PublicKey(), properties.getSm2PrivateKey(),
                    properties.getPublicKey(), properties.getPrivateKey()
            );
        }
        return null;
    }

    private static String extractKey(GatewaySignAlgorithm algorithm,
                                     boolean isPrivate,
                                     String rsaPublic,
                                     String rsaPrivate,
                                     String sm2Public,
                                     String sm2Private,
                                     String generalPublic,
                                     String generalPrivate) {
        String specific = null;
        if (algorithm == GatewaySignAlgorithm.RSA) {
            specific = isPrivate ? rsaPrivate : rsaPublic;
        } else if (algorithm == GatewaySignAlgorithm.SM2) {
            specific = isPrivate ? sm2Private : sm2Public;
        }
        if (StringUtils.hasText(specific)) {
            return specific.trim();
        }
        String fallback = isPrivate ? generalPrivate : generalPublic;
        return trimOrNull(fallback);
    }

    private SignAppProperties resolveAppProperties(String normalizedAppId) {
        Map<String, SignAppProperties> apps = properties.getApps();
        if (apps == null || apps.isEmpty() || normalizedAppId == null) {
            return null;
        }
        return apps.get(normalizedAppId);
    }

    private boolean canFallbackTopLevel(SignAppProperties appProps, String normalizedAppId) {
        if (appProps != null) {
            return isConfiguredDefaultApp(normalizedAppId);
        }
        Map<String, SignAppProperties> apps = properties.getApps();
        return apps == null || apps.isEmpty() || isConfiguredDefaultApp(normalizedAppId);
    }

    private boolean isConfiguredDefaultApp(String normalizedAppId) {
        return normalizedAppId != null
                && StringUtils.hasText(properties.getAppId())
                && properties.getAppId().trim().equals(normalizedAppId);
    }

    private static String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
