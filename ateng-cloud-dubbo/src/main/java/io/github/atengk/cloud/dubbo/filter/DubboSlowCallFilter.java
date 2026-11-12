package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboSlowCallProperties;
import io.github.atengk.cloud.dubbo.support.DubboAttachmentSupport;
import io.github.atengk.cloud.dubbo.support.DubboExceptionUtils;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.dubbo.support.DubboSlowCallMetrics;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.RpcUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dubbo RPC Slow Call Detection 慢调用监测与结构化告警过滤器
 * <p>
 * 1. 自动豁免 {@code injvm} 本地短路调用与 {@code StreamObserver} 长连接流式调用（Stream Passthrough）；
 * 2. 对单次 Unary RPC 同步与异步响应精准计时（包含 {@code onResponse} / {@code onError} 回调阶段）；
 * 3. 当实际耗时达到或超过配置阈值时，输出包含 {@code side}、{@code service}、{@code method}、
 *    {@code elapsedMs}、{@code thresholdMs}、{@code traceId} 的结构化 {@code WARN} 日志并累加
 *    Micrometer {@code dubbo.rpc.slow.calls} 指标。
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Activate(group = {CommonConstants.CONSUMER, CommonConstants.PROVIDER}, order = -9200)
public class DubboSlowCallFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboSlowCallFilter.class);

    private static final String SLOW_CALL_CONTEXT_KEY = DubboSlowCallFilter.class.getName() + ".CONTEXT";

    private static final Duration DEFAULT_THRESHOLD = Duration.ofMillis(3000);

    private static final boolean MICROMETER_PRESENT = ClassUtils.isPresent(
            "io.micrometer.core.instrument.MeterRegistry", DubboSlowCallFilter.class.getClassLoader());

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        AtengDubboProperties properties = DubboPropertiesHolder.get();
        AtengDubboSlowCallProperties slowCallProps = properties != null ? properties.getSlowCall() : null;
        if (!isFeatureEnabled(properties, slowCallProps)) {
            return invoker.invoke(invocation);
        }

        long thresholdMs = resolvePositiveThresholdMs(slowCallProps);
        if (thresholdMs <= 0) {
            return invoker.invoke(invocation);
        }

        if (DubboAttachmentSupport.isInjvmProtocol(invoker) || DubboExceptionUtils.isStreamInvocation(invocation)) {
            return invoker.invoke(invocation);
        }

        boolean consumerSide = DubboAttachmentSupport.isConsumerSide(invoker);
        if (!isSideEnabled(slowCallProps, consumerSide)) {
            return invoker.invoke(invocation);
        }

        SlowCallContext ctx = new SlowCallContext(
                System.nanoTime(),
                thresholdMs,
                consumerSide ? CommonConstants.CONSUMER_SIDE : CommonConstants.PROVIDER_SIDE,
                resolveServiceName(invoker, invocation),
                resolveMethodName(invocation),
                resolveTraceId(invocation, consumerSide)
        );
        if (invocation != null) {
            invocation.put(SLOW_CALL_CONTEXT_KEY, ctx);
        }

        try {
            Result result = invoker.invoke(invocation);
            if (isCompletedResult(result)) {
                reportIfSlow(ctx);
            }
            return result;
        } catch (Throwable ex) {
            reportIfSlow(ctx);
            throw ex;
        }
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        reportFromInvocation(invocation);
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        reportFromInvocation(invocation);
    }

    private void reportFromInvocation(Invocation invocation) {
        if (invocation == null) {
            return;
        }
        Object raw = invocation.get(SLOW_CALL_CONTEXT_KEY);
        if (raw instanceof SlowCallContext ctx) {
            reportIfSlow(ctx);
        }
    }

    private void reportIfSlow(SlowCallContext ctx) {
        if (!ctx.reported.compareAndSet(false, true)) {
            return;
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ctx.startNanos);
        if (elapsedMs < ctx.thresholdMs) {
            return;
        }
        log.warn("[DUBBO-SLOW-CALL] RPC 调用耗时过长: side={}, service={}, method={}, elapsedMs={}, thresholdMs={}, traceId={}",
                ctx.side, ctx.serviceName, ctx.methodName, elapsedMs, ctx.thresholdMs, ctx.traceId);
        if (MICROMETER_PRESENT) {
            DubboSlowCallMetrics.recordSlowCall(ctx.side, ctx.serviceName, ctx.methodName);
        }
    }

    private boolean isFeatureEnabled(AtengDubboProperties properties, AtengDubboSlowCallProperties slowCallProps) {
        if (properties != null && !properties.isEnabled()) {
            return false;
        }
        return slowCallProps == null || slowCallProps.isEnabled();
    }

    private boolean isSideEnabled(AtengDubboSlowCallProperties slowCallProps, boolean consumerSide) {
        if (slowCallProps == null) {
            return true;
        }
        return consumerSide ? slowCallProps.isIncludeConsumer() : slowCallProps.isIncludeProvider();
    }

    private long resolvePositiveThresholdMs(AtengDubboSlowCallProperties slowCallProps) {
        Duration threshold = slowCallProps != null ? slowCallProps.getThreshold() : DEFAULT_THRESHOLD;
        if (threshold == null) {
            return 0L;
        }
        return threshold.toMillis();
    }

    private boolean isCompletedResult(Result result) {
        if (result instanceof AsyncRpcResult asyncResult) {
            return asyncResult.getResponseFuture() == null || asyncResult.getResponseFuture().isDone();
        }
        return true;
    }

    private String resolveServiceName(Invoker<?> invoker, Invocation invocation) {
        if (invoker != null && invoker.getInterface() != null) {
            return invoker.getInterface().getName();
        }
        if (invocation != null && StringUtils.hasText(invocation.getServiceName())) {
            return invocation.getServiceName();
        }
        return "unknown";
    }

    private String resolveMethodName(Invocation invocation) {
        if (invocation == null) {
            return "unknown";
        }
        String methodName = RpcUtils.getMethodName(invocation);
        return StringUtils.hasText(methodName) ? methodName : "unknown";
    }

    private String resolveTraceId(Invocation invocation, boolean consumerSide) {
        String traceId = MDC.get("traceId");
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        traceId = MDC.get(MessageHeaderConstants.TRACE_ID);
        if (StringUtils.hasText(traceId)) {
            return traceId;
        }
        if (invocation != null) {
            traceId = getFirstAttachment(invocation, MessageHeaderConstants.TRACE_ID, "traceId");
            if (StringUtils.hasText(traceId)) {
                return traceId;
            }
        }
        var attachment = consumerSide ? RpcContext.getClientAttachment() : RpcContext.getServerAttachment();
        if (attachment != null) {
            traceId = attachment.getAttachment(MessageHeaderConstants.TRACE_ID);
            if (!StringUtils.hasText(traceId)) {
                traceId = attachment.getAttachment("traceId");
            }
            if (StringUtils.hasText(traceId)) {
                return traceId;
            }
        }
        return "N/A";
    }

    private String getFirstAttachment(Invocation invocation, String primaryKey, String secondaryKey) {
        String value = invocation.getAttachment(primaryKey);
        if (!StringUtils.hasText(value)) {
            value = invocation.getAttachment(primaryKey.toLowerCase(Locale.ROOT));
        }
        if (!StringUtils.hasText(value)) {
            value = invocation.getAttachment(secondaryKey);
        }
        return value;
    }

    private static final class SlowCallContext {
        private final long startNanos;
        private final long thresholdMs;
        private final String side;
        private final String serviceName;
        private final String methodName;
        private final String traceId;
        private final AtomicBoolean reported = new AtomicBoolean(false);

        private SlowCallContext(long startNanos, long thresholdMs, String side,
                                String serviceName, String methodName, String traceId) {
            this.startNanos = startNanos;
            this.thresholdMs = thresholdMs;
            this.side = side;
            this.serviceName = serviceName;
            this.methodName = methodName;
            this.traceId = traceId;
        }
    }
}
