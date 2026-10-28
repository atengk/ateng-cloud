package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feign 因果异常解码器
 * 拦截 HTTP 4xx/5xx 非正常状态码，精准转译为携带完整 cause 因果链的受管 BusinessException
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignErrorDecoder implements ErrorDecoder {

    private static final Logger log = LoggerFactory.getLogger(AtengFeignErrorDecoder.class);

    private final ObjectMapper objectMapper;

    public AtengFeignErrorDecoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        FeignException feignException = FeignException.errorStatus(methodKey, response);
        if (response.body() != null) {
            try {
                byte[] bytes = Util.toByteArray(response.body().asInputStream());
                if (bytes.length > 0) {
                    JsonNode jsonNode = objectMapper.readTree(bytes);
                    if (jsonNode != null && jsonNode.isObject() && jsonNode.has("code") && jsonNode.get("code").isNumber()) {
                        int code = jsonNode.get("code").asInt();
                        String msg = jsonNode.has("msg") && !jsonNode.get("msg").isNull() ? jsonNode.get("msg").asText()
                                : (jsonNode.has("message") && !jsonNode.get("message").isNull() ? jsonNode.get("message").asText() : "");
                        return new BusinessException(code, msg, feignException);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse Feign error response body as JSON Result: {}", e.getMessage());
            }
        }

        // 依据 HTTP 状态码映射标准错误码
        IResultCode resultCode = mapHttpStatusToResultCode(response.status());
        String errorMsg = String.format("Feign 调用下游服务异常: [%s], HTTP 状态码: %d", methodKey, response.status());
        return new BusinessException(resultCode, errorMsg, feignException);
    }

    private IResultCode mapHttpStatusToResultCode(int status) {
        return switch (status) {
            case 400 -> CommonResultCode.BAD_REQUEST;
            case 401 -> CommonResultCode.UNAUTHORIZED;
            case 403 -> CommonResultCode.FORBIDDEN;
            case 404 -> CommonResultCode.NOT_FOUND;
            case 405 -> CommonResultCode.METHOD_NOT_ALLOWED;
            case 413 -> CommonResultCode.PAYLOAD_TOO_LARGE;
            case 429 -> CommonResultCode.TOO_MANY_REQUESTS;
            case 503 -> CommonResultCode.SERVICE_UNAVAILABLE;
            default -> CommonResultCode.INTERNAL_SERVER_ERROR;
        };
    }
}
