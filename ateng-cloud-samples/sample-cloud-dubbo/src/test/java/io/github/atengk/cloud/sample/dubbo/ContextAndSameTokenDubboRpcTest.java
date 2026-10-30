package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.filter.DubboContextPropagationFilter;
import io.github.atengk.cloud.dubbo.filter.DubboSameTokenFilter;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.seata.core.context.RootContext;
import io.github.atengk.cloud.traffic.context.TrafficContext;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dubbo 全链路四维上下文透传、零信任 Same-Token 防穿透与线程池确定性清理端到端测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest
public class ContextAndSameTokenDubboRpcTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int dubboPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> dubboPort);
        registry.add("test.context.dubbo.port", () -> dubboPort);
        registry.add("ateng.cloud.dubbo.same-token.enabled", () -> "true");
        registry.add("ateng.cloud.dubbo.same-token.check", () -> "true");
    }

    @DubboReference(url = "tri://127.0.0.1:${test.context.dubbo.port}", check = false)
    private UserRpcService userRpcService;

    @AfterEach
    void tearDown() {
        if (RootContext.inGlobalTransaction()) {
            RootContext.unbind();
        }
        TrafficContextHolder.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
        RpcContext.removeClientAttachment();
    }

    @Test
    @DisplayName("端到端验证四维上下文、灰度染色标记、泳道元数据与 Seata XID 跨进程透明透传并由服务端正确还原")
    void testFourDimensionalContextPropagation() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        // 1. 在消费端线程设置四维上下文快照、TrafficContext 泳道元数据与 Seata 全局事务 XID
        MDC.put("traceId", "dubbo-trace-99998888");
        UserContextHolder.setUserId("user-ateng-admin");
        TenantContextHolder.setTenantId("tenant-enterprise-001");
        TenantContextHolder.setIgnoreTenant(true);
        TrafficContextHolder.setContext(TrafficContext.of("gray-canary-v2", "user-lane-01", "rule-user-vip"));
        RootContext.bind("192.168.1.50:8091:8877665544");

        // 2. 发起 RPC 调用获取服务端上下文镜像
        Map<String, String> providerContext = userRpcService.getProviderContext();

        // 3. 强断言服务端接收到的上下文与消费端 100% 吻合
        assertNotNull(providerContext, "服务端上下文快照不可为空");
        assertEquals("dubbo-trace-99998888", providerContext.get("traceId"), "Trace ID 跨进程透传不匹配");
        assertEquals("user-ateng-admin", providerContext.get("userId"), "User ID 跨进程透传不匹配");
        assertEquals("tenant-enterprise-001", providerContext.get("tenantId"), "Tenant ID 跨进程透传不匹配");
        assertEquals("true", providerContext.get("ignoreTenant"), "IgnoreTenant 跨进程透传不匹配");
        assertEquals("gray-canary-v2", providerContext.get("trafficTag"), "TrafficTag 跨进程染色透传不匹配");
        assertEquals("user-lane-01", providerContext.get("trafficLane"), "TrafficLane 跨进程泳道名透传不匹配");
        assertEquals("rule-user-vip", providerContext.get("trafficRule"), "TrafficRule 跨进程规则标识透传不匹配");
        assertEquals("192.168.1.50:8091:8877665544", providerContext.get("xid"), "Seata XID 跨进程透传不匹配");
        assertEquals("192.168.1.50:8091:8877665544", RootContext.getXID(), "消费端当前线程 XID 应保持不变");
        assertEquals("gray-canary-v2", TrafficContextHolder.getTag(), "消费端当前线程 TrafficContext 应保持不变");
    }

    @Test
    @DisplayName("验证服务端在请求执行结束后执行确定性清理，杜绝 Netty / Dubbo 线程池上下文脏读")
    void testProviderThreadDeterministicCleanup() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        // 1. 发起一次携带上下文的调用
        UserContextHolder.setUserId("dirty-user-before");
        TenantContextHolder.setTenantId("dirty-tenant-before");
        MDC.put("traceId", "dirty-trace-before");
        RootContext.bind("dirty-xid-before");
        TrafficContextHolder.setContext(TrafficContext.of("dirty-tag-before", "dirty-lane", "dirty-rule"));
        userRpcService.getProviderContext();

        // 2. 清空消费端上下文后再次发起调用
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
        RootContext.unbind();
        TrafficContextHolder.clear();
        Map<String, String> cleanContext = userRpcService.getProviderContext();

        // 3. 强断言服务端未发生上下文残留脏读
        assertNotNull(cleanContext, "服务端上下文不可为空");
        assertNull(cleanContext.get("userId"), "服务端线程池发生 userId 上下文残留污染");
        assertNull(cleanContext.get("tenantId"), "服务端线程池发生 tenantId 上下文残留污染");
        assertNull(cleanContext.get("traceId"), "服务端线程池发生 traceId 上下文残留污染");
        assertNull(cleanContext.get("xid"), "服务端线程池发生 Seata XID 事务上下文残留污染");
        assertNull(cleanContext.get("trafficTag"), "服务端线程池发生 trafficTag 上下文残留污染");
        assertNull(cleanContext.get("trafficLane"), "服务端线程池发生 trafficLane 上下文残留污染");
        assertNull(cleanContext.get("trafficRule"), "服务端线程池发生 trafficRule 上下文残留污染");
        assertEquals("false", cleanContext.get("ignoreTenant"), "服务端线程池发生 ignoreTenant 上下文残留污染");
    }

    @Test
    @DisplayName("端到端验证 TrafficContextHolder 泳道元数据跨进程透传与确定性对称清理")
    void testTrafficContextLanePropagationAndDeterministicCleanup() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        TrafficContextHolder.setContext(TrafficContext.of("canary-lane-v1", "feature-lane-auth", "rule-auth-gray"));

        try {
            Map<String, String> providerContext = userRpcService.getProviderContext();
            assertNotNull(providerContext);
            assertEquals("canary-lane-v1", providerContext.get("trafficTag"));
            assertEquals("feature-lane-auth", providerContext.get("trafficLane"));
            assertEquals("rule-auth-gray", providerContext.get("trafficRule"));
            assertEquals("canary-lane-v1", TrafficContextHolder.getTag(), "消费端当前线程流量标签应保持不变");
        } finally {
            TrafficContextHolder.clear();
        }

        // 再次发起调用，强断言服务端已执行确定性清理
        Map<String, String> postContext = userRpcService.getProviderContext();
        assertNull(postContext.get("trafficTag"), "服务端在请求完成后必须彻底清理流量标签");
        assertNull(postContext.get("trafficLane"), "服务端在请求完成后必须彻底清理泳道名");
        assertNull(postContext.get("trafficRule"), "服务端在请求完成后必须彻底清理规则标识");
    }

    @Test
    @DisplayName("端到端验证 Seata 全局分布式事务 XID 双向透明穿透与确定性对称解绑")
    void testSeataDistributedTransactionPropagationAndSymmetricRelease() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        String testXid = "10.20.30.40:8091:202610310001";
        RootContext.bind(testXid);

        try {
            Map<String, String> providerContext = userRpcService.getProviderContext();
            assertNotNull(providerContext);
            assertEquals(testXid, providerContext.get("xid"), "服务端应正确从 Attachment 提取并绑定 XID");
            assertEquals(testXid, RootContext.getXID(), "消费端当前 XID 应保持不变");
        } finally {
            RootContext.unbind();
        }

        // 再次调用，确认服务端已完成对称解绑，不存在残留
        Map<String, String> postContext = userRpcService.getProviderContext();
        assertNull(postContext.get("xid"), "服务端在请求完成后必须对称解绑 XID");
    }

    @Test
    @DisplayName("验证零信任防穿透拦截：直连请求携带非法无效 Same-Token 时被 Fail-Fast 401 阻断")
    void testSameTokenInvalidRejection() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        // 显式在当前 RPC 调用中覆盖写入非法的 Same-Token
        RpcContext.getClientAttachment().setAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY, "invalid-token-tampered");
        RpcContext.getClientAttachment().setAttachment(DubboSameTokenFilter.SA_SAME_TOKEN_KEY, "invalid-token-tampered");

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userRpcService.getProviderContext();
        }, "携带无效 Same-Token 时服务端必须抛出 BusinessException 阻断请求");

        assertEquals(CommonResultCode.UNAUTHORIZED.getCode(), ex.getCode(), "必须返回 401 UNAUTHORIZED 状态码");
        assertTrue(ex.getMessage().contains("Same-Token"), "错误信息必须明确指向 Same-Token 凭据校验失败");
    }

    @Test
    @DisplayName("验证零信任防穿透拦截：直连请求未携带 Same-Token 时被 Fail-Fast 401 阻断")
    void testSameTokenMissingRejection() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        // 显式在当前 RPC 调用中覆盖写入空白的 Same-Token
        RpcContext.getClientAttachment().setAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY, "   ");
        RpcContext.getClientAttachment().setAttachment(DubboSameTokenFilter.SA_SAME_TOKEN_KEY, "   ");

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userRpcService.getProviderContext();
        }, "未携带 Same-Token 时服务端必须抛出 BusinessException 阻断请求");

        assertEquals(CommonResultCode.UNAUTHORIZED.getCode(), ex.getCode(), "必须返回 401 UNAUTHORIZED 状态码");
        assertTrue(ex.getMessage().contains("未携带合法 Same-Token"), "错误信息必须明确提示未携带合法 Same-Token");
    }
}
