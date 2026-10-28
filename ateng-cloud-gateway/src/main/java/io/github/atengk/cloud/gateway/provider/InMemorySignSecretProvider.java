package io.github.atengk.cloud.gateway.provider;

import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存字典的开放应用签名密钥提供者默认实现
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class InMemorySignSecretProvider implements SignSecretProvider {

    private final Map<String, String> secretMap = new ConcurrentHashMap<>();

    public InMemorySignSecretProvider() {
    }

    public InMemorySignSecretProvider(Map<String, String> initialSecrets) {
        if (initialSecrets != null) {
            secretMap.putAll(initialSecrets);
        }
    }

    public void registerSecret(String appId, String secret) {
        if (StringUtils.hasText(appId) && StringUtils.hasText(secret)) {
            secretMap.put(appId, secret);
        }
    }

    public void removeSecret(String appId) {
        if (StringUtils.hasText(appId)) {
            secretMap.remove(appId);
        }
    }

    @Override
    public String getSecret(String appId) {
        if (!StringUtils.hasText(appId)) {
            return null;
        }
        return secretMap.get(appId);
    }
}
