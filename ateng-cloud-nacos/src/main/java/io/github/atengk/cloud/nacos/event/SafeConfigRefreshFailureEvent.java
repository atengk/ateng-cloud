package io.github.atengk.cloud.nacos.event;

import org.springframework.context.ApplicationEvent;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.Set;

/**
 * Nacos 安全配置热更失败强类型领域事件
 *
 * <p>在配置动态热更流程中，当 {@code @ConfigurationProperties} 增量重绑抛出异常、JSR-303 数据校验未通过，
 * 或受管监听器处理过程抛出未捕获异常时触发发布。
 * 携带错误信息、异常根因链、影响的属性键名集合及 MDC 链路追踪上下文，支持通过 Spring {@code @EventListener} 解耦响应，
 * 用于驱动外部监控告警（如钉钉/企业微信机器人通知）与自动化故障处置。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class SafeConfigRefreshFailureEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 触发本次热更的 Nacos 配置集唯一标识（可为空）
     */
    private final String dataId;

    /**
     * 触发本次热更的 Nacos 配置分组名（可为空）
     */
    private final String group;

    /**
     * 本次发生变更的配置属性键名集合
     */
    private final Set<String> changedKeys;

    /**
     * 失败错误描述信息
     */
    private final String errorMessage;

    /**
     * 失败异常根因链（可为空）
     */
    @Nullable
    private final Throwable cause;

    /**
     * MDC 链路追踪标识 (traceId)
     */
    private final String traceId;

    public SafeConfigRefreshFailureEvent(Object source,
                                         String dataId,
                                         String group,
                                         Set<String> changedKeys,
                                         String errorMessage,
                                         @Nullable Throwable cause,
                                         String traceId) {
        super(source);
        this.dataId = dataId != null ? dataId : "";
        this.group = group != null ? group : "";
        this.changedKeys = changedKeys != null ? Collections.unmodifiableSet(changedKeys) : Collections.emptySet();
        this.errorMessage = errorMessage != null ? errorMessage : "Unknown configuration refresh failure";
        this.cause = cause;
        this.traceId = traceId != null ? traceId : "";
    }

    public String getDataId() {
        return dataId;
    }

    public String getGroup() {
        return group;
    }

    public Set<String> getChangedKeys() {
        return changedKeys;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Nullable
    public Throwable getCause() {
        return cause;
    }

    public String getTraceId() {
        return traceId;
    }

    @Override
    public String toString() {
        return "SafeConfigRefreshFailureEvent{" +
                "dataId='" + dataId + '\'' +
                ", group='" + group + '\'' +
                ", changedKeys=" + changedKeys +
                ", errorMessage='" + errorMessage + '\'' +
                ", traceId='" + traceId + '\'' +
                ", cause=" + (cause != null ? cause.getClass().getName() + ": " + cause.getMessage() : "null") +
                '}';
    }
}
