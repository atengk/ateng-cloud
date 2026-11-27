package io.github.atengk.cloud.gateway.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.gateway.constant.GatewayConstants;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
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
        HttpHeaders headers = response.getHeaders();
        headers.remove(HttpHeaders.TRANSFER_ENCODING);
        headers.remove(HttpHeaders.CONTENT_ENCODING);
        headers.remove(HttpHeaders.CONTENT_DISPOSITION);
        // 清理内层过滤器可能已写入的混合加密信封响应头，防止外层写回明文错误 JSON 时误导客户端解密
        headers.remove(GatewayConstants.X_ENCRYPT_KEY);
        headers.remove(GatewayConstants.X_ENCRYPT_IV);
        headers.remove(GatewayConstants.X_KEY_ID);
        headers.remove(GatewayConstants.X_ENCRYPT_ALG);
        headers.setContentType(MediaType.APPLICATION_JSON);

        String traceId = (String) exchange.getAttributes().get(GatewayConstants.ATTR_TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            headers.set(GatewayConstants.X_TRACE_ID, traceId);
        }

        Result<Void> result = Result.fail(code, msg);
        result.setTraceId(traceId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + code + ",\"msg\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        headers.setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
