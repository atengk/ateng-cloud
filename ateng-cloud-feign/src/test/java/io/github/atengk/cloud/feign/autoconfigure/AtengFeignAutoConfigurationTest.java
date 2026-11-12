package io.github.atengk.cloud.feign.autoconfigure;

import feign.Logger;
import io.github.atengk.cloud.feign.decoder.AtengFeignErrorDecoder;
import io.github.atengk.cloud.feign.filter.FeignContextWebFilter;
import io.github.atengk.cloud.feign.interceptor.FeignContextPropagationInterceptor;
import io.github.atengk.cloud.feign.properties.AtengFeignContextProperties;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.GetMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengFeignAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengFeignAutoConfiguration.class));

    @Test
    @DisplayName("验证默认配置下 HttpClientConnectionManager 连接池参数与定制器正常装配")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(HttpClientConnectionManager.class);
            HttpClientConnectionManager cm = context.getBean(HttpClientConnectionManager.class);
            assertThat(cm).isInstanceOf(PoolingHttpClientConnectionManager.class);
            PoolingHttpClientConnectionManager pcm = (PoolingHttpClientConnectionManager) cm;
            assertThat(pcm.getMaxTotal()).isEqualTo(200);
            assertThat(pcm.getDefaultMaxPerRoute()).isEqualTo(50);

            assertThat(context).hasSingleBean(HttpClient5FeignConfiguration.HttpClientBuilderCustomizer.class);
            assertThat(context).hasSingleBean(AtengFeignErrorDecoder.class);
        });
    }

    @Test
    @DisplayName("验证自定义配置下连接池最大连接数与每路由连接数生效")
    void testCustomHttpclientPoolConfiguration() {
        contextRunner.withPropertyValues(
                "ateng.cloud.feign.httpclient.max-connections=350",
                "ateng.cloud.feign.httpclient.max-connections-per-route=70"
        ).run(context -> {
            HttpClientConnectionManager cm = context.getBean(HttpClientConnectionManager.class);
            assertThat(cm).isInstanceOf(PoolingHttpClientConnectionManager.class);
            PoolingHttpClientConnectionManager pcm = (PoolingHttpClientConnectionManager) cm;
            assertThat(pcm.getMaxTotal()).isEqualTo(350);
            assertThat(pcm.getDefaultMaxPerRoute()).isEqualTo(70);
        });
    }

    @Test
    @DisplayName("验证 ateng.cloud.feign.enabled=false 时自动装配静默关闭")
    void testDisableAutoConfiguration() {
        contextRunner.withPropertyValues("ateng.cloud.feign.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(HttpClientConnectionManager.class);
                    assertThat(context).doesNotHaveBean(HttpClient5FeignConfiguration.HttpClientBuilderCustomizer.class);
                    assertThat(context).doesNotHaveBean(AtengFeignErrorDecoder.class);
                });
    }

    @Test
    @DisplayName("验证 GZIP 传输压缩拦截器按配置条件装配")
    void testGzipCompressionConfiguration() {
        contextRunner.withPropertyValues(
                "ateng.cloud.feign.compression.request-enabled=true",
                "ateng.cloud.feign.compression.response-enabled=true",
                "ateng.cloud.feign.compression.min-request-size=4KB"
        ).run(context -> {
            assertThat(context).hasSingleBean(io.github.atengk.cloud.feign.interceptor.AtengFeignAcceptGzipEncodingInterceptor.class);
            assertThat(context).hasSingleBean(io.github.atengk.cloud.feign.interceptor.AtengFeignContentGzipEncodingInterceptor.class);
        });
    }

    @Test
    @DisplayName("验证 HC5 连接池主动空闲驱逐与生命周期解耦配置生效")
    void testHttpclientIdleEvictionConfiguration() {
        contextRunner.withPropertyValues(
                "ateng.cloud.feign.httpclient.max-idle-time=45s",
                "ateng.cloud.feign.httpclient.evict-idle-connections-interval=30s",
                "ateng.cloud.feign.httpclient.evict-expired-connections=true"
        ).run(context -> {
            io.github.atengk.cloud.feign.properties.AtengFeignProperties properties =
                    context.getBean(io.github.atengk.cloud.feign.properties.AtengFeignProperties.class);
            assertThat(properties.getHttpclient().getMaxIdleTime()).isEqualTo(java.time.Duration.ofSeconds(45));
            assertThat(properties.getHttpclient().getEvictIdleConnectionsInterval()).isEqualTo(java.time.Duration.ofSeconds(30));
            assertThat(properties.getHttpclient().isEvictExpiredConnections()).isTrue();

            HttpClient5FeignConfiguration.HttpClientBuilderCustomizer customizer =
                    context.getBean(HttpClient5FeignConfiguration.HttpClientBuilderCustomizer.class);
            org.apache.hc.client5.http.impl.classic.HttpClientBuilder builder =
                    org.apache.hc.client5.http.impl.classic.HttpClientBuilder.create();
            // 验证定制器能成功对 HC5 builder 注入空闲驱逐与过期驱逐策略
            customizer.customize(builder);
        });
    }

    @Test
    @DisplayName("验证未配置 max-idle-time 时回退使用 evict-idle-connections-interval 驱动驱逐")
    void testHttpclientIdleEvictionFallbackConfiguration() {
        contextRunner.withPropertyValues(
                "ateng.cloud.feign.httpclient.max-idle-time=",
                "ateng.cloud.feign.httpclient.evict-idle-connections-interval=25s"
        ).run(context -> {
            HttpClient5FeignConfiguration.HttpClientBuilderCustomizer customizer =
                    context.getBean(HttpClient5FeignConfiguration.HttpClientBuilderCustomizer.class);
            org.apache.hc.client5.http.impl.classic.HttpClientBuilder builder =
                    org.apache.hc.client5.http.impl.classic.HttpClientBuilder.create();
            customizer.customize(builder);
        });
    }

    @FeignClient(name = "shared-order-service", contextId = "orderReadClient", url = "http://localhost:8080")
    interface OrderReadClient {
        @GetMapping("/orders/read")
        String read();
    }

    @FeignClient(name = "shared-order-service", contextId = "orderFallbackClient", url = "http://localhost:8080")
    interface OrderFallbackClient {
        @GetMapping("/orders/fallback")
        String fallback();
    }

    @FeignClient(name = "shared-order-service", contextId = "orderNativeOverrideClient", url = "http://localhost:8080")
    interface OrderNativeOverrideClient {
        @GetMapping("/orders/native")
        String nativeOverride();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableFeignClients(clients = {OrderReadClient.class, OrderFallbackClient.class, OrderNativeOverrideClient.class})
    static class TestFeignClientsConfig {
    }

    @Test
    @DisplayName("验证配置同步处理器对 FeignClient 按 contextId 优先、服务名回退合并超时与日志级别，且不覆盖原生显式配置")
    void testClientPropertiesSyncContextIdPrecedenceAndNameFallback() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class,
                        FeignAutoConfiguration.class,
                        AtengFeignAutoConfiguration.class
                ))
                .withUserConfiguration(TestFeignClientsConfig.class)
                .withPropertyValues(
                        "ateng.cloud.feign.clients.shared-order-service.connect-timeout=400ms",
                        "ateng.cloud.feign.clients.shared-order-service.socket-timeout=800ms",
                        "ateng.cloud.feign.clients.shared-order-service.logger-level=FULL",
                        "ateng.cloud.feign.clients.orderReadClient.socket-timeout=250ms",
                        "spring.cloud.openfeign.client.config.orderNativeOverrideClient.read-timeout=999"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FeignClientProperties feignClientProperties = context.getBean(FeignClientProperties.class);

                    // 1. orderReadClient：readTimeout 采用 contextId 优先覆盖(250)，connectTimeout 与 loggerLevel 平滑回退继承自 shared-order-service
                    var readConfig = feignClientProperties.getConfig().get("orderReadClient");
                    assertThat(readConfig).isNotNull();
                    assertThat(readConfig.getReadTimeout()).isEqualTo(250);
                    assertThat(readConfig.getConnectTimeout()).isEqualTo(400);
                    assertThat(readConfig.getLoggerLevel()).isEqualTo(Logger.Level.FULL);

                    // 2. orderFallbackClient：未配置 contextId 专属配置，完整回退继承自 shared-order-service
                    var fallbackConfig = feignClientProperties.getConfig().get("orderFallbackClient");
                    assertThat(fallbackConfig).isNotNull();
                    assertThat(fallbackConfig.getConnectTimeout()).isEqualTo(400);
                    assertThat(fallbackConfig.getReadTimeout()).isEqualTo(800);
                    assertThat(fallbackConfig.getLoggerLevel()).isEqualTo(Logger.Level.FULL);

                    // 3. orderNativeOverrideClient：原生 spring.cloud.openfeign.client.config 显式配置了 readTimeout=999，绝不被覆盖
                    var nativeConfig = feignClientProperties.getConfig().get("orderNativeOverrideClient");
                    assertThat(nativeConfig).isNotNull();
                    assertThat(nativeConfig.getReadTimeout()).isEqualTo(999);
                    assertThat(nativeConfig.getConnectTimeout()).isEqualTo(400);
                    assertThat(nativeConfig.getLoggerLevel()).isEqualTo(Logger.Level.FULL);
                });
    }

    @Test
    @DisplayName("验证 Servlet Web 环境下 FeignContextWebFilter 的条件装配与顺序注册")
    void testServletInboundFilterAutoConfiguration() {
        WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengFeignAutoConfiguration.class));

        // 1. 默认开启入站还原，注册 FilterRegistrationBean 且优先级为 HIGHEST_PRECEDENCE + 100
        webRunner.run(context -> {
            assertThat(context).hasBean("feignContextWebFilter");
            FilterRegistrationBean<?> bean = context.getBean("feignContextWebFilter", FilterRegistrationBean.class);
            assertThat(bean.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
        });

        // 2. context.enabled=false 时同时关闭入站过滤器与出站拦截器装配
        webRunner.withPropertyValues("ateng.cloud.feign.context.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("feignContextWebFilter");
                    assertThat(context).doesNotHaveBean(FeignContextPropagationInterceptor.class);
                });

        // 3. inbound-enabled=false 且未开启 check-same-token 时不装配
        webRunner.withPropertyValues("ateng.cloud.feign.context.inbound-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("feignContextWebFilter"));

        // 4. inbound-enabled=false 但显式开启 check-same-token=true 时仍装配以保障零信任拦截
        webRunner.withPropertyValues(
                        "ateng.cloud.feign.context.inbound-enabled=false",
                        "ateng.cloud.feign.context.check-same-token=true"
                )
                .run(context -> assertThat(context).hasBean("feignContextWebFilter"));

        // 5. 业务侧自定义注册 FeignContextWebFilter Bean 时自动装配平滑退让，防止过滤器重复执行
        webRunner.withBean("customFeignFilter", FeignContextWebFilter.class,
                        () -> new FeignContextWebFilter(new AtengFeignContextProperties(), null, null))
                .run(context -> {
                    assertThat(context).doesNotHaveBean("feignContextWebFilter");
                    assertThat(context).hasSingleBean(FeignContextWebFilter.class);
                });

        // 6. 非 Web 环境不装配 Servlet 过滤器
        contextRunner.run(context -> assertThat(context).doesNotHaveBean("feignContextWebFilter"));
    }

    @Test
    @DisplayName("验证慢调用告警阈值与网络异常转译开关配置绑定及自动装配生效")
    void testSlowCallThresholdAndNetworkExceptionTranslationBinding() {
        contextRunner.withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class,
                        FeignAutoConfiguration.class
                ))
                .withPropertyValues(
                        "ateng.cloud.feign.slow-call-threshold=500ms",
                        "ateng.cloud.feign.translate-network-exceptions=true",
                        "ateng.cloud.feign.clients.orderReadClient.slow-call-threshold=150ms"
                ).run(context -> {
                    assertThat(context).hasNotFailed();
                    io.github.atengk.cloud.feign.properties.AtengFeignProperties properties =
                            context.getBean(io.github.atengk.cloud.feign.properties.AtengFeignProperties.class);
                    assertThat(properties.getSlowCallThreshold()).isEqualTo(java.time.Duration.ofMillis(500));
                    assertThat(properties.isTranslateNetworkExceptions()).isTrue();
                    assertThat(properties.getClients().get("orderReadClient").getSlowCallThreshold())
                            .isEqualTo(java.time.Duration.ofMillis(150));

                    assertThat(context).hasSingleBean(org.springframework.cloud.openfeign.FeignLoggerFactory.class);
                    org.springframework.cloud.openfeign.FeignLoggerFactory loggerFactory =
                            context.getBean(org.springframework.cloud.openfeign.FeignLoggerFactory.class);
                    assertThat(loggerFactory).isInstanceOf(io.github.atengk.cloud.feign.logger.AtengFeignLoggerFactory.class);
                    assertThat(loggerFactory.create(OrderReadClient.class))
                            .isInstanceOf(io.github.atengk.cloud.feign.logger.AtengFeignLogger.class);

                    // 验证容器装配的 atengFeignBuilderCustomizer 实际生效网络异常因果转译
                    org.springframework.cloud.openfeign.FeignBuilderCustomizer customizer = context.getBean(
                            "atengFeignBuilderCustomizer",
                            org.springframework.cloud.openfeign.FeignBuilderCustomizer.class);
                    feign.Feign.Builder builder = feign.Feign.builder()
                            .client((request, options) -> {
                                throw new java.net.SocketTimeoutException("Read timed out");
                            });
                    customizer.customize(builder);
                    SampleTimeoutApi api = builder.target(SampleTimeoutApi.class, "http://demo-service");
                    io.github.atengk.boot.core.exception.BusinessException ex =
                            org.junit.jupiter.api.Assertions.assertThrows(
                                    io.github.atengk.boot.core.exception.BusinessException.class, api::ping);
                    assertThat(ex.getCause()).isInstanceOf(feign.RetryableException.class);
                });
    }

    interface SampleTimeoutApi {
        @feign.RequestLine("GET /ping")
        String ping();
    }
}
