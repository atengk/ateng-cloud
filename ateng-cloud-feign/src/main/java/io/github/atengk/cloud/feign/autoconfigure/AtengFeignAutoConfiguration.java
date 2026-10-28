package io.github.atengk.cloud.feign.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Feign;
import feign.InvocationHandlerFactory;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.optionals.OptionalDecoder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.decoder.AtengFeignDecoder;
import io.github.atengk.cloud.feign.decoder.AtengFeignErrorDecoder;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.InvocationHandler;

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
    @ConditionalOnMissingBean
    public FeignBuilderCustomizer atengFeignBuilderCustomizer() {
        return builder -> {
            // 开启 void 方法返回体的响应解码 (使 void 接口也能解析校验 Result<Void> 的 code 业务状态)
            builder.decodeVoid();

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
}
