package io.github.atengk.cloud.sample.nacos.config;

import com.alibaba.nacos.api.naming.pojo.Instance;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 编程式微服务实例拓扑动态变更订阅演示服务
 *
 * <p>基于 {@link NacosTemplate#subscribeInstances(String, java.util.function.Consumer)}
 * 演示首帧拓扑快照回调、MDC 链路传递、变更通知与受管生命周期注销。</p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Service
public class DemoInstanceSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(DemoInstanceSubscriptionService.class);

    public static final String TARGET_SERVICE_NAME = "sample-cloud-nacos";

    private final NacosTemplate nacosTemplate;
    private final AtomicInteger notificationCount = new AtomicInteger(0);
    private final AtomicReference<List<Instance>> currentInstances = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<String> lastTraceId = new AtomicReference<>("");
    private AutoCloseable subscriptionHandle;

    public DemoInstanceSubscriptionService(NacosTemplate nacosTemplate) {
        this.nacosTemplate = nacosTemplate;
    }

    @PostConstruct
    public void init() {
        // 编程式订阅 sample-cloud-nacos 实例拓扑变更，自动挂载 MDC traceId 穿透与异常屏障
        try {
            this.subscriptionHandle = nacosTemplate.subscribeInstances(
                    TARGET_SERVICE_NAME,
                    this::onInstancesChanged
            );
            log.info("[DemoInstanceSubscriptionService] 成功注册 '{}' 实例拓扑动态变更订阅句柄", TARGET_SERVICE_NAME);
        } catch (Exception ex) {
            log.warn("[DemoInstanceSubscriptionService] 初始化实例动态订阅跳过 (离线或无可用服务发现): {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (subscriptionHandle != null) {
            try {
                subscriptionHandle.close();
                log.info("[DemoInstanceSubscriptionService] 已安全注销 '{}' 实例动态变更订阅句柄", TARGET_SERVICE_NAME);
            } catch (Exception ex) {
                log.warn("Error closing instance subscription: {}", ex.getMessage());
            }
        }
    }

    public void onInstancesChanged(List<Instance> instances) {
        int count = notificationCount.incrementAndGet();
        currentInstances.set(instances != null ? instances : Collections.emptyList());
        String traceId = MDC.get("traceId");
        lastTraceId.set(traceId != null ? traceId : "");
        log.info("[DemoInstanceSubscriptionService] 收到微服务实例拓扑变更通知 #{}, traceId={}, count={}",
                count, traceId, currentInstances.get().size());
    }

    public int getNotificationCount() {
        return notificationCount.get();
    }

    public List<Instance> getCurrentInstances() {
        return currentInstances.get();
    }

    public String getLastTraceId() {
        return lastTraceId.get();
    }
}
