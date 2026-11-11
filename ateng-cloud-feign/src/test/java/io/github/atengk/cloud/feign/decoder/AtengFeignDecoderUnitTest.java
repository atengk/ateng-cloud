package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import feign.codec.Decoder;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.properties.AtengFeignClientProperties;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 解码器与异常解码器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignDecoderUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtengFeignErrorDecoder errorDecoder = new AtengFeignErrorDecoder(objectMapper);

    @Test
    @DisplayName("测试 ErrorDecoder 提取 JSON 报文中的业务错误码并保留因果链")
    void testDecodeJsonBusinessError() {
        String json = "{\"code\":6001,\"message\":\"账户余额不足\",\"data\":null}";
        Request request = Request.create(Request.HttpMethod.GET, "/api/test", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(400)
                .reason("Bad Request")
                .request(request)
                .headers(Collections.emptyMap())
                .body(json, StandardCharsets.UTF_8)
                .build();

        Exception ex = errorDecoder.decode("TestClient#test()", response);
        assertTrue(ex instanceof BusinessException);
        BusinessException be = (BusinessException) ex;
        assertEquals(6001, be.getCode());
        assertEquals("账户余额不足", be.getMsg());
        assertNotNull(be.getCause(), "原始因果链 cause 不可为空");
    }

    @Test
    @DisplayName("测试 ErrorDecoder 对非 JSON 响应依据 HTTP 状态码映射标准错误码")
    void testDecodeHttpFallbackError() {
        Request request = Request.create(Request.HttpMethod.GET, "/api/test", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(503)
                .reason("Service Unavailable")
                .request(request)
                .headers(Collections.emptyMap())
                .body("Downstream Service Unavailable", StandardCharsets.UTF_8)
                .build();

        Exception ex = errorDecoder.decode("TestClient#test()", response);
        assertTrue(ex instanceof BusinessException);
        BusinessException be = (BusinessException) ex;
        assertEquals(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), be.getCode());
        assertNotNull(be.getCause());
    }

    @Test
    @DisplayName("测试 FeignResultSupport 契约识别与字段提取能力")
    void testFeignResultSupport() throws Exception {
        String jsonMsg = "{\"code\":200,\"msg\":\"成功\",\"data\":\"ok\"}";
        var nodeMsg = objectMapper.readTree(jsonMsg);
        assertTrue(FeignResultSupport.isResultObject(nodeMsg));
        assertEquals(200, FeignResultSupport.extractCode(nodeMsg));
        assertEquals("成功", FeignResultSupport.extractMessage(nodeMsg));

        String jsonMessage = "{\"code\":500,\"message\":\"系统错误\"}";
        var nodeMessage = objectMapper.readTree(jsonMessage);
        assertTrue(FeignResultSupport.isResultObject(nodeMessage));
        assertEquals(500, FeignResultSupport.extractCode(nodeMessage));
        assertEquals("系统错误", FeignResultSupport.extractMessage(nodeMessage));

        String nonResult = "{\"status\":200,\"data\":\"ok\"}";
        var nodeNonResult = objectMapper.readTree(nonResult);
        assertFalse(FeignResultSupport.isResultObject(nodeNonResult));
        assertEquals("", FeignResultSupport.extractMessage(nodeNonResult));
    }

    @Test
    @DisplayName("测试 AtengFeignDecoder 对原生基本类型返回 null 载荷实施 Fail-Fast 阻断")
    void testPrimitiveNullThrowsExceptionAcrossAllPrimitives() {
        feign.codec.Decoder delegate = (resp, type) -> null;
        io.github.atengk.cloud.feign.properties.AtengFeignProperties properties = new io.github.atengk.cloud.feign.properties.AtengFeignProperties();
        AtengFeignDecoder decoder = new AtengFeignDecoder(delegate, properties, objectMapper);

        String jsonNull = "{\"code\":200,\"msg\":\"成功\",\"data\":null}";
        Request request = Request.create(Request.HttpMethod.GET, "/api/test", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);

        Class<?>[] primitives = new Class<?>[]{
                byte.class, short.class, int.class, long.class,
                float.class, double.class, boolean.class, char.class
        };

        for (Class<?> primitiveType : primitives) {
            Response response = Response.builder()
                    .status(200)
                    .reason("OK")
                    .request(request)
                    .headers(Collections.emptyMap())
                    .body(jsonNull, StandardCharsets.UTF_8)
                    .build();

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> decoder.decode(response, primitiveType),
                    "原生基本类型 [" + primitiveType.getName() + "] 遇到 null 载荷必须抛出 BusinessException");
            assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex.getCode());
            assertTrue(ex.getMsg().contains("无法接收 null 载荷"));
        }
    }

    @Test
    @DisplayName("测试 AtengFeignDecoder 对 HTTP 204 与空响应体在原生基本类型下统一 Fail-Fast")
    void testPrimitiveNullOn204AndEmptyBody() {
        feign.codec.Decoder delegate = (resp, type) -> null;
        io.github.atengk.cloud.feign.properties.AtengFeignProperties properties = new io.github.atengk.cloud.feign.properties.AtengFeignProperties();
        AtengFeignDecoder decoder = new AtengFeignDecoder(delegate, properties, objectMapper);
        Request request = Request.create(Request.HttpMethod.GET, "/api/test", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);

        // 1. HTTP 204 No Content
        Response response204 = Response.builder()
                .status(204)
                .reason("No Content")
                .request(request)
                .headers(Collections.emptyMap())
                .build();
        BusinessException ex204 = assertThrows(BusinessException.class,
                () -> decoder.decode(response204, boolean.class));
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), ex204.getCode());
        assertTrue(ex204.getMsg().contains("boolean"));

        // 2. HTTP 200 空响应体 (0 字节)
        Response responseEmpty = Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(Collections.emptyMap())
                .body(new byte[0])
                .build();
        BusinessException exEmpty = assertThrows(BusinessException.class,
                () -> decoder.decode(responseEmpty, long.class));
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), exEmpty.getCode());
        assertTrue(exEmpty.getMsg().contains("long"));
    }

    @Test
    @DisplayName("测试 AtengFeignDecoder 对包装类型与 void 正常放行 null 载荷")
    void testWrapperTypesAndVoidAllowNull() throws Exception {
        feign.codec.Decoder delegate = (resp, type) -> null;
        io.github.atengk.cloud.feign.properties.AtengFeignProperties properties = new io.github.atengk.cloud.feign.properties.AtengFeignProperties();
        AtengFeignDecoder decoder = new AtengFeignDecoder(delegate, properties, objectMapper);
        Request request = Request.create(Request.HttpMethod.GET, "/api/test", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);

        String jsonNull = "{\"code\":200,\"msg\":\"成功\",\"data\":null}";
        Response response = Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(Collections.emptyMap())
                .body(jsonNull, StandardCharsets.UTF_8)
                .build();

        // 包装类正常接收 null
        assertNull(decoder.decode(response, Long.class));
        assertNull(decoder.decode(response, Boolean.class));
        assertNull(decoder.decode(response, Integer.class));

        // void 正常返回 null
        assertNull(decoder.decode(response, void.class));
        assertNull(decoder.decode(response, Void.class));
    }

    @FeignClient(name = "order-service", contextId = "orderReadClient")
    interface OrderReadClient {
    }

    @FeignClient(name = "order-service", contextId = "orderWriteClient")
    interface OrderWriteClient {
    }

    record SamplePayload(Long id, String name) {
    }

    private Response createResultResponseForClient(Class<?> clientType, String serviceName) {
        String json = "{\"code\":200,\"msg\":\"成功\",\"data\":{\"id\":101,\"name\":\"unwrapped\"}}";
        RequestTemplate template = new RequestTemplate();
        template.feignTarget(new Target.HardCodedTarget<>(clientType, serviceName, "http://" + serviceName));
        Request request = Request.create(Request.HttpMethod.GET, "/api/orders/101", Collections.emptyMap(), null, StandardCharsets.UTF_8, template);
        return Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(Collections.emptyMap())
                .body(json, StandardCharsets.UTF_8)
                .build();
    }

    @Test
    @DisplayName("测试 exclude-clients 优先匹配 @FeignClient(contextId) 且未命中时平滑回退匹配服务名 name")
    void testExcludeClientsContextIdPrecedenceAndNameFallback() throws Exception {
        Decoder delegate = (resp, type) -> new SamplePayload(999L, "raw-delegate");

        // 1. 仅排除 contextId = orderReadClient，同服务的 orderWriteClient 仍保持解包
        AtengFeignProperties propsByContextId = new AtengFeignProperties();
        propsByContextId.getAdaptiveUnwrap().getExcludeClients().add("orderReadClient");
        AtengFeignDecoder decoderByContextId = new AtengFeignDecoder(delegate, propsByContextId, objectMapper);

        SamplePayload readRes = (SamplePayload) decoderByContextId.decode(
                createResultResponseForClient(OrderReadClient.class, "order-service"), SamplePayload.class);
        assertEquals(999L, readRes.id(), "命中 exclude-clients 中的 contextId 时应跳过解包交由 delegate 处理");

        SamplePayload writeRes = (SamplePayload) decoderByContextId.decode(
                createResultResponseForClient(OrderWriteClient.class, "order-service"), SamplePayload.class);
        assertEquals(101L, writeRes.id(), "未命中 exclude-clients 的同服务另一 contextId 应正常执行解包");

        // 2. 排除服务名 order-service，未配置 contextId 规则时平滑回退匹配服务名排除
        AtengFeignProperties propsByServiceName = new AtengFeignProperties();
        propsByServiceName.getAdaptiveUnwrap().getExcludeClients().add("order-service");
        AtengFeignDecoder decoderByServiceName = new AtengFeignDecoder(delegate, propsByServiceName, objectMapper);

        SamplePayload fallbackRes = (SamplePayload) decoderByServiceName.decode(
                createResultResponseForClient(OrderReadClient.class, "order-service"), SamplePayload.class);
        assertEquals(999L, fallbackRes.id(), "未命中 contextId 时应回退匹配服务名 order-service 排除解包");
    }

    @Test
    @DisplayName("测试 clients.<contextId>.adaptive-unwrap 优先覆盖 exclude-clients 与服务名配置")
    void testClientConfigAdaptiveUnwrapContextIdOverride() throws Exception {
        Decoder delegate = (resp, type) -> new SamplePayload(999L, "raw-delegate");

        // 1. 服务名在 exclude-clients 中，但按 contextId 显式开启 adaptive-unwrap=true -> 必须解包
        AtengFeignProperties propsOverrideExclude = new AtengFeignProperties();
        propsOverrideExclude.getAdaptiveUnwrap().getExcludeClients().add("order-service");
        AtengFeignClientProperties readProps = new AtengFeignClientProperties();
        readProps.setAdaptiveUnwrap(true);
        propsOverrideExclude.getClients().put("orderReadClient", readProps);

        AtengFeignDecoder decoder1 = new AtengFeignDecoder(delegate, propsOverrideExclude, objectMapper);
        SamplePayload unwrapped = (SamplePayload) decoder1.decode(
                createResultResponseForClient(OrderReadClient.class, "order-service"), SamplePayload.class);
        assertEquals(101L, unwrapped.id(), "contextId 级 adaptive-unwrap=true 应覆盖服务名级别 exclude-clients");

        // 2. 服务名设置 adaptive-unwrap=true，但 contextId 设置 adaptive-unwrap=false -> contextId 优先禁用
        AtengFeignProperties propsOverrideName = new AtengFeignProperties();
        AtengFeignClientProperties serviceProps = new AtengFeignClientProperties();
        serviceProps.setAdaptiveUnwrap(true);
        AtengFeignClientProperties ctxDisableProps = new AtengFeignClientProperties();
        ctxDisableProps.setAdaptiveUnwrap(false);
        propsOverrideName.getClients().put("order-service", serviceProps);
        propsOverrideName.getClients().put("orderReadClient", ctxDisableProps);

        AtengFeignDecoder decoder2 = new AtengFeignDecoder(delegate, propsOverrideName, objectMapper);
        SamplePayload bypassed = (SamplePayload) decoder2.decode(
                createResultResponseForClient(OrderReadClient.class, "order-service"), SamplePayload.class);
        assertEquals(999L, bypassed.id(), "contextId 级 adaptive-unwrap=false 应优先于服务名级 adaptive-unwrap=true");

        // 3. 同服务另一 contextId 未单独配置 adaptive-unwrap -> 平滑回退匹配服务名级 adaptive-unwrap=false
        AtengFeignProperties propsFallbackToName = new AtengFeignProperties();
        AtengFeignClientProperties serviceDisable = new AtengFeignClientProperties();
        serviceDisable.setAdaptiveUnwrap(false);
        propsFallbackToName.getClients().put("order-service", serviceDisable);
        propsFallbackToName.getClients().put("orderReadClient", new AtengFeignClientProperties()); // 未设置 adaptiveUnwrap

        AtengFeignDecoder decoder3 = new AtengFeignDecoder(delegate, propsFallbackToName, objectMapper);
        SamplePayload fallbackDisabled = (SamplePayload) decoder3.decode(
                createResultResponseForClient(OrderReadClient.class, "order-service"), SamplePayload.class);
        assertEquals(999L, fallbackDisabled.id(), "contextId 配置中未指定 adaptiveUnwrap 时应回退继承服务名配置");
    }
}
