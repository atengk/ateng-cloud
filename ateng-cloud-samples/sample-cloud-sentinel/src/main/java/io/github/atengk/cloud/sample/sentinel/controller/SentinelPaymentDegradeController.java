package io.github.atengk.cloud.sample.sentinel.controller;

import com.alibaba.csp.sentinel.Tracer;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.sentinel.client.RemotePaymentClient;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentPayDTO;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentResultVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付远程调用与受管降级熔断场景控制器
 *
 * <p>展示 Feign 客户端受管降级工厂 (默认因果转译与柔性业务保底) 以及 Web 接口异常比例/慢调用熔断降级。</p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/sentinel/payment")
public class SentinelPaymentDegradeController {

    private final RemotePaymentClient remotePaymentClient;

    @Autowired
    public SentinelPaymentDegradeController(RemotePaymentClient remotePaymentClient) {
        this.remotePaymentClient = remotePaymentClient;
    }

    /**
     * 发起第三方支付提单
     *
     * <p>底层通过 RemotePaymentClient 远程通信。若对端超时或崩溃，
     * 由 PaymentFallbackFactory 代理并转译为业务异常 BusinessException(4202 DEGRADE)，完整保留底层因果链。</p>
     *
     * @param payDTO 支付请求
     * @param simulateTimeout 是否模拟网络超时
     * @param simulateError 是否模拟远程服务 500 异常
     * @return 支付结果
     */
    @PostMapping("/pay")
    public Result<PaymentResultVO> pay(
            @Valid @RequestBody PaymentPayDTO payDTO,
            @RequestParam(name = "simulateTimeout", defaultValue = "false") boolean simulateTimeout,
            @RequestParam(name = "simulateError", defaultValue = "false") boolean simulateError) {
        return remotePaymentClient.payOrder(payDTO, simulateTimeout, simulateError);
    }

    /**
     * 查询第三方支付流水状态
     *
     * <p>当对端服务不可用时，PaymentFallbackFactory 触发自定义 handleFallback，
     * 返回带有 fallback=true 标记的柔性保底视图对象，保障买家查询不中断。</p>
     *
     * @param payNo 支付交易流水号
     * @param simulateFail 是否模拟查询失败
     * @return 交易状态
     */
    @GetMapping("/status/{payNo}")
    public Result<PaymentResultVO> queryStatus(
            @PathVariable("payNo") String payNo,
            @RequestParam(name = "simulateFail", defaultValue = "false") boolean simulateFail) {
        return remotePaymentClient.queryPaymentStatus(payNo, simulateFail);
    }

    /**
     * Web 端接口熔断降级自测
     *
     * <p>当注入模拟故障时，Tracer.trace 记录异常指标；在进入熔断窗口期后，
     * 请求被 Sentinel 熔断器拦截，AtengBlockExceptionHandler 统一返回 HTTP 503 与 DEGRADE(4202)。</p>
     *
     * @param fail 是否模拟业务异常
     * @return 业务结果
     */
    @GetMapping("/circuit-breaker")
    public Result<String> circuitBreaker(@RequestParam(name = "fail", defaultValue = "false") boolean fail) {
        if (fail) {
            IllegalStateException ex = new IllegalStateException("Simulated business exception for degrade circuit breaker");
            Tracer.trace(ex);
            throw ex;
        }
        return Result.success("circuit-breaker-pass");
    }
}
