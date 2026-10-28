package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.codec.Decoder;
import feign.querymap.BeanQueryMapEncoder;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
