package io.github.atengk.cloud.feign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.atengk.cloud.feign.properties.AtengFeignCompressionProperties;

import java.util.Collection;
import java.util.List;

/**
 * Feign GZIP 请求体内容压缩拦截器
 * 对符合 MIME 类型白名单且报文大小达到阈值的请求体声明 Content-Encoding
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignContentGzipEncodingInterceptor implements RequestInterceptor {

    private final AtengFeignCompressionProperties properties;

    public AtengFeignContentGzipEncodingInterceptor(AtengFeignCompressionProperties properties) {
        this.properties = properties;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (template.body() == null || template.body().length == 0 || properties == null) {
            return;
        }
        long minBytes = properties.getMinRequestSize() != null ? properties.getMinRequestSize().toBytes() : 2048;
        if (template.body().length < minBytes) {
            return;
        }
        Collection<String> contentTypes = template.headers().get("Content-Type");
        if (contentTypes != null && matchesMimeType(contentTypes, properties.getMimeTypes())) {
            if (!template.headers().containsKey("Content-Encoding")) {
                template.header("Content-Encoding", "gzip");
            }
        }
    }

    private boolean matchesMimeType(Collection<String> contentTypes, List<String> allowedMimeTypes) {
        if (allowedMimeTypes == null || allowedMimeTypes.isEmpty()) {
            return true;
        }
        for (String contentType : contentTypes) {
            for (String allowed : allowedMimeTypes) {
                if (contentType.toLowerCase().contains(allowed.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
}
