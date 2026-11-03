package io.github.atengk.cloud.sample.sentinel.client;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.sentinel.model.payment.PaymentResultVO;
import io.github.atengk.cloud.sentinel.fallback.AtengFallbackFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 第三方支付客户端受管降级工厂
 *
 * <p>继承 {@link AtengFallbackFactory}，默认将底层熔断与网络异常转译为语义化 BusinessException(4202 DEGRADE) 并保留因果链；
 * 同时重写 {@link #handleFallback(Method, Object[], Throwable)} 为状态查询提供柔性保底数据。</p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Component
public class PaymentFallbackFactory extends AtengFallbackFactory<RemotePaymentClient> {

    @Override
    protected Object handleFallback(Method method, Object[] args, Throwable cause) throws Throwable {
        // 为支付状态查询提供柔性保底值，避免直接抛出异常影响买家查询体验
        if ("queryPaymentStatus".equals(method.getName())) {
            String payNo = (args != null && args.length > 0 && args[0] != null) ? args[0].toString() : "UNKNOWN";
            log.warn("Payment status query fallback triggered for payNo='{}', cause='{}'", payNo,
                    cause != null ? cause.getMessage() : "unknown");
            return Result.success(PaymentResultVO.fallback(payNo, "第三方支付查询熔断降级，返回柔性保底数据"));
        }
        // 其余方法（如扣款 payOrder）平滑委托给框架默认降级行为：自动抛出携带原始 cause 的 BusinessException(4202 DEGRADE)
        return super.handleFallback(method, args, cause);
    }
}
