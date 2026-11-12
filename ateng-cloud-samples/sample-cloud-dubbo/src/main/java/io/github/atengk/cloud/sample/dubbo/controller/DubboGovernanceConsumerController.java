package io.github.atengk.cloud.sample.dubbo.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.cloud.dubbo.filter.DubboSameTokenFilter;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.model.UserCreateRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import jakarta.validation.Valid;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 微服务生产治理容灾 Web 接入消费控制器
 * 集中暴露并体验参数校验防卫、受管业务异常保真、底层异常因果转译、服务端 429 线程池打满过载保护、原生客户端 Mock 降级与 Same-Token 校验
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/dubbo/governance")
public class DubboGovernanceConsumerController {

    @DubboReference(url = "${dubbo.consumer.direct-url:${dubbo.test.reference.url:tri://127.0.0.1:50051}}", check = false)
    private UserRpcService userRpcService;

    @DubboReference(url = "${dubbo.test.unreachable.url:tri://127.0.0.1:59999}", check = false, mock = "true", timeout = 1000)
    private UserRpcService fallbackMockRpcService;

    /**
     * Web 传输边界参数校验快速失败防卫（第一道防线）
     *
     * @param request 创建用户入参
     * @return 创建的用户详情
     */
    @PostMapping("/validation")
    public Result<UserVO> validateUser(@RequestBody @Valid UserCreateRequest request) {
        UserVO vo = userRpcService.createUser(request);
        return Result.success(vo);
    }

    /**
     * RPC 协议边界参数校验快速失败防卫（第二道防线，由 DubboValidationFilter 拦截转译）
     *
     * @param request 创建用户入参
     * @return 创建的用户详情
     */
    @PostMapping("/validation/rpc")
    public Result<UserVO> validateUserRpcDirect(@RequestBody UserCreateRequest request) {
        UserVO vo = userRpcService.createUser(request);
        return Result.success(vo);
    }

    /**
     * RPC 协议边界批量集合参数校验快速失败防卫（绕过 Web 层校验直达 Provider 边界，由 DubboValidationFilter 执行级联校验）
     *
     * @param requests 批量创建用户入参集合
     * @return 批量创建的用户详情列表
     */
    @PostMapping("/validation/batch")
    public Result<List<UserVO>> validateUsersBatch(@RequestBody ValidList<UserCreateRequest> requests) {
        List<UserVO> list = userRpcService.batchCreateUsers(requests);
        return Result.success(list);
    }

    /**
     * 受管业务异常因果保真拦截（白名单放行受管 BusinessException，精准还原 404 状态码）
     *
     * @return 业务结果
     */
    @GetMapping("/business-exception")
    public Result<String> testBusinessException() {
        userRpcService.throwBusinessException();
        return Result.success("未抛出预期异常");
    }

    /**
     * 底层未声明异常安全因果转译（防反序列化崩溃，转译为受管 BusinessException，统一映射为 500 状态码）
     *
     * @return 业务结果
     */
    @GetMapping("/custom-exception")
    public Result<String> testCustomException() {
        userRpcService.throwCustomException();
        return Result.success("未抛出预期异常");
    }

    /**
     * 服务端并发线程池打满 429 过载保护限流
     *
     * @return 业务结果
     */
    @GetMapping("/overload")
    public Result<String> testOverload() {
        userRpcService.simulateOverload();
        return Result.success("未抛出预期异常");
    }

    /**
     * Dubbo 3 原生客户端同包 Mock 自动降级（当远端服务不可达时自动平滑返回兜底数据）
     *
     * @param id 用户 ID
     * @return 降级兜底用户数据
     */
    @GetMapping("/mock/fallback")
    public Result<UserVO> testMockFallback(@RequestParam(required = false, defaultValue = "888") Long id) {
        UserVO vo = fallbackMockRpcService.getUserById(id);
        return Result.success(vo);
    }

    /**
     * 正常在线直连调用（验证服务健康时正常响应真实 Provider 数据）
     *
     * @param id 用户 ID
     * @return 真实用户数据
     */
    @GetMapping("/mock/live")
    public Result<UserVO> testMockLive(@RequestParam(required = false, defaultValue = "123") Long id) {
        UserVO vo = userRpcService.getUserById(id);
        return Result.success(vo);
    }

    /**
     * 零信任 Same-Token 内部受信凭据防穿透校验
     *
     * @param sameToken 自定义受信凭据（可传入非法值或空白模拟非法直连）
     * @return 校验结果
     */
    @GetMapping("/same-token")
    public Result<String> testSameToken(@RequestHeader(value = "Same-Token", required = false) String sameToken) {
        if (sameToken != null) {
            RpcContext.getClientAttachment().setAttachment(DubboSameTokenFilter.SAME_TOKEN_KEY, sameToken);
            RpcContext.getClientAttachment().setAttachment(DubboSameTokenFilter.SA_SAME_TOKEN_KEY, sameToken);
        }
        try {
            String pingResult = userRpcService.ping("same-token-check");
            return Result.success("Same-Token 受信凭据校验通过: " + pingResult);
        } finally {
            RpcContext.removeClientAttachment();
        }
    }

    /**
     * RPC 慢调用主动监测与可观测指标演示（当耗时达到配置阈值时输出 WARN 告警并累加 {@code dubbo.rpc.slow.calls}）
     *
     * @param sleepMillis 模拟远端处理耗时（毫秒，默认 3200ms，范围 0 ~ 10000ms）
     * @return 远端响应结果
     */
    @GetMapping("/slow-call")
    public Result<String> testSlowCall(@RequestParam(defaultValue = "3200") long sleepMillis) {
        long boundedSleepMillis = Math.max(0L, Math.min(sleepMillis, 10_000L));
        return Result.success(userRpcService.slowEcho(boundedSleepMillis));
    }
}
