package io.github.atengk.cloud.gateway.properties;

import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 开放接口数字签名与防重放配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengGatewaySignProperties {

    /**
     * 是否启用开放接口签名防篡改与 Nonce 防重放
     */
    private boolean enabled = false;

    /**
     * 是否启用开放接口请求体 (Request Body) SHA-256 摘要加签防篡改
     */
    private boolean bodyEnabled = false;

    /**
     * 允许加签的最大请求体容量 (默认 2MB 载荷硬防爆)
     */
    private DataSize maxBodySize = DataSize.ofMegabytes(2);

    /**
     * 时间戳允许的最大时钟偏差 (默认 5 分钟)
     */
    private Duration timestampThreshold = Duration.ofMinutes(5);

    /**
     * 需进行签名校验的目标路径列表 (支持 AntPath，若为空且未配置则默认对全部请求生效)
     */
    private List<String> includePaths = new ArrayList<>();

    /**
     * 签名校验排除放行路径列表 (支持 AntPath)
     */
    private List<String> excludePaths = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isBodyEnabled() {
        return bodyEnabled;
    }

    public void setBodyEnabled(boolean bodyEnabled) {
        this.bodyEnabled = bodyEnabled;
    }

    public DataSize getMaxBodySize() {
        return maxBodySize;
    }

    public void setMaxBodySize(DataSize maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public Duration getTimestampThreshold() {
        return timestampThreshold;
    }

    public void setTimestampThreshold(Duration timestampThreshold) {
        this.timestampThreshold = timestampThreshold;
    }

    public List<String> getIncludePaths() {
        return includePaths;
    }

    public void setIncludePaths(List<String> includePaths) {
        this.includePaths = includePaths;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }
}
