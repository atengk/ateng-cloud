package io.github.atengk.cloud.sample.dubbo.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.exception.CustomDatabaseException;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * 用户 RPC 服务实现
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DubboService
public class UserRpcServiceImpl implements UserRpcService {

    @Override
    public String ping(String message) {
        return "pong: " + message;
    }

    @Override
    public UserVO getUserById(Long id) {
        return new UserVO(id, "ateng_" + id, "ateng_" + id + "@example.com");
    }

    @Override
    public Result<UserVO> getUserResult(Long id) {
        return Result.success(new UserVO(id, "ateng_result_" + id, "result_" + id + "@example.com"));
    }

    @Override
    public Result<UserVO> getFailedResult(Long id) {
        return Result.fail(4001, "用户状态已被冻结");
    }

    @Override
    public void throwBusinessException() {
        throw new BusinessException(CommonResultCode.NOT_FOUND, "用户记录不存在");
    }

    @Override
    public void throwCustomException() {
        throw new CustomDatabaseException("模拟底座数据库死锁超时");
    }
}
