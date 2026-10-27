package io.github.atengk.cloud.nacos.event;

import com.alibaba.nacos.common.remote.client.RpcClient;
import com.alibaba.nacos.common.remote.client.RpcClientFactory;
import io.github.atengk.cloud.nacos.metrics.NacosMetricsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos gRPC 长连接监听器注册与生命周期管理器
 *
 * <p>在单例 Bean 实例化完成后，扫描并向 Nacos 客户端工厂（{@link RpcClientFactory}）中
 * 注册长连接事件监听器，实现全域 RPC 通信断连与重连感知。</p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class NacosConnectionManager implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(NacosConnectionManager.class);

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<NacosMetricsManager> metricsManagerProvider;
    private final Set<String> registeredClients = ConcurrentHashMap.newKeySet();

    public NacosConnectionManager(ApplicationEventPublisher eventPublisher,
                                  ObjectProvider<NacosMetricsManager> metricsManagerProvider) {
        this.eventPublisher = eventPublisher;
        this.metricsManagerProvider = metricsManagerProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        registerListeners();
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
                        if (name != null && registeredClients.add(name)) {
                            RpcClient client = entry.getValue();
                            NacosMetricsManager metricsManager = metricsManagerProvider.getIfAvailable();
                            NacosConnectionEventListener listener = new NacosConnectionEventListener(name, eventPublisher, metricsManager);
                            client.registerConnectionListener(listener);
                            count++;
                            log.debug("[ateng-cloud-nacos] Registered NacosConnectionEventListener to RpcClient '{}'", name);
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
        if (name != null && registeredClients.add(name)) {
            NacosMetricsManager metricsManager = metricsManagerProvider.getIfAvailable();
            NacosConnectionEventListener listener = new NacosConnectionEventListener(name, eventPublisher, metricsManager);
            client.registerConnectionListener(listener);
            log.debug("[ateng-cloud-nacos] Manually registered NacosConnectionEventListener to RpcClient '{}'", name);
            return true;
        }
        return false;
    }

    public Set<String> getRegisteredClients() {
        return Collections.unmodifiableSet(registeredClients);
    }
}
