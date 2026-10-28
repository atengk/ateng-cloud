package io.github.atengk.cloud.sample.dubbo.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户流式日志传输视图对象
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class UserLogVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 日志主键
     */
    private Long logId;

    /**
     * 当前登录用户 ID
     */
    private String userId;

    /**
     * 当前所属多租户 ID
     */
    private String tenantId;

    /**
     * 分布式调用链追踪 ID
     */
    private String traceId;

    /**
     * 金丝雀流量染色标记
     */
    private String trafficTag;

    /**
     * 日志内容
     */
    private String content;

    public UserLogVO() {
    }

    public UserLogVO(Long logId, String userId, String tenantId, String traceId, String trafficTag, String content) {
        this.logId = logId;
        this.userId = userId;
        this.tenantId = tenantId;
        this.traceId = traceId;
        this.trafficTag = trafficTag;
        this.content = content;
    }

    public Long getLogId() {
        return logId;
    }

    public void setLogId(Long logId) {
        this.logId = logId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getTrafficTag() {
        return trafficTag;
    }

    public void setTrafficTag(String trafficTag) {
        this.trafficTag = trafficTag;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
