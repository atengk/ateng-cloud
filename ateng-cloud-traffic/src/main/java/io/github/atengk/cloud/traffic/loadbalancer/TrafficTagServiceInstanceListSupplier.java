package io.github.atengk.cloud.traffic.loadbalancer;

import io.github.atengk.cloud.traffic.constant.TrafficConstants;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import io.github.atengk.cloud.traffic.properties.AtengTrafficProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
 * Spring Cloud LoadBalancer 东西向服务间调用流量染色与灰度版本隔离选择器
 *
 * <p>核心治理语义：
 * 1. 精准提取调用上下文中的流量染色标记（优先 {@link TrafficContextHolder}，次选 {@link RequestDataContext} 与 MDC）；
 * 2. 精准匹配服务实例元数据中的灰度标签（{@code metadata.tag}）；
 * 3. 携带灰度标签流量严格调度至对应灰度节点，未染色流量调度至基线默认实例；
 * 4. 容灾平滑降级：在 {@code fallbackToDefault=true} 下，当目标灰度节点全下线或不存在时自动降级至基线默认实例；
 * 5. 金融级强隔离：在 {@code strictLane=true} 下，未命中目标灰度节点时直接阻断返回空列表（Fail-Fast）。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficTagServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

    private static final Logger log = LoggerFactory.getLogger(TrafficTagServiceInstanceListSupplier.class);

    private final AtengTrafficProperties properties;

    public TrafficTagServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
                                                 AtengTrafficProperties properties) {
        super(delegate);
        this.properties = properties != null ? properties : new AtengTrafficProperties();
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        String tag = TrafficContextHolder.getTag();
        return getDelegate().get().map(instances -> filterByTag(instances, tag));
    }

    @Override
    public Flux<List<ServiceInstance>> get(Request request) {
        String tag = extractTag(request);
        return getDelegate().get(request).map(instances -> filterByTag(instances, tag));
    }

    /**
     * 根据流量染色标签过滤目标服务实例
     *
     * @param instances 原始可用服务实例列表
     * @param tag       染色标签 (可为 null)
     * @return 过滤或降级后的可用服务实例列表
     */
    public List<ServiceInstance> filterByTag(List<ServiceInstance> instances, String tag) {
        if (instances == null || instances.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 若配置关闭了流量灰度治理，直接放行全量可用实例
        if (!properties.isEnabled()) {
            return instances;
        }

        // 2. 若请求携带了灰度染色标签
        if (StringUtils.hasText(tag)) {
            String trimmedTag = tag.trim();
            List<ServiceInstance> taggedInstances = instances.stream()
                    .filter(inst -> matchTag(inst, trimmedTag))
                    .toList();

            if (!taggedInstances.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[TrafficLoadBalancer] 命中目标灰度节点 [serviceId={}, tag={}, count={}]",
                            getServiceId(), trimmedTag, taggedInstances.size());
                }
                return Collections.unmodifiableList(taggedInstances);
            }

            // 灰度节点全部下线或不存在时的容灾处理
            if (properties.isStrictLane()) {
                log.warn("[TrafficLoadBalancer] 目标灰度节点不存在且开启严格隔离模式，阻断调度 [serviceId={}, tag={}]",
                        getServiceId(), trimmedTag);
                return Collections.emptyList();
            }

            if (properties.isFallbackToDefault()) {
                log.warn("[TrafficLoadBalancer] 目标灰度节点全下线，触发平滑降级至基线实例 [serviceId={}, tag={}]",
                        getServiceId(), trimmedTag);
                return filterBaselineInstances(instances);
            } else {
                log.warn("[TrafficLoadBalancer] 目标灰度节点全下线且未开启平滑降级，阻断调度 [serviceId={}, tag={}]",
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
                || TrafficConstants.DEFAULT_TAG.equalsIgnoreCase(tag)
                || "baseline".equalsIgnoreCase(tag);
    }

    /**
     * 从服务实例元数据中提取灰度染色标签 (兼容 tag / traffic-tag / traffic.tag / gray)
     */
    private String extractInstanceTag(ServiceInstance instance) {
        if (instance == null || instance.getMetadata() == null) {
            return null;
        }
        Map<String, String> meta = instance.getMetadata();
        String tag = meta.get(TrafficConstants.METADATA_TAG);
        if (!StringUtils.hasText(tag)) {
            tag = meta.get("traffic-tag");
        }
        if (!StringUtils.hasText(tag)) {
            tag = meta.get("traffic.tag");
        }
        if (!StringUtils.hasText(tag)) {
            tag = meta.get("gray");
        }
        return tag != null ? tag.trim() : null;
    }

    /**
     * 从多维调用上下文中解析流量染色标签
     */
    private String extractTag(Request<?> request) {
        // 1. 优先读取当前线程 TrafficContextHolder (Feign / RestTemplate 发起方上下文)
        String tag = TrafficContextHolder.getTag();
        if (StringUtils.hasText(tag)) {
            return tag;
        }

        // 2. 次选从 LoadBalancer Request 上下文中提取
        if (request != null) {
            Object context = request.getContext();
            if (context instanceof String s && StringUtils.hasText(s)) {
                return s.trim();
            }
            if (context instanceof RequestDataContext dataContext) {
                RequestData clientRequest = dataContext.getClientRequest();
                if (clientRequest != null) {
                    if (clientRequest.getHeaders() != null) {
                        String headerName = StringUtils.hasText(properties.getHeaderName())
                                ? properties.getHeaderName() : TrafficConstants.HEADER_TRAFFIC_TAG;
                        String headerTag = clientRequest.getHeaders().getFirst(headerName);
                        if (StringUtils.hasText(headerTag)) {
                            return headerTag.trim();
                        }
                    }
                    if (clientRequest.getAttributes() != null) {
                        Object attrTag = clientRequest.getAttributes().get(TrafficConstants.HEADER_TRAFFIC_TAG);
                        if (attrTag instanceof String s && StringUtils.hasText(s)) {
                            return s.trim();
                        }
                    }
                }
            }
        }

        // 3. 兜底探测 MDC
        String mdcTag = MDC.get(TrafficConstants.MDC_TRAFFIC_TAG);
        if (StringUtils.hasText(mdcTag)) {
            return mdcTag.trim();
        }

        return null;
    }
}
