package io.github.atengk.cloud.feign.autoconfigure;

import io.github.atengk.cloud.feign.decoder.AtengFeignErrorDecoder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration;

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
}
