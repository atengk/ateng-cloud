package io.github.atengk.cloud.nacos.actuator;

import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.metadata.NacosWarmupHelper;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import io.github.atengk.cloud.nacos.refresh.NacosConfigRefreshEngine;
import io.github.atengk.cloud.nacos.spi.NacosConfigChangeListener;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nacos 服务实例专属运维与生命周期端点
 *
 * <p>暴露路径为 {@code /actuator/nacosService}，支持查看当前节点元数据、生命周期状态、
 * 受管配置监听器列表与最近一次动态热更记录，以及支持外部发布平台或 K8s PreStop Hook 主动发起摘牌与上线操作。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Endpoint(id = "nacosService")
public class NacosServiceEndpoint {

    private final NacosLifecycleStateMachine stateMachine;
    private final NacosMetadataProvider metadataProvider;
    private final AtengNacosProperties properties;
    private final Environment environment;
    private final NacosConfigRefreshEngine refreshEngine;

    public NacosServiceEndpoint(NacosLifecycleStateMachine stateMachine,
                                NacosMetadataProvider metadataProvider,
                                AtengNacosProperties properties,
                                Environment environment) {
        this(stateMachine, metadataProvider, properties, environment, null);
    }

    public NacosServiceEndpoint(NacosLifecycleStateMachine stateMachine,
                                NacosMetadataProvider metadataProvider,
                                AtengNacosProperties properties,
                                Environment environment,
                                @Nullable NacosConfigRefreshEngine refreshEngine) {
        this.stateMachine = stateMachine;
        this.metadataProvider = metadataProvider;
        this.properties = properties;
        this.environment = environment;
        this.refreshEngine = refreshEngine;
    }

    /**
     * 读取当前微服务节点的生命周期状态、排空配置、全量元数据与动态配置热更受管信息
     */
    @ReadOperation
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", stateMachine.getState().name());
        result.put("serviceName", environment.getProperty("spring.application.name", "unknown"));
        result.put("drainTimeout", properties.getDrainTimeout() != null ? properties.getDrainTimeout().toString() : "10s");
        result.put("zone", properties.getZone());
        result.put("cluster", properties.getCluster());
        if (metadataProvider != null) {
            result.put("metadata", metadataProvider.getMetadata());
        }

        NacosWarmupHelper.WarmupStatus warmup = NacosWarmupHelper.calculateWarmupStatus(metadataProvider, properties);
        result.put("warmupDuration", warmup.warmupDuration());
        result.put("warmupUptimeSeconds", warmup.warmupUptimeSeconds());
        result.put("warmupRemainingSeconds", warmup.warmupRemainingSeconds());
        result.put("warmupCompleted", warmup.warmupCompleted());

        if (refreshEngine != null) {
            Map<String, Object> configInfo = new LinkedHashMap<>();
            configInfo.put("enforceBeanImmutability", properties != null && properties.getConfig() != null && properties.getConfig().isEnforceBeanImmutability());

            List<Map<String, Object>> listenerDetails = new ArrayList<>();
            for (NacosConfigChangeListener listener : refreshEngine.getListeners()) {
                if (listener != null) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("class", listener.getClass().getName());
                    item.put("prefix", listener.getPrefix());
                    item.put("order", listener.getOrder());
                    listenerDetails.add(item);
                }
            }
            configInfo.put("listeners", listenerDetails);

            NacosConfigRefreshEngine.RefreshRecord lastRecord = refreshEngine.getLastRefreshRecord();
            if (lastRecord != null) {
                Map<String, Object> recordMap = new LinkedHashMap<>();
                recordMap.put("traceId", lastRecord.getTraceId());
                recordMap.put("timestamp", lastRecord.getTimestamp());
                recordMap.put("changedKeys", lastRecord.getChangedKeys());
                recordMap.put("success", lastRecord.isSuccess());
                recordMap.put("durationMs", lastRecord.getDurationMs());
                // 双写 error 与 errorMessage 字段，保持与旧版监控采集端点向后兼容
                if (lastRecord.getError() != null) {
                    recordMap.put("error", lastRecord.getError());
                    recordMap.put("errorMessage", lastRecord.getError());
                } else {
                    recordMap.put("errorMessage", null);
                }
                // 在 config 节点内部与根节点同时暴露 lastRefresh，兼顾微服务通用端点扁平视图与配置中心分组视图
                configInfo.put("lastRefresh", recordMap);
                result.put("lastRefresh", recordMap);
            } else {
                configInfo.put("lastRefresh", null);
                result.put("lastRefresh", null);
            }
            result.put("config", configInfo);
        }

        return result;
    }

    /**
     * 执行主动摘牌下线（offline）或重新挂牌上线（online）运维操作
     *
     * @param action 动作指令：offline 或 online
     */
    @WriteOperation
    public Map<String, Object> executeAction(@Nullable String action) {
        Map<String, Object> response = new LinkedHashMap<>();
        if ("offline".equalsIgnoreCase(action)) {
            boolean changed = stateMachine.markOffline();
            response.put("action", "offline");
            response.put("success", true);
            response.put("message", changed ? "Instance successfully drained and marked offline" : "Instance was already draining or offline");
            response.put("state", stateMachine.getState().name());
            return response;
        }

        if ("online".equalsIgnoreCase(action)) {
            boolean changed = stateMachine.markOnline();
            response.put("action", "online");
            response.put("success", true);
            response.put("message", changed ? "Instance successfully registered and marked online" : "Instance was already online");
            response.put("state", stateMachine.getState().name());
            return response;
        }

        response.put("success", false);
        response.put("message", "Invalid action: '" + action + "'. Supported actions: 'offline', 'online'");
        response.put("state", stateMachine.getState().name());
        return response;
    }
}
