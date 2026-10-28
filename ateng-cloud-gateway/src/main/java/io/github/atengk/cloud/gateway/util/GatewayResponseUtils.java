package io.github.atengk.cloud.gateway.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 响应式网关统一 JSON 异常写出与响应工具类
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class GatewayResponseUtils {

    private GatewayResponseUtils() {
    }

    /**
     * 写出强类型 JSON 错误响应
     *
     * @param exchange     响应式交换机
     * @param objectMapper Jackson 对象映射器
     * @param httpStatus   HTTP 状态码
     * @param code         业务错误码
     * @param msg          错误消息
     * @return Mono<Void>
     */
    public static Mono<Void> writeJsonError(ServerWebExchange exchange,
                                            ObjectMapper objectMapper,
                                            HttpStatus httpStatus,
                                            int code,
                                            String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String traceId = (String) exchange.getAttributes().get(GatewayConstants.ATTR_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            response.getHeaders().set(GatewayConstants.X_TRACE_ID, traceId);
        }

        Result<Void> result = Result.fail(code, msg);
        result.setTraceId(traceId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + code + ",\"msg\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
