package io.github.atengk.cloud.sample.dubbo.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.dubbo.filter.DubboContextPropagationFilter;
import io.github.atengk.cloud.sample.dubbo.api.UserRpcService;
import io.github.atengk.cloud.sample.dubbo.exception.CustomDatabaseException;
import io.github.atengk.cloud.sample.dubbo.model.UserCreateRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserLogStreamRequest;
import io.github.atengk.cloud.sample.dubbo.model.UserLogVO;
import io.github.atengk.cloud.sample.dubbo.model.UserVO;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

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

    @Override
    public java.util.Map<String, String> getProviderContext() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        map.put("traceId", org.slf4j.MDC.get("traceId"));
        map.put("userId", io.github.atengk.boot.core.context.UserContextHolder.getUserId());
        map.put("tenantId", io.github.atengk.boot.core.context.TenantContextHolder.getTenantId());
        map.put("ignoreTenant", String.valueOf(io.github.atengk.boot.core.context.TenantContextHolder.isIgnoreTenant()));
        map.put("trafficTag", org.slf4j.MDC.get("X-Traffic-Tag"));
        map.put("xid", io.github.atengk.cloud.dubbo.support.DubboSeataPropagationSupport.getXID());

        var trafficSnapshot = io.github.atengk.cloud.dubbo.support.DubboTrafficPropagationSupport.getSnapshot();
        if (trafficSnapshot != null) {
            if (trafficSnapshot.tag() != null) {
                map.put("trafficTag", trafficSnapshot.tag());
            }
            if (trafficSnapshot.laneName() != null) {
                map.put("trafficLane", trafficSnapshot.laneName());
            }
            if (trafficSnapshot.matchedRule() != null) {
                map.put("trafficRule", trafficSnapshot.matchedRule());
            }
        }
        return map;
    }

    @Override
    public io.github.atengk.cloud.sample.dubbo.model.UserVO createUser(io.github.atengk.cloud.sample.dubbo.model.UserCreateRequest request) {
        return new io.github.atengk.cloud.sample.dubbo.model.UserVO(100L, request.getUsername(), "ateng@example.com");
    }

    @Override
    public void simulateOverload() {
        io.github.atengk.cloud.dubbo.overload.DubboOverloadListener.incrementRejectCount();
        throw new BusinessException(
                CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                "服务端并发线程池已耗尽，触发过载保护限流"
        );
    }

    @Override
    public void streamUserLogs(UserLogStreamRequest request, StreamObserver<UserLogVO> responseObserver) {
        String currentUserId = UserContextHolder.getUserId();
        String currentTenantId = TenantContextHolder.getTenantId();
        String currentTraceId = MDC.get("traceId");
        String currentTrafficTag = MDC.get(DubboContextPropagationFilter.TRAFFIC_TAG);

        int count = request.getLimit() != null && request.getLimit() > 0 ? request.getLimit() : 3;
        for (int i = 1; i <= count; i++) {
            UserLogVO log = new UserLogVO(
                    (long) i,
                    currentUserId,
                    currentTenantId,
                    currentTraceId,
                    currentTrafficTag,
                    "Log event #" + i + " for action: " + request.getAction()
            );
            responseObserver.onNext(log);
        }
        responseObserver.onCompleted();
    }

    @Override
    public void streamErrorLogs(UserLogStreamRequest request, StreamObserver<UserLogVO> responseObserver) {
        String currentUserId = UserContextHolder.getUserId();
        responseObserver.onNext(new UserLogVO(1L, currentUserId, null, null, null, "Initial event before error"));
        responseObserver.onError(new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "流式处理中断异常"));
    }

    @Override
    public int echoPayload(String data) {
        return data != null ? data.length() : 0;
    }
}
