package io.github.atengk.cloud.feign.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Client;
import feign.Feign;
import feign.FeignException;
import feign.RequestLine;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import feign.codec.ErrorDecoder;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import io.github.atengk.cloud.feign.retry.AtengFeignRetryer;
import io.github.atengk.cloud.feign.support.AtengFeignInvocationHandlerFactory;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.ResponseEntityDecoder;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Feign 网络异常可选因果转译与代理调用拦截器单元测试
 *
 * @author Ateng
 * @since 2026-11-12
 */
@DisplayName("Feign 网络异常可选因果转译单元测试")
class AtengFeignInvocationExceptionTranslationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    record DemoPayload(Long id, String name) {
    }

    interface DemoRemoteApi {
        @RequestLine("GET /demo/ping")
        String ping();

        @RequestLine("GET /demo/payload")
        DemoPayload payload();

        @RequestLine("POST /demo/submit")
        String submit();
    }

    private DemoRemoteApi buildClient(AtengFeignProperties properties, Client mockClient, ErrorDecoder errorDecoder) {
        ObjectFactory<HttpMessageConverters> converters = () ->
                new HttpMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper));
        AtengFeignDecoder decoder = new AtengFeignDecoder(
                new ResponseEntityDecoder(new SpringDecoder(converters)),
                properties,
                objectMapper
        );
        return Feign.builder()
                .client(mockClient)
                .retryer(new AtengFeignRetryer(10, 20, 2, properties))
                .decoder(decoder)
                .errorDecoder(errorDecoder)
                .invocationHandlerFactory(new AtengFeignInvocationHandlerFactory(properties))
                .target(DemoRemoteApi.class, "http://demo-service");
    }

    @Test
    @DisplayName("translate-network-exceptions 默认为 false 时保留原生 RetryableException 与未转译 FeignException，但仍剥壳 DecodeException 内 BusinessException")
    void testDefaultDisabledPreservesRetryableExceptionAndUnwrapsDecodeBusinessException() {
        AtengFeignProperties properties = new AtengFeignProperties();
        assertThat(properties.isTranslateNetworkExceptions()).isFalse();

        // 1. 建连超时经弹性重试 1 次后仍抛出原生 RetryableException
        AtomicInteger connectAttempts = new AtomicInteger();
        DemoRemoteApi connectClient = buildClient(properties, (request, options) -> {
            connectAttempts.incrementAndGet();
            throw new ConnectTimeoutException("connect timed out");
        }, new ErrorDecoder.Default());

        RetryableException connectEx = assertThrows(RetryableException.class, connectClient::ping);
        assertThat(connectAttempts.get()).isEqualTo(2);
        assertThat(connectEx.getCause()).isInstanceOf(ConnectTimeoutException.class);

        // 2. 写请求读超时禁重试，抛出原生 RetryableException
        AtomicInteger readAttempts = new AtomicInteger();
        DemoRemoteApi readClient = buildClient(properties, (request, options) -> {
            readAttempts.incrementAndGet();
            throw new SocketTimeoutException("Read timed out");
        }, new ErrorDecoder.Default());

        RetryableException readEx = assertThrows(RetryableException.class, readClient::submit);
        assertThat(readAttempts.get()).isEqualTo(1);
        assertThat(readEx.getCause()).isInstanceOf(SocketTimeoutException.class);

        // 3. 响应内嵌业务错误（Result code != 200）通过 DecodeException 剥壳，始终直接抛出 BusinessException
        DemoRemoteApi bizErrorClient = buildClient(properties, (request, options) -> Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(Collections.emptyMap())
                .body("{\"code\":4221,\"msg\":\"库存不足\"}", StandardCharsets.UTF_8)
                .build(), new ErrorDecoder.Default());

        BusinessException bizEx = assertThrows(BusinessException.class, bizErrorClient::ping);
        assertThat(bizEx.getCode()).isEqualTo(4221);
        assertThat(bizEx.getMsg()).isEqualTo("库存不足");
    }

    @Test
    @DisplayName("translate-network-exceptions 为 true 时将建连失败与读超时因果转译为 SERVICE_UNAVAILABLE BusinessException")
    void testTranslateNetworkExceptionsOnTimeoutAndConnectFailure() {
        AtengFeignProperties properties = new AtengFeignProperties();
        properties.setTranslateNetworkExceptions(true);

        // 1. 建连失败仍按防雪崩规则弹性重试后转译
        AtomicInteger connectAttempts = new AtomicInteger();
        DemoRemoteApi connectClient = buildClient(properties, (request, options) -> {
            connectAttempts.incrementAndGet();
            throw new ConnectTimeoutException("connect timed out");
        }, new AtengFeignErrorDecoder(objectMapper));

        BusinessException connectBe = assertThrows(BusinessException.class, connectClient::ping);
        assertThat(connectAttempts.get()).isEqualTo(2);
        assertThat(connectBe.getCode()).isEqualTo(CommonResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThat(connectBe.getCause()).isInstanceOf(RetryableException.class);
        assertThat(connectBe.getCause().getCause()).isInstanceOf(ConnectTimeoutException.class);

        // 2. 写请求读超时严格不重试，并因果转译为 SERVICE_UNAVAILABLE
        AtomicInteger readAttempts = new AtomicInteger();
        DemoRemoteApi readClient = buildClient(properties, (request, options) -> {
            readAttempts.incrementAndGet();
            throw new SocketTimeoutException("Read timed out");
        }, new AtengFeignErrorDecoder(objectMapper));

        BusinessException readBe = assertThrows(BusinessException.class, readClient::submit);
        assertThat(readAttempts.get()).isEqualTo(1);
        assertThat(readBe.getCode()).isEqualTo(CommonResultCode.SERVICE_UNAVAILABLE.getCode());
        assertThat(readBe.getCause()).isInstanceOf(RetryableException.class);
        assertThat(readBe.getCause().getCause()).isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("translate-network-exceptions 为 true 时兼容未转译 FeignException 映射及非法 JSON 解码异常不误判为 SERVICE_UNAVAILABLE")
    void testTranslateUntranslatedFeignExceptionAndDecodeFailure() {
        AtengFeignProperties properties = new AtengFeignProperties();
        properties.setTranslateNetworkExceptions(true);

        // 1. 自定义 ErrorDecoder 返回原生 HTTP 404 FeignException -> 映射 NOT_FOUND 并保留原始 FeignException cause
        DemoRemoteApi notFoundClient = buildClient(properties, (request, options) -> Response.builder()
                .status(404)
                .reason("Not Found")
                .request(request)
                .headers(Collections.emptyMap())
                .body("missing", StandardCharsets.UTF_8)
                .build(), new ErrorDecoder.Default());

        BusinessException notFoundBe = assertThrows(BusinessException.class, notFoundClient::ping);
        assertThat(notFoundBe.getCode()).isEqualTo(CommonResultCode.NOT_FOUND.getCode());
        assertThat(notFoundBe.getCause()).isInstanceOf(FeignException.class);

        // 2. HTTP 200 返回坏 JSON 触发 DecodeException -> 映射 INTERNAL_SERVER_ERROR（而非 503 SERVICE_UNAVAILABLE）且保留 DecodeException cause
        DemoRemoteApi invalidJsonClient = buildClient(properties, (request, options) -> Response.builder()
                .status(200)
                .reason("OK")
                .request(request)
                .headers(Collections.singletonMap("Content-Type", Collections.singletonList("application/json")))
                .body("{broken-json", StandardCharsets.UTF_8)
                .build(), new ErrorDecoder.Default());

        BusinessException decodeBe = assertThrows(BusinessException.class, invalidJsonClient::payload);
        assertThat(decodeBe.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(decodeBe.getCause()).isInstanceOf(DecodeException.class);
    }

    @Test
    @DisplayName("校验 HTTP 409/504/406/415 状态码映射及 ErrorDecoder 远端 traceId 回填")
    void testHttp409And504MappingAndErrorTraceIdPropagation() {
        AtengFeignProperties properties = new AtengFeignProperties();
        AtengFeignErrorDecoder errorDecoder = new AtengFeignErrorDecoder(objectMapper);

        // 1. 纯文本 HTTP 409 -> CommonResultCode.CONFLICT (409) + X-Trace-Id
        DemoRemoteApi conflictClient = buildClient(properties, (request, options) -> Response.builder()
                .status(409)
                .reason("Conflict")
                .request(request)
                .headers(Collections.singletonMap("X-Trace-Id", Collections.singletonList("trace-409-header")))
                .body("version conflict", StandardCharsets.UTF_8)
                .build(), errorDecoder);
        BusinessException conflictEx = assertThrows(BusinessException.class, conflictClient::submit);
        assertThat(conflictEx.getCode()).isEqualTo(CommonResultCode.CONFLICT.getCode());
        assertThat(conflictEx.getTraceId()).isEqualTo("trace-409-header");

        // 2. HTTP 504 -> SERVICE_UNAVAILABLE (503) + JSON Result.traceId 优先
        DemoRemoteApi gatewayTimeoutClient = buildClient(properties, (request, options) -> Response.builder()
                .status(504)
                .reason("Gateway Timeout")
                .request(request)
                .headers(Collections.singletonMap("X-Trace-Id", Collections.singletonList("trace-header-ignored")))
                .body("{\"code\":504,\"msg\":\"upstream timeout\",\"traceId\":\"trace-504-body\"}", StandardCharsets.UTF_8)
                .build(), errorDecoder);
        BusinessException timeoutEx = assertThrows(BusinessException.class, gatewayTimeoutClient::ping);
        assertThat(timeoutEx.getCode()).isEqualTo(504);
        assertThat(timeoutEx.getTraceId()).isEqualTo("trace-504-body");

        // 3. 验证 406 / 415 / 504 映射表覆盖完整
        assertThat(io.github.atengk.cloud.feign.support.FeignErrorSupport.mapHttpStatusToResultCode(406))
                .isEqualTo(CommonResultCode.NOT_ACCEPTABLE);
        assertThat(io.github.atengk.cloud.feign.support.FeignErrorSupport.mapHttpStatusToResultCode(409))
                .isEqualTo(CommonResultCode.CONFLICT);
        assertThat(io.github.atengk.cloud.feign.support.FeignErrorSupport.mapHttpStatusToResultCode(415))
                .isEqualTo(CommonResultCode.UNSUPPORTED_MEDIA_TYPE);
        assertThat(io.github.atengk.cloud.feign.support.FeignErrorSupport.mapHttpStatusToResultCode(504))
                .isEqualTo(CommonResultCode.SERVICE_UNAVAILABLE);

        // 4. 一次性底层 InputStream 在 FeignException.errorStatus 消耗后仍可从缓存字节解析 Result.traceId
        byte[] oneShotBytes = "{\"code\":500,\"msg\":\"server err\",\"traceId\":\"trace-oneshot-500\"}"
                .getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayInputStream oneShotStream = new java.io.ByteArrayInputStream(oneShotBytes);
        Response.Body oneShotBody = new Response.Body() {
            @Override
            public Integer length() {
                return oneShotBytes.length;
            }

            @Override
            public boolean isRepeatable() {
                return false;
            }

            @Override
            public java.io.InputStream asInputStream() {
                return oneShotStream;
            }

            @Override
            public java.io.Reader asReader(java.nio.charset.Charset charset) {
                return new java.io.InputStreamReader(oneShotStream, charset);
            }

            @Override
            public void close() {
            }
        };
        Response oneShotResponse = Response.builder()
                .status(500)
                .reason("Internal Server Error")
                .request(feign.Request.create(feign.Request.HttpMethod.GET, "/err", Collections.emptyMap(), null, StandardCharsets.UTF_8, null))
                .headers(Collections.singletonMap("X-Trace-Id", Collections.singletonList("trace-header-ignored")))
                .body(oneShotBody)
                .build();
        Exception decoded = errorDecoder.decode("DemoRemoteApi#ping()", oneShotResponse);
        assertThat(decoded).isInstanceOf(BusinessException.class);
        BusinessException oneShotBe = (BusinessException) decoded;
        assertThat(oneShotBe.getCode()).isEqualTo(500);
        assertThat(oneShotBe.getMsg()).isEqualTo("server err");
        assertThat(oneShotBe.getTraceId()).isEqualTo("trace-oneshot-500");
    }
}
