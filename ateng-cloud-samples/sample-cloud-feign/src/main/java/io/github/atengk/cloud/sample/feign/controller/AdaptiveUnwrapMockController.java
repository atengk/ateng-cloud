package io.github.atengk.cloud.sample.feign.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.feign.model.UserVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 自适应解包与异常转译 Mock 控制器
 *
 * @author Ateng
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/api/mock/adaptive")
public class AdaptiveUnwrapMockController {

    @GetMapping("/user/{id}")
    public Result<UserVO> getUserById(@PathVariable("id") Long id) {
        return Result.success(new UserVO(id, "ateng-" + id));
    }

    @GetMapping("/raw-user/{id}")
    public Result<UserVO> getRawUser(@PathVariable("id") Long id) {
        return Result.success(new UserVO(id, "raw-ateng-" + id));
    }

    @GetMapping("/user/business-error")
    public Result<UserVO> getBusinessError() {
        return Result.fail(5001, "库存不足，下单失败");
    }

    @PostMapping("/user/void-success")
    public Result<Void> voidSuccess() {
        return Result.success();
    }

    @PostMapping("/user/void-error")
    public Result<Void> voidError() {
        return Result.fail(5002, "无权删除当前用户");
    }

    @GetMapping("/user/http-500")
    public ResponseEntity<String> getHttp500() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Internal System Crash");
    }

    @GetMapping("/third-party/data")
    public Map<String, Object> getThirdPartyData() {
        return Map.of("id", 888L, "username", "third-party-user");
    }
}
