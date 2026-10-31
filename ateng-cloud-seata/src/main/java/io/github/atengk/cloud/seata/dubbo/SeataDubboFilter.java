package io.github.atengk.cloud.seata.dubbo;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Dubbo 3.3 RPC 全局事务 XID 透明透传与自适应幂等防卫过滤器
 * <p>
 * 遵循微服务 7 大架构铁律之二（全链路跨进程透明穿透与严格清理）：
 * 1. 消费端出站：无感提取当前线程的 Seata 全局分布式事务 XID，注入 RPC Attachment (TX_XID)；
 * 2. 服务端入站：从 RPC Attachment 还原全局事务 XID 并绑定至当前工作线程 RootContext；
 * 3. 自适应幂等防卫：若检测到当前线程已被外部过滤器（如 ateng-cloud-dubbo 的 DubboContextPropagationFilter）
 *    绑定了相同 XID，则静默放行并不参与二次解绑，杜绝多 Filter 并存时的重复绑定与次生提前解绑；
 * 4. 确定性生命周期清理：在服务端出口的 finally 块中依据本次入站绑定状态确定性解绑，彻底杜绝 Netty 线程池污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Activate(group = {CommonConstants.CONSUMER, CommonConstants.PROVIDER}, order = -9000)
public class SeataDubboFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SeataDubboFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 本地短路调用 (injvm 进程内内存直接调用) 上下文天然共享，直接执行
        if (invoker != null && invoker.getUrl() != null
                && "injvm".equalsIgnoreCase(invoker.getUrl().getProtocol())) {
            return invoker.invoke(invocation);
        }

        boolean isConsumer = isConsumerSide(invoker);
        if (isConsumer) {
            handleConsumer(invocation);
            return invoker.invoke(invocation);
        } else {
            boolean bound = false;
            try {
                bound = handleProvider(invocation);
                return invoker.invoke(invocation);
            } finally {
                cleanContext(bound);
            }
        }
    }

    private boolean isConsumerSide(Invoker<?> invoker) {
        if (invoker != null && invoker.getUrl() != null) {
            String side = invoker.getUrl().getParameter(CommonConstants.SIDE_KEY);
            if (CommonConstants.CONSUMER_SIDE.equalsIgnoreCase(side)) {
                return true;
            }
            if (CommonConstants.PROVIDER_SIDE.equalsIgnoreCase(side)) {
                return false;
            }
        }
        return RpcContext.getContext().isConsumerSide();
    }

    private void handleConsumer(Invocation invocation) {
        String xid = RootContext.getXID();
        if (StringUtils.hasText(xid)) {
            if (invocation instanceof RpcInvocation rpcInvocation) {
                rpcInvocation.setAttachment(SeataConstants.TX_XID, xid);
            }
            RpcContext.getClientAttachment().setAttachment(SeataConstants.TX_XID, xid);
            if (log.isDebugEnabled()) {
                log.debug("[SEATA-DUBBO] 消费端成功注入全局事务 XID: [{}]", xid);
            }
        }
    }

    private boolean handleProvider(Invocation invocation) {
        String rpcXid = getAttachment(invocation, SeataConstants.TX_XID);
        if (!StringUtils.hasText(rpcXid)) {
            rpcXid = getAttachment(invocation, "tx_xid");
        }
        if (!StringUtils.hasText(rpcXid) || "null".equalsIgnoreCase(rpcXid.trim())) {
            return false;
        }

        String trimmedXid = rpcXid.trim();
        String currentXid = RootContext.getXID();

        // 1. 若当前线程尚未绑定 XID，则由本过滤器安全绑定并标记 bound = true
        if (currentXid == null) {
            RootContext.bind(trimmedXid);
            if (log.isDebugEnabled()) {
                log.debug("[SEATA-DUBBO] 服务端成功为当前线程绑定全局事务 XID: [{}]", trimmedXid);
            }
            return true;
        }

        // 2. 自适应幂等防卫：若外部环境已经绑定了相同的 XID，静默放行，标记 bound = false 避免二次解绑
        if (trimmedXid.equalsIgnoreCase(currentXid)) {
            log.trace("[SEATA-DUBBO] 当前线程已被外部环境绑定 XID [{}], 激活自适应幂等防卫，静默放行", currentXid);
            return false;
        }

        // 3. 冲突提示：当前线程已存在不同 XID
        log.warn("[SEATA-DUBBO] 入站请求携带 XID [{}] 与当前线程已绑定 XID [{}] 冲突，跳过重新绑定",
                trimmedXid, currentXid);
        return false;
    }

    private void cleanContext(boolean bound) {
        if (!bound) {
            return;
        }
        try {
            RootContext.unbind();
            if (log.isDebugEnabled()) {
                log.debug("[SEATA-DUBBO] 服务端出口成功对称解绑当前线程全局事务 XID");
            }
        } catch (Throwable t) {
            log.warn("[SEATA-DUBBO] 服务端出口对称解绑 Seata 全局事务 XID 异常: {}", t.getMessage());
        }
    }

    private String getAttachment(Invocation invocation, String key) {
        if (invocation == null || key == null) {
            return null;
        }
        String value = invocation.getAttachment(key);
        if (StringUtils.hasText(value)) {
            return value;
        }
        var serverAttachment = RpcContext.getServerAttachment();
        if (serverAttachment != null) {
            value = serverAttachment.getAttachment(key);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
