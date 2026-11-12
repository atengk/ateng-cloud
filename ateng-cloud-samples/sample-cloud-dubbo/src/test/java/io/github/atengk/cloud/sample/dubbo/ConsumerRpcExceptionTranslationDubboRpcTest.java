package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.filter.DubboTrafficTagFilter;
import io.github.atengk.cloud.dubbo.support.DubboExceptionUtils;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcServiceMock;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dubbo 消费端 RpcException 按需因果转译与原生 Mock 降级自适应协同端到端测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@SpringBootTest(properties = {
        "ateng.cloud.dubbo.exception.translate-consumer-rpc-exception=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ConsumerRpcExceptionTranslationDubboRpcTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int livePort = TestSocketUtils.findAvailableTcpPort();
        int unreachablePort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> livePort);
        registry.add("test.consumer.exception.live.port", () -> livePort);
        registry.add("test.consumer.exception.unreachable.port", () -> unreachablePort);
    }

    @DubboReference(url = "tri://127.0.0.1:${test.consumer.exception.unreachable.port}", check = false, timeout = 1000)
    private UserRpcService unreachableUserService;

    @DubboReference(url = "tri://127.0.0.1:${test.consumer.exception.live.port}", check = false, timeout = 150)
    private UserRpcService shortTimeoutUserService;

    @DubboReference(url = "tri://127.0.0.1:${test.consumer.exception.live.port}", check = false)
    private UserRpcService liveUserService;

    @DubboReference(url = "tri://127.0.0.1:${test.consumer.exception.unreachable.port}", check = false, mock = "true", timeout = 1000)
    private UserRpcService mockUnreachableUserService;

    @AfterEach
    void tearDown() {
        TrafficContextHolder.clear();
        MDC.clear();
        RpcContext.removeClientAttachment();
    }

    @AfterAll
    static void restorePropertiesHolder() {
        DubboPropertiesHolder.reset();
    }

    @Test
    @DisplayName("开启转译且未配置 mock 时，目标不可达网络异常转译为 503 SERVICE_UNAVAILABLE 并保留原始 RpcException cause")
    void testUnreachableProviderTranslatedTo503() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> unreachableUserService.ping("network-check"),
                "未启用 mock 时网络不可达必须转译为 BusinessException");

        assertEquals(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), ex.getCode());
        assertInstanceOf(RpcException.class, ex.getCause(), "必须 100% 保留原始 RpcException 作为 cause");
    }

    @Test
    @DisplayName("开启转译且未配置 mock 时，调用超时转译为 504 GATEWAY_TIMEOUT 并保留原始 TIMEOUT RpcException cause")
    void testTimeoutTranslatedTo504() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> shortTimeoutUserService.slowEcho(600L),
                "未启用 mock 时调用超时必须转译为 504 BusinessException");

        assertEquals(DubboExceptionUtils.GATEWAY_TIMEOUT_CODE, ex.getCode());
        RpcException rootCause = assertInstanceOf(RpcException.class, ex.getCause(), "必须 100% 保留原始 RpcException 作为 cause");
        assertTrue(DubboExceptionUtils.isTimeoutRpcException(rootCause), "原始 cause 链应包含超时异常语义");
    }

    @Test
    @DisplayName("开启转译且未配置 mock 时，tagForce=true 无可用提供者转译为 503 SERVICE_UNAVAILABLE 并保留 cause")
    void testTagForceNoProviderTranslatedTo503() {
        RpcContext.getClientAttachment().setAttachment(Constants.FORCE_USE_TAG, "true");
        MDC.put(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER, "offline-canary");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> liveUserService.ping("tagforce-check"),
                "开启消费端转译后 tagForce 无提供者应转译为 503 BusinessException");

        assertEquals(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), ex.getCode());
        assertInstanceOf(RpcException.class, ex.getCause(), "必须 100% 保留原始 RpcException 作为 cause");
    }

    @Test
    @DisplayName("开启转译但配置 mock=true 时，无条件放行原生 RpcException 交由 MockClusterInvoker 触发降级兜底")
    void testMockFallbackPreservedWhenTranslationEnabled() {
        UserVO fallbackUser = mockUnreachableUserService.getUserById(888L);
        assertNotNull(fallbackUser);
        assertEquals("mock-user-fallback", fallbackUser.getUsername());
        assertEquals(UserRpcServiceMock.DEFAULT_MOCK_EMAIL, fallbackUser.getEmail());

        String slowFallback = mockUnreachableUserService.slowEcho(600L);
        assertEquals(UserRpcServiceMock.MOCK_TAG + " slow-fallback:600", slowFallback,
                "配置 mock=true 时必须放行原生 RpcException 并触发 UserRpcServiceMock 兜底");
    }
}
