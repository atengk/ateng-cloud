package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 弹性重试与容灾调用统计响应视图
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class RetryStatVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务操作类型 (如 CREATE_ORDER, QUERY_INVENTORY)
     */
    private String action;

    /**
     * 底层实际尝试调用次数
     */
    private int attemptCount;

    /**
     * 最终执行状态
     */
    private String status;

    /**
     * 业务或异常提示信息
     */
    private String message;

    public RetryStatVO() {
    }

    public RetryStatVO(String action, int attemptCount, String status, String message) {
        this.action = action;
        this.attemptCount = attemptCount;
        this.status = status;
        this.message = message;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
