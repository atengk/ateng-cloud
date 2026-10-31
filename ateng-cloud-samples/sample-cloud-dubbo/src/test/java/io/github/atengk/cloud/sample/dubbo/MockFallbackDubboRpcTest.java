package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcServiceMock;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dubbo 3 原生客户端 Mock 服务降级契约与弹性容灾端到端测试 (100% 离线自洽)
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest
public class MockFallbackDubboRpcTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int liveDubboPort = TestSocketUtils.findAvailableTcpPort();
        int unreachablePort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> liveDubboPort);
        registry.add("test.mock.live.port", () -> liveDubboPort);
        registry.add("test.mock.unreachable.port", () -> unreachablePort);
    }

    /**
     * 声明式挂载原生 mock = "true"：当下游不可用时自动触发 UserRpcServiceMock 降级兜底
     */
    @DubboReference(url = "tri://127.0.0.1:${test.mock.unreachable.port}", check = false, mock = "true", timeout = 1000)
    private UserRpcService fallbackMockRpcService;

    /**
     * 正常在线直连服务：具备 mock = "true" 容灾但服务健康时优先响应真实业务数据
     */
    @DubboReference(url = "tri://127.0.0.1:${test.mock.live.port}", check = false, mock = "true")
    private UserRpcService liveRpcService;

    @Test
    @DisplayName("当目标服务不可达时，消费端自动无感触发 UserRpcServiceMock 降级并返回业务保底对象")
    void testMockFallbackWhenProviderUnreachable() {
        assertNotNull(fallbackMockRpcService, "fallbackMockRpcService 引用不可为空");

        // 1. 验证常规方法在目标端口无服务时自动降级
        String pingResult = fallbackMockRpcService.ping("test-unreachable");
        assertTrue(pingResult.startsWith(UserRpcServiceMock.MOCK_TAG), "未可用服务必须触发 Mock 前缀响应");
        assertTrue(pingResult.contains("pong: test-unreachable"), "入参应在降级方法中保留");

        // 2. 验证强类型对象查询在宕机时平滑返回兜底 UserVO
        UserVO fallbackUser = fallbackMockRpcService.getUserById(888L);
        assertNotNull(fallbackUser, "降级兜底用户对象不可为空");
        assertEquals(888L, fallbackUser.getId(), "降级对象应承载查询入参 ID");
        assertEquals("mock-user-fallback", fallbackUser.getUsername(), "应返回受管 Mock 默认用户名");
        assertEquals(UserRpcServiceMock.DEFAULT_MOCK_EMAIL, fallbackUser.getEmail(), "应返回受管 Mock 默认邮箱");

        // 3. 验证 Result 包装契约降级返回成功保底对象
        Result<UserVO> userResult = fallbackMockRpcService.getUserResult(666L);
        assertNotNull(userResult, "Result 包装对象不可为空");
        assertTrue(userResult.isSuccess(), "降级兜底 Result 应标记为成功");
        assertNotNull(userResult.getData(), "Result 数据载体不可为空");
        assertEquals(666L, userResult.getData().getId());

        // 4. 验证受管失败降级契约
        Result<UserVO> failedResult = fallbackMockRpcService.getFailedResult(1L);
        assertNotNull(failedResult);
        assertFalse(failedResult.isSuccess());
        assertEquals(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), failedResult.getCode());
        assertTrue(failedResult.getMsg().contains("Mock 降级"));

        // 5. 验证复杂对象入参方法降级兜底
        UserVO createdMockUser = fallbackMockRpcService.createUser(new io.github.atengk.cloud.sample.dubbo.model.UserCreateRequest("new-client-user", 25));
        assertNotNull(createdMockUser);
        assertEquals("new-client-user-mock", createdMockUser.getUsername());

        // 6. 验证基础类型与上下文降级返回
        assertEquals(-1, fallbackMockRpcService.echoPayload("some-payload"));
        assertEquals("true", fallbackMockRpcService.getProviderContext().get("mock"));

        // 7. 验证异常方法降级静默短路（不向外抛出异常）
        assertDoesNotThrow(() -> fallbackMockRpcService.throwBusinessException(), "异常方法降级应静默短路");
        assertDoesNotThrow(() -> fallbackMockRpcService.throwCustomException(), "底层异常方法降级应静默短路");
        assertDoesNotThrow(() -> fallbackMockRpcService.simulateOverload(), "过载方法降级应静默短路");
    }

    @Test
    @DisplayName("当下游服务正常健康时，即使配置 mock = true 也必须优先响应真实 Provider 业务数据")
    void testLiveProviderCallSucceedsWhenHealthy() {
        assertNotNull(liveRpcService, "liveRpcService 引用不可为空");

        // 1. 验证正常通信响应真实 Provider 数据（无 MOCK_TAG 前缀）
        String ping = liveRpcService.ping("healthy");
        assertEquals("pong: healthy", ping, "服务健康时必须返回真实业务响应");
        assertFalse(ping.contains(UserRpcServiceMock.MOCK_TAG), "服务健康时严禁误触发降级逻辑");

        // 2. 验证返回真实 Provider 用户实体
        UserVO liveUser = liveRpcService.getUserById(100L);
        assertNotNull(liveUser);
        assertEquals(100L, liveUser.getId());
        assertEquals("ateng_100", liveUser.getUsername(), "必须返回真实 Provider 用户名");
        assertEquals("ateng_100@example.com", liveUser.getEmail(), "必须返回真实 Provider 邮箱");
    }
}
