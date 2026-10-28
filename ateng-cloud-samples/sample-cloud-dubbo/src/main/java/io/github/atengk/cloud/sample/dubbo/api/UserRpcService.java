package io.github.atengk.cloud.sample.dubbo.api;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.dubbo.model.UserCreateRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserLogStreamRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserLogVO;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import jakarta.validation.Valid;
import org.apache.dubbo.common.stream.StreamObserver;

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

    /**
     * 依据 ID 查询用户信息（显式包装 Result 强契约返回）
     *
     * @param id 用户主键
     * @return 封装的 Result 统一契约
     */
    Result<UserVO> getUserResult(Long id);

    /**
     * 返回业务失败状态的 Result（验证消费端 DubboResultUnwrapFilter 自动拦截并抛出 BusinessException）
     *
     * @param id 用户主键
     * @return 失败的业务结果
     */
    Result<UserVO> getFailedResult(Long id);

    /**
     * 服务端直接抛出受管 BusinessException 异常（验证因果保真白名单放行）
     */
    void throwBusinessException();

    /**
     * 服务端抛出未受检非 JDK 自定义底层异常（验证异常转译为标准 BusinessException 并防御反序列化崩溃）
     */
    void throwCustomException();

    /**
     * 获取服务端当前线程绑定的上下文镜像（验证四维上下文与流量染色透传及服务端线程确定性清理）
     *
     * @return 服务端线程上下文快照
     */
    java.util.Map<String, String> getProviderContext();

    /**
     * 创建用户（验证 JSR-303 参数校验拦截与结构化转译为标准 BusinessException）
     *
     * @param request 创建用户入参
     * @return 用户详情
     */
    UserVO createUser(@Valid UserCreateRequest request);

    /**
     * 模拟服务端线程池过载拒绝异常抛出（验证过载保护过滤器转译）
     */
    void simulateOverload();

    /**
     * Triple Server Stream 服务端流式推送用户审计日志
     *
     * @param request 查询请求
     * @param responseObserver 响应流观察者
     */
    void streamUserLogs(UserLogStreamRequest request, StreamObserver<UserLogVO> responseObserver);

    /**
     * 模拟 Server Stream 异常中断流式推送
     *
     * @param request 查询请求
     * @param responseObserver 响应流观察者
     */
    void streamErrorLogs(UserLogStreamRequest request, StreamObserver<UserLogVO> responseObserver);

    /**
     * 发送大载荷数据（验证报文载荷硬防爆上限）
     *
     * @param data 报文内容
     * @return 接收到的报文长度
     */
    int echoPayload(String data);
}

