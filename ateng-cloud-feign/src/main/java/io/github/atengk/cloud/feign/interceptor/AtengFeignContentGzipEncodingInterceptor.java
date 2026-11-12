package io.github.atengk.cloud.feign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.properties.AtengFeignCompressionProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Feign GZIP 请求体内容压缩拦截器
 * 对符合 MIME 类型白名单且报文大小达到阈值的请求体执行真实 GZIP 压缩并声明 Content-Encoding
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignContentGzipEncodingInterceptor implements RequestInterceptor {

    private static final long DEFAULT_MIN_REQUEST_SIZE_BYTES = DataSize.ofKilobytes(2).toBytes();
    private static final String GZIP_ENCODING = "gzip";

    private final AtengFeignCompressionProperties properties;

    public AtengFeignContentGzipEncodingInterceptor(AtengFeignCompressionProperties properties) {
        this.properties = properties;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (template == null || properties == null || !properties.isRequestEnabled()) {
            return;
        }
        byte[] rawBody = template.body();
        if (rawBody == null || rawBody.length == 0) {
            return;
        }
        long minBytes = resolveMinRequestSizeBytes(properties.getMinRequestSize());
        if (rawBody.length < minBytes) {
            return;
        }
        if (hasHeaderIgnoreCase(template, HttpHeaders.CONTENT_ENCODING)) {
            return;
        }
        Collection<String> contentTypes = findHeaderValuesIgnoreCase(template, HttpHeaders.CONTENT_TYPE);
        if (contentTypes.isEmpty() || !matchesMimeType(contentTypes, properties.getMimeTypes())) {
            return;
        }

        byte[] compressedBody = compressGzip(rawBody);
        removeHeaderIgnoreCase(template, HttpHeaders.CONTENT_LENGTH);
        template.body(compressedBody, null);
        template.header(HttpHeaders.CONTENT_ENCODING, GZIP_ENCODING);
    }

    private long resolveMinRequestSizeBytes(DataSize minRequestSize) {
        if (minRequestSize == null) {
            return DEFAULT_MIN_REQUEST_SIZE_BYTES;
        }
        long bytes = minRequestSize.toBytes();
        if (bytes < 0) {
            throw new IllegalArgumentException("Feign compression.min-request-size 不能为负数: " + minRequestSize);
        }
        return bytes;
    }

    private byte[] compressGzip(byte[] rawBody) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(256, rawBody.length / 2));
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(rawBody);
        } catch (IOException e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "Feign 请求体 GZIP 压缩失败", e);
        }
        return baos.toByteArray();
    }

    private boolean hasHeaderIgnoreCase(RequestTemplate template, String headerName) {
        return !findHeaderValuesIgnoreCase(template, headerName).isEmpty();
    }

    private Collection<String> findHeaderValuesIgnoreCase(RequestTemplate template, String headerName) {
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Collection<String>> entry : template.headers().entrySet()) {
            if (headerName.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                for (String value : entry.getValue()) {
                    if (StringUtils.hasText(value)) {
                        matched.add(value);
                    }
                }
            }
        }
        return matched;
    }

    private void removeHeaderIgnoreCase(RequestTemplate template, String headerName) {
        List<String> keysToRemove = new ArrayList<>();
        for (String key : template.headers().keySet()) {
            if (headerName.equalsIgnoreCase(key)) {
                keysToRemove.add(key);
            }
        }
        for (String key : keysToRemove) {
            template.removeHeader(key);
        }
    }

    private boolean matchesMimeType(Collection<String> contentTypes, List<String> allowedMimeTypes) {
        if (allowedMimeTypes == null || allowedMimeTypes.isEmpty()) {
            return true;
        }
        for (String contentType : contentTypes) {
            if (!StringUtils.hasText(contentType)) {
                continue;
            }
            String lowerContentType = contentType.toLowerCase(Locale.ROOT);
            for (String allowed : allowedMimeTypes) {
                if (StringUtils.hasText(allowed)
                        && lowerContentType.contains(allowed.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
