package io.github.atengk.cloud.feign.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Capability;
import feign.Feign;
import feign.InvocationHandlerFactory;
import feign.QueryMapEncoder;
import feign.Retryer;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.micrometer.MicrometerObservationCapability;
import feign.optionals.OptionalDecoder;
import feign.querymap.BeanQueryMapEncoder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.decoder.AtengFeignDecoder;
import io.github.atengk.cloud.feign.decoder.AtengFeignErrorDecoder;
import io.github.atengk.cloud.feign.interceptor.FeignContextPropagationInterceptor;
import io.github.atengk.cloud.feign.logger.AtengFeignLoggerFactory;
import io.github.atengk.cloud.feign.preheat.AtengFeignPreheatListener;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import io.github.atengk.cloud.feign.retry.AtengFeignRetryer;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;
import io.github.atengk.cloud.feign.support.SaTokenSameTokenProvider;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.cloud.openfeign.FeignLoggerFactory;
import io.github.atengk.cloud.feign.interceptor.AtengFeignAcceptGzipEncodingInterceptor;
import io.github.atengk.cloud.feign.interceptor.AtengFeignContentGzipEncodingInterceptor;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.ApplicationContext;
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
@AutoConfigureBefore(FeignAutoConfiguration.class)
@ConditionalOnClass(Feign.class)
@EnableConfigurationProperties(AtengFeignProperties.class)
@ConditionalOnProperty(prefix = "ateng.cloud.feign", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengFeignAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(HttpClientConnectionManager.class)
    @ConditionalOnClass(HttpClientConnectionManager.class)
    public HttpClientConnectionManager hc5ConnectionManager(AtengFeignProperties properties) {
        var httpclientProps = properties.getHttpclient();
        var builder = PoolingHttpClientConnectionManagerBuilder.create();
        if (httpclientProps != null && httpclientProps.isEnabled()) {
            builder.setMaxConnTotal(httpclientProps.getMaxConnections());
            builder.setMaxConnPerRoute(httpclientProps.getMaxConnectionsPerRoute());
            if (httpclientProps.getTimeToLive() != null) {
                builder.setConnectionTimeToLive(TimeValue.ofMilliseconds(httpclientProps.getTimeToLive().toMillis()));
            }
        }
        return builder.build();
    }

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
    @ConditionalOnMissingBean(QueryMapEncoder.class)
    public QueryMapEncoder feignQueryMapEncoder() {
        return new BeanQueryMapEncoder();
    }

    @Bean
    @ConditionalOnClass(name = "feign.micrometer.MicrometerObservationCapability")
    @ConditionalOnBean(ObservationRegistry.class)
    @ConditionalOnMissingBean(MicrometerObservationCapability.class)
    public MicrometerObservationCapability feignMicrometerObservationCapability(ObservationRegistry registry) {
        return new MicrometerObservationCapability(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ateng.cloud.feign.preheat", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AtengFeignPreheatListener atengFeignPreheatListener(ApplicationContext applicationContext, AtengFeignProperties properties) {
        return new AtengFeignPreheatListener(applicationContext, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public FeignBuilderCustomizer atengFeignBuilderCustomizer(ObjectProvider<Retryer> retryerProvider,
                                                             ObjectProvider<QueryMapEncoder> queryMapEncoderProvider,
                                                             ObjectProvider<Capability> capabilitiesProvider) {
        return builder -> {
            // 开启 void 方法返回体的响应解码 (使 void 接口也能解析校验 Result<Void> 的 code 业务状态)
            builder.decodeVoid();

            // 挂载防雪崩弹性重试器
            Retryer retryer = retryerProvider.getIfAvailable();
            if (retryer != null) {
                builder.retryer(retryer);
            }

            // 挂载 SpringQueryMap / BeanQueryMapEncoder 参数平铺适配器
            QueryMapEncoder queryMapEncoder = queryMapEncoderProvider.getIfAvailable(BeanQueryMapEncoder::new);
            builder.queryMapEncoder(queryMapEncoder);

            // 挂载度量监控等扩展能力 (如 MicrometerObservationCapability)
            capabilitiesProvider.orderedStream().forEach(builder::addCapability);

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
    @ConditionalOnMissingBean(name = "atengFeignClientPropertiesSyncPostProcessor")
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

    @Bean
    @ConditionalOnMissingBean(AtengFeignAcceptGzipEncodingInterceptor.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.feign.compression", name = "response-enabled", havingValue = "true")
    public AtengFeignAcceptGzipEncodingInterceptor atengFeignAcceptGzipEncodingInterceptor() {
        return new AtengFeignAcceptGzipEncodingInterceptor();
    }

    @Bean
    @ConditionalOnMissingBean(AtengFeignContentGzipEncodingInterceptor.class)
    @ConditionalOnProperty(prefix = "ateng.cloud.feign.compression", name = "request-enabled", havingValue = "true")
    public AtengFeignContentGzipEncodingInterceptor atengFeignContentGzipEncodingInterceptor(AtengFeignProperties properties) {
        return new AtengFeignContentGzipEncodingInterceptor(properties.getCompression());
    }
}
