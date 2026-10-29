package io.github.atengk.cloud.seata.properties;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 分布式事务增强配置属性类
 *
 * @author Ateng
 * @since 2026-10-29
 */
@ConfigurationProperties(prefix = SeataConstants.PROPERTIES_PREFIX)
public class SeataProperties {

    /**
     * 是否开启 Seata 分布式事务增强底座
     */
    private boolean enabled = true;

    /**
     * 全局事务控制配置
     */
    @NestedConfigurationProperty
    private TxProperties tx = new TxProperties();

    /**
     * 全局锁与排他锁配置
     */
    @NestedConfigurationProperty
    private LockProperties lock = new LockProperties();

    /**
     * TC 服务端断连与容灾配置
     */
    @NestedConfigurationProperty
    private DegradeProperties degrade = new DegradeProperties();

    /**
     * TCC 模式防卫屏障配置
     */
    @NestedConfigurationProperty
    private TccProperties tcc = new TccProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public TxProperties getTx() {
        return tx;
    }

    public void setTx(TxProperties tx) {
        this.tx = tx;
    }

    public LockProperties getLock() {
        return lock;
    }

    public void setLock(LockProperties lock) {
        this.lock = lock;
    }

    public DegradeProperties getDegrade() {
        return degrade;
    }

    public void setDegrade(DegradeProperties degrade) {
        this.degrade = degrade;
    }

    public TccProperties getTcc() {
        return tcc;
    }

    public void setTcc(TccProperties tcc) {
        this.tcc = tcc;
    }

    /**
     * 全局事务控制配置属性
     */
    public static class TxProperties {

        /**
         * 全局事务默认超时时间（默认 30 秒，防止长事务阻塞连接池）
         */
        private Duration defaultTimeout = Duration.ofSeconds(30);

        public Duration getDefaultTimeout() {
            return defaultTimeout;
        }

        public void setDefaultTimeout(Duration defaultTimeout) {
            this.defaultTimeout = defaultTimeout;
        }
    }

    /**
     * 全局锁重试与排他锁配置属性
     */
    public static class LockProperties {

        /**
         * 全局锁获取重试间隔（默认 50ms，避免高频自旋打爆网络）
         */
        private Duration retryInterval = Duration.ofMillis(50);

        /**
         * 全局锁获取最大重试次数（默认 30 次，总计约 1.5 秒后 Fail-Fast 释放本地事务）
         */
        private int retryTimes = 30;

        public Duration getRetryInterval() {
            return retryInterval;
        }

        public void setRetryInterval(Duration retryInterval) {
            this.retryInterval = retryInterval;
        }

        public int getRetryTimes() {
            return retryTimes;
        }

        public void setRetryTimes(int retryTimes) {
            this.retryTimes = retryTimes;
        }
    }

    /**
     * TC 协调器服务断连与容灾降级配置
     */
    public static class DegradeProperties {

        /**
         * TC 宕机或网络不可达时是否降级为本地事务执行（默认 false：金融级强一致刚性阻断 Fail-Fast，防止单边脏写资损）
         */
        private boolean enabled = false;

        /**
         * TC 降级与健康探活探测周期
         */
        private Duration degradeCheckPeriod = Duration.ofSeconds(2);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getDegradeCheckPeriod() {
            return degradeCheckPeriod;
        }

        public void setDegradeCheckPeriod(Duration degradeCheckPeriod) {
            this.degradeCheckPeriod = degradeCheckPeriod;
        }
    }

    /**
     * TCC 模式防悬挂屏障与防卫日志配置
     */
    public static class TccProperties {

        /**
         * 是否开启 TCC 防悬挂、防空回滚与幂等业务防卫屏障
         */
        private boolean fenceEnabled = true;

        /**
         * TCC 防卫日志历史记录清理定时任务执行周期
         */
        private Duration cleanPeriod = Duration.ofDays(1);

        /**
         * TCC 防卫日志存活保留时长（超过此时间的历史日志被自动物理清理）
         */
        private Duration logTtl = Duration.ofDays(3);

        /**
         * TCC 防卫日志存储数据表名
         */
        private String tableName = SeataConstants.DEFAULT_TCC_FENCE_TABLE_NAME;

        public boolean isFenceEnabled() {
            return fenceEnabled;
        }

        public void setFenceEnabled(boolean fenceEnabled) {
            this.fenceEnabled = fenceEnabled;
        }

        public Duration getCleanPeriod() {
            return cleanPeriod;
        }

        public void setCleanPeriod(Duration cleanPeriod) {
            this.cleanPeriod = cleanPeriod;
        }

        public Duration getLogTtl() {
            return logTtl;
        }

        public void setLogTtl(Duration logTtl) {
            this.logTtl = logTtl;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
    }
}
