package io.github.atengk.cloud.sample.feign.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;

/**
 * 订单容灾与边界防御远程调用 Feign 客户端
 * 映射 retry-client 专属隔离配置（socket-timeout: 400ms, connect-timeout: 300ms）
 *
 * @author Ateng
 * @since 2026-11-03
 */
@FeignClient(name = "retry-client", url = "http://localhost:${test.server.port:8080}")
public interface OrderRemoteFeignClient {

    /**
     * 1. 非幂等写超时：调用服务端耗时 1200ms 的写接口，触发 400ms 读超时且绝不重试
     */
    @PostMapping("/sample/cloud/feign/remote/tolerance/post-timeout")
    String postTimeout();

    /**
     * 2. 只读超时：调用服务端耗时 1200ms 的只读接口，触发 400ms 读超时且不发起重试
     */
    @GetMapping("/sample/cloud/feign/remote/tolerance/get-timeout")
    String getTimeout();

    /**
     * 3. 业务错误码因果解码：对端返回 code=6001，转译为 BusinessException
     */
    @GetMapping("/sample/cloud/feign/remote/tolerance/business-error")
    String getBusinessError();

    /**
     * 4. HTTP 500 异常转译：对端返回 500，转译为 BusinessException 并保留 FeignException 因果链
     */
    @GetMapping("/sample/cloud/feign/remote/tolerance/raw-500")
    String getRaw500();

    /**
     * 5. 超大载荷防爆拦截：对端返回 11MB 报文，触发客户端 10MB 响应防爆熔断
     */
    @GetMapping("/sample/cloud/feign/remote/tolerance/large-payload")
    String largePayload();

    /**
     * 6. 出站标头回显：获取对端接收到的全部请求头
     */
    @GetMapping("/sample/cloud/feign/remote/tolerance/echo-headers")
    Map<String, String> echoHeaders();
}
