package io.github.atengk.cloud.sentinel.advice;

import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.UndeclaredThrowableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SentinelBlockExceptionAdvice} 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SentinelBlockExceptionAdviceTest {

    private AtengSentinelProperties properties;
    private SentinelBlockExceptionAdvice advice;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        properties = new AtengSentinelProperties();
        advice = new SentinelBlockExceptionAdvice(properties);
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/resource/test");
        request.setRemoteAddr("192.168.1.100");
    }

    @Test
    @DisplayName("直接捕获 FlowException：返回 HTTP 429 与 FLOW_LIMIT(4201)")
    void testHandleFlowException() {
        FlowException ex = new FlowException("flow limited");
        ResponseEntity<Result<Void>> response = advice.handleBlockException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
        assertThat(response.getBody().getMsg()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getMsg());
    }

    @Test
    @DisplayName("直接捕获 DegradeException：返回 HTTP 503 与 DEGRADE(4202)")
    void testHandleDegradeException() {
        DegradeException ex = new DegradeException("degraded");
        ResponseEntity<Result<Void>> response = advice.handleBlockException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.DEGRADE.getCode());
    }

    @Test
    @DisplayName("直接捕获 ParamFlowException：返回 HTTP 429 与 PARAM_FLOW_LIMIT(4203)")
    void testHandleParamFlowException() {
        ParamFlowException ex = new ParamFlowException("goods-resource", "goods-101");
        ResponseEntity<Result<Void>> response = advice.handleBlockException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.PARAM_FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("直接捕获 SystemBlockException：返回 HTTP 429 与 SYSTEM_BLOCK(4204)")
    void testHandleSystemBlockException() {
        SystemBlockException ex = new SystemBlockException("heavy-calc", "load over");
        ResponseEntity<Result<Void>> response = advice.handleBlockException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.SYSTEM_BLOCK.getCode());
    }

    @Test
    @DisplayName("直接捕获 AuthorityException：返回 HTTP 429 与 AUTHORITY_BLOCK(4205)")
    void testHandleAuthorityException() {
        AuthorityException ex = new AuthorityException("admin-resource");
        ResponseEntity<Result<Void>> response = advice.handleBlockException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.AUTHORITY_BLOCK.getCode());
    }

    @Test
    @DisplayName("捕获动态代理包装的 UndeclaredThrowableException(FlowException)：成功解包返回 429")
    void testHandleUndeclaredThrowableExceptionWithBlockException() throws Throwable {
        FlowException flowException = new FlowException("flow limited");
        UndeclaredThrowableException ute = new UndeclaredThrowableException(flowException);

        ResponseEntity<Result<Void>> response = advice.handleUndeclaredThrowableException(ute, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("捕获 UndeclaredThrowableException(RuntimeException)：非 Sentinel 异常原样抛出")
    void testHandleUndeclaredThrowableExceptionWithOtherException() {
        RuntimeException otherException = new RuntimeException("other error");
        UndeclaredThrowableException ute = new UndeclaredThrowableException(otherException);

        assertThatThrownBy(() -> advice.handleUndeclaredThrowableException(ute, request))
                .isSameAs(ute);
    }

    @Test
    @DisplayName("独立控制 HTTP 状态码物理对齐：blockAdvice 开关与 blockHandler 解耦自洽")
    void testHttpStatusAlignIndependent() {
        FlowException ex = new FlowException("flow limited");

        // 1. blockAdvice 开启状态码对齐，即使 blockHandler 关闭，仍物理对齐 429
        properties.getBlockHandler().setHttpStatusAlign(false);
        properties.getBlockAdvice().setHttpStatusAlign(true);
        ResponseEntity<Result<Void>> responseAligned = advice.handleBlockException(ex, request);
        assertThat(responseAligned.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(responseAligned.getBody()).isNotNull();
        assertThat(responseAligned.getBody().getCode()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());

        // 2. blockAdvice 关闭状态码对齐，返回 HTTP 200 OK
        properties.getBlockAdvice().setHttpStatusAlign(false);
        ResponseEntity<Result<Void>> responseOk = advice.handleBlockException(ex, request);
        assertThat(responseOk.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseOk.getBody()).isNotNull();
        assertThat(responseOk.getBody().getCode()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("阻断计数器联动：验证 @SentinelResource 切面兜底拦截时计数器递增且快照准确")
    void testBlockCounterLinkage() {
        SentinelBlockCounter counter = new SentinelBlockCounter();
        SentinelBlockExceptionAdvice adviceWithCounter = new SentinelBlockExceptionAdvice(properties, counter);

        DegradeException ex = new DegradeException("circuit-breaker-open");
        adviceWithCounter.handleBlockException(ex, request);

        assertThat(counter.getTotalBlocked()).isEqualTo(1);
        assertThat(counter.getDegradeCount()).isEqualTo(1);
        assertThat(counter.getLastBlockEvent()).isNotNull();
        assertThat(counter.getLastBlockEvent().ruleType()).isEqualTo("DEGRADE");
        assertThat(counter.getLastBlockEvent().clientIp()).isEqualTo("192.168.1.100");
    }
}
