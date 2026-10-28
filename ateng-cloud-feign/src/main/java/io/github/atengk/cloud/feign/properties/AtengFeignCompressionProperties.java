package io.github.atengk.cloud.feign.properties;

import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Feign GZIP 传输压缩配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignCompressionProperties {

    /**
     * 是否启用请求 GZIP 压缩，默认 false
     */
    private boolean requestEnabled = false;

    /**
     * 是否启用响应 GZIP 压缩，默认 false
     */
    private boolean responseEnabled = false;

    /**
     * 触发请求 GZIP 压缩的最小请求体阈值（强类型 DataSize），默认 2KB
     */
    private DataSize minRequestSize = DataSize.ofKilobytes(2);

    /**
     * 支持请求 GZIP 压缩的 MIME 类型列表
     */
    private List<String> mimeTypes = List.of("text/xml", "application/xml", "application/json");

    public boolean isRequestEnabled() {
        return requestEnabled;
    }

    public void setRequestEnabled(boolean requestEnabled) {
        this.requestEnabled = requestEnabled;
    }

    public boolean isResponseEnabled() {
        return responseEnabled;
    }

    public void setResponseEnabled(boolean responseEnabled) {
        this.responseEnabled = responseEnabled;
    }

    public DataSize getMinRequestSize() {
        return minRequestSize;
    }

    public void setMinRequestSize(DataSize minRequestSize) {
        this.minRequestSize = minRequestSize;
    }

    public List<String> getMimeTypes() {
        return mimeTypes;
    }

    public void setMimeTypes(List<String> mimeTypes) {
        this.mimeTypes = mimeTypes;
    }
}
