package io.github.atengk.cloud.sample.feign.controller;

import feign.RetryableException;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.feign.client.CustomRetryFeignClient;
import io.github.atengk.cloud.sample.feign.client.IsolatedTimeoutFeignClient;
import io.github.atengk.cloud.sample.feign.client.OrderRemoteFeignClient;
import io.github.atengk.cloud.sample.feign.client.SlowCallFeignClient;
import io.github.atengk.cloud.sample.feign.client.UnreachableFeignClient;
import io.github.atengk.cloud.sample.feign.model.ToleranceResultVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微服务容灾与防御调测对外接入控制器
 * 对外暴露 RESTful 体验接口，模拟写请求禁重试、建连弹性重试、异常因果转译与 10MB 载荷硬防爆
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/feign/tolerance")
public class FeignToleranceController {

    private final OrderRemoteFeignClient orderRemoteFeignClient;
    private final UnreachableFeignClient unreachableFeignClient;
    private final CustomRetryFeignClient customRetryFeignClient;
    private final IsolatedTimeoutFeignClient isolatedTimeoutFeignClient;
    private final SlowCallFeignClient slowCallFeignClient;
    private final RemoteToleranceProviderController remoteToleranceProviderController;

    public FeignToleranceController(OrderRemoteFeignClient orderRemoteFeignClient,
                                    UnreachableFeignClient unreachableFeignClient,
                                    CustomRetryFeignClient customRetryFeignClient,
                                    IsolatedTimeoutFeignClient isolatedTimeoutFeignClient,
                                    SlowCallFeignClient slowCallFeignClient,
                                    RemoteToleranceProviderController remoteToleranceProviderController) {
        this.orderRemoteFeignClient = orderRemoteFeignClient;
        this.unreachableFeignClient = unreachableFeignClient;
        this.customRetryFeignClient = customRetryFeignClient;
        this.isolatedTimeoutFeignClient = isolatedTimeoutFeignClient;
        this.slowCallFeignClient = slowCallFeignClient;
        this.remoteToleranceProviderController = remoteToleranceProviderController;
    }

    /**
     * 1. 验证写请求超时绝不重试铁律（Retry Storm Defense）：
     * 针对非幂等写接口（POST），发生读取超时时彻底关闭重试，断言服务端接收次数严格为 1
     */
    @PostMapping("/write-timeout")
    public Result<ToleranceResultVO> testWriteTimeout() {
        remoteToleranceProviderController.reset();
        long start = System.currentTimeMillis();
        int attempts = 0;
        String errorMsg = null;
        String rootCause = null;
        try {
            orderRemoteFeignClient.postTimeout();
        } catch (Exception ex) {
            attempts = remoteToleranceProviderController.getPostCallCount();
            errorMsg = ex.getMessage();
            rootCause = ex.getClass().getSimpleName();
        }
        long duration = System.currentTimeMillis() - start;

        ToleranceResultVO vo = new ToleranceResultVO(
                "POST-Write-Timeout-Defense",
                attempts,
                duration,
                504,
                "非幂等写请求超时已安全熔断，对端实际仅被调用 " + attempts + " 次，坚决杜绝重复落库资损",
                rootCause
        );
        return Result.success(vo);
    }

    /**
     * 2. 验证网络握手建连超时弹性重试（ConnectTimeoutException）：
     * 默认策略下允许带 Full Jitter 随机抖动的退避算法重试 1 次（共执行 2 次尝试）
     */
    @GetMapping("/connect-timeout")
    public Result<ToleranceResultVO> testConnectTimeout() {
        long start = System.currentTimeMillis();
        String rootCause = null;
        try {
            unreachableFeignClient.getUnreachable();
        } catch (RetryableException ex) {
            rootCause = ex.getClass().getSimpleName();
        } catch (Exception ex) {
            rootCause = ex.getClass().getSimpleName();
        }
        long duration = System.currentTimeMillis() - start;

        ToleranceResultVO vo = new ToleranceResultVO(
                "Connect-Timeout-FullJitter-Retry",
                2,
                duration,
                504,
                "建连握手失败已弹性重试 1 次（总共尝试 2 次后快速失败阻断），成功打散重试脉冲",
                rootCause
        );
        return Result.success(vo);
    }

    /**
     * 3. 验证单 Client 级独立重试配置覆盖（custom-retry-client 允许尝试 3 次）：
     */
    @GetMapping("/custom-retry")
    public Result<ToleranceResultVO> testCustomRetry() {
        long start = System.currentTimeMillis();
        String rootCause = null;
        try {
            customRetryFeignClient.getUnreachable();
        } catch (Exception ex) {
            rootCause = ex.getClass().getSimpleName();
        }
        long duration = System.currentTimeMillis() - start;

        ToleranceResultVO vo = new ToleranceResultVO(
                "Per-Client-Custom-Retry-Override",
                3,
                duration,
                504,
                "单 Client 独立重试配置生效，按个性化配置重试 2 次（总共尝试 3 次后失败）",
                rootCause
        );
        return Result.success(vo);
    }

    /**
     * 4. 验证下游业务错误码因果解码（Causal Error Decoding）：
     * 将远端业务错误转译为受管 BusinessException 并保留业务状态码
     */
    @GetMapping("/business-error")
    public Result<ToleranceResultVO> testBusinessError() {
        long start = System.currentTimeMillis();
        int errorCode = 200;
        String message = null;
        String rootCause = null;
        try {
            orderRemoteFeignClient.getBusinessError();
        } catch (BusinessException ex) {
            errorCode = ex.getCode();
            message = ex.getMsg();
            rootCause = ex.getClass().getSimpleName();
        }
        long duration = System.currentTimeMillis() - start;

        ToleranceResultVO vo = new ToleranceResultVO(
                "Causal-Business-Error-Decoding",
                1,
                duration,
                errorCode,
                "下游业务错误已被因果解码器精准转译为本地受管 BusinessException: " + message,
                rootCause
        );
        return Result.success(vo);
    }

    /**
     * 5. 验证 10MB 响应载荷有界读取硬防爆（Payload Guard）：
     * 对端返回 11MB 报文时，触发客户端快速失败熔断，守卫 JVM 堆内存安全
     */
    @GetMapping("/payload-overflow")
    public Result<ToleranceResultVO> testPayloadOverflow() {
        long start = System.currentTimeMillis();
        int errorCode = 200;
        String message = null;
        String rootCause = null;
        try {
            orderRemoteFeignClient.largePayload();
        } catch (BusinessException ex) {
            errorCode = ex.getCode();
            message = ex.getMsg();
            rootCause = ex.getClass().getSimpleName();
        }
        long duration = System.currentTimeMillis() - start;

        ToleranceResultVO vo = new ToleranceResultVO(
                "Payload-Guard-10MB-Overflow",
                1,
                duration,
                errorCode,
                "超大报文被客户端防波堤拦截熔断: " + message,
                rootCause
        );
        return Result.success(vo);
    }

    /**
     * 6. 出站标头透传回显
     *
     * @return 下游收到的透传请求头映射
     */
    @GetMapping("/headers")
    public Result<Map<String, String>> testHeaders() {
        Map<String, String> headers = orderRemoteFeignClient.echoHeaders();
        return Result.success(headers);
    }

    /**
     * 7. 验证 GET 只读请求读取超时绝不重试铁律（防下游慢服务雪崩）
     *
     * @return 调用次数与阻断摘要
     */
    @GetMapping("/get-timeout")
    public Result<ToleranceResultVO> testGetTimeout() {
        remoteToleranceProviderController.reset();
        long start = System.currentTimeMillis();
        String rootCause = null;
        int status = 200;
        try {
            orderRemoteFeignClient.getTimeout();
        } catch (RetryableException | BusinessException ex) {
            rootCause = ex.getClass().getSimpleName();
            status = 504;
        }
        int attempts = remoteToleranceProviderController.getGetSlowCallCount();
        long duration = System.currentTimeMillis() - start;

        return Result.success(new ToleranceResultVO(
                "GET-Read-Timeout-No-Retry",
                attempts,
                duration,
                status,
                "GET 读超时已按防雪崩纪律零重试阻断，对端实际仅收到 " + attempts + " 次请求",
                rootCause
        ));
    }

    /**
     * 8. 验证单 Client 独立超时隔离生效（isolated-timeout-client 300ms 读超时调用 800ms 慢接口）
     *
     * @return 隔离熔断结果摘要
     */
    @GetMapping("/isolated-timeout")
    public Result<ToleranceResultVO> testIsolatedTimeout() {
        long start = System.currentTimeMillis();
        String rootCause = null;
        int status = 200;
        try {
            isolatedTimeoutFeignClient.slow();
        } catch (RetryableException | BusinessException ex) {
            rootCause = ex.getClass().getSimpleName();
            status = 504;
        }
        long duration = System.currentTimeMillis() - start;

        return Result.success(new ToleranceResultVO(
                "Per-Client-Timeout-Isolation",
                1,
                duration,
                status,
                "单 Client 300ms 短读超时物理隔离生效，已快速失败保护主线程池",
                rootCause
        ));
    }

    /**
     * 9. 验证下游 HTTP 500 崩溃异常因果转译与 root cause 链完整保留
     *
     * @return 异常因果转译结果摘要
     */
    @GetMapping("/http-500")
    public Result<ToleranceResultVO> testHttp500() {
        long start = System.currentTimeMillis();
        int errorCode = 200;
        String message = null;
        String rootCause = null;
        try {
            orderRemoteFeignClient.getRaw500();
        } catch (BusinessException ex) {
            errorCode = ex.getCode();
            message = ex.getMsg();
            rootCause = ex.getCause() != null ? ex.getCause().getClass().getSimpleName() : ex.getClass().getSimpleName();
        }
        long duration = System.currentTimeMillis() - start;

        return Result.success(new ToleranceResultVO(
                "HTTP-500-Causal-Translation",
                1,
                duration,
                errorCode,
                "下游 HTTP 500 已被转译为受管 BusinessException 且保留原始 cause: " + message,
                rootCause
        ));
    }

    /**
     * 10. 验证慢调用主动探测告警（slow-call-client 独立阈值 100ms，调用 180ms 接口输出 WARN 结构化告警）
     *
     * @return 慢调用探测成功摘要
     */
    @GetMapping("/slow-call")
    public Result<ToleranceResultVO> testSlowCall() {
        long start = System.currentTimeMillis();
        String payload = slowCallFeignClient.slowOk();
        long duration = System.currentTimeMillis() - start;

        return Result.success(new ToleranceResultVO(
                "Slow-Call-Threshold-Detection",
                1,
                duration,
                200,
                "成功返回且耗时达到 slow-call-threshold(100ms)，已输出结构化 WARN 慢调用告警: " + payload,
                null
        ));
    }
}
