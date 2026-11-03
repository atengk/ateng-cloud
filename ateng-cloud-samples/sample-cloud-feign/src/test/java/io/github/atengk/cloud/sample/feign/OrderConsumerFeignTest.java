package io.github.atengk.cloud.sample.feign;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.feign.client.ClassLevelExcludedProductFeignClient;
import io.github.atengk.cloud.sample.feign.client.ExcludedProductFeignClient;
import io.github.atengk.cloud.sample.feign.client.ForcedAdaptiveProductFeignClient;
import io.github.atengk.cloud.sample.feign.client.ProductFeignClient;
import io.github.atengk.cloud.sample.feign.controller.FeignOrderConsumerController;
import io.github.atengk.cloud.sample.feign.model.OrderCreateDTO;
import io.github.atengk.cloud.sample.feign.model.OrderDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductDetailVO;
import io.github.atengk.cloud.sample.feign.model.ProductQueryDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.net.ServerSocket;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 订单中心声明式远程调用与自适应解包端到端全场景测试
 * 覆盖：自适应解包、原生透传、GET POJO 平铺、二进制流下载、Multipart 上传、四维上下文与灰度透传、异常转译与空安全防卫
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext
public class OrderConsumerFeignTest {

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        int port = findAvailablePort();
        registry.add("server.port", () -> port);
        registry.add("test.server.port", () -> port);
        registry.add("ateng.cloud.feign.adaptive-unwrap.exclude-clients", () -> "excluded-client");
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            int localPort = socket.getLocalPort();
            if (localPort < 10000) {
                return 20000 + (int) (Math.random() * 30000);
            }
            return localPort;
        } catch (Exception e) {
            return 20000 + (int) (Math.random() * 30000);
        }
    }

    @Autowired
    private ProductFeignClient productFeignClient;

    @Autowired
    private ExcludedProductFeignClient excludedProductFeignClient;

    @Autowired
    private ClassLevelExcludedProductFeignClient classLevelExcludedProductFeignClient;

    @Autowired
    private ForcedAdaptiveProductFeignClient forcedAdaptiveProductFeignClient;

    @Autowired
    private FeignOrderConsumerController feignOrderConsumerController;

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("1. 验证声明返回业务对象 ProductDetailVO 时，底层自动校验 code==200 并剥离 Result 外壳返回业务实体")
    void testAdaptiveUnwrapSuccess() {
        ProductDetailVO product = productFeignClient.getProductById(1L);
        assertNotNull(product, "剥壳后业务实体不可为空");
        assertEquals(1L, product.getId());
        assertEquals("SKU-1", product.getSkuCode());
        assertEquals(0, new BigDecimal("999.00").compareTo(product.getPrice()));
    }

    @Test
    @DisplayName("2. 验证声明返回 Result<ProductDetailVO> 时，解码器原样透传不做剥壳")
    void testRawResultPassthrough() {
        Result<ProductDetailVO> result = productFeignClient.getRawProductById(2L);
        assertNotNull(result, "原生 Result 容器不可为空");
        assertTrue(result.isSuccess());
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals(2L, result.getData().getId());
        assertEquals("RAW-SKU-2", result.getData().getSkuCode());
    }

    @Test
    @DisplayName("3. 验证通过 SpringQueryMap 自动将 GET POJO 展开为 URL Query 参数")
    void testQueryMapAutoBinding() {
        ProductQueryDTO query = new ProductQueryDTO("手机", "数码", new BigDecimal("1000.00"), 1, 20);
        ProductQueryDTO echoed = productFeignClient.queryProducts(query);
        assertNotNull(echoed);
        assertEquals("手机", echoed.getKeyword());
        assertEquals("数码", echoed.getCategory());
        assertEquals(0, new BigDecimal("1000.00").compareTo(echoed.getMinPrice()));
        assertEquals(1, echoed.getPageNum());
        assertEquals(20, echoed.getPageSize());
    }

    @Test
    @DisplayName("4. 验证二进制 byte[] 文件流无条件触发 StreamPassthrough 白名单，无损传输不碰 JSON")
    void testInvoiceStreamDownload() {
        byte[] bytes = productFeignClient.downloadInvoice();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("INVOICE-PDF-BINARY-DATA-SAMPLE-2026"));
    }

    @Test
    @DisplayName("5. 验证 Multipart 表单文件上传与参数混合传输")
    void testAttachmentUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample-invoice.txt",
                "text/plain",
                "Invoice-Content-Bytes-12345".getBytes(StandardCharsets.UTF_8)
        );
        Result<String> result = productFeignClient.uploadAttachment(file, "发票凭证附件");
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertTrue(result.getData().contains("filename=sample-invoice.txt"));
        assertTrue(result.getData().contains("description=发票凭证附件"));
    }

    @Test
    @DisplayName("6. 验证出站透明透传四维上下文 (traceId/userId/tenantId/ignoreTenant)、X-Traffic-Tag 灰度染色")
    void testOrderCreateWithContext() {
        MDC.put("traceId", "trace-e2e-order-10086");
        MDC.put("X-Traffic-Tag", "canary-v2");
        UserContextHolder.setUserId("user-buyer-007");
        TenantContextHolder.setTenantId("tenant-ecommerce-999");

        OrderCreateDTO createDTO = new OrderCreateDTO(100L, 3, "北京市海淀区中关村南大街1号", "请工作日派送");
        Result<OrderDetailVO> orderResult = productFeignClient.createOrder(createDTO);

        assertNotNull(orderResult);
        assertTrue(orderResult.isSuccess());
        OrderDetailVO order = orderResult.getData();
        assertNotNull(order);
        assertEquals("user-buyer-007", order.getBuyerId(), "买家 ID 必须成功透传自 UserContext");
        assertEquals("tenant-ecommerce-999", order.getTenantId(), "多租户 ID 必须成功透传自 TenantContext");
        assertEquals("trace-e2e-order-10086", order.getTraceId(), "链路追踪 TraceId 必须成功透传自 MDC");
        assertEquals("canary-v2", order.getTrafficTag(), "金丝雀灰度染色必须成功透传自 MDC");
        assertEquals(3, order.getQuantity());
        assertEquals(0, new BigDecimal("897.00").compareTo(order.getTotalAmount()));
    }

    @Test
    @DisplayName("7. 验证标注 @AdaptiveUnwrap(false) 时，跳过 Result 剥壳，直接反序列化为实体")
    void testThirdPartyDataAnnotationBypass() {
        ProductDetailVO product = productFeignClient.getThirdPartyProduct(888L);
        assertNotNull(product);
        assertEquals(888L, product.getId());
        assertEquals("THIRD-PARTY-SKU", product.getSkuCode());
    }

    @Test
    @DisplayName("8. 验证配置 exclude-clients 白名单排除客户端时，跳过 Result 剥壳，直接反序列化为实体")
    void testExcludeClientsConfigBypass() {
        ProductDetailVO product = excludedProductFeignClient.getThirdPartyProduct(888L);
        assertNotNull(product);
        assertEquals(888L, product.getId());
        assertEquals("THIRD-PARTY-SKU", product.getSkuCode());
    }

    @Test
    @DisplayName("9. 验证类级标注 @AdaptiveUnwrap(false) 时，整接口跳过 Result 剥壳，直接反序列化为实体")
    void testClassLevelAnnotationBypass() {
        ProductDetailVO product = classLevelExcludedProductFeignClient.getThirdPartyProduct(888L);
        assertNotNull(product);
        assertEquals(888L, product.getId());
        assertEquals("THIRD-PARTY-SKU", product.getSkuCode());
    }

    @Test
    @DisplayName("10. 验证客户端虽在 exclude-clients 白名单中，但类级标注 @AdaptiveUnwrap(true) 时强制剥壳（注解优先级最高）")
    void testAnnotationOverridesExcludeClients() {
        ProductDetailVO product = forcedAdaptiveProductFeignClient.getProductById(1L);
        assertNotNull(product, "声明式注解覆盖排除名单后，必须成功剥壳");
        assertEquals(1L, product.getId());
        assertEquals("SKU-1", product.getSkuCode());
    }

    @Test
    @DisplayName("11. 验证对端返回 HTTP 200 但业务报错 code!=200 时，精准转译为受管 BusinessException")
    void testBusinessErrorTranslation() {
        BusinessException ex = assertThrows(BusinessException.class, () -> productFeignClient.getBusinessError());
        assertEquals(5001, ex.getCode(), "转译后的业务错误码必须与服务端完全一致");
        assertTrue(ex.getMsg().contains("库存不足"), "错误描述文案必须完整保留");
    }

    @Test
    @DisplayName("12. 验证声明返回 void 时，对端返回 code==200 正常执行返回")
    void testVoidSuccess() {
        assertDoesNotThrow(() -> productFeignClient.voidSuccess());
    }

    @Test
    @DisplayName("13. 验证声明返回 void 时，对端返回 code!=200 触发 BusinessException 阻断")
    void testVoidError() {
        BusinessException ex = assertThrows(BusinessException.class, () -> productFeignClient.voidError());
        assertEquals(5002, ex.getCode());
        assertTrue(ex.getMsg().contains("无权删除"));
    }

    @Test
    @DisplayName("14. 验证对端返回 HTTP 500 异常时，统一转译为 BusinessException 且完整保留因果链 cause")
    void testHttp500Translation() {
        BusinessException ex = assertThrows(BusinessException.class, () -> productFeignClient.getHttp500());
        assertNotNull(ex.getCause(), "底层因果链 cause 严禁被吞咽丢弃");
        assertTrue(ex.getCause() instanceof feign.FeignException, "因果链 root cause 必须保留原始 FeignException");
    }

    @Test
    @DisplayName("15. 验证声明返回原生基本类型 long 时，若对端返回 null 数据，触发 BusinessException 阻断裸 NPE")
    void testPrimitiveReturnNullDataFailFast() {
        BusinessException ex = assertThrows(BusinessException.class, () -> productFeignClient.getPrimitiveNullData());
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("无法接收 null 载荷"));
    }

    @Test
    @DisplayName("16. 验证声明返回包装类型 Long 时，若对端返回 null 数据，正常接收 null 并不抛出异常")
    void testWrapperReturnNullDataSuccess() {
        Long value = productFeignClient.getWrapperNullData();
        assertNull(value, "包装类型必须能够安全接收 null 返回");
    }

    @Test
    @DisplayName("17. 验证声明返回原生基本类型 boolean 时，若对端返回 HTTP 204 No Content，触发 BusinessException 阻断")
    void testPrimitiveReturnHttp204FailFast() {
        BusinessException ex = assertThrows(BusinessException.class, () -> productFeignClient.getPrimitive204());
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("boolean"));
    }

    @Test
    @DisplayName("18. 验证 FeignOrderConsumerController 对外接入控制器端到端调用联动")
    void testOrderConsumerControllerEndpoints() {
        // 查商品
        Result<ProductDetailVO> productResult = feignOrderConsumerController.getProductById(1L);
        assertTrue(productResult.isSuccess());
        assertEquals(1L, productResult.getData().getId());

        // 查原生
        Result<ProductDetailVO> rawResult = feignOrderConsumerController.getRawProductById(2L);
        assertTrue(rawResult.isSuccess());
        assertEquals(2L, rawResult.getData().getId());

        // 查分页
        ProductQueryDTO query = new ProductQueryDTO("电脑", "数码", new BigDecimal("5000.00"), 1, 10);
        Result<ProductQueryDTO> queryResult = feignOrderConsumerController.queryProducts(query);
        assertTrue(queryResult.isSuccess());
        assertEquals("电脑", queryResult.getData().getKeyword());

        // 下载发票
        ResponseEntity<byte[]> invoiceEntity = feignOrderConsumerController.downloadInvoice();
        assertTrue(invoiceEntity.getStatusCode().is2xxSuccessful());
        assertNotNull(invoiceEntity.getBody());

        // 创建订单
        OrderCreateDTO createDTO = new OrderCreateDTO(100L, 2, "上海市浦东新区张江高科", "加急");
        Result<OrderDetailVO> createResult = feignOrderConsumerController.createOrder(createDTO);
        assertTrue(createResult.isSuccess());
        assertEquals(2, createResult.getData().getQuantity());
    }
}
