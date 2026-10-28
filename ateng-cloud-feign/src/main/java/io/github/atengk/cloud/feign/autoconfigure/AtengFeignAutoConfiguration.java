package io.github.atengk.cloud.feign.autoconfigure;

import feign.Feign;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Feign 客户端核心治理自动装配
 *
 * @author Ateng
 * @since 2026-10-28
 */
@AutoConfiguration
@ConditionalOnClass(Feign.class)
@EnableConfigurationProperties(AtengFeignProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.feign", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengFeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(HttpClient5FeignConfiguration.HttpClientBuilderCustomizer.class)
    public HttpClient5FeignConfiguration.HttpClientBuilderCustomizer atengHttpClient5BuilderCustomizer(AtengFeignProperties properties) {
        return builder -> {
            var httpclientProps = properties.getHttpclient();
            if (httpclientProps != null && httpclientProps.isEnabled()) {
                RequestConfig requestConfig = RequestConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(httpclientProps.getConnectionTimeout().toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(httpclientProps.getSocketTimeout().toMillis()))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(httpclientProps.getConnectionRequestTimeout().toMillis()))
                        .build();
                builder.setDefaultRequestConfig(requestConfig);
            }
        };
    }
}
