package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dubbo 基础连通性与 Triple 协议端到端集成测试 (支持离线自洽与真实 Nacos 注册发现双模自适应)
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest
public class BaselineDubboRpcTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String activeProfile = System.getProperty("spring.profiles.active");
        if (activeProfile == null) {
            activeProfile = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        boolean isLocal = "local".equalsIgnoreCase(activeProfile);
        if (isLocal) {
            // 本地真实 Nacos 环境下：固定使用 50051 端口注册至物理 Nacos，置空 direct URL 触发 Nacos 服务发现寻址
            registry.add("dubbo.protocol.port", () -> 50051);
            registry.add("test.dubbo.port", () -> 50051);
            registry.add("dubbo.test.reference.url", () -> "");
        } else {
            // 离线自洽环境下 (默认/CI)：动态分配随机端口并通过直连 URL 极速执行，100% 离线免依赖
            int dubboPort = TestSocketUtils.findAvailableTcpPort();
            registry.add("dubbo.protocol.port", () -> dubboPort);
            registry.add("test.dubbo.port", () -> dubboPort);
            registry.add("dubbo.test.reference.url", () -> "tri://127.0.0.1:" + dubboPort);
        }
    }

    @DubboReference(url = "${dubbo.test.reference.url:}", check = false, providedBy = "sample-cloud-dubbo")
    private UserRpcService userRpcService;

    @Autowired(required = false)
    private AtengDubboProperties dubboProperties;

    @Test
    @DisplayName("端到端验证 Triple 协议 RPC 调用 ping 探活方法成功 (支持离线直连与 Nacos 注册发现双模自适应)")
    void testPing() {
        assertNotNull(userRpcService, "UserRpcService DubboReference 代理对象不可为空");
        String response = userRpcService.ping("hello-triple");
        assertEquals("pong: hello-triple", response);
    }

    @Test
    @DisplayName("端到端验证 Triple 协议与 Fastjson2 二进制序列化传输原生 POJO 实体成功")
    void testGetUserById() {
        assertNotNull(userRpcService, "UserRpcService DubboReference 代理对象不可为空");
        UserVO user = userRpcService.getUserById(888L);
        assertNotNull(user, "返回的用户实体不可为空");
        assertEquals(888L, user.getId());
        assertEquals("ateng_888", user.getUsername());
        assertEquals("ateng_888@example.com", user.getEmail());
    }

    @Test
    @DisplayName("验证 AtengDubboProperties 正确注入且生产安全基线参数生效")
    void testDubboProperties() {
        assertNotNull(dubboProperties, "AtengDubboProperties 必须被 Spring 容器受管初始化");
        assertTrue(dubboProperties.isEnabled(), "ateng.cloud.dubbo 默认应为启用");
        assertEquals(0, dubboProperties.getRetries(), "生产基线默认 retries 必须为 0 (禁止写请求重试)");
        assertEquals(5000, dubboProperties.getTimeout().toMillis(), "生产基线默认超时必须为 5000ms");
        assertNotNull(dubboProperties.getProtocol(), "Protocol 配置不可为空");
        assertEquals("tri", dubboProperties.getProtocol().getName(), "默认通信协议应为 Triple (tri)");
        assertEquals("fastjson2", dubboProperties.getProtocol().getSerialization(), "默认序列化应为 fastjson2");
        assertEquals("instance", dubboProperties.getApplication().getRegisterMode(), "服务发现模式应为纯应用级 instance");
    }
}
