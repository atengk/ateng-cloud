package io.github.atengk.cloud.dubbo.overload;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dubbo 服务端线程池过载拒绝执行处理器
 * <p>
 * 定制线程池拒绝执行策略 ({@link RejectedExecutionHandler})：
 * 1. 线程池耗尽打满触发拒绝时，打印关键过载诊断日志；
 * 2. 累加 Micrometer 监控指标；
 * 3. 抛出微服务架构标准受管 {@link BusinessException} (FLOW_LIMIT / 429)，实现快速失败与优雅熔断防雪崩。
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class DubboOverloadHandler implements RejectedExecutionHandler {

    private static final Logger log = LoggerFactory.getLogger(DubboOverloadHandler.class);

    private final String threadPoolName;

    public DubboOverloadHandler() {
        this("DubboServerHandler");
    }

    public DubboOverloadHandler(String threadPoolName) {
        this.threadPoolName = threadPoolName != null ? threadPoolName : "DubboServerHandler";
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        long currentRejectCount = DubboOverloadListener.incrementRejectCount();
        log.error("[DUBBO-OVERLOAD] Provider 线程池打满触发过载保护: poolName={}, active={}, poolSize={}, core={}, max={}, queueSize={}, completed={}, totalRejects={}",
                threadPoolName,
                executor.getActiveCount(),
                executor.getPoolSize(),
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getQueue() != null ? executor.getQueue().size() : 0,
                executor.getCompletedTaskCount(),
                currentRejectCount);

        throw new BusinessException(
                CommonResultCode.TOO_MANY_REQUESTS.getCode(),
                "服务端并发线程池已耗尽，触发过载保护限流"
        );
    }
}
