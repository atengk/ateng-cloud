package io.github.atengk.cloud.gateway.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.SameTokenInvalidException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 网关全局统一响应式异常处理器
 * <p>
 * 遵循 HTTP 物理状态码与 Result 语义双重对齐契约，将网关层未捕获的各类异常
 * 统一转译为标准强类型 Result 格式，并在生产环境下对未知系统错误强制脱敏。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Order(-2)
public class GatewayGlobalExceptionHandler implements ErrorWebExceptionHandler, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayGlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GatewayGlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public int getOrder() {
        return -2;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        // 1. 提取或生成当前请求的链路追踪标识
        String traceId = resolveTraceId(exchange);

        // 2. 解析异常类型与对齐 HTTP 物理状态码
        ErrorContext errorContext = resolveErrorContext(ex);

        // 3. 记录日志 (系统崩溃异常打印完整堆栈，普通业务/客户端异常打印 WARN/INFO)
        logException(exchange, ex, errorContext, traceId);

        // 4. 构造标准 Result 响应契约
        Result<Void> result = Result.fail(errorContext.code, errorContext.message);
        result.setTraceId(traceId);

        // 5. 格式化输出 JSON
        exchange.getResponse().setStatusCode(errorContext.httpStatus);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(GatewayConstants.X_TRACE_ID, traceId);
        if (errorContext.httpStatus.value() == HttpStatus.TOO_MANY_REQUESTS.value()
                && !exchange.getResponse().getHeaders().containsKey(org.springframework.http.HttpHeaders.RETRY_AFTER)) {
            exchange.getResponse().getHeaders().set(org.springframework.http.HttpHeaders.RETRY_AFTER, "1");
        }

        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            log.error("[Gateway] 异常响应 JSON 序列化失败, traceId={}", traceId, e);
            bytes = ("{\"code\":" + errorContext.code + ",\"msg\":\"" + errorContext.message + "\",\"traceId\":\"" + traceId + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = bufferFactory.wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        // 1. 优先从 Exchange Attribute 获取 (由 GatewayTraceFilter 注入)
        String traceId = exchange.getAttribute(GatewayConstants.ATTR_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        // 2. 其次探测入站标头
        traceId = exchange.getRequest().getHeaders().getFirst(GatewayConstants.X_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        // 3. 兜底自动生成
        traceId = UUID.randomUUID().toString().replace("-", "");
        exchange.getAttributes().put(GatewayConstants.ATTR_TRACE_ID, traceId);
        return traceId;
    }

    private ErrorContext resolveErrorContext(Throwable ex) {
        // 模式 1：框架受管 BusinessException 业务异常 (100% 原始透传业务码与描述，支持链式展开)
        BusinessException be = findCause(ex, BusinessException.class);
        if (be != null) {
            int code = be.getCode();
            String msg = be.getMessage();
            HttpStatusCode status = resolveHttpStatusFromCode(code);
            return new ErrorContext(status, code, msg, false);
        }

        // 模式 1.1：Sa-Token 集中安全认证与权限异常
        if (isCausedBy(ex, NotLoginException.class)) {
            return new ErrorContext(
                    HttpStatus.UNAUTHORIZED,
                    CommonResultCode.UNAUTHORIZED.getCode(),
                    "未登录或登录状态已过期，请重新登录",
                    false
            );
        }

        if (isCausedBy(ex, NotPermissionException.class) || isCausedBy(ex, NotRoleException.class)) {
            return new ErrorContext(
                    HttpStatus.FORBIDDEN,
                    CommonResultCode.FORBIDDEN.getCode(),
                    "无权限访问当前资源",
                    false
            );
        }

        if (isCausedBy(ex, SameTokenInvalidException.class)) {
            return new ErrorContext(
                    HttpStatus.UNAUTHORIZED,
                    CommonResultCode.UNAUTHORIZED.getCode(),
                    "内部受信同源凭据无效或已过期",
                    false
            );
        }

        // 模式 1.2：Sentinel 响应式微服务流控与降级异常自适应转译
        ErrorContext sentinelContext = resolveSentinelErrorContext(ex);
        if (sentinelContext != null) {
            return sentinelContext;
        }

        // 模式 2：Spring WebFlux 响应式状态异常 ResponseStatusException (如 NoResourceFoundException)
        if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode status = rse.getStatusCode();
            int code = status.value();
            String reason = rse.getReason();
            String msg = (reason != null && !reason.isBlank()) ? reason : status.toString();
            if (status.value() == 404) {
                msg = "请求的资源未找到或服务路由未匹配: " + msg;
            }
            return new ErrorContext(status, code, msg, status.is5xxServerError());
        }

        // 模式 3：Spring Cloud Gateway NotFoundException (404 路由未找到 / 实例不存在)
        if (ex.getClass().getSimpleName().contains("NotFoundException")) {
            return new ErrorContext(
                    HttpStatus.NOT_FOUND,
                    CommonResultCode.NOT_FOUND.getCode(),
                    "请求的资源未找到或服务路由未匹配",
                    false
            );
        }

        // 模式 4：报文超限异常 (413 Payload Too Large)
        if (ex.getClass().getSimpleName().contains("PayloadTooLarge")
                || ex.getClass().getSimpleName().contains("DataBufferLimitException")
                || isCausedBy(ex, org.springframework.core.io.buffer.DataBufferLimitException.class)) {
            return new ErrorContext(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    CommonResultCode.PAYLOAD_TOO_LARGE.getCode(),
                    "请求报文超出最大允许限制 (2MB)",
                    false
            );
        }

        // 模式 4.1：限流熔断异常 (429 Too Many Requests)
        if (ex.getClass().getSimpleName().contains("RateLimit")
                || (ex instanceof ResponseStatusException rse && rse.getStatusCode().value() == 429)) {
            return new ErrorContext(
                    HttpStatus.TOO_MANY_REQUESTS,
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "当前访问过于频繁，请稍后重试",
                    false
            );
        }

        // 模式 5：下游建连失败 (503 Service Unavailable)
        if (isCausedBy(ex, ConnectException.class)) {
            return new ErrorContext(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    CommonResultCode.SERVICE_UNAVAILABLE.getCode(),
                    "下游微服务连接失败或服务暂不可用",
                    true
            );
        }

        // 模式 5.1：下游连接突发中断或信道关闭 (502 Bad Gateway)
        if (isCausedBy(ex, java.nio.channels.ClosedChannelException.class)
                || isCausedByClassName(ex, "PrematureCloseException")) {
            return new ErrorContext(
                    HttpStatus.BAD_GATEWAY,
                    HttpStatus.BAD_GATEWAY.value(),
                    "下游微服务连接异常中断或已过早关闭 (Bad Gateway)",
                    true
            );
        }

        // 模式 6：下游调用读取超时 (504 Gateway Timeout)
        if (isCausedBy(ex, TimeoutException.class)) {
            return new ErrorContext(
                    HttpStatus.GATEWAY_TIMEOUT,
                    504,
                    "网关调用下游微服务响应超时",
                    true
            );
        }

        // 模式 7：未受检的未知系统内部异常 (强制生产脱敏为统一友好文案)
        return new ErrorContext(
                HttpStatus.INTERNAL_SERVER_ERROR,
                CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                "系统服务繁忙，请稍后重试",
                true
        );
    }

    @SuppressWarnings("unchecked")
    private <T extends Throwable> T findCause(Throwable ex, Class<T> targetClass) {
        Throwable current = ex;
        while (current != null) {
            if (targetClass.isInstance(current)) {
                return (T) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private ErrorContext resolveSentinelErrorContext(Throwable ex) {
        Throwable blockEx = findCauseByName(ex, "com.alibaba.csp.sentinel.slots.block.BlockException");
        if (blockEx == null) {
            return null;
        }
        String className = blockEx.getClass().getName();
        if (className.contains("DegradeException")) {
            return new ErrorContext(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "下游微服务触发熔断降级，暂不可用",
                    false
            );
        }
        if (className.contains("AuthorityException")) {
            return new ErrorContext(
                    HttpStatus.FORBIDDEN,
                    HttpStatus.FORBIDDEN.value(),
                    "网关访问权限受限，触发授权拦截",
                    false
            );
        }
        // FlowException, ParamFlowException, SystemBlockException 或基础 BlockException
        return new ErrorContext(
                HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "当前访问过于频繁，触发流量控制",
                false
        );
    }

    private Throwable findCauseByName(Throwable ex, String targetClassName) {
        Throwable current = ex;
        while (current != null) {
            Class<?> clazz = current.getClass();
            while (clazz != null && clazz != Object.class) {
                if (targetClassName.equals(clazz.getName())) {
                    return current;
                }
                clazz = clazz.getSuperclass();
            }
            current = current.getCause();
        }
        return null;
    }

    private boolean isCausedByClassName(Throwable ex, String simpleOrFullPattern) {
        Throwable current = ex;
        while (current != null) {
            if (current.getClass().getName().contains(simpleOrFullPattern)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private HttpStatusCode resolveHttpStatusFromCode(int code) {
        if (code >= 200 && code < 600) {
            HttpStatus resolved = HttpStatus.resolve(code);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.OK;
    }

    private boolean isCausedBy(Throwable ex, Class<? extends Throwable> targetClass) {
        Throwable current = ex;
        while (current != null) {
            if (targetClass.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void logException(ServerWebExchange exchange, Throwable ex, ErrorContext ctx, String traceId) {
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();
        if (ctx.isServerError) {
            log.error("[Gateway] 服务端异常: method={}, path={}, status={}, code={}, traceId={}",
                    method, path, ctx.httpStatus.value(), ctx.code, traceId, ex);
        } else {
            log.warn("[Gateway] 客户端/路由异常: method={}, path={}, status={}, code={}, msg={}, traceId={}",
                    method, path, ctx.httpStatus.value(), ctx.code, ctx.message, traceId);
        }
    }

    private static class ErrorContext {
        private final HttpStatusCode httpStatus;
        private final int code;
        private final String message;
        private final boolean isServerError;

        public ErrorContext(HttpStatusCode httpStatus, int code, String message, boolean isServerError) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.message = message;
            this.isServerError = isServerError;
        }
    }
}
