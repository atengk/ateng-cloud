package io.github.atengk.cloud.sample.feign;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.feign.client.AdaptiveFeignClient;
import io.github.atengk.cloud.sample.feign.client.ClassLevelExcludedFeignClient;
import io.github.atengk.cloud.sample.feign.client.ExcludedFeignClient;
import io.github.atengk.cloud.sample.feign.client.ForcedAdaptiveFeignClient;
import io.github.atengk.cloud.sample.feign.model.UserVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 双模自适应契约解包与因果异常转译端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class AdaptiveFeignClientTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int port = TestSocketUtils.findAvailableTcpPort();
        registry.add("server.port", () -> port);
        registry.add("test.server.port", () -> port);
        registry.add("ateng.cloud.feign.adaptive-unwrap.exclude-clients", () -> "excluded-client");
    }

    @Autowired
    private AdaptiveFeignClient adaptiveFeignClient;

    @Autowired
    private ExcludedFeignClient excludedFeignClient;

    @Autowired
    private ClassLevelExcludedFeignClient classLevelExcludedFeignClient;

    @Autowired
    private ForcedAdaptiveFeignClient forcedAdaptiveFeignClient;

    @Test
    @DisplayName("1. 验证声明返回业务对象 T 时，底层自动校验 code==200 并剥离 Result 外壳返回业务实体")
    void testAdaptiveUnwrapSuccess() {
        UserVO user = adaptiveFeignClient.getUserById(1L);
        assertNotNull(user, "剥壳后业务实体不可为空");
        assertEquals(1L, user.getId());
        assertEquals("ateng-1", user.getUsername());
    }

    @Test
    @DisplayName("2. 验证声明返回 Result<T> 时，解码器原样透传不做剥壳")
    void testRawResultPassthrough() {
        Result<UserVO> result = adaptiveFeignClient.getRawUser(2L);
        assertNotNull(result, "原生 Result 容器不可为空");
        assertTrue(result.isSuccess());
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals(2L, result.getData().getId());
        assertEquals("raw-ateng-2", result.getData().getUsername());
    }

    @Test
    @DisplayName("3. 验证对端返回 HTTP 200 但业务报错 code!=200 时，精准转译为受管 BusinessException")
    void testBusinessErrorTranslation() {
        BusinessException ex = assertThrows(BusinessException.class, () -> adaptiveFeignClient.getBusinessError());
        assertEquals(5001, ex.getCode(), "转译后的业务错误码必须与服务端完全一致");
        assertTrue(ex.getMsg().contains("库存不足"), "错误描述文案必须完整保留");
    }

    @Test
    @DisplayName("4. 验证声明返回 void 时，对端返回 code==200 正常执行返回")
    void testVoidSuccess() {
        assertDoesNotThrow(() -> adaptiveFeignClient.voidSuccess());
    }

    @Test
    @DisplayName("5. 验证声明返回 void 时，对端返回 code!=200 触发 BusinessException 阻断")
    void testVoidError() {
        BusinessException ex = assertThrows(BusinessException.class, () -> adaptiveFeignClient.voidError());
        assertEquals(5002, ex.getCode());
        assertTrue(ex.getMsg().contains("无权删除"));
    }

    @Test
    @DisplayName("6. 验证对端返回 HTTP 500 异常时，统一转译为 BusinessException 且完整保留因果链 cause")
    void testHttp500Translation() {
        BusinessException ex = assertThrows(BusinessException.class, () -> adaptiveFeignClient.getHttp500());
        assertNotNull(ex.getCause(), "底层因果链 cause 严禁被吞咽丢弃");
        assertTrue(ex.getCause() instanceof feign.FeignException, "因果链 root cause 必须保留原始 FeignException");
    }

    @Test
    @DisplayName("7. 验证标注 @AdaptiveUnwrap(false) 时，跳过 Result 剥壳，直接反序列化为 UserVO")
    void testThirdPartyDataAnnotationBypass() {
        UserVO user = adaptiveFeignClient.getThirdPartyData();
        assertNotNull(user);
        assertEquals(888L, user.getId());
        assertEquals("third-party-user", user.getUsername());
    }

    @Test
    @DisplayName("8. 验证配置 exclude-clients 白名单排除客户端时，跳过 Result 剥壳，直接反序列化为 UserVO")
    void testExcludeClientsConfigBypass() {
        UserVO user = excludedFeignClient.getThirdPartyData();
        assertNotNull(user);
        assertEquals(888L, user.getId());
        assertEquals("third-party-user", user.getUsername());
    }

    @Test
    @DisplayName("9. 验证类级标注 @AdaptiveUnwrap(false) 时，整接口跳过 Result 剥壳，直接反序列化为 UserVO")
    void testClassLevelAnnotationBypass() {
        UserVO user = classLevelExcludedFeignClient.getThirdPartyData();
        assertNotNull(user);
        assertEquals(888L, user.getId());
        assertEquals("third-party-user", user.getUsername());
    }

    @Test
    @DisplayName("10. 验证客户端虽在 exclude-clients 白名单中，但类级标注 @AdaptiveUnwrap(true) 时强制剥壳（注解优先级最高）")
    void testAnnotationOverridesExcludeClients() {
        UserVO user = forcedAdaptiveFeignClient.getUserById(1L);
        assertNotNull(user, "声明式注解覆盖排除名单后，必须成功剥壳");
        assertEquals(1L, user.getId());
        assertEquals("ateng-1", user.getUsername());
    }

    @Test
    @DisplayName("11. 验证声明返回原生基本类型 long 时，若对端返回 null 数据，触发 BusinessException 阻断裸 NPE")
    void testPrimitiveReturnNullDataFailFast() {
        BusinessException ex = assertThrows(BusinessException.class, () -> adaptiveFeignClient.getPrimitiveNullData());
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("无法接收 null 载荷"));
    }

    @Test
    @DisplayName("12. 验证声明返回包装类型 Long 时，若对端返回 null 数据，正常接收 null 并不抛出异常")
    void testWrapperReturnNullDataSuccess() {
        Long value = adaptiveFeignClient.getWrapperNullData();
        assertNull(value, "包装类型必须能够安全接收 null 返回");
    }

    @Test
    @DisplayName("13. 验证声明返回原生基本类型 boolean 时，若对端返回 HTTP 204 No Content，触发 BusinessException 阻断")
    void testPrimitiveReturnHttp204FailFast() {
        BusinessException ex = assertThrows(BusinessException.class, () -> adaptiveFeignClient.getPrimitive204());
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("boolean"));
    }
}
