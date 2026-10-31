package io.github.atengk.cloud.seata.context;

import io.github.atengk.boot.core.context.AsyncContextSnapshotProvider;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/**
 * Seata 全局事务异步任务快照提供者
 * <p>
 * 深度接入 {@code ateng-boot-core} 的异步线程池装饰器设施 ({@code ContextCopyingTaskDecorator})，
 * 在向受管线程池提交异步任务时自动快照当前线程的 Seata 全局事务 XID，
 * 在工作子线程中无缝恢复绑定，并同步维护 SLF4J MDC 黄金关联 ({@code xid})；
 * 在任务执行完毕后安全清理与解绑，彻底杜绝多线程任务 XID 丢失断流与线程池污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataAsyncContextSnapshotProvider implements AsyncContextSnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(SeataAsyncContextSnapshotProvider.class);

    @Override
    public Object capture() {
        return RootContext.getXID();
    }

    @Override
    public void apply(Object snapshot) {
        applyOrClear(snapshot);
    }

    @Override
    public void restore(Object previous) {
        applyOrClear(previous);
    }

    private void applyOrClear(Object xidSnapshot) {
        if (xidSnapshot instanceof String xid && StringUtils.hasText(xid)) {
            RootContext.bind(xid);
            MDC.put(SeataConstants.MDC_KEY_XID, xid);
            log.trace("异步子线程成功恢复 Seata 全局事务 XID 与 MDC 黄金关联: [{}]", xid);
        } else {
            RootContext.unbind();
            MDC.remove(SeataConstants.MDC_KEY_XID);
        }
    }
}
