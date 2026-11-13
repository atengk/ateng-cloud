package io.github.atengk.cloud.sentinel.advice;

import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import io.github.atengk.cloud.sentinel.endpoint.SentinelBlockCounter;
import io.github.atengk.cloud.sentinel.fallback.AtengFallbackFactory;
import io.github.atengk.cloud.sentinel.parser.AtengRequestOriginParser;
import io.github.atengk.cloud.sentinel.properties.AtengSentinelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketTimeoutException;

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
        MDC.clear();
        properties = new AtengSentinelProperties();
        advice = new SentinelBlockExceptionAdvice(properties);
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/resource/test");
        request.setRemoteAddr("192.168.1.100");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
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
        assertThat(response.getHeaders().getFirst("X-Sentinel-Block-Rule")).isEqualTo("PARAM_FLOW");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.PARAM_FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("直接捕获 SystemBlockException：返回 HTTP 429 与 SYSTEM_BLOCK(4204)")
    void testHandleSystemBlockException() {
        SystemBlockException ex = new SystemBlockException("heavy-calc", "load over");
        ResponseEntity<Result<Void>> response = advice.handleBlockException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("X-Sentinel-Block-Rule")).isEqualTo("SYSTEM");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.SYSTEM_BLOCK.getCode());
    }

    @Test
    @DisplayName("直接捕获 AuthorityException：返回 HTTP 429 与 AUTHORITY_BLOCK(4205)")
    void testHandleAuthorityException() {
        AuthorityException ex = new AuthorityException("admin-resource");
        ResponseEntity<Result<Void>> response = advice.handleBlockException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("X-Sentinel-Block-Rule")).isEqualTo("AUTHORITY");
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

    @Test
    @DisplayName("切面阻断响应标头与溯源：输出 X-Sentinel-Block-Rule、可选 Retry-After 及 traceId/origin")
    void testAdviceBlockHeadersAndTraceOrigin() {
        MDC.put("traceId", "trace-advice-002");
        properties.getBlockAdvice().setRetryAfter(java.time.Duration.ofSeconds(15));

        SentinelBlockCounter counter = new SentinelBlockCounter();
        AtengRequestOriginParser originParser = new AtengRequestOriginParser(properties);
        SentinelBlockExceptionAdvice fullAdvice = new SentinelBlockExceptionAdvice(properties, counter, originParser);
        request.addHeader("X-Origin-Service", "order-web");

        ResponseEntity<Result<Void>> response = fullAdvice.handleBlockException(new FlowException("flow"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("X-Sentinel-Block-Rule")).isEqualTo("FLOW");
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("15");

        assertThat(counter.getLastBlockEvent()).isNotNull();
        assertThat(counter.getLastBlockEvent().traceId()).isEqualTo("trace-advice-002");
        assertThat(counter.getLastBlockEvent().origin()).isEqualTo("order-web");
    }

    @Test
    @DisplayName("捕获反射包装异常 InvocationTargetException(FlowException)：成功解包返回 429")
    void testHandleInvocationTargetExceptionWithBlockException() throws Throwable {
        InvocationTargetException ite = new InvocationTargetException(new ParamFlowException("res", "p1"));

        ResponseEntity<Result<Void>> response = advice.handleInvocationTargetException(ite, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("X-Sentinel-Block-Rule")).isEqualTo("PARAM_FLOW");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(SentinelResultCode.PARAM_FLOW_LIMIT.getCode());
    }

    @Test
    @DisplayName("捕获携带 SentinelResultCode 的 BusinessException：对齐 HTTP 429/503 与响应头并防止重复计数")
    void testHandleSentinelBusinessExceptionAndDedupCounter() throws Throwable {
        properties.getBlockAdvice().setRetryAfter(java.time.Duration.ofSeconds(10));
        SentinelBlockCounter counter = new SentinelBlockCounter();
        SentinelBlockExceptionAdvice fullAdvice = new SentinelBlockExceptionAdvice(properties, counter);

        // 1. 通过源头无计数器降级厂触发异常，Advice 负责补录 1 次计数并输出 HTTP 503
        UnwiredFallbackFactory unwiredFactory = new UnwiredFallbackFactory();
        DummyClient unwiredClient = unwiredFactory.create(new SocketTimeoutException("read timeout"));
        BusinessException uncountedEx = null;
        try {
            unwiredClient.call();
        } catch (BusinessException ex) {
            uncountedEx = ex;
        }
        assertThat(uncountedEx).isNotNull();
        ResponseEntity<Result<Void>> response1 = fullAdvice.handleBusinessException(uncountedEx, request);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response1.getHeaders().getFirst("X-Sentinel-Block-Rule")).isEqualTo("DEGRADE");
        assertThat(response1.getHeaders().getFirst("Retry-After")).isEqualTo("10");
        assertThat(counter.getTotalBlocked()).isEqualTo(1);

        // 2. 通过已注入同一计数器的降级厂源头计数后，Advice 命中防重守卫不再重复累加
        UnwiredFallbackFactory wiredFactory = new UnwiredFallbackFactory();
        wiredFactory.setBlockCounter(counter);
        DummyClient wiredClient = wiredFactory.create(new FlowException("client flow limit"));
        BusinessException alreadyCountedEx = null;
        try {
            wiredClient.call();
        } catch (BusinessException ex) {
            alreadyCountedEx = ex;
        }
        assertThat(alreadyCountedEx).isNotNull();
        ResponseEntity<Result<Void>> response2 = fullAdvice.handleBusinessException(alreadyCountedEx, request);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response2.getHeaders().getFirst("X-Sentinel-Block-Rule")).isEqualTo("FLOW");
        assertThat(counter.getTotalBlocked()).isEqualTo(2);
    }

    @Test
    @DisplayName("普通 BusinessException：直接调用 handleBusinessException 原样抛出，resolveException 返回 null 交由全局切面")
    void testOrdinaryBusinessExceptionPassthrough() {
        BusinessException ordinary = new BusinessException(10001, "账户余额不足");

        assertThatThrownBy(() -> advice.handleBusinessException(ordinary, request))
                .isSameAs(ordinary);
        assertThat(advice.resolveException(request, new MockHttpServletResponse(), null, ordinary)).isNull();
    }

    @Test
    @DisplayName("代理/反射二次包装源头已计数 TrackedFallbackBusinessException：resolveException 优先命中防重守卫不重复累加")
    void testWrappedTrackedFallbackBusinessExceptionDoesNotDoubleCount() {
        SentinelBlockCounter counter = new SentinelBlockCounter();
        SentinelBlockExceptionAdvice fullAdvice = new SentinelBlockExceptionAdvice(properties, counter);
        UnwiredFallbackFactory wiredFactory = new UnwiredFallbackFactory();
        wiredFactory.setBlockCounter(counter);

        DummyClient wiredClient = wiredFactory.create(new FlowException("client-flow"));
        BusinessException tracked = null;
        try {
            wiredClient.call();
        } catch (BusinessException ex) {
            tracked = ex;
        }
        assertThat(tracked).isNotNull();
        assertThat(counter.getTotalBlocked()).isEqualTo(1);

        UndeclaredThrowableException ute = new UndeclaredThrowableException(tracked);
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        assertThat(fullAdvice.resolveException(request, resp1, null, ute)).isNotNull();
        assertThat(resp1.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(counter.getTotalBlocked()).isEqualTo(1);

        InvocationTargetException ite = new InvocationTargetException(tracked);
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        assertThat(fullAdvice.resolveException(request, resp2, null, ite)).isNotNull();
        assertThat(resp2.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(counter.getTotalBlocked()).isEqualTo(1);
    }

    interface DummyClient {
        String call();
    }

    static class UnwiredFallbackFactory extends AtengFallbackFactory<DummyClient> {
    }
}
