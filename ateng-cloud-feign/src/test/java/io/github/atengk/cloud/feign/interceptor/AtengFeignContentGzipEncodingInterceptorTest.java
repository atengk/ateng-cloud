package io.github.atengk.cloud.feign.interceptor;

import feign.Request;
import feign.RequestTemplate;
import io.github.atengk.cloud.feign.properties.AtengFeignCompressionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign GZIP 请求体内容压缩拦截器单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
class AtengFeignContentGzipEncodingInterceptorTest {

    @Test
    @DisplayName("1. 达标 JSON 请求经压缩拦截器处理后，body 为真实 GZIP 字节流且可完整解压还原")
    void testCompressesEligibleJsonBodyToValidGzipStream() throws Exception {
        AtengFeignCompressionProperties properties = new AtengFeignCompressionProperties();
        properties.setRequestEnabled(true);
        properties.setMinRequestSize(DataSize.ofKilobytes(2));

        AtengFeignContentGzipEncodingInterceptor interceptor =
                new AtengFeignContentGzipEncodingInterceptor(properties);

        String originalJson = "{\"items\":[\"" + "ateng-cloud-feign-".repeat(180) + "\"]}";
        byte[] originalBytes = originalJson.getBytes(StandardCharsets.UTF_8);
        assertTrue(originalBytes.length >= 2048);

        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.target("http://order-service/api/orders");
        template.header("Content-Type", "application/json; charset=UTF-8");
        template.body(originalBytes, StandardCharsets.UTF_8);

        interceptor.apply(template);

        byte[] compressed = template.body();
        assertNotNull(compressed);
        assertTrue(compressed.length >= 2);
        assertEquals((byte) 0x1f, compressed[0], "压缩后字节流首字节必须为 GZIP 魔数 0x1f");
        assertEquals((byte) 0x8b, compressed[1], "压缩后字节流次字节必须为 GZIP 魔数 0x8b");
        assertTrue(compressed.length < originalBytes.length, "重复 JSON 载荷经 GZIP 压缩后体积应显著减小");
        Request resolvedRequest = template.resolve(Collections.emptyMap()).request();
        assertNull(resolvedRequest.charset(), "压缩后的二进制载荷应清空文本字符集避免传输层二次转码破坏");
        assertTrue(resolvedRequest.isBinary(), "压缩后生成的 feign.Request 必须标记为二进制流");

        Collection<String> encodingHeaders = template.headers().get("Content-Encoding");
        assertNotNull(encodingHeaders);
        assertTrue(encodingHeaders.contains("gzip"));
        Collection<String> lengthHeaders = template.headers().get("Content-Length");
        assertNotNull(lengthHeaders);
        assertTrue(lengthHeaders.contains(String.valueOf(compressed.length)));

        try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            String decompressed = new String(gzipIn.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(originalJson, decompressed);
        }
    }

    @Test
    @DisplayName("2. 未开启开关、体积低于阈值、MIME 不匹配或已有 Content-Encoding 时保持请求体原样")
    void testSkipsWhenDisabledBelowThresholdUnsupportedMimeOrExistingEncoding() {
        String payload = "{\"k\":\"" + "x".repeat(2500) + "\"}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        // 2.1 requestEnabled=false 不压缩
        AtengFeignCompressionProperties disabledProps = new AtengFeignCompressionProperties();
        disabledProps.setRequestEnabled(false);
        RequestTemplate disabledTemplate = createTemplate(payloadBytes, "application/json");
        new AtengFeignContentGzipEncodingInterceptor(disabledProps).apply(disabledTemplate);
        assertArrayEquals(payloadBytes, disabledTemplate.body());
        assertFalse(disabledTemplate.headers().containsKey("Content-Encoding"));

        // 2.2 未达到 minRequestSize 阈值不压缩
        AtengFeignCompressionProperties enabledProps = new AtengFeignCompressionProperties();
        enabledProps.setRequestEnabled(true);
        enabledProps.setMinRequestSize(DataSize.ofKilobytes(2));
        byte[] smallBytes = "{\"k\":\"small\"}".getBytes(StandardCharsets.UTF_8);
        RequestTemplate smallTemplate = createTemplate(smallBytes, "application/json");
        new AtengFeignContentGzipEncodingInterceptor(enabledProps).apply(smallTemplate);
        assertArrayEquals(smallBytes, smallTemplate.body());
        assertFalse(smallTemplate.headers().containsKey("Content-Encoding"));

        // 2.3 MIME 类型不在白名单时不压缩
        RequestTemplate binaryTemplate = createTemplate(payloadBytes, "application/octet-stream");
        new AtengFeignContentGzipEncodingInterceptor(enabledProps).apply(binaryTemplate);
        assertArrayEquals(payloadBytes, binaryTemplate.body());
        assertFalse(binaryTemplate.headers().containsKey("Content-Encoding"));

        // 2.4 缺少 Content-Type 时不压缩，空请求体安全跳过
        RequestTemplate noContentTypeTemplate = createTemplate(payloadBytes, null);
        new AtengFeignContentGzipEncodingInterceptor(enabledProps).apply(noContentTypeTemplate);
        assertArrayEquals(payloadBytes, noContentTypeTemplate.body());

        RequestTemplate emptyTemplate = createTemplate(new byte[0], "application/json");
        assertDoesNotThrow(() -> new AtengFeignContentGzipEncodingInterceptor(enabledProps).apply(emptyTemplate));
        assertDoesNotThrow(() -> new AtengFeignContentGzipEncodingInterceptor(enabledProps).apply(null));

        // 2.5 已存在 Content-Encoding（忽略大小写）时不重复压缩或覆盖
        RequestTemplate alreadyEncodedTemplate = createTemplate(payloadBytes, "application/json");
        alreadyEncodedTemplate.header("content-encoding", "br");
        new AtengFeignContentGzipEncodingInterceptor(enabledProps).apply(alreadyEncodedTemplate);
        assertArrayEquals(payloadBytes, alreadyEncodedTemplate.body());
        assertEquals(List.of("br"), List.copyOf(alreadyEncodedTemplate.headers().get("content-encoding")));
    }

    @Test
    @DisplayName("3. 支持大小写不敏感的 Content-Type/Content-Length 处理，且非法负阈值 Fail-Fast")
    void testCaseInsensitiveHeadersAndNegativeMinSizeFailFast() throws Exception {
        AtengFeignCompressionProperties props = new AtengFeignCompressionProperties();
        props.setRequestEnabled(true);
        props.setMinRequestSize(DataSize.ofBytes(64));

        String xmlPayload = "<root>" + "<item>data</item>".repeat(20) + "</root>";
        byte[] xmlBytes = xmlPayload.getBytes(StandardCharsets.UTF_8);

        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.target("http://xml-service/api/xml");
        template.header("content-type", "TEXT/XML; charset=UTF-8");
        template.header("content-length", String.valueOf(xmlBytes.length));
        template.body(xmlBytes, StandardCharsets.UTF_8);

        new AtengFeignContentGzipEncodingInterceptor(props).apply(template);

        byte[] compressed = template.body();
        assertEquals((byte) 0x1f, compressed[0]);
        assertEquals((byte) 0x8b, compressed[1]);
        assertEquals(1, template.headers().get("Content-Length").size(), "压缩后 Content-Length 标头不得重复残留旧值");
        assertTrue(template.headers().get("Content-Length").contains(String.valueOf(compressed.length)));
        try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            assertEquals(xmlPayload, new String(gzipIn.readAllBytes(), StandardCharsets.UTF_8));
        }

        // 负数 minRequestSize 必须触发 Fail-Fast
        AtengFeignCompressionProperties negativeProps = new AtengFeignCompressionProperties();
        negativeProps.setRequestEnabled(true);
        negativeProps.setMinRequestSize(DataSize.ofBytes(-1));
        RequestTemplate validTemplate = createTemplate(xmlBytes, "application/json");
        assertThrows(IllegalArgumentException.class,
                () -> new AtengFeignContentGzipEncodingInterceptor(negativeProps).apply(validTemplate));
    }

    private RequestTemplate createTemplate(byte[] body, String contentType) {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.target("http://order-service/api/orders");
        if (contentType != null) {
            template.header("Content-Type", contentType);
        }
        template.body(body, StandardCharsets.UTF_8);
        return template;
    }
}
