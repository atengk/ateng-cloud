package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Dubbo 经典 TCP dubbo 协议与 Fastjson2 序列化端到端集成测试 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(properties = {
        "dubbo.protocol.name=dubbo",
        "ateng.cloud.dubbo.protocol.name=dubbo"
})
public class ClassicDubboRpcTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int tcpDubboPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> tcpDubboPort);
        registry.add("test.classic.dubbo.port", () -> tcpDubboPort);
    }

    @DubboReference(url = "dubbo://127.0.0.1:${test.classic.dubbo.port}", check = false)
    private UserRpcService userRpcService;

    @Test
    @DisplayName("端到端验证经典 TCP dubbo 协议 RPC 调用与 Fastjson2 传输原生 POJO 实体成功")
    void testClassicDubboGetUserById() {
        assertNotNull(userRpcService, "UserRpcService DubboReference 代理对象不可为空");
        UserVO user = userRpcService.getUserById(777L);
        assertNotNull(user, "返回的用户实体不可为空");
        assertEquals(777L, user.getId());
        assertEquals("ateng_777", user.getUsername());
        assertEquals("ateng_777@example.com", user.getEmail());
    }

    @Test
    @DisplayName("端到端验证经典 TCP dubbo 协议 ping 探活正常")
    void testClassicDubboPing() {
        assertNotNull(userRpcService, "UserRpcService DubboReference 代理对象不可为空");
        String response = userRpcService.ping("classic-tcp");
        assertEquals("pong: classic-tcp", response);
    }
}
