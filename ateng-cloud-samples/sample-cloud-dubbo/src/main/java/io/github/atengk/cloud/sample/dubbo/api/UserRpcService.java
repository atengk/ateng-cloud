package io.github.atengk.cloud.sample.dubbo.api;

import io.github.atengk.cloud.sample.dubbo.model.UserVO;

/**
 * 用户 RPC 服务契约接口
 *
 * @author Ateng
 * @since 2026-10-28
 */
public interface UserRpcService {

    /**
     * 健康探活 ping 方法
     *
     * @param message 探活入参
     * @return 响应消息
     */
    String ping(String message);

    /**
     * 依据 ID 查询用户信息（原生 POJO 强类型返回契约）
     *
     * @param id 用户主键
     * @return 用户详情对象
     */
    UserVO getUserById(Long id);
}
