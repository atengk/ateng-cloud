package io.github.atengk.cloud.sentinel.advice;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SentinelExceptionTraceAdvice} 单元测试
 *
 * @author Ateng
 * @since 2026-11-13
 */
class SentinelExceptionTraceAdviceTest {

    private static final String TEST_RESOURCE = "/api/trace/test";

    private AtengSentinelProperties properties;
    private SentinelExceptionTraceAdvice traceAdvice;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        properties = new AtengSentinelProperties();
        traceAdvice = new SentinelExceptionTraceAdvice(properties);
        request = new MockHttpServletRequest();
        request.setRequestURI(TEST_RESOURCE);
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        ContextUtil.exit();
    }

    @Test
    @DisplayName("非豁免系统异常：自动注入 Sentinel Tracer 异常统计并高优先级放行")
    void testSystemExceptionTracedAndReturnsNull() throws Exception {
        assertThat(traceAdvice.getOrder()).isLessThan(0);
        assertThat(traceAdvice.getOrder()).isLessThan(Ordered.HIGHEST_PRECEDENCE + 100);

        ContextUtil.enter("trace-unit-context");
        Entry entry = SphU.entry(TEST_RESOURCE);
        try {
            traceAdvice.resolveException(request, response, null, new IllegalStateException("db down"));
        } finally {
            entry.exit();
        }

        assertThat(entry.getCurNode().totalException()).isEqualTo(1L);
    }

    @Test
    @DisplayName("单次请求幂等防重：同一 HttpServletRequest 多次触发解析仅累计 1 次异常")
    void testPerRequestIdempotentTrace() throws Exception {
        ContextUtil.enter("trace-idempotent-context");
        Entry entry = SphU.entry(TEST_RESOURCE);
        try {
            IllegalStateException ex = new IllegalStateException("downstream timeout");
            traceAdvice.resolveException(request, response, null, ex);
            traceAdvice.resolveException(request, response, null, ex);
        } finally {
            entry.exit();
        }

        assertThat(entry.getCurNode().totalException()).isEqualTo(1L);
    }

    @Test
    @DisplayName("默认豁免常规 BusinessException，开启 traceBusinessException=true 后放开统计")
    void testBusinessExceptionExemptionAndOptIn() throws Exception {
        ContextUtil.enter("trace-biz-default");
        Entry entry1 = SphU.entry(TEST_RESOURCE);
        try {
            traceAdvice.resolveException(request, response, null, new BusinessException(10001, "余额不足"));
        } finally {
            entry1.exit();
        }
        assertThat(entry1.getCurNode().totalException()).isZero();

        properties.getTrace().setTraceBusinessException(true);
        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        secondRequest.setRequestURI(TEST_RESOURCE);
        ContextUtil.enter("trace-biz-opt-in");
        Entry entry2 = SphU.entry(TEST_RESOURCE);
        try {
            traceAdvice.resolveException(secondRequest, new MockHttpServletResponse(), null,
                    new BusinessException(10001, "余额不足"));
            // 即使开启 traceBusinessException=true，携带 SentinelResultCode 的降级异常仍应强制豁免防雪崩放大
            MockHttpServletRequest sentinelBizReq = new MockHttpServletRequest();
            sentinelBizReq.setRequestURI(TEST_RESOURCE);
            traceAdvice.resolveException(sentinelBizReq, new MockHttpServletResponse(), null,
                    new BusinessException(SentinelResultCode.DEGRADE, "sentinel degrade"));
        } finally {
            entry2.exit();
        }
        assertThat(entry2.getCurNode().totalException()).isEqualTo(1L);
    }

    @Test
    @DisplayName("默认豁免 BlockException 及其代理反射包装异常")
    void testBlockExceptionAndWrappedBlockExempted() throws Exception {
        ContextUtil.enter("trace-block-exempt");
        Entry entry = SphU.entry(TEST_RESOURCE);
        try {
            traceAdvice.resolveException(request, response, null, new FlowException("flow"));

            MockHttpServletRequest r2 = new MockHttpServletRequest();
            traceAdvice.resolveException(r2, new MockHttpServletResponse(), null,
                    new UndeclaredThrowableException(new FlowException("ute-flow")));

            MockHttpServletRequest r3 = new MockHttpServletRequest();
            traceAdvice.resolveException(r3, new MockHttpServletResponse(), null,
                    new InvocationTargetException(new FlowException("ite-flow")));
        } finally {
            entry.exit();
        }
        assertThat(entry.getCurNode().totalException()).isZero();
    }

    @Test
    @DisplayName("默认豁免 Spring 4xx 参数校验与协议异常")
    void testSpring4xxValidationAndProtocolExceptionsExempted() throws Exception {
        Method sampleMethod = DummyController.class.getDeclaredMethod("handle", String.class);
        MethodParameter parameter = new MethodParameter(sampleMethod, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult("target", "target");
        MethodArgumentNotValidException validationEx = new MethodArgumentNotValidException(parameter, bindingResult);

        HttpInputMessage inputMessage = new MockHttpInputMessage("{}".getBytes(StandardCharsets.UTF_8));
        HttpMessageNotReadableException notReadableEx = new HttpMessageNotReadableException("bad json", inputMessage);

        MissingServletRequestParameterException missingParamEx =
                new MissingServletRequestParameterException("id", "Long");

        MethodArgumentTypeMismatchException typeMismatchEx = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", parameter, new NumberFormatException("abc"));

        ClientBadRequestException annotated4xxEx = new ClientBadRequestException("bad request");

        ContextUtil.enter("trace-4xx-exempt");
        Entry entry = SphU.entry(TEST_RESOURCE);
        try {
            traceAdvice.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null, validationEx);
            traceAdvice.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null, notReadableEx);
            traceAdvice.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null, missingParamEx);
            traceAdvice.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null, typeMismatchEx);
            traceAdvice.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null, annotated4xxEx);
        } finally {
            entry.exit();
        }
        assertThat(entry.getCurNode().totalException()).isZero();
    }

    @Test
    @DisplayName("自定义 ignoreExceptions 与 enabled=false 开关生效")
    void testCustomIgnoreExceptionsAndDisabledToggle() throws Exception {
        properties.getTrace().setIgnoreExceptions(List.of(IllegalArgumentException.class));

        ContextUtil.enter("trace-custom-ignore");
        Entry entry = SphU.entry(TEST_RESOURCE);
        try {
            traceAdvice.resolveException(request, response, null, new IllegalArgumentException("bad arg"));

            properties.getTrace().setEnabled(false);
            MockHttpServletRequest r2 = new MockHttpServletRequest();
            traceAdvice.resolveException(r2, new MockHttpServletResponse(), null, new IllegalStateException("ignored when off"));
        } finally {
            entry.exit();
        }
        assertThat(entry.getCurNode().totalException()).isZero();
    }

    static class DummyController {
        @SuppressWarnings("unused")
        void handle(String value) {
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    static class ClientBadRequestException extends RuntimeException {
        ClientBadRequestException(String message) {
            super(message);
        }
    }
}
