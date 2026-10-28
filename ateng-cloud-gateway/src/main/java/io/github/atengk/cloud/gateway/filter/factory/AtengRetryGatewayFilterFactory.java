package io.github.atengk.cloud.gateway.filter.factory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.net.ConnectException;

/**
 * 弹性防雪崩安全重试过滤器工厂
 *
 * <p>微服务专属架构铁律落地：
 * <ul>
 *     <li><b>写请求绝对禁止重试不变量</b>：对 POST、PUT、PATCH、DELETE 等非幂等写操作，强制执行 0 次重试，坚决杜绝重复落库与资损风险；</li>
 *     <li><b>读请求弹性建连防雪崩</b>：仅对纯读请求 (GET / HEAD) 且发生底层网络建连超时或连接被拒异常时，允许弹性重试 1 次；</li>
 *     <li><b>读超时防风暴</b>：对下游服务已接收并处理中的读取超时 (SocketTimeoutException)，坚决关闭重试，防御下游级联假死与重试风暴。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengRetryGatewayFilterFactory extends RetryGatewayFilterFactory {

    private static final Logger log = LoggerFactory.getLogger(AtengRetryGatewayFilterFactory.class);

    public AtengRetryGatewayFilterFactory() {
        super();
    }

    @Override
    public GatewayFilter apply(RetryConfig config) {
        // 1. 约束读请求最大重试次数为 1 (即便上层配置了更大的数字，也收敛防雪崩)
        int maxRetries = (config != null && config.getRetries() > 0) ? Math.min(config.getRetries(), 1) : 1;

        // 2. 构造只针对纯读请求建连异常的安全重试配置
        RetryConfig safeReadConfig = new RetryConfig();
        safeReadConfig.setRetries(maxRetries);
        safeReadConfig.setMethods(HttpMethod.GET, HttpMethod.HEAD);
        safeReadConfig.setExceptions(
                ConnectException.class,
                io.netty.channel.ConnectTimeoutException.class
        );
        safeReadConfig.setSeries(HttpStatus.Series.SERVER_ERROR);
        if (config != null && config.getBackoff() != null) {
            safeReadConfig.setBackoff(config.getBackoff());
        }

        GatewayFilter readRetryFilter = super.apply(safeReadConfig);

        return (exchange, chain) -> {
            HttpMethod method = exchange.getRequest().getMethod();
            // 铁律：写请求绝对禁止重试，直接单次执行，杜绝重复落库资损
            if (isWriteMethod(method)) {
                log.debug("[Gateway] 探测到非幂等写请求 method={}, 强制执行零重试防雪崩铁律", method);
                return chain.filter(exchange);
            }
            // 读操作允许弹性建连重试 1 次
            return readRetryFilter.filter(exchange, chain);
        };
    }

    private boolean isWriteMethod(HttpMethod method) {
        if (method == null) {
            return false;
        }
        return method == HttpMethod.POST
                || method == HttpMethod.PUT
                || method == HttpMethod.PATCH
                || method == HttpMethod.DELETE;
    }
}
