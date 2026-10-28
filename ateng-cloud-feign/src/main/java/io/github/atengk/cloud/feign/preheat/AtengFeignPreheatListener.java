package io.github.atengk.cloud.feign.preheat;

import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientFactory;
import org.springframework.context.ApplicationContext;

import java.util.List;

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
        int preheatedCount = 0;

        // 1. 预热底层 HC5 通信传输客户端与连接池
        try {
            if (applicationContext.containsBean("feignClient")) {
                applicationContext.getBean("feignClient");
            }
        } catch (Exception e) {
            log.debug("Feign 底层传输客户端预热提示: {}", e.getMessage());
        }

        // 2. 预热指定的或已注册的 FeignClient 实例与子容器
        FeignClientFactory feignClientFactory = applicationContext.getBeanProvider(FeignClientFactory.class).getIfAvailable();
        List<String> targetClients = properties.getPreheat().getClients();

        if (targetClients != null && !targetClients.isEmpty() && feignClientFactory != null) {
            for (String clientName : targetClients) {
                try {
                    feignClientFactory.getInstance(clientName, feign.Feign.Builder.class);
                    preheatedCount++;
                    log.info("Feign 客户端 [{}] 子容器饥饿预热就绪", clientName);
                } catch (Exception e) {
                    log.warn("Feign 客户端 [{}] 预热失败: {}", clientName, e.getMessage());
                }
            }
        } else {
            // 若未显式指定 clients 列表，优先预热 FeignClientFactory 中所有已注册的子容器
            if (feignClientFactory != null && feignClientFactory.getConfigurations() != null) {
                for (String clientName : feignClientFactory.getConfigurations().keySet()) {
                    try {
                        feignClientFactory.getInstance(clientName, feign.Feign.Builder.class);
                        preheatedCount++;
                        log.debug("Feign 客户端 [{}] 子容器预热就绪", clientName);
                    } catch (Exception e) {
                        log.warn("Feign 客户端 [{}] 预热失败: {}", clientName, e.getMessage());
                    }
                }
            }
            // 自动预热上下文中扫描到的全部 @FeignClient 实例
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

        long elapsed = System.currentTimeMillis() - start;
        log.info("Feign 启动期饥饿预热完成: 共预热 {} 个客户端，耗时 {} ms", preheatedCount, elapsed);
    }
}
