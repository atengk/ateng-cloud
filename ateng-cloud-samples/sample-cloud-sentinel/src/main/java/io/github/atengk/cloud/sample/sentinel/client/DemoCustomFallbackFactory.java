package io.github.atengk.cloud.sample.sentinel.client;

import io.github.atengk.cloud.sentinel.fallback.AtengFallbackFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 柔性受管 Feign 降级工厂 (重写 handleFallback 返回自定义保底业务数据)
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Component
public class DemoCustomFallbackFactory extends AtengFallbackFactory<DemoCustomFeignClient> {

    @Override
    protected Object handleFallback(Method method, Object[] args, Throwable cause) throws Throwable {
        if ("getRemoteOrder".equals(method.getName())) {
            Long orderId = (args != null && args.length > 0) ? (Long) args[0] : 0L;
            return "MockOrderFallback-" + orderId;
        }
        return super.handleFallback(method, args, cause);
    }
}
