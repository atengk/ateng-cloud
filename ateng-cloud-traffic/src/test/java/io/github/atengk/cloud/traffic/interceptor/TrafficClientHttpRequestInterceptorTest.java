package io.github.atengk.cloud.traffic.interceptor;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站 HTTP 客户端流量染色拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class TrafficClientHttpRequestInterceptorTest {

    private AtengTrafficProperties properties;
    private TrafficClientHttpRequestInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new AtengTrafficProperties();
        interceptor = new TrafficClientHttpRequestInterceptor(properties);
        TrafficContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
    }

    @Test
    @DisplayName("测试当存在流量标签时自动向出站请求注入 X-Traffic-Tag 标头")
    void testInjectHeaderWhenTagPresent() throws IOException {
        TrafficContextHolder.setTag("gray-feature-x");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://inventory-service/api/stock"));

        interceptor.intercept(request, new byte[0], (req, body) -> {
            HttpHeaders headers = req.getHeaders();
            assertThat(headers.getFirst(TrafficConstants.HEADER_TRAFFIC_TAG)).isEqualTo("gray-feature-x");
            return null;
        });
    }

    @Test
    @DisplayName("测试当无流量标签时出站请求不注入标头")
    void testNoHeaderWhenNoTag() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://inventory-service/api/stock"));

        interceptor.intercept(request, new byte[0], (req, body) -> {
            HttpHeaders headers = req.getHeaders();
            assertThat(headers.containsKey(TrafficConstants.HEADER_TRAFFIC_TAG)).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("测试关闭 laneContinuity 时出站不透传标签")
    void testNoHeaderWhenLaneContinuityDisabled() throws IOException {
        properties.setLaneContinuity(false);
        TrafficContextHolder.setTag("gray-feature-y");
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://inventory-service/api/stock"));

        interceptor.intercept(request, new byte[0], (req, body) -> {
            HttpHeaders headers = req.getHeaders();
            assertThat(headers.containsKey(TrafficConstants.HEADER_TRAFFIC_TAG)).isFalse();
            return null;
        });
    }
}
