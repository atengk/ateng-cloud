package io.github.atengk.cloud.seata.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 租户处理器后置处理器
 * 自动为容器中的 TenantLineHandler 包装 Seata 内部表忽略逻辑（包含默认表与自定义 TCC 防卫表）
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataTenantLineHandlerPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(SeataTenantLineHandlerPostProcessor.class);

    private final String customTccTableName;

    public SeataTenantLineHandlerPostProcessor() {
        this(null);
    }

    public SeataTenantLineHandlerPostProcessor(String customTccTableName) {
        this.customTccTableName = customTccTableName;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof TenantLineHandler handler && !(bean instanceof SeataTenantLineHandlerWrapper)) {
            log.info("为 TenantLineHandler 实例 [{}] 附加 Seata 内部表多租户忽略保护, 自定义表名: [{}]",
                    beanName, customTccTableName != null ? customTccTableName : "无");
            return new SeataTenantLineHandlerWrapper(handler, customTccTableName);
        }
        return bean;
    }

    public String getCustomTccTableName() {
        return customTccTableName;
    }
}
