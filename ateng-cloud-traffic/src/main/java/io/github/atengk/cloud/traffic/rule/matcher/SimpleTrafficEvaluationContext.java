package io.github.atengk.cloud.traffic.rule.matcher;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 内存通用的流量规则评估上下文实现
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SimpleTrafficEvaluationContext implements TrafficEvaluationContext {

    private final Map<String, String> headers;
    private final Map<String, String> queryParams;
    private final Map<String, String> cookies;
    private final String path;
    private final String userId;
    private final String tenantId;
    private final String clientIp;

    public SimpleTrafficEvaluationContext(Builder builder) {
        this.headers = builder.headers != null ? new HashMap<>(builder.headers) : Collections.emptyMap();
        this.queryParams = builder.queryParams != null ? new HashMap<>(builder.queryParams) : Collections.emptyMap();
        this.cookies = builder.cookies != null ? new HashMap<>(builder.cookies) : Collections.emptyMap();
        this.path = builder.path;
        this.userId = builder.userId;
        this.tenantId = builder.tenantId;
        this.clientIp = builder.clientIp;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getHeader(String name) {
        if (name == null || headers.isEmpty()) {
            return null;
        }
        String val = headers.get(name);
        if (val != null) {
            return val;
        }
        // 大小写不敏感查找
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public String getQueryParam(String name) {
        return name != null ? queryParams.get(name) : null;
    }

    @Override
    public String getCookie(String name) {
        return name != null ? cookies.get(name) : null;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getUserId() {
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        return UserContextHolder.getUserId();
    }

    @Override
    public String getTenantId() {
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        return TenantContextHolder.getTenantId();
    }

    @Override
    public String getClientIp() {
        return clientIp;
    }

    public static final class Builder {
        private final Map<String, String> headers = new HashMap<>();
        private final Map<String, String> queryParams = new HashMap<>();
        private final Map<String, String> cookies = new HashMap<>();
        private String path;
        private String userId;
        private String tenantId;
        private String clientIp;

        private Builder() {
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                this.headers.put(name, value);
            }
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder queryParam(String name, String value) {
            if (name != null && value != null) {
                this.queryParams.put(name, value);
            }
            return this;
        }

        public Builder queryParams(Map<String, String> queryParams) {
            if (queryParams != null) {
                this.queryParams.putAll(queryParams);
            }
            return this;
        }

        public Builder cookie(String name, String value) {
            if (name != null && value != null) {
                this.cookies.put(name, value);
            }
            return this;
        }

        public Builder cookies(Map<String, String> cookies) {
            if (cookies != null) {
                this.cookies.putAll(cookies);
            }
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public SimpleTrafficEvaluationContext build() {
            return new SimpleTrafficEvaluationContext(this);
        }
    }
}
