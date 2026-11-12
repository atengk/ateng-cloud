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
import io.github.atengk.cloud.sample.feign.model.ToleranceResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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

    @Autowired
    private TestRestTemplate restTemplate;

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

    @Test
    @DisplayName("19. 验证 FeignOrderConsumerController 全量新增体验端点与真实 HTTP 1:1 可达性（含对称上下文还原）")
    void testAllOrderControllerDocumentedHttpRoutes() {
        // 1. 白名单与注解豁免/强制解包控制器方法直调验证
        Result<ProductDetailVO> excludedRes = feignOrderConsumerController.getExcludedClientProduct(1L);
        assertTrue(excludedRes.isSuccess());
        assertEquals("THIRD-PARTY-SKU", excludedRes.getData().getSkuCode());

        Result<ProductDetailVO> classExcludedRes = feignOrderConsumerController.getClassLevelExcludedProduct(2L);
        assertTrue(classExcludedRes.isSuccess());
        assertEquals("THIRD-PARTY-SKU", classExcludedRes.getData().getSkuCode());

        Result<ProductDetailVO> methodExcludedRes = feignOrderConsumerController.getMethodExcludedProduct(3L);
        assertTrue(methodExcludedRes.isSuccess());
        assertEquals("THIRD-PARTY-SKU", methodExcludedRes.getData().getSkuCode());

        Result<ProductDetailVO> forcedRes = feignOrderConsumerController.getForcedUnwrapProduct(1L);
        assertTrue(forcedRes.isSuccess());
        assertEquals("SKU-1", forcedRes.getData().getSkuCode());

        Result<ToleranceResultVO> primitiveGuardRes = feignOrderConsumerController.testPrimitiveNullGuard();
        assertTrue(primitiveGuardRes.isSuccess());
        assertEquals(500, primitiveGuardRes.getData().getErrorCode());

        Result<Long> wrapperNullRes = feignOrderConsumerController.getWrapperNullData();
        assertTrue(wrapperNullRes.isSuccess());
        assertNull(wrapperNullRes.getData());

        Result<String> voidRes = feignOrderConsumerController.testVoidSuccess();
        assertTrue(voidRes.isSuccess());

        // 2. 真实 HTTP 协议 1:1 路由可达性与端到端对称上下文还原验证
        ResponseEntity<String> unwrapHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/unwrap/1", String.class);
        assertEquals(HttpStatus.OK, unwrapHttp.getStatusCode());
        assertTrue(unwrapHttp.getBody() != null && unwrapHttp.getBody().contains("SKU-1"));

        ResponseEntity<String> rawHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/raw/2", String.class);
        assertEquals(HttpStatus.OK, rawHttp.getStatusCode());
        assertTrue(rawHttp.getBody() != null && rawHttp.getBody().contains("RAW-SKU-2"));

        ResponseEntity<String> excludedHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/excluded-unwrap/1", String.class);
        assertEquals(HttpStatus.OK, excludedHttp.getStatusCode());
        assertTrue(excludedHttp.getBody() != null && excludedHttp.getBody().contains("THIRD-PARTY-SKU"));

        ResponseEntity<String> classExcludedHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/class-excluded/1", String.class);
        assertEquals(HttpStatus.OK, classExcludedHttp.getStatusCode());
        assertTrue(classExcludedHttp.getBody() != null && classExcludedHttp.getBody().contains("THIRD-PARTY-SKU"));

        ResponseEntity<String> methodExcludedHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/method-excluded/1", String.class);
        assertEquals(HttpStatus.OK, methodExcludedHttp.getStatusCode());
        assertTrue(methodExcludedHttp.getBody() != null && methodExcludedHttp.getBody().contains("THIRD-PARTY-SKU"));

        ResponseEntity<String> forcedHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/forced-unwrap/1", String.class);
        assertEquals(HttpStatus.OK, forcedHttp.getStatusCode());
        assertTrue(forcedHttp.getBody() != null && forcedHttp.getBody().contains("SKU-1"));

        ResponseEntity<String> queryHttp = restTemplate.getForEntity(
                "/sample/cloud/feign/order/product/query?keyword=手机&category=数码&minPrice=1000&pageNum=1&pageSize=10",
                String.class);
        assertEquals(HttpStatus.OK, queryHttp.getStatusCode());
        assertTrue(queryHttp.getBody() != null && queryHttp.getBody().contains("手机"));

        ResponseEntity<byte[]> downloadHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/invoice/download", byte[].class);
        assertEquals(HttpStatus.OK, downloadHttp.getStatusCode());
        assertNotNull(downloadHttp.getBody());
        assertTrue(new String(downloadHttp.getBody(), StandardCharsets.UTF_8).contains("INVOICE-PDF-BINARY-DATA-SAMPLE-2026"));

        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
        multipartBody.add("file", new ByteArrayResource("hello-attachment".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "proof.txt";
            }
        });
        multipartBody.add("description", "体验上传");
        ResponseEntity<String> uploadHttp = restTemplate.postForEntity(
                "/sample/cloud/feign/order/product/attachment/upload",
                new HttpEntity<>(multipartBody, multipartHeaders),
                String.class);
        assertEquals(HttpStatus.OK, uploadHttp.getStatusCode());
        assertTrue(uploadHttp.getBody() != null && uploadHttp.getBody().contains("filename=proof.txt"));

        // 通过 HTTP Header 入站，由 FeignContextWebFilter 对称还原至 Provider 上下文（不直接读 Header）
        HttpHeaders orderHeaders = new HttpHeaders();
        orderHeaders.setContentType(MediaType.APPLICATION_JSON);
        orderHeaders.add("x-trace-id", "trace-http-sym-100");
        orderHeaders.add("x-user-id", "user-http-007");
        orderHeaders.add("x-tenant-id", "tenant-http-999");
        orderHeaders.add("X-Traffic-Tag", "canary-v2");
        OrderCreateDTO httpDto = new OrderCreateDTO(100L, 2, "北京市海淀区中关村南大街1号", "HTTP 端到端验证");
        ResponseEntity<String> createHttp = restTemplate.postForEntity(
                "/sample/cloud/feign/order/create",
                new HttpEntity<>(httpDto, orderHeaders),
                String.class);
        assertEquals(HttpStatus.OK, createHttp.getStatusCode());
        assertNotNull(createHttp.getBody());
        assertTrue(createHttp.getBody().contains("trace-http-sym-100"));
        assertTrue(createHttp.getBody().contains("user-http-007"));
        assertTrue(createHttp.getBody().contains("tenant-http-999"));
        assertTrue(createHttp.getBody().contains("canary-v2"));

        ResponseEntity<String> primitiveGuardHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/primitive-null-guard", String.class);
        assertEquals(HttpStatus.OK, primitiveGuardHttp.getStatusCode());
        assertTrue(primitiveGuardHttp.getBody() != null && primitiveGuardHttp.getBody().contains("Primitive-Return-Null-FailFast"));

        ResponseEntity<String> wrapperNullHttp = restTemplate.getForEntity("/sample/cloud/feign/order/product/wrapper-null", String.class);
        assertEquals(HttpStatus.OK, wrapperNullHttp.getStatusCode());
        assertTrue(wrapperNullHttp.getBody() != null && wrapperNullHttp.getBody().contains("\"code\":200"));

        ResponseEntity<String> voidHttp = restTemplate.postForEntity("/sample/cloud/feign/order/product/void-success", null, String.class);
        assertEquals(HttpStatus.OK, voidHttp.getStatusCode());
        assertTrue(voidHttp.getBody() != null && voidHttp.getBody().contains("\"code\":200"));
    }
}
