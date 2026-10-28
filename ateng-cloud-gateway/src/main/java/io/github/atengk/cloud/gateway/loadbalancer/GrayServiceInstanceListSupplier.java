package io.github.atengk.cloud.gateway.loadbalancer;

import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import io.github.atengk.cloud.gateway.properties.AtengGatewayTrafficProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.loadbalancer.core.DelegatingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 响应式网关金丝雀灰度与平滑降级服务实例提供者
 *
 * <p>核心治理语义：
 * 1. 精准匹配 Nacos 等注册中心服务实例元数据中的 {@code metadata.tag}；
 * 2. 携带灰度标签流量严格调度至对应灰度节点，未染色流量调度至基线默认实例；
 * 3. 当灰度节点全部下线或未配置对应标签时，在 {@code fallbackToDefault=true} 下平滑降级至基线默认实例，杜绝 503 报警。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class GrayServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private static final Logger log = LoggerFactory.getLogger(GrayServiceInstanceListSupplier.class);

    private final AtengGatewayTrafficProperties properties;

    public GrayServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
                                           AtengGatewayTrafficProperties properties) {
        super(delegate);
        this.properties = properties;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return getDelegate().get().map(instances -> filterByTag(instances, null));
    }

    @Override
    public Flux<List<ServiceInstance>> get(Request request) {
        String tag = extractTagFromRequest(request);
        return getDelegate().get(request).map(instances -> filterByTag(instances, tag));
    }

    /**
     * 根据流量染色标签过滤目标服务实例
     *
     * @param instances 原始可用服务实例列表
     * @param tag       染色标签 (可为 null)
     * @return 过滤或平滑降级后的服务实例列表
     */
    public List<ServiceInstance> filterByTag(List<ServiceInstance> instances, String tag) {
        if (instances == null || instances.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 若配置关闭了灰度治理，直接放行全量实例
        if (properties == null || !properties.isEnabled()) {
            return instances;
        }

        // 2. 若请求携带了灰度染色标签
        if (StringUtils.hasText(tag)) {
            String trimmedTag = tag.trim();
            List<ServiceInstance> taggedInstances = instances.stream()
                    .filter(inst -> matchTag(inst, trimmedTag))
                    .toList();

            if (!taggedInstances.isEmpty()) {
                log.debug("[GatewayGray] 命中灰度节点 [serviceId={}, tag={}, count={}]",
                        getServiceId(), trimmedTag, taggedInstances.size());
                return Collections.unmodifiableList(taggedInstances);
            }

            // 灰度节点全部下线或不存在
            if (properties.isFallbackToDefault()) {
                log.warn("[GatewayGray] 灰度节点全部下线或未配置对应标签，触发平滑降级至基线实例 [serviceId={}, tag={}]",
                        getServiceId(), trimmedTag);
                return filterBaselineInstances(instances);
            } else {
                log.warn("[GatewayGray] 灰度节点全部下线且未开启降级，阻断流量 [serviceId={}, tag={}]",
                        getServiceId(), trimmedTag);
                return Collections.emptyList();
            }
        }

        // 3. 未携带灰度标签的普通基线流量，优先路由至基线实例
        return filterBaselineInstances(instances);
    }

    /**
     * 过滤基线默认实例 (未配置 tag 或 tag 为 default / baseline 的实例)
     */
    private List<ServiceInstance> filterBaselineInstances(List<ServiceInstance> instances) {
        List<ServiceInstance> baselineInstances = instances.stream()
                .filter(this::isBaselineInstance)
                .toList();

        if (!baselineInstances.isEmpty()) {
            return Collections.unmodifiableList(baselineInstances);
        }

        // 若集群全量节点均带有特殊 tag，保底返回全体实例避免正常请求全灭
        return instances;
    }

    /**
     * 判定实例是否匹配指定的灰度标签
     */
    private boolean matchTag(ServiceInstance instance, String targetTag) {
        String instanceTag = extractInstanceTag(instance);
        return targetTag != null && targetTag.equalsIgnoreCase(instanceTag);
    }

    /**
     * 判定实例是否为基线实例
     */
    private boolean isBaselineInstance(ServiceInstance instance) {
        String tag = extractInstanceTag(instance);
        return !StringUtils.hasText(tag)
                || "default".equalsIgnoreCase(tag)
                || "baseline".equalsIgnoreCase(tag);
    }

    /**
     * 从服务实例元数据中提取灰度染色标签 (兼容 tag / traffic-tag / traffic.tag)
     */
    private String extractInstanceTag(ServiceInstance instance) {
        if (instance == null || instance.getMetadata() == null) {
            return null;
        }
        Map<String, String> meta = instance.getMetadata();
        String tag = meta.get("tag");
        if (!StringUtils.hasText(tag)) {
            tag = meta.get("traffic-tag");
        }
        if (!StringUtils.hasText(tag)) {
            tag = meta.get("traffic.tag");
        }
        return tag;
    }

    /**
     * 从负载均衡请求上下文中提取灰度染色标签
     */
    private String extractTagFromRequest(Request<?> request) {
        if (request != null && request.getContext() instanceof RequestDataContext dataContext) {
            RequestData clientRequest = dataContext.getClientRequest();
            if (clientRequest != null) {
                if (clientRequest.getHeaders() != null) {
                    String headerTag = clientRequest.getHeaders().getFirst(GatewayConstants.X_TRAFFIC_TAG);
                    if (StringUtils.hasText(headerTag)) {
                        return headerTag.trim();
                    }
                }
                if (clientRequest.getAttributes() != null) {
                    Object attrTag = clientRequest.getAttributes().get(GatewayConstants.ATTR_TRAFFIC_TAG);
                    if (attrTag instanceof String s && StringUtils.hasText(s)) {
                        return s.trim();
                    }
                }
            }
        }
        return null;
    }
}
