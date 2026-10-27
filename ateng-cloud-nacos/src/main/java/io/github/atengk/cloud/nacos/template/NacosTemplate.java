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
import java.util.HashMap;
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
        String serviceName = extractPureServiceName(discoveryProperties.getService());
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
     * 判断指定 IP 与端口是否对应当前运行微服务实例
     *
     * @param ip   目标实例 IP
     * @param port 目标实例端口
     * @return 是否属于当前节点自身
     */
    public boolean isCurrentInstance(String ip, int port) {
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        if (discoveryProperties == null) {
            return false;
        }
        return Objects.equals(discoveryProperties.getIp(), ip) && discoveryProperties.getPort() == port;
    }

    /**
     * 清洗服务名，剔除可能附带的分组前缀（如 DEFAULT_GROUP@@sample-service -> sample-service）
     *
     * @param serviceName 原始服务名
     * @return 纯净的服务名
     */
    public static String extractPureServiceName(String serviceName) {
        if (!StringUtils.hasText(serviceName)) {
            return serviceName;
        }
        int index = serviceName.indexOf(Constants.SERVICE_INFO_SPLITER);
        if (index >= 0) {
            return serviceName.substring(index + Constants.SERVICE_INFO_SPLITER.length());
        }
        return serviceName;
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
            String pureServiceName = extractPureServiceName(serviceName);
            String group = resolveGroupName(groupName);
            Instance instance = findOrCreateInstance(pureServiceName, group, ip, port);
            instance.setServiceName(pureServiceName);
            instance.setEnabled(enabled);

            // 若目标为当前节点自身：使用 NamingService gRPC 链路做原子增量覆盖（原生兼容 Nacos 1.x/2.x/3.x）
            if (isCurrentInstance(ip, port)) {
                NamingService namingService = getNamingService();
                if (namingService != null) {
                    namingService.registerInstance(pureServiceName, group, instance);
                    log.info("Successfully updated current Nacos instance enabled state to '{}' via gRPC in '{}:{}'",
                            enabled, group, pureServiceName);
                    return null;
                }
            }

            // 跨实例外部运维：使用 NamingMaintainService
            NamingMaintainService maintainService = getNamingMaintainService();
            if (maintainService == null) {
                throw new BusinessException(NacosResultCode.NACOS_CLIENT_ERROR, "NamingMaintainService unavailable");
            }
            maintainService.updateInstance(pureServiceName, group, instance);
            log.info("Successfully set Nacos instance {}:{} enabled state to '{}' for service '{}:{}'",
                    ip, port, enabled, group, pureServiceName);
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
            String pureServiceName = extractPureServiceName(serviceName);
            String group = resolveGroupName(groupName);
            Instance instance = findOrCreateInstance(pureServiceName, group, ip, port);
            instance.setServiceName(pureServiceName);
            instance.setWeight(weight);

            // 若目标为当前节点自身：使用 NamingService gRPC 链路无损覆盖
            if (isCurrentInstance(ip, port)) {
                NamingService namingService = getNamingService();
                if (namingService != null) {
                    namingService.registerInstance(pureServiceName, group, instance);
                    if (weight <= 0.0) {
                        log.warn("Current Nacos instance weight set to {} (traffic isolated) via gRPC in '{}:{}'",
                                weight, group, pureServiceName);
                    } else {
                        log.info("Successfully updated current Nacos instance weight to {} via gRPC in '{}:{}'",
                                weight, group, pureServiceName);
                    }
                    return null;
                }
            }

            // 跨实例外部运维：使用 NamingMaintainService
            NamingMaintainService maintainService = getNamingMaintainService();
            if (maintainService == null) {
                throw new BusinessException(NacosResultCode.NACOS_CLIENT_ERROR, "NamingMaintainService unavailable");
            }
            maintainService.updateInstance(pureServiceName, group, instance);
            if (weight <= 0.0) {
                log.warn("Nacos instance {}:{} weight set to {} (traffic completely isolated) in '{}:{}'",
                        ip, port, weight, group, pureServiceName);
            } else {
                log.info("Successfully updated Nacos instance {}:{} weight to {} in '{}:{}'",
                        ip, port, weight, group, pureServiceName);
            }
            return null;
        });
    }

    /**
     * 动态注册或更新当前服务实例的元数据
     *
     * <p>优先将元数据同步至本地缓存与配置属性，随后通过受管 {@link NamingService}
     * 基于长连接 gRPC 协议推送至 Nacos 集群完成原子覆写，原生兼容 Nacos 1.x、2.x 及 3.x。</p>
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
        pushSelfInstanceMetadata(key, value, true);
    }

    /**
     * 动态移除当前服务实例指定的元数据
     *
     * <p>从本地配置属性及 Nacos 注册中心当前实例元数据中移除指定键值，并基于 gRPC 协议推送更新。</p>
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
        pushSelfInstanceMetadata(key, null, false);
    }

    private void pushSelfInstanceMetadata(String key, String value, boolean isAddition) {
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        String serviceName = discoveryProperties != null ? extractPureServiceName(discoveryProperties.getService()) : null;
        String group = resolveGroupName(discoveryProperties != null ? discoveryProperties.getGroup() : null);
        String ip = discoveryProperties != null ? discoveryProperties.getIp() : null;
        int port = discoveryProperties != null ? discoveryProperties.getPort() : 0;

        if (StringUtils.hasText(serviceName) && StringUtils.hasText(ip) && port > 0) {
            executeWithExceptionTranslation(isAddition ? "registerMetadata" : "removeMetadata", () -> {
                NamingService namingService = getNamingService();
                if (namingService != null) {
                    Instance instance = findOrCreateInstance(serviceName, group, ip, port);
                    instance.setServiceName(serviceName);
                    if (isAddition) {
                        instance.getMetadata().put(key, value);
                    } else {
                        instance.getMetadata().remove(key);
                    }
                    namingService.registerInstance(serviceName, group, instance);
                    if (isAddition) {
                        log.info("Successfully registered metadata '{}'='{}' for current Nacos instance via gRPC", key, value);
                    } else {
                        log.info("Successfully removed metadata key '{}' for current Nacos instance via gRPC", key);
                    }
                } else {
                    log.debug("NamingService unavailable in current context. Updated local metadata provider only.");
                }
                return null;
            });
        }
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
            String pureServiceName = extractPureServiceName(serviceName);
            String group = resolveGroupName(groupName);
            Instance instance = findOrCreateInstance(pureServiceName, group, ip, port);
            instance.setServiceName(pureServiceName);
            if (metadata != null) {
                instance.getMetadata().putAll(metadata);
            }

            if (isCurrentInstance(ip, port)) {
                NamingService namingService = getNamingService();
                if (namingService != null) {
                    namingService.registerInstance(pureServiceName, group, instance);
                    log.info("Successfully updated current Nacos instance metadata via gRPC in '{}:{}'", group, pureServiceName);
                    return null;
                }
            }

            NamingMaintainService maintainService = getNamingMaintainService();
            if (maintainService == null) {
                throw new BusinessException(NacosResultCode.NACOS_CLIENT_ERROR, "NamingMaintainService unavailable");
            }
            maintainService.updateInstance(pureServiceName, group, instance);
            log.info("Successfully updated metadata for Nacos instance {}:{} in service '{}:{}'",
                    ip, port, group, pureServiceName);
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
            String errMsg = e.getErrMsg();
            if (errMsg != null && (errMsg.contains("no such api") || errMsg.contains("501"))) {
                errMsg = errMsg + " (提示: Nacos 3.x 默认未内置遗留 HTTP v1 OpenAPI，跨实例管理请在服务端挂载 nacos-api-legacy-adapter 插件)";
            }
            throw new BusinessException(NacosResultCode.NACOS_CLIENT_ERROR.getCode(),
                    "Nacos " + operation + " 失败: " + errMsg, e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(NacosResultCode.NACOS_OPERATION_FAILED.getCode(),
                    "Nacos " + operation + " 异常: " + e.getMessage(), e);
        }
    }

    private Instance findOrCreateInstance(String serviceName, String groupName, String ip, int port) {
        String pureServiceName = extractPureServiceName(serviceName);
        List<Instance> instances = getAllInstances(pureServiceName, groupName);
        NacosDiscoveryProperties discoveryProperties = discoveryPropertiesProvider.getIfAvailable();
        return instances.stream()
                .filter(i -> Objects.equals(i.getIp(), ip) && i.getPort() == port)
                .findFirst()
                .map(inst -> {
                    inst.setServiceName(pureServiceName);
                    if (isCurrentInstance(ip, port) && discoveryProperties != null && discoveryProperties.getMetadata() != null) {
                        discoveryProperties.getMetadata().forEach((k, v) -> inst.getMetadata().putIfAbsent(k, v));
                    }
                    return inst;
                })
                .orElseGet(() -> {
                    Instance inst = new Instance();
                    inst.setServiceName(pureServiceName);
                    inst.setIp(ip);
                    inst.setPort(port);
                    inst.setClusterName(properties != null && StringUtils.hasText(properties.getCluster())
                            ? properties.getCluster() : Constants.DEFAULT_CLUSTER_NAME);
                    inst.setEphemeral(true);
                    if (isCurrentInstance(ip, port) && discoveryProperties != null && discoveryProperties.getMetadata() != null) {
                        inst.setMetadata(new HashMap<>(discoveryProperties.getMetadata()));
                        inst.setWeight(discoveryProperties.getWeight());
                    }
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
