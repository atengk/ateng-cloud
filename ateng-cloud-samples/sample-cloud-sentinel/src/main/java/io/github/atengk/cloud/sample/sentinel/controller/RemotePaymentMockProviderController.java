package io.github.atengk.cloud.sample.sentinel.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentPayDTO;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentResultVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 远程第三方支付服务本地模拟提供者控制器 (离线自洽沙箱)
 *
 * <p>提供真实支付业务模拟，并支持通过请求参数主动注入超时或异常故障，供 Feign 熔断与受管降级自测。</p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/sentinel/provider/payment")
public class RemotePaymentMockProviderController {

    /**
     * 模拟第三方支付下单扣款接口
     *
     * @param payDTO 支付请求数据
     * @param simulateTimeout 是否模拟网络超时 (休眠 3 秒)
     * @param simulateError 是否模拟网关内部 500 异常
     * @return 支付结果
     * @throws Exception 模拟异常或中断
     */
    @PostMapping("/pay")
    public Result<PaymentResultVO> pay(
            @RequestBody PaymentPayDTO payDTO,
            @RequestParam(name = "simulateTimeout", defaultValue = "false") boolean simulateTimeout,
            @RequestParam(name = "simulateError", defaultValue = "false") boolean simulateError) throws Exception {
        if (simulateTimeout) {
            Thread.sleep(3000L);
        }
        if (simulateError) {
            throw new IllegalStateException("模拟第三方支付网关远程服务 500 内部崩溃");
        }
        PaymentResultVO vo = new PaymentResultVO(
                "PAY-" + System.currentTimeMillis(),
                payDTO.getOrderNo(),
                payDTO.getAmount(),
                "SUCCESS",
                "第三方支付交易成功",
                false,
                LocalDateTime.now()
        );
        return Result.success(vo);
    }

    /**
     * 模拟第三方支付交易状态查询接口
     *
     * @param payNo 支付流水号
     * @param simulateFail 是否模拟查询失败
     * @return 交易状态
     */
    @GetMapping("/status/{payNo}")
    public Result<PaymentResultVO> queryStatus(
            @PathVariable("payNo") String payNo,
            @RequestParam(name = "simulateFail", defaultValue = "false") boolean simulateFail) {
        if (simulateFail) {
            throw new IllegalStateException("模拟第三方支付交易状态查询服务异常");
        }
        PaymentResultVO vo = new PaymentResultVO(
                payNo,
                "ORD-MOCK-1001",
                new BigDecimal("199.00"),
                "SUCCESS",
                "支付状态查询正常",
                false,
                LocalDateTime.now()
        );
        return Result.success(vo);
    }
}
