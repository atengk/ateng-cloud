package io.github.atengk.cloud.sample.sentinel.client;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentPayDTO;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentResultVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 远程第三方支付服务声明式 Feign 客户端 (受管熔断降级)
 *
 * @author Ateng
 * @since 2026-11-03
 */
@FeignClient(
        name = "remote-payment-client",
        url = "${sentinel.payment.url:http://localhost:8080}",
        fallbackFactory = PaymentFallbackFactory.class
)
public interface RemotePaymentClient {

    /**
     * 调用远程第三方支付提单扣款
     *
     * @param payDTO 支付请求
     * @param simulateTimeout 是否模拟网络超时
     * @param simulateError 是否模拟网关异常
     * @return 支付结果
     */
    @PostMapping("/sample/cloud/sentinel/provider/payment/pay")
    Result<PaymentResultVO> payOrder(
            @RequestBody PaymentPayDTO payDTO,
            @RequestParam(name = "simulateTimeout", defaultValue = "false") boolean simulateTimeout,
            @RequestParam(name = "simulateError", defaultValue = "false") boolean simulateError
    );

    /**
     * 查询远程第三方支付交易状态
     *
     * @param payNo 支付交易流水号
     * @param simulateFail 是否模拟查询失败
     * @return 支付状态结果
     */
    @GetMapping("/sample/cloud/sentinel/provider/payment/status/{payNo}")
    Result<PaymentResultVO> queryPaymentStatus(
            @PathVariable("payNo") String payNo,
            @RequestParam(name = "simulateFail", defaultValue = "false") boolean simulateFail
    );
}
