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
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

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

    record GzipSampleUser(Long id, String name) {
    }

    @Test
    @DisplayName("8. 验证 GZIP 响应流透明解压与 Adaptive Unwrap（兼容 HC5 已解压响应与 GZIP 魔数兜底及逐字节流）")
    void testGzipResponseTransparentDecompressionAndAdaptiveUnwrap() throws Exception {
        AtengFeignProperties properties = new AtengFeignProperties();
        Decoder mockDelegate = (resp, type) -> {
            byte[] bytes = resp.body().asInputStream().readAllBytes();
            return objectMapper.readValue(bytes, objectMapper.getTypeFactory().constructType(type));
        };
        AtengFeignDecoder decoder = new AtengFeignDecoder(mockDelegate, properties, objectMapper);

        String rawResultJson = "{\"code\":200,\"msg\":\"成功\",\"data\":{\"id\":42,\"name\":\"gzip-ok\"}}";
        byte[] gzipBytes = gzipCompress(rawResultJson.getBytes(StandardCharsets.UTF_8));

        // 8.1 携带 Content-Encoding: gzip 的标准压缩响应 -> 透明解压并完成 Adaptive Unwrap
        Response gzipWithHeader = createMockResponse(gzipBytes, Map.of("Content-Encoding", List.of("gzip")));
        GzipSampleUser user1 = (GzipSampleUser) decoder.decode(gzipWithHeader, GzipSampleUser.class);
        assertEquals(42L, user1.id());
        assertEquals("gzip-ok", user1.name());

        // 8.2 模拟 HC5 已自动解压（仍保留 Content-Encoding: gzip 标头但响应体已是明文 JSON）-> 不触发 ZipException
        Response hc5AlreadyDecompressed = createMockResponse(
                rawResultJson.getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Encoding", List.of("gzip"))
        );
        GzipSampleUser user2 = (GzipSampleUser) decoder.decode(hc5AlreadyDecompressed, GzipSampleUser.class);
        assertEquals(42L, user2.id());
        assertEquals("gzip-ok", user2.name());

        // 8.3 缺失 Content-Encoding 标头但流首部含 0x1f8b 魔数，且底层流每次仅返回 1 字节 -> 仍能识别并完整解压
        Request request = Request.create(Request.HttpMethod.GET, "/test", Collections.emptyMap(), null, new RequestTemplate());
        Response trickleGzipNoHeader = Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(Collections.emptyMap())
                .body(new OneByteAtATimeInputStream(new ByteArrayInputStream(gzipBytes)), gzipBytes.length)
                .build();
        GzipSampleUser user3 = (GzipSampleUser) decoder.decode(trickleGzipNoHeader, GzipSampleUser.class);
        assertEquals(42L, user3.id());
        assertEquals("gzip-ok", user3.name());

        // 8.4 禁用解包（@AdaptiveUnwrap(false)）客户端收到 GZIP 响应时，传给 delegate 的 bufferedResponse 已为解压明文
        byte[] rawThirdParty = "{\"id\":88,\"name\":\"raw-gzip\"}".getBytes(StandardCharsets.UTF_8);
        Response rawClientGzip = createClientResponse(
                RawThirdPartyReportClient.class,
                "report-service",
                gzipCompress(rawThirdParty),
                Map.of("Content-Encoding", List.of("gzip"))
        );
        GzipSampleUser rawUser = (GzipSampleUser) decoder.decode(rawClientGzip, GzipSampleUser.class);
        assertEquals(88L, rawUser.id());
        assertEquals("raw-gzip", rawUser.name());
    }

    @Test
    @DisplayName("9. 验证 GZIP 解压炸弹按解压后真实字节数拦截抛出 PAYLOAD_TOO_LARGE，并遵循单客户端隔离与资源释放")
    void testGzipDecompressionBombEnforcesMaxResponseSizeAndResourceClose() throws Exception {
        // 构造 64KB 高重复 JSON，GZIP 压缩后体积仅约 100+ 字节（远小于 1KB 阈值）
        String bombJson = "{\"code\":200,\"data\":\"" + "A".repeat(64 * 1024) + "\"}";
        byte[] compressedBomb = gzipCompress(bombJson.getBytes(StandardCharsets.UTF_8));
        assertTrue(compressedBomb.length < 512, "测试夹具压缩后体积必须远小于防爆阈值以模拟解压炸弹");

        AtengFeignProperties properties = new AtengFeignProperties();
        properties.getPayload().setMaxResponseSize(DataSize.ofKilobytes(1)); // 1KB
        AtengFeignDecoder decoder = new AtengFeignDecoder((resp, type) -> null, properties, objectMapper);

        AtomicBoolean closed = new AtomicBoolean(false);
        InputStream trackedStream = new FilterInputStream(new ByteArrayInputStream(compressedBomb)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        Request request = Request.create(Request.HttpMethod.GET, "/bomb", Collections.emptyMap(), null, new RequestTemplate());
        Response bombResponse = Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(Map.of(
                        "Content-Encoding", List.of("gzip"),
                        "Content-Length", List.of(String.valueOf(compressedBomb.length))
                ))
                .body(trackedStream, compressedBomb.length)
                .build();

        BusinessException ex = assertThrows(BusinessException.class, () -> decoder.decode(bombResponse, String.class));
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), ex.getCode());
        assertTrue(closed.get(), "GZIP 解压炸弹触发熔断后必须确定性关闭底层响应流");

        // 验证单客户端 maxResponseSize 覆盖同样作用于 GZIP 解压后字节数
        String smallJson = "{\"code\":200,\"data\":\"" + "B".repeat(1024) + "\"}";
        byte[] compressedSmall = gzipCompress(smallJson.getBytes(StandardCharsets.UTF_8));

        AtengFeignProperties perClientProps = new AtengFeignProperties();
        AtengFeignClientProperties serviceProps = new AtengFeignClientProperties();
        serviceProps.setMaxResponseSize(DataSize.ofKilobytes(4));
        perClientProps.getClients().put("report-service", serviceProps);
        AtengFeignClientProperties ctxProps = new AtengFeignClientProperties();
        ctxProps.setMaxResponseSize(DataSize.ofBytes(256));
        perClientProps.getClients().put("smallLimitReportClient", ctxProps);

        AtengFeignDecoder perClientDecoder = new AtengFeignDecoder((resp, type) -> "ok", perClientProps, objectMapper);

        BusinessException ctxBombEx = assertThrows(BusinessException.class, () -> perClientDecoder.decode(
                createClientResponse(SmallLimitReportClient.class, "report-service", compressedSmall,
                        Map.of("Content-Encoding", List.of("gzip"))),
                String.class
        ));
        assertEquals(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), ctxBombEx.getCode());

        String normalData = (String) perClientDecoder.decode(
                createClientResponse(NormalReportClient.class, "report-service", compressedSmall,
                        Map.of("Content-Encoding", List.of("gzip"))),
                String.class
        );
        assertEquals(1024, normalData.length());
    }

    @Test
    @DisplayName("10. 验证 GZIP 响应透传 Result 时刷新 Content-Length/移除 Content-Encoding，并透传业务错误码")
    void testGzipResponseUpdatesBufferedHeadersAndPropagatesBusinessError() throws Exception {
        byte[] rawResultBytes = "{\"code\":200,\"msg\":\"ok\",\"data\":{\"id\":99,\"name\":\"hdr\"}}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] compressedResult = gzipCompress(rawResultBytes);

        AtomicBoolean verifiedHeaders = new AtomicBoolean(false);
        Decoder headerCheckingDelegate = (resp, type) -> {
            assertFalse(resp.headers().containsKey("Content-Encoding"),
                    "解压后透传给底层解码器的响应不应再保留 Content-Encoding");
            assertEquals(List.of(String.valueOf(rawResultBytes.length)),
                    List.copyOf(resp.headers().get("Content-Length")),
                    "解压后透传给底层解码器的 Content-Length 应更新为明文长度");
            verifiedHeaders.set(true);
            return objectMapper.readValue(resp.body().asInputStream().readAllBytes(),
                    objectMapper.getTypeFactory().constructType(type));
        };

        AtengFeignDecoder decoder = new AtengFeignDecoder(headerCheckingDelegate, new AtengFeignProperties(), objectMapper);
        Response gzipResultResponse = createMockResponse(compressedResult, Map.of(
                "Content-Encoding", List.of("gzip"),
                "Content-Length", List.of(String.valueOf(compressedResult.length))
        ));
        Object decoded = decoder.decode(gzipResultResponse, Result.class);
        assertTrue(decoded instanceof Result<?>);
        assertTrue(verifiedHeaders.get());

        // 验证 GZIP 压缩的业务失败报文仍能解压并触发 BusinessException
        byte[] errorBytes = "{\"code\":6001,\"msg\":\"库存不足\"}".getBytes(StandardCharsets.UTF_8);
        Response gzipErrorResponse = createMockResponse(gzipCompress(errorBytes), Map.of("Content-Encoding", List.of("gzip")));
        BusinessException be = assertThrows(BusinessException.class,
                () -> decoder.decode(gzipErrorResponse, GzipSampleUser.class));
        assertEquals(6001, be.getCode());
        assertEquals("库存不足", be.getMsg());
    }

    private static byte[] gzipCompress(byte[] raw) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(raw);
        }
        return baos.toByteArray();
    }

    private static final class OneByteAtATimeInputStream extends FilterInputStream {
        private OneByteAtATimeInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(1, len));
        }
    }
}
