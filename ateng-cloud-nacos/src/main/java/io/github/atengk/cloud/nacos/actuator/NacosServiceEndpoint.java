package io.github.atengk.cloud.nacos.actuator;

import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nacos 服务实例专属运维与生命周期端点
 *
 * <p>暴露路径为 {@code /actuator/nacos-service}，支持查看当前节点元数据与状态，
 * 以及支持外部发布平台或 K8s PreStop Hook 主动发起摘牌与上线操作。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Endpoint(id = "nacos-service")
public class NacosServiceEndpoint {

    private final NacosLifecycleStateMachine stateMachine;
    private final NacosMetadataProvider metadataProvider;
    private final AtengNacosProperties properties;
    private final Environment environment;

    public NacosServiceEndpoint(NacosLifecycleStateMachine stateMachine,
                                NacosMetadataProvider metadataProvider,
                                AtengNacosProperties properties,
                                Environment environment) {
        this.stateMachine = stateMachine;
        this.metadataProvider = metadataProvider;
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * 读取当前微服务节点的生命周期状态、排空配置与全量元数据
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
