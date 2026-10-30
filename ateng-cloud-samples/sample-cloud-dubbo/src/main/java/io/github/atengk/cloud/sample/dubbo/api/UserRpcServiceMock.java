package io.github.atengk.cloud.sample.dubbo.api;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.dubbo.model.UserCreateRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserLogStreamRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserLogVO;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import jakarta.validation.Valid;
import org.apache.dubbo.common.stream.StreamObserver;

import java.util.Collections;
import java.util.Map;

/**
 * 用户 RPC 服务原生客户端降级契约实现 (对齐 Dubbo 3 原生 Mock 同包约定)
 * <p>
 * 遵循微服务弹性容灾与服务降级规范：
 * 当下游服务出现宕机、网络不可达或超时中断时，由 Dubbo 消费端拦截器自动平滑切换至当前 Mock 实现，
 * 返回受管的业务保底对象，防止服务雪崩与异常级联扩散。
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class UserRpcServiceMock implements UserRpcService {

    public static final String MOCK_TAG = "[MOCK-FALLBACK]";

    @Override
    public String ping(String message) {
        return MOCK_TAG + " pong: " + message;
    }

    @Override
    public UserVO getUserById(Long id) {
        return new UserVO(id != null ? id : 0L, "mock-user-fallback", "mock@example.com");
    }

    @Override
    public Result<UserVO> getUserResult(Long id) {
        return Result.success(new UserVO(id != null ? id : 0L, "mock-result-fallback", "mock@example.com"));
    }

    @Override
    public Result<UserVO> getFailedResult(Long id) {
        return Result.fail(5003, "下游服务不可用，已触发客户端 Mock 降级");
    }

    @Override
    public void throwBusinessException() {
        // 降级静默短路
    }

    @Override
    public void throwCustomException() {
        // 降级静默短路
    }

    @Override
    public Map<String, String> getProviderContext() {
        return Collections.singletonMap("mock", "true");
    }

    @Override
    public UserVO createUser(@Valid UserCreateRequest request) {
        String username = request != null ? request.getUsername() : "mock-user";
        return new UserVO(0L, username + "-mock", "mock@example.com");
    }

    @Override
    public void simulateOverload() {
        // 降级静默短路
    }

    @Override
    public void streamUserLogs(UserLogStreamRequest request, StreamObserver<UserLogVO> responseObserver) {
        if (responseObserver != null) {
            responseObserver.onNext(new UserLogVO(0L, "mock-user", "mock-tenant", "mock-trace", "mock-traffic", "mock-log-event"));
            responseObserver.onCompleted();
        }
    }

    @Override
    public void streamErrorLogs(UserLogStreamRequest request, StreamObserver<UserLogVO> responseObserver) {
        if (responseObserver != null) {
            responseObserver.onCompleted();
        }
    }

    @Override
    public int echoPayload(String data) {
        return -1;
    }
}
