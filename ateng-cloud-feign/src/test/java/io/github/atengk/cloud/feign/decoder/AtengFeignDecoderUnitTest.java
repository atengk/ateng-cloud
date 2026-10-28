package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.Response;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign 解码器与异常解码器单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignDecoderUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtengFeignErrorDecoder errorDecoder = new AtengFeignErrorDecoder(objectMapper);

    @Test
    @DisplayName("测试 ErrorDecoder 提取 JSON 报文中的业务错误码并保留因果链")
    void testDecodeJsonBusinessError() {
        String json = "{\"code\":6001,\"message\":\"账户余额不足\",\"data\":null}";
        Request request = Request.create(Request.HttpMethod.GET, "/api/test", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(400)
                .reason("Bad Request")
                .request(request)
                .headers(Collections.emptyMap())
                .body(json, StandardCharsets.UTF_8)
                .build();

        Exception ex = errorDecoder.decode("TestClient#test()", response);
        assertTrue(ex instanceof BusinessException);
        BusinessException be = (BusinessException) ex;
        assertEquals(6001, be.getCode());
        assertEquals("账户余额不足", be.getMsg());
        assertNotNull(be.getCause(), "原始因果链 cause 不可为空");
    }

    @Test
    @DisplayName("测试 ErrorDecoder 对非 JSON 响应依据 HTTP 状态码映射标准错误码")
    void testDecodeHttpFallbackError() {
        Request request = Request.create(Request.HttpMethod.GET, "/api/test", Collections.emptyMap(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(503)
                .reason("Service Unavailable")
                .request(request)
                .headers(Collections.emptyMap())
                .body("Downstream Service Unavailable", StandardCharsets.UTF_8)
                .build();

        Exception ex = errorDecoder.decode("TestClient#test()", response);
        assertTrue(ex instanceof BusinessException);
        BusinessException be = (BusinessException) ex;
        assertEquals(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), be.getCode());
        assertNotNull(be.getCause());
    }

    @Test
    @DisplayName("测试 FeignResultSupport 契约识别与字段提取能力")
    void testFeignResultSupport() throws Exception {
        String jsonMsg = "{\"code\":200,\"msg\":\"成功\",\"data\":\"ok\"}";
        var nodeMsg = objectMapper.readTree(jsonMsg);
        assertTrue(FeignResultSupport.isResultObject(nodeMsg));
        assertEquals(200, FeignResultSupport.extractCode(nodeMsg));
        assertEquals("成功", FeignResultSupport.extractMessage(nodeMsg));

        String jsonMessage = "{\"code\":500,\"message\":\"系统错误\"}";
        var nodeMessage = objectMapper.readTree(jsonMessage);
        assertTrue(FeignResultSupport.isResultObject(nodeMessage));
        assertEquals(500, FeignResultSupport.extractCode(nodeMessage));
        assertEquals("系统错误", FeignResultSupport.extractMessage(nodeMessage));

        String nonResult = "{\"status\":200,\"data\":\"ok\"}";
        var nodeNonResult = objectMapper.readTree(nonResult);
        assertFalse(FeignResultSupport.isResultObject(nodeNonResult));
        assertEquals("", FeignResultSupport.extractMessage(nodeNonResult));
    }
}
