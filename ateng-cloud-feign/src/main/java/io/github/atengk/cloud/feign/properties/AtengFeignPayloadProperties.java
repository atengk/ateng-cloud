package io.github.atengk.cloud.feign.properties;

import org.springframework.util.unit.DataSize;

/**
 * Feign 客户端载荷硬防爆与内存安全配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignPayloadProperties {

    /**
     * 响应报文最大允许字节大小硬防爆阈值，默认 10MB
     */
    private DataSize maxResponseSize = DataSize.ofMegabytes(10);

    public DataSize getMaxResponseSize() {
        return maxResponseSize;
    }

    public void setMaxResponseSize(DataSize maxResponseSize) {
        this.maxResponseSize = maxResponseSize;
    }
}
