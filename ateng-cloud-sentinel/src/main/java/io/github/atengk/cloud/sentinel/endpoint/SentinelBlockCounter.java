package io.github.atengk.cloud.sentinel.endpoint;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sentinel 流量防卫原子阻断计数器
 *
 * <p>基于原子无锁架构设计，专职在微服务运行期实时统计限流、熔断与防卫阻断事件，
 * 支持按规则类型细分计数，并以原子快照形式保留最近一次阻断事件上下文，
 * 供全景自省端点（{@code /actuator/sentinel-panorama}）秒级直读，微秒级极速响应且零锁竞争。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SentinelBlockCounter {

    private final AtomicLong totalBlocked = new AtomicLong(0);
    private final AtomicLong flowCount = new AtomicLong(0);
    private final AtomicLong degradeCount = new AtomicLong(0);
    private final AtomicLong paramFlowCount = new AtomicLong(0);
    private final AtomicLong systemCount = new AtomicLong(0);
    private final AtomicLong authorityCount = new AtomicLong(0);
    private final AtomicLong otherCount = new AtomicLong(0);

    private final AtomicReference<BlockEventSnapshot> lastBlockEvent = new AtomicReference<>();

    /**
     * 记录一次阻断事件（缺省 clientIp）
     *
     * @param ex 阻断异常
     * @param resource 资源名称
     */
    public void recordBlock(BlockException ex, String resource) {
        recordBlock(ex, resource, null);
    }

    /**
     * 记录一次阻断事件
     *
     * @param ex 阻断异常
     * @param resource 资源名称
     * @param clientIp 客户端来源 IP
     */
    public void recordBlock(BlockException ex, String resource, String clientIp) {
        if (ex == null) {
            return;
        }

        totalBlocked.incrementAndGet();

        String ruleType;
        if (ex instanceof FlowException) {
            flowCount.incrementAndGet();
            ruleType = "FLOW";
        } else if (ex instanceof DegradeException) {
            degradeCount.incrementAndGet();
            ruleType = "DEGRADE";
        } else if (ex instanceof ParamFlowException) {
            paramFlowCount.incrementAndGet();
            ruleType = "PARAM_FLOW";
        } else if (ex instanceof SystemBlockException) {
            systemCount.incrementAndGet();
            ruleType = "SYSTEM";
        } else if (ex instanceof AuthorityException) {
            authorityCount.incrementAndGet();
            ruleType = "AUTHORITY";
        } else {
            otherCount.incrementAndGet();
            ruleType = "UNKNOWN";
        }

        String safeResource = (resource != null && !resource.trim().isEmpty()) ? resource.trim() : "unknown";
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = ex.getRuleLimitApp();
        }
        if (message == null || message.trim().isEmpty()) {
            message = ex.getClass().getSimpleName();
        }
        lastBlockEvent.set(new BlockEventSnapshot(System.currentTimeMillis(), safeResource, ruleType, clientIp, message));
    }

    /**
     * 获取累计总阻断数 (符合 Spec 契约命名)
     */
    public long getBlockedTotal() {
        return totalBlocked.get();
    }

    /**
     * 获取累计总阻断数 (兼容存量 getter)
     */
    public long getTotalBlocked() {
        return getBlockedTotal();
    }

    public long getFlowCount() {
        return flowCount.get();
    }

    public long getDegradeCount() {
        return degradeCount.get();
    }

    public long getParamFlowCount() {
        return paramFlowCount.get();
    }

    public long getSystemCount() {
        return systemCount.get();
    }

    public long getAuthorityCount() {
        return authorityCount.get();
    }

    public long getOtherCount() {
        return otherCount.get();
    }

    public BlockEventSnapshot getLastBlockEvent() {
        return lastBlockEvent.get();
    }

    /**
     * 重置所有阻断计数与快照（主要用于单元测试与运维重置）
     */
    public void reset() {
        totalBlocked.set(0);
        flowCount.set(0);
        degradeCount.set(0);
        paramFlowCount.set(0);
        systemCount.set(0);
        authorityCount.set(0);
        otherCount.set(0);
        lastBlockEvent.set(null);
    }

    /**
     * 阻断事件只读快照
     */
    public record BlockEventSnapshot(
            long timestamp,
            String resource,
            String ruleType,
            String clientIp,
            String message
    ) implements Serializable {}
}
