package io.github.atengk.cloud.dubbo.overload;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadpool.ThreadPool;
import org.apache.dubbo.common.threadpool.support.fixed.FixedThreadPool;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 挂载 DubboOverloadHandler 的生产级过载保护线程池实现 (Dubbo ThreadPool SPI)
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class DubboOverloadThreadPool extends FixedThreadPool implements ThreadPool {

    @Override
    public Executor getExecutor(URL url) {
        Executor executor = super.getExecutor(url);
        if (executor instanceof ThreadPoolExecutor threadPoolExecutor) {
            String poolName = url != null ? url.getParameter("threadname", "DubboServerHandler") : "DubboServerHandler";
            threadPoolExecutor.setRejectedExecutionHandler(new DubboOverloadHandler(poolName));
        }
        return executor;
    }
}
