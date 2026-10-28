package io.github.atengk.cloud.feign.preheat;

import feign.Client;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.cloud.openfeign.FeignClientSpecification;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feign 客户端启动期饥饿预热监听器
 * 在 Spring Boot 单例 Bean 全部初始化完成后，静默预热底层 HC5 连接池与 FeignClient 代理及子容器，
 * 彻底消除生产滚动发布与冷启动时的首调超时尖刺。
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignPreheatListener implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(AtengFeignPreheatListener.class);

    private final ApplicationContext applicationContext;
    private final AtengFeignProperties properties;

    private volatile boolean preheated = false;
    private volatile int preheatedCount = 0;

    public AtengFeignPreheatListener(ApplicationContext applicationContext, AtengFeignProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (properties.getPreheat() == null || !properties.getPreheat().isEnabled()) {
            return;
        }

        long start = System.currentTimeMillis();
        int count = 0;

        // 1. 强类型受管探测预热底层通信传输客户端与 HC5 连接池管理器
        try {
            applicationContext.getBeanProvider(HttpClientConnectionManager.class)
                    .ifAvailable(cm -> log.debug("HC5 连接池管理器预热就绪: {}", cm.getClass().getSimpleName()));
            applicationContext.getBeanProvider(Client.class)
                    .ifAvailable(client -> log.debug("Feign 底层通信客户端预热就绪: {}", client.getClass().getSimpleName()));
        } catch (Exception e) {
            log.debug("Feign 底层传输客户端预热提示: {}", e.getMessage());
        }

        // 2. 预热指定的或已注册的 FeignClient 实例与子容器
        FeignClientFactory feignClientFactory = applicationContext.getBeanProvider(FeignClientFactory.class).getIfAvailable();
        List<String> targetClients = properties.getPreheat().getClients();

        if (targetClients != null && !targetClients.isEmpty() && feignClientFactory != null) {
            for (String clientName : targetClients) {
                if (preheatClient(feignClientFactory, clientName)) {
                    count++;
                }
            }
        } else if (feignClientFactory != null) {
            // 若未显式指定 clients 列表，通过多种机制汇聚所有注册的 FeignClient 名称
            Set<String> allClientNames = new LinkedHashSet<>();

            // 机制 A：从 FeignClientFactory 既有配置命名空间提取
            if (feignClientFactory.getConfigurations() != null) {
                allClientNames.addAll(feignClientFactory.getConfigurations().keySet());
            }

            // 机制 B：从 ApplicationContext 中注册的 FeignClientSpecification 规范提取
            Map<String, FeignClientSpecification> specs = applicationContext.getBeansOfType(FeignClientSpecification.class);
            for (FeignClientSpecification spec : specs.values()) {
                if (spec.getName() != null && !spec.getName().isBlank() && !spec.getName().startsWith("default.")) {
                    allClientNames.add(spec.getName());
                }
            }

            for (String clientName : allClientNames) {
                if (preheatClient(feignClientFactory, clientName)) {
                    count++;
                }
            }

            // 自动触达上下文中扫描到的全部 @FeignClient 实例代理 Bean
            String[] feignClientBeanNames = applicationContext.getBeanNamesForAnnotation(FeignClient.class);
            for (String beanName : feignClientBeanNames) {
                try {
                    applicationContext.getBean(beanName);
                    log.debug("Feign 客户端 Bean [{}] 动态代理预热就绪", beanName);
                } catch (Exception e) {
                    log.warn("Feign 客户端 Bean [{}] 预热失败: {}", beanName, e.getMessage());
                }
            }
        }

        this.preheated = true;
        this.preheatedCount = count;

        long elapsed = System.currentTimeMillis() - start;
        log.info("Feign 启动期饥饿预热完成: 共预热 {} 个客户端，耗时 {} ms", count, elapsed);
    }

    private boolean preheatClient(FeignClientFactory factory, String clientName) {
        if (factory == null || clientName == null || clientName.isBlank() || "default".equals(clientName)) {
            return false;
        }
        try {
            factory.getInstance(clientName, feign.Feign.Builder.class);
            log.info("Feign 客户端 [{}] 子容器饥饿预热就绪", clientName);
            return true;
        } catch (Exception e) {
            log.warn("Feign 客户端 [{}] 预热失败: {}", clientName, e.getMessage());
            return false;
        }
    }

    public boolean isPreheated() {
        return preheated;
    }

    public int getPreheatedCount() {
        return preheatedCount;
    }
}
