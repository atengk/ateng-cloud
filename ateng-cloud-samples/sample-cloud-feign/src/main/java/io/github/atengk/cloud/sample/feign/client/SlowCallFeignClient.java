package io.github.atengk.cloud.sample.feign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 慢调用告警探测验证 Feign 客户端
 * 独立配置 {@code slow-call-threshold: 100ms}，调用耗时 180ms 的安全慢接口验证无视日志级别的 WARN 告警输出
 *
 * @author Ateng
 * @since 2026-11-12
 */
@FeignClient(name = "slow-call-client", url = "http://localhost:${test.server.port:8080}")
public interface SlowCallFeignClient {

    /**
     * 调用未超读超时上限但超过慢调用阈值的接口，触发 Slow Call Detection WARN 告警
     *
     * @return 对端成功响应结果
     */
    @GetMapping("/sample/cloud/feign/remote/tolerance/slow-ok")
    String slowOk();
}
