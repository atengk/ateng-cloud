package io.github.atengk.cloud.nacos.template;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingMaintainService;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.alibaba.nacos.api.naming.pojo.Service;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.nacos.code.NacosResultCode;
import io.github.atengk.cloud.nacos.filter.ZoneAffinityInstanceFilter;
import io.github.atengk.cloud.nacos.lifecycle.NacosLifecycleStateMachine;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import io.github.atengk.cloud.nacos.properties.AtengNacosProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Nacos 服务注册与发现统一运维抽象门面
 *
 * <p>核心契约与设计规范：
 * 1. <b>对称运维操作</b>：提供对等完备的 {@code markOnline}/{@code markOffline}、
 *    {@code registerMetadata}/{@code removeMetadata} 等对称 API；
 * 2. <b>空安全契约</b>：列表查询无匹配时保底返回空集合（绝不返回 {@code null}），
 *    单实例查询返回 {@link Optional}，彻底杜绝 NPE；
 * 3. <b>机房亲和路由</b>：集成 {@link ZoneAffinityInstanceFilter}，支持同机房优先与跨区平滑容灾降级；
 * 4. <b>动态权重响应与过载保护</b>：严格过滤权重 {@code <= 0.0} 节点，探测并透传集群过载保护阈值状态；
 * 5. <b>异常因果转译</b>：100% 拦截底层 {@link NacosException} 并转译为包含原始因果链的 {@link BusinessException}；
 * 6. <b>离线平滑空转</b>：在 {@code discovery.enabled=false} 或未配置外部 Nacos 环境下安全空转，保障离线单测平滑。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class NacosTemplate {

    private static final Logger log = LoggerFactory.getLogger(NacosTemplate.class);

    private final ObjectProvider<NacosServiceManager> serviceManagerProvider;
    private final ObjectProvider<NacosDiscoveryProperties> discoveryPropertiesProvider;
    private final AtengNacosProperties properties;
    private final NacosMetadataProvider metadataProvider;
    private final ObjectProvider<NacosLifecycleStateMachine> stateMachineProvider;
    private final ZoneAffinityInstanceFilter zoneAffinityFilter;

    public NacosTemplate(ObjectProvider<NacosServiceManager> serviceManagerProvider,
                         ObjectProvider<NacosDiscoveryProperties> discoveryPropertiesProvider,
                         AtengNacosProperties properties,
                         NacosMetadataProvider metadataProvider,
                         ObjectProvider<NacosLifecycleStateMachine> stateMachineProvider,
                         ZoneAffinityInstanceFilter zoneAffinityFilter) {
        this.serviceManagerProvider = serviceManagerProvider;
        this.discoveryPropertiesProvider = discoveryPropertiesProvider;
        this.properties = properties;
        this.metadataProvider = metadataProvider;
        this.stateMachineProvider = stateMachineProvider;
        this.zoneAffinityFilter = zoneAffinityFilter != null ? zoneAffinityFilter : new ZoneAffinityInstanceFilter();
    }

    // ==========================================
    // 1. 服务发现与查询 API (Null-Safe)
    // ==========================================

    /**
     * 查询指定服务的所有已注册实例（含健康与不健康节点）
     *
     * @param serviceName 服务名 (如 order-service)
     * @return 实例列表 (空安全保证，绝不返回 null)
     */
    public List<Instance> getAllInstances(String serviceName) {
        return getAllInstances(serviceName, null);
    }

    /**
     * 查询指定分组下服务的所有已注册实例
     *
     * @param serviceName 服务名
     * @param groupName   分组名 (为 null 时使用默认组)
     * @return 实例列表 (空安全保证，绝不返回 null)
     */
    public List<Instance> getAllInstances(String serviceName, String groupName) {
        if (!isDiscoveryAvailable() || !StringUtils.hasText(serviceName)) {
            return Collections.emptyList();
        }
        return executeWithExceptionTranslation("getAllInstances", () -> {
            NamingService namingService = getNamingService();
            if (namingService == null) {
                return Collections.emptyList();
            }
            List<Instance> list = namingService.getAllInstances(serviceName, resolveGroupName(groupName));
            return list != null ? list : Collections.emptyList();
        });
    }

    /**
     * 查询指定服务的健康可用实例（已过滤权重 <= 0.0 与禁用节点）
     *
     * @param serviceName 服务名
     * @return 健康可用实例列表 (空安全保证，绝不返回 null)
     */
    public List<Instance> getHealthyInstances(String serviceName) {
        return getHealthyInstances(serviceName, null);
    }

    /**
     * 查询指定分组下服务的健康可用实例（已过滤权重 <= 0.0 与禁用节点）
     *
     * @param serviceName 服务名
     * @param groupName   分组名 (为 null 时使用默认组)
     * @return 健康可用实例列表 (空安全保证，绝不返回 null)
     */
    public List<Instance> getHealthyInstances(String serviceName, String groupName) {
        if (!isDiscoveryAvailable() || !StringUtils.hasText(serviceName)) {
            return Collections.emptyList();
        }
        return executeWithExceptionTranslation("getHealthyInstances", () -> {
            NamingService namingService = getNamingService();
            if (namingService == null) {
                return Collections.emptyList();
            }
            List<Instance> list = namingService.selectInstances(serviceName, resolveGroupName(groupName), true);
            if (list == null || list.isEmpty()) {
                return Collections.emptyList();
            }
            // 严格响应动态权重与禁用状态
            return list.stream()
                    .filter(i -> i != null && i.isEnabled() && i.isHealthy() && i.getWeight() > 0.0)
                    .collect(Collectors.toList());
        });
    }

    /**
     * 查询指定服务的可用实例列表 (语义同 {@link #getHealthyInstances(String)})
     *
     * @param serviceName 服务名
     * @return 实例列表 (空安全保证，绝不返回 null)
     */
    public List<Instance> getInstances(String serviceName) {
        return getHealthyInstances(serviceName);
    }

    /**
     * 查询指定分组下服务的可用实例列表 (语义同 {@link #getHealthyInstances(String, String)})
     *
     * @param serviceName 服务名
     * @param groupName   分组名
     * @return 实例列表 (空安全保证，绝不返回 null)
     */
    public List<Instance> getInstances(String serviceName, String groupName) {
        return getHealthyInstances(serviceName, groupName);
    }

    /**
     * 随机选择单个健康可用实例
     *
     * @param serviceName 服务名
     * @return 单实例 Optional 封装 (绝不返回 null)
     */
    public Optional<Instance> selectOneHealthyInstance(String serviceName) {
        return selectOneHealthyInstance(serviceName, null);
    }

    /**
     * 随机选择指定分组下单个健康可用实例
     *
     * @param serviceName 服务名
     * @param groupName   分组名
     * @return 单实例 Optional 封装 (绝不返回 null)
     */
    public Optional<Instance> selectOneHealthyInstance(String serviceName, String groupName) {
        List<Instance> healthy = getHealthyInstances(serviceName, groupName);
        if (healthy.isEmpty()) {
            return Optional.empty();
        }
        int index = ThreadLocalRandom.current().nextInt(healthy.size());
        return Optional.of(healthy.get(index));
    }

    /**
     * 获取当前服务自身在 Nacos 注册的实例信息
     *
     * @return 当前实例 Optional 封装 (未注册或不可用时返回 Optional.empty())
     */
    public Optional<Instance> getMyInstance() {
        if (!isDiscoveryAvailable()) {
            return Optional.empty();
        }
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        if (discoveryProperties == null) {
            return Optional.empty();
        }
        String serviceName = discoveryProperties.getService();
        String groupName = discoveryProperties.getGroup();
        String ip = discoveryProperties.getIp();
        int port = discoveryProperties.getPort();

        List<Instance> all = getAllInstances(serviceName, groupName);
        return all.stream()
                .filter(i -> Objects.equals(i.getIp(), ip) && i.getPort() == port)
                .findFirst();
    }

    /**
     * 基于同机房就近亲和策略获取目标服务健康实例
     *
     * <p>优先返回处于相同 zone 的健康节点；若本机房无可用节点，自动平滑降级使用全集群健康实例。</p>
     *
     * @param serviceName 目标服务名
     * @return 亲和筛选后的实例列表 (空安全保证)
     */
    public List<Instance> getSameZoneInstances(String serviceName) {
        return getSameZoneInstances(serviceName, null);
    }

    /**
     * 基于同机房就近亲和策略获取指定分组目标服务健康实例
     *
     * @param serviceName 目标服务名
     * @param groupName   分组名
     * @return 亲和筛选后的实例列表 (空安全保证)
     */
    public List<Instance> getSameZoneInstances(String serviceName, String groupName) {
        List<Instance> healthyInstances = getHealthyInstances(serviceName, groupName);
        if (healthyInstances.isEmpty()) {
            return Collections.emptyList();
        }
        String currentZone = properties != null ? properties.getZone() : null;
        if (!StringUtils.hasText(currentZone) && metadataProvider != null) {
            currentZone = metadataProvider.getMetadata().get(ZoneAffinityInstanceFilter.METADATA_KEY_ZONE);
        }
        return zoneAffinityFilter.filter(healthyInstances, currentZone);
    }

    /**
     * 分页查询服务端所有已注册的服务名列表
     *
     * @param pageNo   页码 (从 1 起始)
     * @param pageSize 每页条数
     * @return 服务名列表 (空安全保证)
     */
    public List<String> getServices(int pageNo, int pageSize) {
        return getServices(pageNo, pageSize, null);
    }

    /**
     * 分页查询指定分组下所有已注册的服务名列表
     *
     * @param pageNo    页码
     * @param pageSize  每页条数
     * @param groupName 分组名
     * @return 服务名列表 (空安全保证)
     */
    public List<String> getServices(int pageNo, int pageSize, String groupName) {
        if (!isDiscoveryAvailable()) {
            return Collections.emptyList();
        }
        return executeWithExceptionTranslation("getServices", () -> {
            NamingService namingService = getNamingService();
            if (namingService == null) {
                return Collections.emptyList();
            }
            ListView<String> listView = namingService.getServicesOfServer(pageNo, pageSize, resolveGroupName(groupName));
            if (listView != null && listView.getData() != null) {
                return listView.getData();
            }
            return Collections.emptyList();
        });
    }

    /**
     * 检测指定服务是否触发 Nacos 集群过载保护阈值 (Under Protection Mode)
     *
     * @param serviceName 服务名
     * @return 是否触发保护阈值
     */
    public boolean isUnderProtection(String serviceName) {
        return isUnderProtection(serviceName, null);
    }

    /**
     * 检测指定分组下服务是否触发 Nacos 集群过载保护阈值
     *
     * @param serviceName 服务名
     * @param groupName   分组名
     * @return 是否触发保护阈值
     */
    public boolean isUnderProtection(String serviceName, String groupName) {
        if (!isDiscoveryAvailable() || !StringUtils.hasText(serviceName)) {
            return false;
        }
        return executeWithExceptionTranslation("isUnderProtection", () -> {
            NamingService namingService = getNamingService();
            String group = resolveGroupName(groupName);

            // 1. 从客户端已订阅的缓存 ServiceInfo 检测
            if (namingService != null) {
                List<ServiceInfo> subscribeServices = namingService.getSubscribeServices();
                if (subscribeServices != null) {
                    for (ServiceInfo info : subscribeServices) {
                        if (serviceName.equalsIgnoreCase(info.getName())
                                && group.equalsIgnoreCase(info.getGroupName())) {
                            if (info.isReachProtectionThreshold()) {
                                log.warn("Nacos service '{}:{}' is UNDER PROTECTION (ServiceInfo reached threshold)!",
                                        group, serviceName);
                                return true;
                            }
                        }
                    }
                }
            }

            // 2. 从 NamingMaintainService 获取 ProtectThreshold 阈值进行动态健康比校验
            NamingMaintainService maintainService = getNamingMaintainService();
            if (maintainService != null) {
                try {
                    Service service = maintainService.queryService(serviceName, group);
                    if (service != null && service.getProtectThreshold() > 0.0f) {
                        float threshold = service.getProtectThreshold();
                        List<Instance> allInstances = getAllInstances(serviceName, group);
                        if (!allInstances.isEmpty()) {
                            long healthyCount = allInstances.stream()
                                    .filter(inst -> inst.isHealthy() && inst.isEnabled() && inst.getWeight() > 0.0)
                                    .count();
                            float ratio = (float) healthyCount / allInstances.size();
                            if (ratio <= threshold) {
                                log.warn("Nacos service '{}:{}' is UNDER PROTECTION! Healthy ratio {} <= threshold {}",
                                        group, serviceName, ratio, threshold);
                                return true;
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.debug("Unable to query Nacos service threshold: {}", ex.getMessage());
                }
            }
            return false;
        });
    }

    // ==========================================
    // 2. 对称服务治理与运维 API (Symmetric)
    // ==========================================

    /**
     * 当前服务主动恢复上线（注册回 Nacos 并切换就绪探针为 UP）
     */
    public void markOnline() {
        NacosLifecycleStateMachine stateMachine = stateMachineProvider.getIfAvailable();
        if (stateMachine != null) {
            stateMachine.markOnline();
        } else {
            log.warn("NacosLifecycleStateMachine is not available. Cannot execute markOnline.");
        }
    }

    /**
     * 当前服务主动优雅下线（注销 Nacos 并触发两阶段流量排空）
     */
    public void markOffline() {
        NacosLifecycleStateMachine stateMachine = stateMachineProvider.getIfAvailable();
        if (stateMachine != null) {
            stateMachine.markOffline();
        } else {
            log.warn("NacosLifecycleStateMachine is not available. Cannot execute markOffline.");
        }
    }

    /**
     * 动态设置指定服务实例的启用/禁用状态
     *
     * @param serviceName 服务名
     * @param ip          实例 IP
     * @param port        实例端口
     * @param enabled     是否启用
     */
    public void setInstanceEnabled(String serviceName, String ip, int port, boolean enabled) {
        setInstanceEnabled(serviceName, null, ip, port, enabled);
    }

    /**
     * 动态设置指定分组下服务实例的启用/禁用状态
     *
     * @param serviceName 服务名
     * @param groupName   分组名
     * @param ip          实例 IP
     * @param port        实例端口
     * @param enabled     是否启用
     */
    public void setInstanceEnabled(String serviceName, String groupName, String ip, int port, boolean enabled) {
        if (!isDiscoveryAvailable()) {
            log.warn("Nacos discovery is disabled. Skipping setInstanceEnabled for {}:{}", ip, port);
            return;
        }
        executeWithExceptionTranslation("setInstanceEnabled", () -> {
            NamingMaintainService maintainService = getNamingMaintainService();
            if (maintainService == null) {
                throw new BusinessException(NacosResultCode.NACOS_CLIENT_ERROR, "NamingMaintainService unavailable");
            }
            String group = resolveGroupName(groupName);
            Instance instance = findOrCreateInstance(serviceName, group, ip, port);
            instance.setEnabled(enabled);
            maintainService.updateInstance(serviceName, group, instance);
            log.info("Successfully set Nacos instance {}:{} enabled state to '{}' for service '{}:{}'",
                    ip, port, enabled, group, serviceName);
            return null;
        });
    }

    /**
     * 动态调整指定服务实例权重
     *
     * @param serviceName 服务名
     * @param ip          实例 IP
     * @param port        实例端口
     * @param weight      权重值 (0.0 表示流量隔离)
     */
    public void updateInstanceWeight(String serviceName, String ip, int port, double weight) {
        updateInstanceWeight(serviceName, null, ip, port, weight);
    }

    /**
     * 动态调整指定分组下服务实例权重
     *
     * @param serviceName 服务名
     * @param groupName   分组名
     * @param ip          实例 IP
     * @param port        实例端口
     * @param weight      权重值 (0.0 表示流量隔离)
     */
    public void updateInstanceWeight(String serviceName, String groupName, String ip, int port, double weight) {
        if (!isDiscoveryAvailable()) {
            log.warn("Nacos discovery is disabled. Skipping updateInstanceWeight for {}:{}", ip, port);
            return;
        }
        executeWithExceptionTranslation("updateInstanceWeight", () -> {
            NamingMaintainService maintainService = getNamingMaintainService();
            if (maintainService == null) {
                throw new BusinessException(NacosResultCode.NACOS_CLIENT_ERROR, "NamingMaintainService unavailable");
            }
            String group = resolveGroupName(groupName);
            Instance instance = findOrCreateInstance(serviceName, group, ip, port);
            instance.setWeight(weight);
            maintainService.updateInstance(serviceName, group, instance);
            if (weight <= 0.0) {
                log.warn("Nacos instance {}:{} weight set to {} (traffic completely isolated) in '{}:{}'",
                        ip, port, weight, group, serviceName);
            } else {
                log.info("Successfully updated Nacos instance {}:{} weight to {} in '{}:{}'",
                        ip, port, weight, group, serviceName);
            }
            return null;
        });
    }

    /**
     * 动态注册或更新当前服务实例的元数据
     *
     * @param key   元数据键
     * @param value 元数据值
     */
    public void registerMetadata(String key, String value) {
        if (!isDiscoveryAvailable() || !StringUtils.hasText(key)) {
            return;
        }
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        if (discoveryProperties != null) {
            discoveryProperties.getMetadata().put(key, value);
        }
        getMyInstance().ifPresent(instance -> {
            try {
                NamingMaintainService maintainService = getNamingMaintainService();
                if (maintainService != null) {
                    instance.getMetadata().put(key, value);
                    maintainService.updateInstance(instance.getServiceName(), resolveGroupName(null), instance);
                    log.info("Successfully registered metadata '{}'='{}' for current Nacos instance", key, value);
                }
            } catch (Exception ex) {
                log.warn("Failed to push updated metadata to Nacos server: {}", ex.getMessage());
            }
        });
    }

    /**
     * 动态移除当前服务实例指定的元数据
     *
     * @param key 元数据键
     */
    public void removeMetadata(String key) {
        if (!isDiscoveryAvailable() || !StringUtils.hasText(key)) {
            return;
        }
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        if (discoveryProperties != null) {
            discoveryProperties.getMetadata().remove(key);
        }
        getMyInstance().ifPresent(instance -> {
            try {
                NamingMaintainService maintainService = getNamingMaintainService();
                if (maintainService != null) {
                    instance.getMetadata().remove(key);
                    maintainService.updateInstance(instance.getServiceName(), resolveGroupName(null), instance);
                    log.info("Successfully removed metadata key '{}' for current Nacos instance", key);
                }
            } catch (Exception ex) {
                log.warn("Failed to push removed metadata to Nacos server: {}", ex.getMessage());
            }
        });
    }

    /**
     * 批量更新指定实例的元数据
     *
     * @param serviceName 服务名
     * @param ip          实例 IP
     * @param port        实例端口
     * @param metadata    待更新的元数据映射表
     */
    public void updateInstanceMetadata(String serviceName, String ip, int port, Map<String, String> metadata) {
        updateInstanceMetadata(serviceName, null, ip, port, metadata);
    }

    /**
     * 批量更新指定分组下服务实例的元数据
     *
     * @param serviceName 服务名
     * @param groupName   分组名
     * @param ip          实例 IP
     * @param port        实例端口
     * @param metadata    待更新的元数据映射表
     */
    public void updateInstanceMetadata(String serviceName, String groupName, String ip, int port, Map<String, String> metadata) {
        if (!isDiscoveryAvailable()) {
            log.warn("Nacos discovery is disabled. Skipping updateInstanceMetadata for {}:{}", ip, port);
            return;
        }
        executeWithExceptionTranslation("updateInstanceMetadata", () -> {
            NamingMaintainService maintainService = getNamingMaintainService();
            if (maintainService == null) {
                throw new BusinessException(NacosResultCode.NACOS_CLIENT_ERROR, "NamingMaintainService unavailable");
            }
            String group = resolveGroupName(groupName);
            Instance instance = findOrCreateInstance(serviceName, group, ip, port);
            if (metadata != null) {
                instance.getMetadata().putAll(metadata);
            }
            maintainService.updateInstance(serviceName, group, instance);
            log.info("Successfully updated metadata for Nacos instance {}:{} in service '{}:{}'",
                    ip, port, group, serviceName);
            return null;
        });
    }

    // ==========================================
    // 3. 内部辅助与异常因果转译机制
    // ==========================================

    @FunctionalInterface
    public interface NacosAction<T> {
        T execute() throws Exception;
    }

    /**
     * 执行底层调用并强制进行异常因果转译，100% 传递原始因果链
     */
    protected <T> T executeWithExceptionTranslation(String operation, NacosAction<T> action) {
        try {
            return action.execute();
        } catch (NacosException e) {
            log.error("Nacos operation '{}' failed with NacosException: {}", operation, e.getErrMsg(), e);
            throw new BusinessException(NacosResultCode.NACOS_CLIENT_ERROR,
                    "Nacos " + operation + " 失败: " + e.getErrMsg(), e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Nacos operation '{}' encountered unexpected error: {}", operation, e.getMessage(), e);
            throw new BusinessException(NacosResultCode.NACOS_OPERATION_FAILED,
                    "Nacos " + operation + " 异常: " + e.getMessage(), e);
        }
    }

    private Instance findOrCreateInstance(String serviceName, String groupName, String ip, int port) {
        List<Instance> instances = getAllInstances(serviceName, groupName);
        return instances.stream()
                .filter(i -> Objects.equals(i.getIp(), ip) && i.getPort() == port)
                .findFirst()
                .orElseGet(() -> {
                    Instance inst = new Instance();
                    inst.setServiceName(serviceName);
                    inst.setIp(ip);
                    inst.setPort(port);
                    return inst;
                });
    }

    private boolean isDiscoveryAvailable() {
        NacosServiceManager serviceManager = serviceManagerProvider.getIfAvailable();
        if (serviceManager == null) {
            return false;
        }
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        if (discoveryProperties != null) {
            return StringUtils.hasText(discoveryProperties.getServerAddr());
        }
        try {
            return getNamingService() != null;
        } catch (Exception ex) {
            return false;
        }
    }

    private Properties resolveNacosProperties() {
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        if (discoveryProperties == null) {
            return new Properties();
        }
        try {
            Properties props = discoveryProperties.getNacosProperties();
            return props != null ? props : new Properties();
        } catch (Exception ex) {
            Properties props = new Properties();
            if (StringUtils.hasText(discoveryProperties.getServerAddr())) {
                props.put("serverAddr", discoveryProperties.getServerAddr());
            }
            if (StringUtils.hasText(discoveryProperties.getNamespace())) {
                props.put("namespace", discoveryProperties.getNamespace());
            }
            return props;
        }
    }

    private NamingService getNamingService() {
        NacosServiceManager serviceManager = serviceManagerProvider.getIfAvailable();
        if (serviceManager == null) {
            return null;
        }
        return serviceManager.getNamingService(resolveNacosProperties());
    }

    private NamingMaintainService getNamingMaintainService() {
        NacosServiceManager serviceManager = serviceManagerProvider.getIfAvailable();
        if (serviceManager == null) {
            return null;
        }
        return serviceManager.getNamingMaintainService(resolveNacosProperties());
    }

    private String resolveGroupName(String groupName) {
        if (StringUtils.hasText(groupName)) {
            return groupName;
        }
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        return discoveryProperties != null && StringUtils.hasText(discoveryProperties.getGroup())
                ? discoveryProperties.getGroup()
                : Constants.DEFAULT_GROUP;
    }
}
