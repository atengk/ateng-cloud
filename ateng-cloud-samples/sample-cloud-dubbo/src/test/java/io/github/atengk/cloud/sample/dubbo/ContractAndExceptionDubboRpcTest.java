package io.github.atengk.cloud.sample.dubbo;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
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
 * Dubbo 双模契约校验与因果异常保真端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest
public class ContractAndExceptionDubboRpcTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int dubboPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("dubbo.protocol.port", () -> dubboPort);
        registry.add("test.contract.dubbo.port", () -> dubboPort);
    }

    @DubboReference(url = "tri://127.0.0.1:${test.contract.dubbo.port}", check = false)
    private UserRpcService userRpcService;

    @Test
    @DisplayName("验证声明返回 Result<T> 且业务成功时正常接收包裹对象并断言成功状态")
    void testSuccessfulResultReturn() {
        assertNotNull(userRpcService, "UserRpcService 代理引用不可为空");
        Result<UserVO> result = userRpcService.getUserResult(123L);
        assertNotNull(result, "RPC 返回的 Result 契约对象不可为空");
        assertTrue(result.isSuccess(), "业务状态必须为成功");
        assertEquals(200, result.getCode());
        assertNotNull(result.getData(), "数据载荷不可为空");
        assertEquals(123L, result.getData().getId());
        assertEquals("ateng_result_123", result.getData().getUsername());
    }

    @Test
    @DisplayName("验证声明返回 Result<T> 且业务失败时由 DubboContractAssertFilter 自动转译为本地 BusinessException")
    void testFailedResultConvertedToBusinessException() {
        assertNotNull(userRpcService, "UserRpcService 代理引用不可为空");
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userRpcService.getFailedResult(999L);
        }, "消费端必须将非 200 业务结果自动转译为 BusinessException 抛出");

        assertEquals(4001, ex.getCode(), "必须原样保留服务端返回的具体业务错误码");
        assertTrue(ex.getMessage().contains("用户状态已被冻结"), "必须原样保留服务端返回的业务错误描述");
    }

    @Test
    @DisplayName("验证服务端抛出受管 BusinessException 时由 AtengDubboExceptionFilter 白名单放行并 100% 因果保真")
    void testBusinessExceptionCausalPreservation() {
        assertNotNull(userRpcService, "UserRpcService 代理引用不可为空");
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userRpcService.throwBusinessException();
        }, "消费端必须直接捕获到原生的 BusinessException");

        assertEquals(CommonResultCode.NOT_FOUND.getCode(), ex.getCode(), "必须精确匹配服务端抛出的 NOT_FOUND 错误码");
        assertTrue(ex.getMessage().contains("用户记录不存在"), "必须精确匹配服务端抛出的业务异常描述");
    }

    @Test
    @DisplayName("验证服务端抛出未声明的底层未受检异常时安全转译为受管 BusinessException 防御反序列化崩溃")
    void testCustomExceptionTranslatedToBusinessException() {
        assertNotNull(userRpcService, "UserRpcService 代理引用不可为空");
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            userRpcService.throwCustomException();
        }, "未检底层异常必须在服务端被安全转译为受管 BusinessException，杜绝 ClassNotFound 崩溃");

        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("模拟底座数据库死锁超时") || ex.getMessage().contains("CustomDatabaseException"),
                "转译后的异常消息必须包含原始底层异常关键诊断信息");
    }
}
