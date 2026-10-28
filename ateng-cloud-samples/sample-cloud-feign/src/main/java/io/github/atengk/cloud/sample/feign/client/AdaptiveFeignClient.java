package io.github.atengk.cloud.sample.feign.client;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.feign.annotation.AdaptiveUnwrap;
import io.github.atengk.cloud.sample.feign.model.UserVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 自适应解包与异常转译 Feign 测试客户端
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FeignClient(name = "adaptive-client", url = "http://localhost:${test.server.port:8080}")
public interface AdaptiveFeignClient {

    /**
     * 1. 自动剥壳：方法声明返回业务对象 UserVO，底层自动剥壳
     */
    @GetMapping("/api/mock/adaptive/user/{id}")
    UserVO getUserById(@PathVariable("id") Long id);

    /**
     * 2. 原生透传：方法显式声明返回 Result&lt;UserVO&gt;，原样透传
     */
    @GetMapping("/api/mock/adaptive/raw-user/{id}")
    Result<UserVO> getRawUser(@PathVariable("id") Long id);

    /**
     * 3. 业务报错：对端 HTTP 200 但 code=5001，自动转译为 BusinessException
     */
    @GetMapping("/api/mock/adaptive/user/business-error")
    UserVO getBusinessError();

    /**
     * 4. void 返回：对端返回 code=200 正常执行返回
     */
    @PostMapping("/api/mock/adaptive/user/void-success")
    void voidSuccess();

    /**
     * 5. void 返回：对端返回 code=5002 触发 BusinessException 阻断
     */
    @PostMapping("/api/mock/adaptive/user/void-error")
    void voidError();

    /**
     * 6. HTTP 500 异常：底层网络与状态码异常转译为 BusinessException 且保留 cause
     */
    @GetMapping("/api/mock/adaptive/user/http-500")
    UserVO getHttp500();

    /**
     * 7. 声明式注解规避：标注 @AdaptiveUnwrap(false) 禁用解包，直接反序列化为 UserVO
     */
    @GetMapping("/api/mock/adaptive/third-party/data")
    @AdaptiveUnwrap(false)
    UserVO getThirdPartyData();
}
