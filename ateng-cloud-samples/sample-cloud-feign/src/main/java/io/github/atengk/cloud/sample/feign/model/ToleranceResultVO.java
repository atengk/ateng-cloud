package io.github.atengk.cloud.sample.feign.model;

import java.io.Serializable;

/**
 * 微服务容灾与防御调测结果视图对象
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class ToleranceResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 场景名称
     */
    private String scenario;

    /**
     * 实际发起调用/尝试次数
     */
    private Integer attempts;

    /**
     * 调用总耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 捕获的业务错误码或 HTTP 状态
     */
    private Integer errorCode;

    /**
     * 结果说明或异常信息摘要
     */
    private String message;

    /**
     * 根因底层异常类名
     */
    private String rootCause;

    public ToleranceResultVO() {
    }

    public ToleranceResultVO(String scenario, Integer attempts, Long durationMs, Integer errorCode, String message, String rootCause) {
        this.scenario = scenario;
        this.attempts = attempts;
        this.durationMs = durationMs;
        this.errorCode = errorCode;
        this.message = message;
        this.rootCause = rootCause;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }
}
