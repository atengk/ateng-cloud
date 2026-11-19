package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Response;
import feign.codec.ErrorDecoder;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.support.FeignErrorSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Optional;

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
        // FeignException.errorStatus 会预读并关闭一次性 Response.Body 流，后续统一从其缓存字节解析
        FeignException feignException = FeignException.errorStatus(methodKey, response);
        byte[] bytes = extractErrorBodyBytes(feignException);
        if (bytes.length > 0) {
            try {
                JsonNode jsonNode = objectMapper.readTree(bytes);
                if (FeignResultSupport.isResultObject(jsonNode)) {
                    int code = FeignResultSupport.extractCode(jsonNode);
                    String msg = FeignResultSupport.extractMessage(jsonNode);
                    String remoteTraceId = FeignResultSupport.resolveResponseTraceId(jsonNode, response);
                    return new BusinessException(code, msg, feignException).withTraceId(remoteTraceId);
                }
            } catch (Exception e) {
                log.debug("Failed to parse Feign error response body as JSON Result: {}", e.getMessage());
            }
        }

        // 依据 HTTP 状态码映射标准错误码
        IResultCode resultCode = FeignErrorSupport.mapHttpStatusToResultCode(response.status());
        String errorMsg = String.format("Feign 调用下游服务异常: [%s], HTTP 状态码: %d", methodKey, response.status());
        String headerTraceId = FeignResultSupport.resolveResponseTraceId(null, response);
        return new BusinessException(resultCode, errorMsg, feignException).withTraceId(headerTraceId);
    }

    private static byte[] extractErrorBodyBytes(FeignException feignException) {
        Optional<ByteBuffer> bodyOpt = feignException.responseBody();
        if (bodyOpt.isPresent()) {
            ByteBuffer buffer = bodyOpt.get().asReadOnlyBuffer();
            if (buffer.hasRemaining()) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return bytes;
            }
        }
        byte[] content = feignException.content();
        return content != null ? content : new byte[0];
    }
}
