package io.github.atengk.cloud.seata.datasource.guard;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import io.github.atengk.cloud.seata.properties.SeataProperties;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 协同 Seata 全局分布式事务的动态数据源切源安全守卫
 * <p>
 * 严密协同 Spring 单连接事务、Baomidou 本地多源事务协调器与 Seata 全局分布式事务。
 * 当检测到当前线程处于 Seata 全局分布式事务中（{@link RootContext#getXID()} 非空）时，
 * 合法放行跨库切源操作，彻底消除传统切源守卫将 Seata 分布式事务误判为假切源造成的误杀拦截。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataDynamicDatasourceGuard {

    private static final Logger log = LoggerFactory.getLogger(SeataDynamicDatasourceGuard.class);

    /**
     * 绑定至 Spring 事务上下文的当前事务数据源标识资源 Key
     */
    public static final String TX_BOUND_DATASOURCE_RESOURCE_KEY = "ATENG_TX_BOUND_DATASOURCE_KEY";

    private final SeataProperties seataProperties;

    public SeataDynamicDatasourceGuard(SeataProperties seataProperties) {
        this.seataProperties = seataProperties != null ? seataProperties : new SeataProperties();
    }

    /**
     * 校验当前执行上下文切源操作是否安全
     *
     * @param targetDsKey 目标数据源 Key
     */
    public void validateSwitch(String targetDsKey) {
        validateSwitch(targetDsKey, null, null);
    }

    /**
     * 校验当前执行上下文切源操作是否安全（携带方法反射信息）
     *
     * @param targetDsKey 目标数据源 Key
     * @param method      切源目标方法（用于提取 @Transactional 传播属性）
     */
    public void validateSwitch(String targetDsKey, Method method) {
        validateSwitch(targetDsKey, method, method != null ? method.getDeclaringClass() : null);
    }

    /**
     * 校验当前执行上下文切源操作是否安全（完整重载）
     *
     * @param targetDsKey 目标数据源 Key
     * @param method      切源目标方法
     * @param targetClass 切源目标类
     */
    public void validateSwitch(String targetDsKey, Method method, Class<?> targetClass) {
        if (!seataProperties.getDynamicDatasource().isGuardEnabled() || !StringUtils.hasText(targetDsKey)) {
            return;
        }

        // 1. 若当前处于 Seata 全局分布式事务中，放行跨库合法切源
        if (isSeataTransactionActive()) {
            log.debug("当前处于 Seata 全局分布式事务 (XID=[{}]), 放行数据源切换至: [{}]",
                    RootContext.getXID(), targetDsKey);
            return;
        }

        // 2. 若当前处于 Baomidou @DSTransactional 本地多源事务协调器上下文中，放行切源
        if (isDSTransactionActive()) {
            log.debug("当前处于 Baomidou @DSTransactional 多源协调事务 (XID=[{}]), 放行数据源切换至: [{}]",
                    TransactionContext.getXID(), targetDsKey);
            return;
        }

        // 3. 检查当前是否存在处于活动状态的 Spring 单物理连接事务
        if (!isSpringTransactionActive()) {
            // 无活动事务，安全放行
            return;
        }

        // 4. 当前处于活动 Spring 事务中：检查是否通过 Propagation.REQUIRES_NEW 声明开启独立新事务
        if (isRequiresNewTransaction(method, targetClass)) {
            log.debug("检测到切源方法声明了 @Transactional(propagation = Propagation.REQUIRES_NEW)，放行独立事务切源至: [{}]",
                    targetDsKey);
            return;
        }

        // 5. 获取当前 Spring 事务初始绑定或执行中的物理数据源 Key
        String currentTxDsKey = getCurrentTransactionDataSourceKey();

        // 6. 若目标数据源与当前 Spring 事务绑定的数据源相同，未发生跨库物理切换，安全放行
        if (Objects.equals(currentTxDsKey, targetDsKey)) {
            return;
        }

        // 7. 检测到跨库切换，判定为 Spring 事务假切源致命隐患
        String errorMessage = String.format(
                "检测到 Spring 单连接事务 (DataSourceTransactionManager) 假切源致命风险：" +
                "当前事务已绑定数据源 [%s]，尝试切源至 [%s] 将被 Spring 事务静默忽略并造成串库！" +
                "若需跨库独立执行，请在目标方法开启 Seata 全局分布式事务 (@GlobalTransactional) 或声明 @Transactional(propagation = Propagation.REQUIRES_NEW)。",
                currentTxDsKey, targetDsKey
        );

        if (seataProperties.getDynamicDatasource().isFailOnFalseSwitch()) {
            log.error(errorMessage);
            throw new IllegalStateException(errorMessage);
        } else {
            log.warn("假切源拦截降级 (failOnFalseSwitch=false): {}", errorMessage);
        }
    }

    /**
     * 校验当前执行上下文切源操作是否隔离安全（兼容编程式守卫契约）
     *
     * @param targetDsKey 目标数据源 Key
     */
    public void validateContextIsolated(String targetDsKey) {
        validateSwitch(targetDsKey);
    }

    /**
     * 判断当前线程是否处于 Seata 全局分布式事务活跃上下文中
     *
     * @return true 表示处于 Seata 全局分布式事务中
     */
    public boolean isSeataTransactionActive() {
        return StringUtils.hasText(RootContext.getXID());
    }

    /**
     * 判断当前线程是否处于 Baomidou @DSTransactional 多数据源事务协调器上下文中
     *
     * @return true 表示处于 @DSTransactional 事务中；false 表示未激活
     */
    public boolean isDSTransactionActive() {
        return StringUtils.hasText(TransactionContext.getXID());
    }

    /**
     * 判断当前线程是否处于标准 Spring 事务 (DataSourceTransactionManager) 活跃上下文中
     *
     * @return true 表示处于 Spring 事务中；false 表示未激活
     */
    public boolean isSpringTransactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    /**
     * 获取当前处于活动状态的 Spring 事务绑定的数据源 Key
     *
     * @return 当前事务绑定的数据源 Key，若无活动事务则返回 null
     */
    public String getCurrentTransactionDataSourceKey() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }

        // 优先从 Spring 事务资源绑定表中读取
        if (TransactionSynchronizationManager.hasResource(TX_BOUND_DATASOURCE_RESOURCE_KEY)) {
            Object resource = TransactionSynchronizationManager.getResource(TX_BOUND_DATASOURCE_RESOURCE_KEY);
            if (resource instanceof String boundKey && StringUtils.hasText(boundKey)) {
                return boundKey;
            }
        }

        // 若尚未记录，则取当前线程栈顶数据源，栈为空则取默认主库 "master"
        String currentKey = DynamicDataSourceContextHolder.peek();
        if (!StringUtils.hasText(currentKey)) {
            currentKey = "master";
        }

        // 若当前事务同步机制已激活，将初始数据源绑定至事务资源表，并在事务完成时解绑
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                TransactionSynchronizationManager.bindResource(TX_BOUND_DATASOURCE_RESOURCE_KEY, currentKey);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (TransactionSynchronizationManager.hasResource(TX_BOUND_DATASOURCE_RESOURCE_KEY)) {
                            TransactionSynchronizationManager.unbindResource(TX_BOUND_DATASOURCE_RESOURCE_KEY);
                        }
                    }
                });
            } catch (Throwable t) {
                log.debug("注册事务数据源解绑同步器异常: {}", t.getMessage());
            }
        }

        return currentKey;
    }

    /**
     * 判定目标方法或目标类是否声明了 Propagation.REQUIRES_NEW 独立事务
     *
     * @param method      切源方法
     * @param targetClass 切源目标类
     * @return true 表示声明了 REQUIRES_NEW
     */
    public boolean isRequiresNewTransaction(Method method, Class<?> targetClass) {
        if (method != null) {
            Transactional methodTx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
            if (methodTx != null) {
                return methodTx.propagation() == Propagation.REQUIRES_NEW;
            }
        }
        if (targetClass != null) {
            Transactional classTx = AnnotatedElementUtils.findMergedAnnotation(targetClass, Transactional.class);
            if (classTx != null) {
                return classTx.propagation() == Propagation.REQUIRES_NEW;
            }
        }
        return false;
    }
}
