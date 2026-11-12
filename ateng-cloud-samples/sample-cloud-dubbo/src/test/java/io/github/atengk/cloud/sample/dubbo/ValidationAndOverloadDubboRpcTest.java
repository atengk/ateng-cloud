package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.cloud.dubbo.overload.DubboOverloadHandler;
import io.github.atengk.cloud.dubbo.overload.DubboOverloadListener;
import io.github.atengk.cloud.dubbo.overload.DubboOverloadThreadPool;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.model.UserCreateRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dubbo JSR-303 参数防卫校验与服务端线程池过载保护端到端测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest
public class ValidationAndOverloadDubboRpcTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int dubboPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> dubboPort);
        registry.add("test.validation.dubbo.port", () -> dubboPort);
        registry.add("ateng.cloud.dubbo.validation.enabled", () -> "true");
        registry.add("ateng.cloud.dubbo.overload.enabled", () -> "true");
        registry.add("ateng.cloud.dubbo.overload.metrics-enabled", () -> "true");
    }

    @DubboReference(url = "tri://127.0.0.1:${test.validation.dubbo.port}", check = false)
    private UserRpcService userRpcService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @AfterEach
    void tearDown() {
        RpcContext.removeClientAttachment();
    }

    @Test
    @DisplayName("JSR-303 格式校验通过时正常执行业务并返回结果")
    void testUserValidationSuccess() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        UserCreateRequest validReq = new UserCreateRequest("ateng-admin", 25);
        UserVO vo = userRpcService.createUser(validReq);

        assertNotNull(vo, "创建用户响应不可为空");
        assertEquals("ateng-admin", vo.getUsername());
    }

    @Test
    @DisplayName("当用户名为空触发 JSR-303 违规时，自动转译为结构化 BusinessException(BAD_REQUEST)")
    void testUserValidationFailureOnBlankUsername() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        UserCreateRequest invalidReq = new UserCreateRequest("", 25);
        BusinessException ex = assertThrows(BusinessException.class, () -> userRpcService.createUser(invalidReq),
                "用户名为空必须被 JSR-303 防卫拦截并转译为 BusinessException");

        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex.getCode(), "错误码必须精确为 400 (BAD_REQUEST)");
        assertTrue(ex.getMessage().contains("用户名不能为空") || ex.getMessage().contains("username"),
                "错误描述必须清晰指出用户名违规");
    }

    @Test
    @DisplayName("当年龄小于 1 触发 JSR-303 违规时，自动转译为结构化 BusinessException(BAD_REQUEST)")
    void testUserValidationFailureOnInvalidAge() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        UserCreateRequest invalidReq = new UserCreateRequest("ateng", 0);
        BusinessException ex = assertThrows(BusinessException.class, () -> userRpcService.createUser(invalidReq),
                "年龄不合法必须被拦截并转译为 BusinessException");

        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex.getCode(), "错误码必须精确为 400 (BAD_REQUEST)");
        assertTrue(ex.getMessage().contains("年龄必须大于0") || ex.getMessage().contains("age"),
                "错误描述必须清晰指出年龄违规");
    }

    @Test
    @DisplayName("批量集合与数组入参合法时（List/ValidList/UserCreateRequest[]）正常穿透执行并返回结果")
    void testBatchUserValidationSuccess() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        List<UserVO> listResult = userRpcService.batchCreateUsers(List.of(
                new UserCreateRequest("batch-list-1", 22),
                new UserCreateRequest("batch-list-2", 28)
        ));
        assertEquals(2, listResult.size());
        assertEquals("batch-list-1", listResult.get(0).getUsername());

        ValidList<UserCreateRequest> validList = ValidList.of(
                new UserCreateRequest("batch-validlist-1", 25)
        );
        List<UserVO> validListResult = userRpcService.batchCreateUsers(validList);
        assertEquals(1, validListResult.size());
        assertEquals("batch-validlist-1", validListResult.get(0).getUsername());

        UserCreateRequest[] arrayReq = new UserCreateRequest[]{
                new UserCreateRequest("batch-array-1", 31),
                new UserCreateRequest("batch-array-2", 35)
        };
        List<UserVO> arrayResult = userRpcService.batchCreateUsersArray(arrayReq);
        assertEquals(2, arrayResult.size());
        assertEquals("batch-array-2", arrayResult.get(1).getUsername());
    }

    @Test
    @DisplayName("List/ValidList/对象数组任一元素违反 JSR-303 约束时，RPC 边界短路拦截并返回 400 BusinessException")
    void testBatchUserValidationFailureFailFast() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        List<UserCreateRequest> invalidList = List.of(
                new UserCreateRequest("ok-user", 20),
                new UserCreateRequest("", 25)
        );
        BusinessException listEx = assertThrows(BusinessException.class,
                () -> userRpcService.batchCreateUsers(invalidList),
                "List 内非法元素必须被 DubboValidationFilter 短路拦截");
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), listEx.getCode());
        assertTrue(listEx.getMessage().contains("参数校验失败") && listEx.getMessage().contains("[1]"),
                "异常消息应包含违规元素索引路径 [1]");

        ValidList<UserCreateRequest> invalidValidList = ValidList.of(
                new UserCreateRequest("ok-user", 20),
                new UserCreateRequest("bad-age", 0)
        );
        BusinessException validListEx = assertThrows(BusinessException.class,
                () -> userRpcService.batchCreateUsers(invalidValidList),
                "ValidList 内非法元素必须被 DubboValidationFilter 短路拦截");
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), validListEx.getCode());
        assertTrue(validListEx.getMessage().contains("[1]") && validListEx.getMessage().contains("age"));

        UserCreateRequest[] invalidArray = new UserCreateRequest[]{
                new UserCreateRequest("ok-user", 20),
                new UserCreateRequest(" ", 25)
        };
        BusinessException arrayEx = assertThrows(BusinessException.class,
                () -> userRpcService.batchCreateUsersArray(invalidArray),
                "对象数组内非法元素必须被 DubboValidationFilter 短路拦截");
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), arrayEx.getCode());
        assertTrue(arrayEx.getMessage().contains("[1]") && arrayEx.getMessage().contains("username"));
    }

    @Test
    @DisplayName("服务端发生线程池耗尽过载异常时，DubboOverloadFilter 自动转译为受管 BusinessException(TOO_MANY_REQUESTS)")
    void testProviderThreadOverloadProtection() {
        assertNotNull(userRpcService, "UserRpcService 引用不可为空");

        long countBefore = DubboOverloadListener.getRejectedCount();

        BusinessException ex = assertThrows(BusinessException.class, () -> userRpcService.simulateOverload(),
                "线程池耗尽必须被拦截转译为受管业务异常");

        assertEquals(CommonResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode(), "过载保护状态码必须为 429 (TOO_MANY_REQUESTS)");
        assertTrue(ex.getMessage().contains("服务端并发线程池已耗尽") || ex.getMessage().contains("限流"),
                "错误描述必须体现限流过载保护语义");
        assertTrue(DubboOverloadListener.getRejectedCount() > countBefore, "过载拒绝次数应递增");
    }

    @Test
    @DisplayName("定制 DubboOverloadHandler 拒绝策略执行时抛出 BusinessException(TOO_MANY_REQUESTS) 并记录过载诊断")
    void testDubboOverloadHandlerExecution() {
        DubboOverloadHandler handler = new DubboOverloadHandler("TestWorkerPool");
        ThreadPoolExecutor mockPool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));

        long countBefore = DubboOverloadListener.getRejectedCount();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> handler.rejectedExecution(() -> {}, mockPool),
                "拒绝处理器必须抛出 BusinessException");

        assertEquals(CommonResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        assertTrue(DubboOverloadListener.getRejectedCount() > countBefore, "全局拒绝总数应递增");
        mockPool.shutdown();
    }

    @Test
    @DisplayName("Micrometer 指标成功绑定并可采集线程池拒绝计数与状态")
    void testMicrometerMetricsExport() {
        assertNotNull(meterRegistry, "Spring Boot Actuator MeterRegistry 应自动装配成功");

        FunctionCounter counter = meterRegistry.find("dubbo.threadpool.reject").functionCounter();
        assertNotNull(counter, "必须成功注册 dubbo.threadpool.reject 拒绝指标");
        assertTrue(counter.count() >= 0.0, "拒绝指标计数值应非负");
    }

    @Test
    @DisplayName("验证 DubboOverloadThreadPool SPI 正确装配并挂载 DubboOverloadHandler 拒绝策略")
    void testDubboOverloadThreadPoolSpi() {
        DubboOverloadThreadPool threadPool = new DubboOverloadThreadPool();
        org.apache.dubbo.common.URL url = org.apache.dubbo.common.URL.valueOf("dubbo://127.0.0.1:20880?threadname=TestDubboOverloadPool");
        java.util.concurrent.Executor executor = threadPool.getExecutor(url);
        assertNotNull(executor, "过载保护线程池不可为空");
        assertTrue(executor instanceof ThreadPoolExecutor, "线程池底层应为 ThreadPoolExecutor 实例");
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
        assertTrue(tpe.getRejectedExecutionHandler() instanceof DubboOverloadHandler, "拒绝策略必须为 DubboOverloadHandler");
        tpe.shutdown();
    }
}
