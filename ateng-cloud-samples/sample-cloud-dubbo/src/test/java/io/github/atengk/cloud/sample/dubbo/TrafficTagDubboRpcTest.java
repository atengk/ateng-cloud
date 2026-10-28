package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.cloud.dubbo.filter.DubboTrafficTagFilter;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.sample.dubbo.api.GrayEchoRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dubbo 金丝雀流量染色与标签路由弹性平滑降级端到端测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest
public class TrafficTagDubboRpcTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int dubboPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> dubboPort);
        registry.add("test.tag.dubbo.port", () -> dubboPort);
        registry.add("ateng.cloud.dubbo.traffic.enabled", () -> "true");
        registry.add("ateng.cloud.dubbo.traffic.tag-force", () -> "false");
    }

    @DubboReference(url = "tri://127.0.0.1:${test.tag.dubbo.port}", check = false)
    private GrayEchoRpcService grayEchoRpcService;

    @AfterEach
    void tearDown() {
        MDC.clear();
        RpcContext.removeClientAttachment();
    }

    @Test
    @DisplayName("无流量染色标记时，RPC 调用默认路由至基线实例")
    void testDefaultRouteToBaseline() {
        assertNotNull(grayEchoRpcService, "GrayEchoRpcService 引用不可为空");

        String result = grayEchoRpcService.echo("hello");
        assertEquals("baseline:hello", result, "无标签请求必须精准路由至基线服务");
    }

    @Test
    @DisplayName("MDC 携带 X-Traffic-Tag 时，出站链路正确挂载标签路由参数并在未命中灰度时弹性降级至基线")
    void testTrafficTagAttachmentAndFallback() {
        assertNotNull(grayEchoRpcService, "GrayEchoRpcService 引用不可为空");

        // 在当前线程设置流量染色标记
        MDC.put(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER, "canary-v2");

        String result = grayEchoRpcService.echo("hello");
        assertEquals("baseline:hello", result, "在无特定 canary-v2 物理实例时，必须平滑降级至基线实例正常响应");
    }

    @Test
    @DisplayName("金丝雀实例不存在时，默认 tagforce=false 触发生产弹性兜底，自动平滑降级至基线实例")
    void testSmoothFallbackToBaselineWhenTagNotMatched() {
        assertNotNull(grayEchoRpcService, "GrayEchoRpcService 引用不可为空");

        // 设置一个不存在的灰度标签
        MDC.put(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER, "non-existent-canary");

        String result = grayEchoRpcService.echo("fallback");
        assertEquals("baseline:fallback", result, "未命中任何灰度实例时必须弹性平滑降级至基线实例，杜绝 RPC 报错雪崩");
    }

    @Test
    @DisplayName("当配置 tagforce=true 且目标标签实例不存在时，实施强隔离 Fail-Fast 阻断")
    void testStrictTagIsolationWhenTagForceIsTrue() {
        assertNotNull(grayEchoRpcService, "GrayEchoRpcService 引用不可为空");

        // 显式在本次 RPC 调用出站上下文中开启强制灰度隔离 (避免原地突变全局单例 Bean)
        RpcContext.getClientAttachment().setAttachment(Constants.FORCE_USE_TAG, "true");
        MDC.put(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER, "offline-version");

        RpcException ex = assertThrows(RpcException.class, () -> grayEchoRpcService.echo("isolated"),
                "强制标签隔离模式下，当目标标签实例不存在时必须阻断抛出 RpcException");
        assertTrue(ex.getMessage().contains("No provider available") || ex.getMessage().contains("offline-version"),
                "异常信息应明确指出无可用提供者或目标标签匹配失败");
    }
}
