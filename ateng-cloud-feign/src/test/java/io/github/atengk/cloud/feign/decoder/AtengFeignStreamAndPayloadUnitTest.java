package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import feign.codec.Decoder;
import feign.querymap.BeanQueryMapEncoder;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.feign.annotation.AdaptiveUnwrap;
import io.github.atengk.cloud.feign.properties.AtengFeignClientProperties;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 二进制流直接透传与响应载荷硬防爆单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignStreamAndPayloadUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Response createMockResponse(byte[] body, Map<String, Collection<String>> headers) {
        return Response.builder()
                .status(200)
                .reason("OK")
                .request(Request.create(Request.HttpMethod.GET, "/test", Collections.emptyMap(), null, new RequestTemplate()))
                .headers(headers != null ? headers : Collections.emptyMap())
                .body(body)
                .build();
    }

    @Test
    @DisplayName("1. 验证 byte[] 二进制流返回声明直接透传，豁免 JSON 校验与解包")
    void testByteArrayPassthrough() throws Exception {
        byte[] expected = "binary content 123".getBytes(StandardCharsets.UTF_8);
        Decoder mockDelegate = (response, type) -> expected;

        AtengFeignProperties properties = new AtengFeignProperties();
        AtengFeignDecoder decoder = new AtengFeignDecoder(mockDelegate, properties, objectMapper);

        Response response = createMockResponse(expected, null);
        Object result = decoder.decode(response, byte[].class);

        assertSame(expected, result, "byte[] 必须直接交由底层 delegate 解码透传");
    }

    @Test
    @DisplayName("2. 验证 Resource / InputStream 流式返回声明直接透传")
    void testResourceAndInputStreamPassthrough() throws Exception {
        Resource expectedResource = new ByteArrayResource("file content".getBytes(StandardCharsets.UTF_8));
        Decoder mockDelegate = (response, type) -> expectedResource;

        AtengFeignProperties properties = new AtengFeignProperties();
        AtengFeignDecoder decoder = new AtengFeignDecoder(mockDelegate, properties, objectMapper);

        Response response = createMockResponse("file content".getBytes(StandardCharsets.UTF_8), null);
        Object result = decoder.decode(response, Resource.class);

        assertSame(expectedResource, result);
    }

    @Test
    @DisplayName("3. 验证响应体大小超过 maxResponseSize 硬防爆限制时直接阻断抛出 PAYLOAD_TOO_LARGE")
    void testMaxResponseSizeEnforcedOnStreamingRead() {
        AtengFeignProperties properties = new AtengFeignProperties();
        properties.getPayload().setMaxResponseSize(DataSize.ofKilobytes(1)); // 限制 1KB

        Decoder mockDelegate = (response, type) -> null;
        AtengFeignDecoder decoder = new AtengFeignDecoder(mockDelegate, properties, objectMapper);

        // 构造 2KB 的超大响应体
        byte[] largeBody = new byte[2048];
        Response response = createMockResponse(largeBody, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> decoder.decode(response, String.class));
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("超出硬防爆限制"));
    }

    @Test
    @DisplayName("4. 验证 Content-Length 标头超大时前置快速失败")
    void testContentLengthHeaderFastFail() {
        AtengFeignProperties properties = new AtengFeignProperties();
        properties.getPayload().setMaxResponseSize(DataSize.ofKilobytes(10)); // 限制 10KB

        Decoder mockDelegate = (response, type) -> null;
        AtengFeignDecoder decoder = new AtengFeignDecoder(mockDelegate, properties, objectMapper);

        Map<String, Collection<String>> headers = Map.of("content-length", List.of("20480")); // 20KB
        Response response = createMockResponse("small body".getBytes(StandardCharsets.UTF_8), headers);

        BusinessException ex = assertThrows(BusinessException.class, () -> decoder.decode(response, String.class));
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("Content-Length"));
    }

    @Test
    @DisplayName("5. 验证 BeanQueryMapEncoder 能够将 POJO 查询对象平铺映射为 Map 参数")
    void testBeanQueryMapEncoderEncodesPojo() {
        BeanQueryMapEncoder encoder = new BeanQueryMapEncoder();

        TestQuery query = new TestQuery();
        query.setKeyword("spring");
        query.setPage(1);
        query.setSize(20);

        Map<String, Object> map = encoder.encode(query);
        assertNotNull(map);
        assertEquals("spring", map.get("keyword"));
        assertEquals(1, map.get("page"));
        assertEquals(20, map.get("size"));
    }

    public static class TestQuery {
        private String keyword;
        private Integer page;
        private Integer size;

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public Integer getPage() {
            return page;
        }

        public void setPage(Integer page) {
            this.page = page;
        }

        public Integer getSize() {
            return size;
        }

        public void setSize(Integer size) {
            this.size = size;
        }
    }

    @FeignClient(name = "report-service", contextId = "smallLimitReportClient")
    interface SmallLimitReportClient {
    }

    @FeignClient(name = "report-service", contextId = "normalReportClient")
    interface NormalReportClient {
    }

    @FeignClient(name = "report-service", contextId = "rawThirdPartyReportClient")
    @AdaptiveUnwrap(false)
    interface RawThirdPartyReportClient {
    }

    private Response createClientResponse(Class<?> clientType, String serviceName, byte[] body, Map<String, Collection<String>> headers) {
        RequestTemplate template = new RequestTemplate();
        template.feignTarget(new Target.HardCodedTarget<>(clientType, serviceName, "http://" + serviceName));
        Request request = Request.create(Request.HttpMethod.GET, "/report", Collections.emptyMap(), null, StandardCharsets.UTF_8, template);
        return Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(headers != null ? headers : Collections.emptyMap())
                .body(body)
                .build();
    }

    @Test
    @DisplayName("6. 验证单客户端 max-response-size 遵循 contextId 优先、服务名回退及全局基线回退")
    void testPerClientMaxResponseSizePrecedenceAndFallback() throws Exception {
        AtengFeignProperties properties = new AtengFeignProperties();
        // 全局默认 10MB，先配置服务名级 4KB、contextId 级 256B
        AtengFeignClientProperties serviceProps = new AtengFeignClientProperties();
        serviceProps.setMaxResponseSize(DataSize.ofKilobytes(4));
        properties.getClients().put("report-service", serviceProps);

        AtengFeignClientProperties smallCtxProps = new AtengFeignClientProperties();
        smallCtxProps.setMaxResponseSize(DataSize.ofBytes(256));
        properties.getClients().put("smallLimitReportClient", smallCtxProps);

        Decoder mockDelegate = (response, type) -> "ok";
        AtengFeignDecoder decoder = new AtengFeignDecoder(mockDelegate, properties, objectMapper);

        byte[] payload512 = ("{\"code\":200,\"data\":\"" + "a".repeat(480) + "\"}").getBytes(StandardCharsets.UTF_8);

        // 1. smallLimitReportClient 命中 contextId 级 256B -> 512B 报文触发熔断
        BusinessException exCtx = assertThrows(BusinessException.class,
                () -> decoder.decode(createClientResponse(SmallLimitReportClient.class, "report-service", payload512, null), String.class));
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), exCtx.getCode());

        // 2. normalReportClient 未单独配置 -> 回退服务名 report-service (4KB)，512B 放行，6KB 触发熔断
        Object ok512 = decoder.decode(createClientResponse(NormalReportClient.class, "report-service", payload512, null), String.class);
        assertNotNull(ok512);

        byte[] payload6KB = ("{\"code\":200,\"data\":\"" + "b".repeat(6 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        BusinessException exServiceFallback = assertThrows(BusinessException.class,
                () -> decoder.decode(createClientResponse(NormalReportClient.class, "report-service", payload6KB, null), String.class));
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), exServiceFallback.getCode());

        // 3. 当全局设较小上限 256B 而单客户端放大至 4KB 时，单客户端放行 512B 报文
        AtengFeignProperties boostProps = new AtengFeignProperties();
        boostProps.getPayload().setMaxResponseSize(DataSize.ofBytes(256));
        AtengFeignClientProperties boostedClient = new AtengFeignClientProperties();
        boostedClient.setMaxResponseSize(DataSize.ofKilobytes(4));
        boostProps.getClients().put("normalReportClient", boostedClient);

        AtengFeignDecoder boostDecoder = new AtengFeignDecoder(mockDelegate, boostProps, objectMapper);
        Object boostedResult = boostDecoder.decode(createClientResponse(NormalReportClient.class, "report-service", payload512, null), String.class);
        assertNotNull(boostedResult);
    }

    @Test
    @DisplayName("7. 验证禁用解包与返回 Result 时仍受单客户端 max-response-size 保护，且非法非正值阈值 Fail-Fast")
    void testPayloadGuardForNonUnwrapAndInvalidThreshold() {
        AtengFeignProperties properties = new AtengFeignProperties();
        AtengFeignClientProperties rawProps = new AtengFeignClientProperties();
        rawProps.setMaxResponseSize(DataSize.ofBytes(256));
        properties.getClients().put("rawThirdPartyReportClient", rawProps);

        Decoder mockDelegate = (response, type) -> Result.success("ok");
        AtengFeignDecoder decoder = new AtengFeignDecoder(mockDelegate, properties, objectMapper);

        byte[] payload512 = ("{\"id\":1,\"text\":\"" + "x".repeat(500) + "\"}").getBytes(StandardCharsets.UTF_8);

        // 1. @AdaptiveUnwrap(false) 非流式响应同样受单客户端防爆上限约束
        BusinessException rawEx = assertThrows(BusinessException.class,
                () -> decoder.decode(createClientResponse(RawThirdPartyReportClient.class, "report-service", payload512, null), String.class));
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), rawEx.getCode());

        // 2. 显式返回 Result<String> 同样受单客户端防爆上限约束
        BusinessException resultEx = assertThrows(BusinessException.class,
                () -> decoder.decode(createClientResponse(RawThirdPartyReportClient.class, "report-service", payload512, null), Result.class));
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), resultEx.getCode());

        // 3. 非正数 maxResponseSize 触发 Fail-Fast 异常
        AtengFeignProperties invalidProps = new AtengFeignProperties();
        AtengFeignClientProperties zeroProps = new AtengFeignClientProperties();
        zeroProps.setMaxResponseSize(DataSize.ofBytes(0));
        invalidProps.getClients().put("normalReportClient", zeroProps);

        AtengFeignDecoder invalidDecoder = new AtengFeignDecoder(mockDelegate, invalidProps, objectMapper);
        assertThrows(IllegalArgumentException.class,
                () -> invalidDecoder.decode(createClientResponse(NormalReportClient.class, "report-service", payload512, null), String.class));
    }
}
