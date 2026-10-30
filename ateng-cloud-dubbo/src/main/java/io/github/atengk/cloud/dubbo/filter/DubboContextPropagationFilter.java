package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.dubbo.support.DubboSeataPropagationSupport;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Dubbo 全链路四维上下文透传与 Netty 线程池确定性清理过滤器
 * <p>
 * 遵循微服务全链路上下文透明透传铁律：
 * 1. 消费端出站：无感提取当前线程的四维上下文快照（MDC traceId、userId、tenantId、ignoreTenant）及金丝雀流量染色标记 (X-Traffic-Tag)，注入 RPC Attachment；
 * 2. 服务端入站：从 RPC Attachment 还原四维上下文至当前工作线程；
 * 3. 确定性生命周期清理：在执行结束的 finally 块中绝对可靠清理，彻底杜绝 Netty/Dubbo 线程池复用污染与多租户越权。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = {CommonConstants.CONSUMER, CommonConstants.PROVIDER}, order = -10000)
public class DubboContextPropagationFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboContextPropagationFilter.class);

    public static final String TRAFFIC_TAG = "X-Traffic-Tag";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && (!properties.isEnabled()
                || (properties.getContext() != null && !properties.getContext().isEnabled()))) {
            return invoker.invoke(invocation);
        }

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
            boolean boundXid = false;
            try {
                boundXid = handleProvider(invocation);
                return invoker.invoke(invocation);
            } finally {
                cleanContexts(boundXid);
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
        // 1. 提取并注入分布式链路追踪 Trace ID
        String traceId = MDC.get("traceId");
        if (!StringUtils.hasText(traceId)) {
            traceId = MDC.get(MessageHeaderConstants.TRACE_ID);
        }
        setAttachmentIfValid(invocation, MessageHeaderConstants.TRACE_ID, traceId);

        // 2. 提取并注入当前登录用户 ID
        String userId = UserContextHolder.getUserId();
        setAttachmentIfValid(invocation, MessageHeaderConstants.USER_ID, userId);

        // 3. 提取并注入当前租户 ID
        String tenantId = TenantContextHolder.getTenantId();
        setAttachmentIfValid(invocation, MessageHeaderConstants.TENANT_ID, tenantId);

        // 4. 提取并注入忽略租户标记 (仅当为 true 时显式透传)
        if (TenantContextHolder.isIgnoreTenant()) {
            setAttachmentIfValid(invocation, MessageHeaderConstants.IGNORE_TENANT, Boolean.TRUE.toString());
        }

        // 5. 提取并注入金丝雀流量染色标记
        String trafficTag = MDC.get(TRAFFIC_TAG);
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = MDC.get("trafficTag");
        }
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = MDC.get("traffic-tag");
        }
        setAttachmentIfValid(invocation, TRAFFIC_TAG, trafficTag);

        // 6. 提取并注入 Seata 全局分布式事务 XID
        String xid = DubboSeataPropagationSupport.getXID();
        setAttachmentIfValid(invocation, DubboSeataPropagationSupport.TX_XID, xid);
    }

    private boolean handleProvider(Invocation invocation) {
        // 1. 还原分布式调用链 Trace ID
        String traceId = getAttachment(invocation, MessageHeaderConstants.TRACE_ID);
        if (!StringUtils.hasText(traceId)) {
            traceId = getAttachment(invocation, "traceId");
        }
        if (StringUtils.hasText(traceId)) {
            MDC.put("traceId", traceId);
            MDC.put(MessageHeaderConstants.TRACE_ID, traceId);
        }

        // 2. 还原当前登录用户 ID
        String userId = getAttachment(invocation, MessageHeaderConstants.USER_ID);
        if (StringUtils.hasText(userId)) {
            UserContextHolder.setUserId(userId);
        }

        // 3. 还原当前多租户 ID
        String tenantId = getAttachment(invocation, MessageHeaderConstants.TENANT_ID);
        if (StringUtils.hasText(tenantId)) {
            TenantContextHolder.setTenantId(tenantId);
        }

        // 4. 还原忽略租户标记
        String ignoreTenant = getAttachment(invocation, MessageHeaderConstants.IGNORE_TENANT);
        if ("true".equalsIgnoreCase(ignoreTenant)) {
            TenantContextHolder.setIgnoreTenant(true);
        }

        // 5. 还原流量染色路由标记
        String trafficTag = getAttachment(invocation, TRAFFIC_TAG);
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = getAttachment(invocation, "trafficTag");
        }
        if (StringUtils.hasText(trafficTag)) {
            MDC.put(TRAFFIC_TAG, trafficTag);
            MDC.put("trafficTag", trafficTag);
        }

        // 6. 还原 Seata 分布式事务 XID 并执行安全绑定
        String xid = getAttachment(invocation, DubboSeataPropagationSupport.TX_XID);
        if (!StringUtils.hasText(xid)) {
            xid = getAttachment(invocation, DubboSeataPropagationSupport.TX_XID_LOWERCASE);
        }
        return DubboSeataPropagationSupport.bind(xid);
    }

    private void cleanContexts(boolean boundXid) {
        DubboSeataPropagationSupport.unbind(boundXid);
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    private void setAttachmentIfValid(Invocation invocation, String key, String value) {
        if (!StringUtils.hasText(value) || "null".equalsIgnoreCase(value.trim())) {
            return;
        }
        String trimmed = value.trim();
        RpcContext.getClientAttachment().setAttachment(key, trimmed);
        if (invocation instanceof RpcInvocation rpcInvocation) {
            rpcInvocation.setAttachment(key, trimmed);
        }
    }

    private String getAttachment(Invocation invocation, String key) {
        String value = invocation.getAttachment(key);
        if (!StringUtils.hasText(value)) {
            value = invocation.getAttachment(key.toLowerCase(Locale.ROOT));
        }
        if (!StringUtils.hasText(value)) {
            value = RpcContext.getServerAttachment().getAttachment(key);
        }
        if (!StringUtils.hasText(value)) {
            value = RpcContext.getServerAttachment().getAttachment(key.toLowerCase(Locale.ROOT));
        }
        return value;
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        if (!isConsumerSide(invoker)) {
            cleanContexts(false);
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        if (!isConsumerSide(invoker)) {
            cleanContexts(false);
        }
    }
}
