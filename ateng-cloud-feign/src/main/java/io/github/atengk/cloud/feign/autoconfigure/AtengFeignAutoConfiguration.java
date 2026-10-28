package io.github.atengk.cloud.feign.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.InvocationHandlerFactory;
import feign.Retryer;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.optionals.OptionalDecoder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.decoder.AtengFeignDecoder;
import io.github.atengk.cloud.feign.decoder.AtengFeignErrorDecoder;
import io.github.atengk.cloud.feign.interceptor.FeignContextPropagationInterceptor;
import io.github.atengk.cloud.feign.logger.AtengFeignLoggerFactory;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import io.github.atengk.cloud.feign.retry.AtengFeignRetryer;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import io.github.atengk.cloud.feign.support.SaTokenSameTokenProvider;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.cloud.openfeign.FeignLoggerFactory;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.InvocationHandler;
import java.time.Duration;
import java.util.Map;

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

    @Bean
    @ConditionalOnMissingBean
    public Decoder feignDecoder(ObjectFactory<HttpMessageConverters> messageConverters,
                                ObjectProvider<HttpMessageConverterCustomizer> customizers,
                                AtengFeignProperties properties,
                                ObjectProvider<ObjectMapper> objectMapperProvider) {
        Decoder delegate = new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(messageConverters, customizers)));
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new AtengFeignDecoder(delegate, properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ErrorDecoder feignErrorDecoder(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new AtengFeignErrorDecoder(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(Retryer.class)
    public AtengFeignRetryer feignRetryer(AtengFeignProperties properties) {
        return new AtengFeignRetryer(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public FeignBuilderCustomizer atengFeignBuilderCustomizer(ObjectProvider<Retryer> retryerProvider) {
        return builder -> {
            // 开启 void 方法返回体的响应解码 (使 void 接口也能解析校验 Result<Void> 的 code 业务状态)
            builder.decodeVoid();

            // 挂载防雪崩弹性重试器
            Retryer retryer = retryerProvider.getIfAvailable();
            if (retryer != null) {
                builder.retryer(retryer);
            }

            // 挂载动态代理调用拦截，将 DecodeException 内部的 BusinessException 剥壳向外抛出
            builder.invocationHandlerFactory((target, dispatch) -> {
                InvocationHandlerFactory defaultFactory = new InvocationHandlerFactory.Default();
                InvocationHandler defaultHandler = defaultFactory.create(target, dispatch);
                return (proxy, method, args) -> {
                    try {
                        return defaultHandler.invoke(proxy, method, args);
                    } catch (DecodeException e) {
                        if (e.getCause() instanceof BusinessException be) {
                            throw be;
                        }
                        throw e;
                    }
                };
            });
        };
    }

    @Bean
    public static BeanPostProcessor atengFeignClientPropertiesSyncPostProcessor(
            ObjectProvider<AtengFeignProperties> propertiesProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FeignClientProperties feignClientProperties) {
                    AtengFeignProperties properties = propertiesProvider.getIfAvailable();
                    if (properties != null) {
                        syncFeignClientProperties(feignClientProperties, properties);
                    }
                }
                return bean;
            }
        };
    }

    private static void syncFeignClientProperties(FeignClientProperties feignClientProperties,
                                                 AtengFeignProperties properties) {
        // 1. 设置默认全局连接与读取超时 (若未被显式设置)
        if (properties.getHttpclient() != null && properties.getHttpclient().isEnabled()) {
            var defaultConfig = feignClientProperties.getConfig()
                    .computeIfAbsent(feignClientProperties.getDefaultConfig(), k -> new FeignClientProperties.FeignClientConfiguration());
            if (defaultConfig.getConnectTimeout() == null) {
                defaultConfig.setConnectTimeout((int) properties.getHttpclient().getConnectionTimeout().toMillis());
            }
            if (defaultConfig.getReadTimeout() == null) {
                defaultConfig.setReadTimeout((int) properties.getHttpclient().getSocketTimeout().toMillis());
            }
            if (defaultConfig.getLoggerLevel() == null && properties.getLoggerLevel() != null) {
                defaultConfig.setLoggerLevel(properties.getLoggerLevel());
            }
        }

        // 2. 映射单 Client 独立隔离配置 (ateng.cloud.feign.clients.<name>.*)
        if (properties.getClients() != null) {
            for (Map.Entry<String, io.github.atengk.cloud.feign.properties.AtengFeignClientProperties> entry : properties.getClients().entrySet()) {
                String clientName = entry.getKey();
                var clientProps = entry.getValue();
                if (clientProps == null) {
                    continue;
                }
                var config = feignClientProperties.getConfig()
                        .computeIfAbsent(clientName, k -> new FeignClientProperties.FeignClientConfiguration());

                if (clientProps.getConnectTimeout() != null) {
                    config.setConnectTimeout((int) clientProps.getConnectTimeout().toMillis());
                }
                Duration readTimeout = clientProps.getReadTimeout() != null ? clientProps.getReadTimeout() : clientProps.getSocketTimeout();
                if (readTimeout != null) {
                    config.setReadTimeout((int) readTimeout.toMillis());
                }
                if (clientProps.getLoggerLevel() != null) {
                    config.setLoggerLevel(clientProps.getLoggerLevel());
                }
            }
        }
    }

    @Bean
    @ConditionalOnClass(name = "cn.dev33.satoken.same.SaSameUtil")
    @ConditionalOnMissingBean(SameTokenProvider.class)
    public SameTokenProvider saTokenSameTokenProvider() {
        return new SaTokenSameTokenProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.feign.context", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FeignContextPropagationInterceptor feignContextPropagationInterceptor(ObjectProvider<SameTokenProvider> sameTokenProvider) {
        return new FeignContextPropagationInterceptor(sameTokenProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(FeignLoggerFactory.class)
    public FeignLoggerFactory feignLoggerFactory() {
        return new AtengFeignLoggerFactory();
    }

    @Bean
    @ConditionalOnMissingBean(feign.Logger.Level.class)
    public feign.Logger.Level feignLoggerLevel(AtengFeignProperties properties) {
        return properties.getLoggerLevel() != null ? properties.getLoggerLevel() : feign.Logger.Level.BASIC;
    }
}
