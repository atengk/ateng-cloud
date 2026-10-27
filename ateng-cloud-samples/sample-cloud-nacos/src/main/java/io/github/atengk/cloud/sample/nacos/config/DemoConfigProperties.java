package io.github.atengk.cloud.sample.nacos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 业务配置属性 POJO（演示单例不可变性与增量热绑）
 *
 * <p>微服务铁律：业务配置通过 {@link ConfigurationProperties} 声明，热更时仅增量重新绑定字段，
 * 严禁在业务 Service 上挂载 {@code @RefreshScope}。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Component
@ConfigurationProperties(prefix = "demo.config")
public class DemoConfigProperties {

    /**
     * 平台标题
     */
    private String title = "Default Title";

    /**
     * 敏感密钥属性（演示 ENC(...) 密文解析与脱敏屏障）
     */
    private String secretKey = "";

    /**
     * 超时时限（强类型 Duration 规范）
     */
    private Duration timeout = Duration.ofSeconds(5);

    /**
     * 连接池大小
     */
    private int poolSize = 10;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }
}
