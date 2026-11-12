package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.support.DubboAttachmentSupport;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.dubbo.support.DubboSeataPropagationSupport;
import io.github.atengk.cloud.dubbo.support.DubboTrafficPropagationSupport;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
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

    private static final String[] CONSUMER_MANAGED_ATTACHMENT_KEYS = {
            MessageHeaderConstants.TRACE_ID,
            MessageHeaderConstants.USER_ID,
            MessageHeaderConstants.TENANT_ID,
            MessageHeaderConstants.IGNORE_TENANT,
            TRAFFIC_TAG,
            DubboTrafficPropagationSupport.TRAFFIC_LANE,
            DubboTrafficPropagationSupport.TRAFFIC_RULE,
            DubboSeataPropagationSupport.TX_XID,
            DubboSeataPropagationSupport.TX_XID_LOWERCASE
    };

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (!isContextEnabled(properties) || DubboAttachmentSupport.isInjvmProtocol(invoker)) {
            return invoker.invoke(invocation);
        }

        boolean isConsumer = DubboAttachmentSupport.isConsumerSide(invoker);
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

    private boolean isContextEnabled(AtengDubboProperties properties) {
        return properties == null
                || (properties.isEnabled() && (properties.getContext() == null || properties.getContext().isEnabled()));
    }

    private void handleConsumer(Invocation invocation) {
        // 1. 对称对齐分布式链路追踪 Trace ID
        String traceId = DubboAttachmentSupport.resolveMdcTraceId();
        DubboAttachmentSupport.reconcileOutboundAttachment(invocation, MessageHeaderConstants.TRACE_ID, traceId);

        // 2. 对称对齐当前登录用户 ID 与当前租户 ID
        DubboAttachmentSupport.reconcileOutboundAttachment(
                invocation, MessageHeaderConstants.USER_ID, UserContextHolder.getUserId());
        DubboAttachmentSupport.reconcileOutboundAttachment(
                invocation, MessageHeaderConstants.TENANT_ID, TenantContextHolder.getTenantId());

        // 3. 对称对齐忽略租户标记 (未开启时必须主动清除前次或预填残留)
        String ignoreTenant = TenantContextHolder.isIgnoreTenant() ? Boolean.TRUE.toString() : null;
        DubboAttachmentSupport.reconcileOutboundAttachment(invocation, MessageHeaderConstants.IGNORE_TENANT, ignoreTenant);

        // 4. 对称对齐金丝雀流量染色标记与泳道上下文快照
        // 当本地线程无有效流量标签时清理 ClientAttachment 残留，同时仅清洗从 ClientAttachment 镜像拷贝的旧值或无效占位符，
        // 保留调用方在本次 Invocation 显式指定的有效 X-Traffic-Tag 供下游 DubboTrafficTagFilter(-8500) 解析路由。
        var trafficSnapshot = DubboTrafficPropagationSupport.getSnapshot();
        String trafficTag = (trafficSnapshot != null && trafficSnapshot.hasTag()) ? trafficSnapshot.tag() : null;
        String trafficLane = (trafficSnapshot != null && trafficSnapshot.hasLane()) ? trafficSnapshot.laneName() : null;
        String trafficRule = (trafficSnapshot != null && trafficSnapshot.isValid()) ? trafficSnapshot.matchedRule() : null;
        if (DubboAttachmentSupport.isValidAttachmentValue(trafficTag)) {
            DubboAttachmentSupport.setOutboundAttachment(invocation, TRAFFIC_TAG, trafficTag.trim());
        } else {
            boolean echoedFromClient = DubboAttachmentSupport.isEchoedClientAttachment(invocation, TRAFFIC_TAG);
            DubboAttachmentSupport.removeClientAttachments(TRAFFIC_TAG);
            String invTrafficTag = invocation != null ? invocation.getAttachment(TRAFFIC_TAG) : null;
            if (echoedFromClient
                    || (invTrafficTag != null && !DubboAttachmentSupport.isValidAttachmentValue(invTrafficTag))) {
                DubboAttachmentSupport.removeOutboundAttachment(invocation, TRAFFIC_TAG);
            }
        }
        DubboAttachmentSupport.reconcileOutboundAttachment(
                invocation, DubboTrafficPropagationSupport.TRAFFIC_LANE, trafficLane);
        DubboAttachmentSupport.reconcileOutboundAttachment(
                invocation, DubboTrafficPropagationSupport.TRAFFIC_RULE, trafficRule);

        // 5. 对称对齐 Seata 全局分布式事务 XID（出站统一收敛至规范键 TX_XID 并无条件清洗小写兼容键）
        String xid = DubboSeataPropagationSupport.getXID();
        DubboAttachmentSupport.reconcileOutboundAttachment(invocation, DubboSeataPropagationSupport.TX_XID, xid);
        DubboAttachmentSupport.removeOutboundAttachment(invocation, DubboSeataPropagationSupport.TX_XID_LOWERCASE);
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

        // 5. 还原流量染色路由标记与泳道上下文
        String trafficTag = getAttachment(invocation, TRAFFIC_TAG);
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = getAttachment(invocation, "trafficTag");
        }
        if (!StringUtils.hasText(trafficTag)) {
            trafficTag = getAttachment(invocation, CommonConstants.TAG_KEY);
        }
        String trafficLane = getAttachment(invocation, DubboTrafficPropagationSupport.TRAFFIC_LANE);
        String trafficRule = getAttachment(invocation, DubboTrafficPropagationSupport.TRAFFIC_RULE);
        DubboTrafficPropagationSupport.restore(trafficTag, trafficLane, trafficRule);

        // 6. 还原 Seata 分布式事务 XID 并执行安全绑定
        String xid = getAttachment(invocation, DubboSeataPropagationSupport.TX_XID);
        if (!StringUtils.hasText(xid)) {
            xid = getAttachment(invocation, DubboSeataPropagationSupport.TX_XID_LOWERCASE);
        }
        return DubboSeataPropagationSupport.bind(xid);
    }

    private void cleanContexts(boolean boundXid) {
        DubboSeataPropagationSupport.unbind(boundXid);
        DubboTrafficPropagationSupport.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.remove("traceId");
        MDC.remove(MessageHeaderConstants.TRACE_ID);
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
        cleanupCallback(invoker);
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        cleanupCallback(invoker);
    }

    private void cleanupCallback(Invoker<?> invoker) {
        if (!isContextEnabled(DubboPropertiesHolder.get()) || DubboAttachmentSupport.isInjvmProtocol(invoker)) {
            return;
        }
        if (DubboAttachmentSupport.isConsumerSide(invoker)) {
            DubboAttachmentSupport.removeClientAttachments(CONSUMER_MANAGED_ATTACHMENT_KEYS);
        } else {
            cleanContexts(false);
        }
    }
}
