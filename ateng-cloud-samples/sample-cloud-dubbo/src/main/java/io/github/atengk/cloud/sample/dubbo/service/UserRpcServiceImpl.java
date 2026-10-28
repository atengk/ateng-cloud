package io.github.atengk.cloud.sample.dubbo.service;

import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
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
}
