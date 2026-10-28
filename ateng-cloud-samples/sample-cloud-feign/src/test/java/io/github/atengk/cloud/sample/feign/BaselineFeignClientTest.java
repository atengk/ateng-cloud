package io.github.atengk.cloud.sample.feign;

import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import io.github.atengk.cloud.sample.feign.client.BaselineFeignClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 基础连通性与 HC5 连接池端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class BaselineFeignClientTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int port = TestSocketUtils.findAvailableTcpPort();
        registry.add("server.port", () -> port);
        registry.add("test.server.port", () -> port);
    }

    @Autowired
    private BaselineFeignClient baselineFeignClient;

    @Autowired(required = false)
    private AtengFeignProperties feignProperties;

    @Autowired(required = false)
    private feign.Client feignClient;

    @Test
    @DisplayName("端到端验证 FeignClient 发起 HTTP GET 请求成功并接收响应")
    void testEcho() {
        String response = baselineFeignClient.echo("Hello Feign");
        assertEquals("Echo: Hello Feign", response);
    }

    @Test
    @DisplayName("验证 AtengFeignProperties 配置类正确注入且生产参数生效")
    void testFeignProperties() {
        assertNotNull(feignProperties, "AtengFeignProperties 必须被 Spring 容器受管初始化");
        assertTrue(feignProperties.isEnabled(), "ateng.cloud.feign 默认应为启用");
        assertNotNull(feignProperties.getHttpclient(), "HttpClient 配置不可为空");
        assertEquals(200, feignProperties.getHttpclient().getMaxConnections());
        assertEquals(50, feignProperties.getHttpclient().getMaxConnectionsPerRoute());
    }

    @Test
    @DisplayName("验证底层传输客户端已成功装配为 ApacheHttp5Client 连接池实例")
    void testHttpClient5Active() {
        assertNotNull(feignClient, "feign.Client 必须被 Spring 容器受管初始化");
        assertTrue(feignClient instanceof feign.hc5.ApacheHttp5Client,
                "底层通信传输客户端必须是 feign.hc5.ApacheHttp5Client 实例");
    }
}
