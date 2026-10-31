package io.github.atengk.cloud.sample.gateway.controller;

import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 响应式 Sentinel 异常转译集成测试 Mock 控制器
 *
 * @author Ateng
 * @since 2026-10-31
 */
@RestController
@RequestMapping("/mock/sentinel")
public class MockSentinelTestController {

    @GetMapping("/flow")
    public Mono<Result<Void>> flow() {
        return Mono.error(new FlowException("流量过载触发 Sentinel 限流"));
    }

    @GetMapping("/degrade")
    public Mono<Result<Void>> degrade() {
        return Mono.error(new DegradeException("慢调用比例超限触发 Sentinel 熔断降级"));
    }

    @GetMapping("/authority")
    public Mono<Result<Void>> authority() {
        return Mono.error(new AuthorityException("来源受限触发 Sentinel 授权拦截"));
    }

    @GetMapping("/system")
    public Mono<Result<Void>> system() {
        return Mono.error(new SystemBlockException("系统负载过高触发 Sentinel 保护"));
    }
}
