package io.github.atengk.cloud.sample.sentinel.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.sentinel.model.seckill.SecKillOrderDTO;
import io.github.atengk.cloud.sample.sentinel.model.seckill.SecKillProductVO;
import io.github.atengk.cloud.sample.sentinel.model.seckill.SecKillResultVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀高频流控与热点限流场景演示控制器
 *
 * <p>展示 Web 入站 QPS 限流、{@code @SentinelResource} 热点参数流控及切面异常逃逸兜底拦截能力。</p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/sentinel/seckill")
public class SentinelSecKillFlowController {

    /**
     * 高频秒杀提单接口
     *
     * <p>入站受 Spring MVC 默认过滤器拦截，当流量超过预设 QPS 阈值时自动返回 HTTP 429 与 FLOW_LIMIT(4201)。</p>
     *
     * @param orderDTO 秒杀下单请求对象
     * @return 秒杀结果视图对象
     */
    @PostMapping("/order")
    public Result<SecKillResultVO> createOrder(@Valid @RequestBody SecKillOrderDTO orderDTO) {
        SecKillResultVO result = new SecKillResultVO(
                "SK-" + System.currentTimeMillis() + "-" + orderDTO.getProductId(),
                orderDTO.getUserId(),
                orderDTO.getProductId(),
                orderDTO.getCount(),
                "SUCCESS",
                LocalDateTime.now()
        );
        return Result.success(result);
    }

    /**
     * 秒杀商品详情查询接口（热点参数限流防卫）
     *
     * <p>标注 {@code @SentinelResource} 且未配置 blockHandler，当特定热门商品 ID 触发限流时，
     * 逃逸的 ParamFlowException 会被 SentinelBlockExceptionAdvice 统一兜底拦截并映射为 HTTP 429 与 PARAM_FLOW_LIMIT(4203)。</p>
     *
     * @param productId 秒杀商品 ID
     * @return 秒杀商品详情
     */
    @GetMapping("/product/{productId}")
    @SentinelResource(value = "seckill-product-hotspot")
    public Result<SecKillProductVO> getProductDetail(@PathVariable("productId") Long productId) {
        SecKillProductVO product = new SecKillProductVO(
                productId,
                "秒杀专享热销商品-" + productId,
                new BigDecimal("99.00"),
                500,
                "AVAILABLE"
        );
        return Result.success(product);
    }

    /**
     * 秒杀 VIP 绿色通道接口（注解级 QPS 阈值限流）
     *
     * <p>演示无 blockHandler 的 {@code @SentinelResource} 方法在普通 QPS 规则超限时，
     * 由切面兜底捕获 FlowException 并统一输出 HTTP 429 与 FLOW_LIMIT(4201)。</p>
     *
     * @return 业务通行标识
     */
    @GetMapping("/vip-channel")
    @SentinelResource(value = "seckill-vip-channel")
    public Result<String> vipChannel() {
        return Result.success("vip-pass");
    }
}
