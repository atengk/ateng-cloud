package io.github.atengk.cloud.sample.nacos.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 实例拓扑编程式订阅状态视图对象
 *
 * <p>封装服务实例拓扑编程式动态变更订阅通知的累计次数、当前生效的健康实例列表及最近一次变更链路追踪标识。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class InstanceSubscriptionStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 收到实例变更通知的总次数
     */
    private final int count;

    /**
     * 当前快照健康实例列表
     */
    private final List<InstanceVO> instances;

    /**
     * 最近一次变更通知时的链路追踪标识
     */
    private final String traceId;

    public InstanceSubscriptionStatusVO(int count, List<InstanceVO> instances, String traceId) {
        this.count = count;
        this.instances = instances != null ? Collections.unmodifiableList(instances) : Collections.emptyList();
        this.traceId = traceId != null ? traceId : "";
    }

    public int getCount() {
        return count;
    }

    public List<InstanceVO> getInstances() {
        return instances;
    }

    public String getTraceId() {
        return traceId;
    }
}
