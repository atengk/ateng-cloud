package io.github.atengk.cloud.nacos.event;

import com.alibaba.nacos.common.remote.client.RpcClient;
import com.alibaba.nacos.common.remote.client.RpcClientFactory;
import io.github.atengk.cloud.nacos.metrics.NacosMetricsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos gRPC 长连接监听器注册与生命周期管理器
 *
 * <p>在单例 Bean 实例化完成后及应用完全就绪时，扫描并向 Nacos 客户端工厂（{@link RpcClientFactory}）中
 * 注册长连接事件监听器，实现全域 RPC 通信断连与重连感知；在容器销毁时安全阻断事件广播与释放资源。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosConnectionManager implements SmartInitializingSingleton, DisposableBean, ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(NacosConnectionManager.class);

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<NacosMetricsManager> metricsManagerProvider;
    private final Map<String, NacosConnectionEventListener> registeredListeners = new ConcurrentHashMap<>();

    public NacosConnectionManager(ApplicationEventPublisher eventPublisher,
                                  ObjectProvider<NacosMetricsManager> metricsManagerProvider) {
        this.eventPublisher = eventPublisher;
        this.metricsManagerProvider = metricsManagerProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        registerListeners();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 在微服务启动完全就绪后，二次扫描注册可能在 Web 容器启动或动态发现阶段初始化的 RpcClient
        int count = registerListeners();
        if (count > 0) {
            log.info("[ateng-cloud-nacos] Dynamically registered connection listeners for {} newly discovered RpcClient(s) on ApplicationReadyEvent", count);
        }
    }

    /**
     * 扫描并向所有既有及新发现的 RpcClient 注册连接监听器
     *
     * @return 成功注册的新客户端数量
     */
    public int registerListeners() {
        int count = 0;
        try {
            Set<Map.Entry<String, RpcClient>> entries = RpcClientFactory.getAllClientEntries();
            if (entries != null) {
                for (Map.Entry<String, RpcClient> entry : entries) {
                    if (entry != null && entry.getValue() != null) {
                        String name = entry.getKey();
                        if (name != null && !registeredListeners.containsKey(name)) {
                            RpcClient client = entry.getValue();
                            if (registerToClient(client)) {
                                count++;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ex) {
            log.debug("[ateng-cloud-nacos] Error scanning RpcClientFactory for connection listeners: {}", ex.getMessage());
        }
        return count;
    }

    /**
     * 手动向指定的 RpcClient 注册连接监听器
     *
     * @param client 目标 RPC 客户端
     * @return 是否注册成功（已注册过则返回 false）
     */
    public boolean registerToClient(RpcClient client) {
        if (client == null) {
            return false;
        }
        String name = client.getName();
        if (name != null && !registeredListeners.containsKey(name)) {
            NacosMetricsManager metricsManager = metricsManagerProvider.getIfAvailable();
            NacosConnectionEventListener listener = new NacosConnectionEventListener(name, eventPublisher, metricsManager);
            NacosConnectionEventListener existing = registeredListeners.putIfAbsent(name, listener);
            if (existing == null) {
                client.registerConnectionListener(listener);
                log.debug("[ateng-cloud-nacos] Registered NacosConnectionEventListener to RpcClient '{}'", name);
                return true;
            }
        }
        return false;
    }

    /**
     * 注销指定客户端的监听器并阻断后续事件传播
     *
     * @param clientName 客户端标识
     * @return 是否成功注销（未注册过则返回 false）
     */
    public boolean deregisterClient(String clientName) {
        if (clientName == null) {
            return false;
        }
        NacosConnectionEventListener listener = registeredListeners.remove(clientName);
        if (listener != null) {
            listener.close();
            log.debug("[ateng-cloud-nacos] Deregistered NacosConnectionEventListener for client '{}'", clientName);
            return true;
        }
        return false;
    }

    @Override
    public void destroy() {
        for (Map.Entry<String, NacosConnectionEventListener> entry : registeredListeners.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Throwable ex) {
                log.debug("[ateng-cloud-nacos] Error closing NacosConnectionEventListener for '{}': {}", entry.getKey(), ex.getMessage());
            }
        }
        registeredListeners.clear();
        log.info("[ateng-cloud-nacos] NacosConnectionManager destroyed and all registered listeners closed.");
    }

    public Set<String> getRegisteredClients() {
        return Collections.unmodifiableSet(registeredListeners.keySet());
    }
}
