package io.github.atengk.cloud.dubbo.support;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.service.GenericService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * Dubbo 出站附件对称对齐与生命周期清理内部辅助工具
 *
 * @author Ateng
 * @since 2026-11-12
 */
public final class DubboAttachmentSupport {

    private static final Logger log = LoggerFactory.getLogger(DubboAttachmentSupport.class);

    private DubboAttachmentSupport() {
    }

    /**
     * 判断当前调用是否为进程内 injvm 短路协议
     *
     * @param invoker Dubbo 调用器
     * @return true 表示 injvm 协议，false 表示非 injvm 或无法识别
     */
    public static boolean isInjvmProtocol(Invoker<?> invoker) {
        return invoker != null
                && invoker.getUrl() != null
                && "injvm".equalsIgnoreCase(invoker.getUrl().getProtocol());
    }

    /**
     * 安全判定当前节点是否处于 Consumer 侧（防御 RpcServiceContext URL 为空时的原生 NPE）
     *
     * @param invoker Dubbo 调用器
     * @return true 表示消费端出站，false 表示服务端入站或上下文未绑定
     */
    public static boolean isConsumerSide(Invoker<?> invoker) {
        if (invoker != null && invoker.getUrl() != null) {
            String side = invoker.getUrl().getParameter(CommonConstants.SIDE_KEY);
            if (CommonConstants.CONSUMER_SIDE.equalsIgnoreCase(side)) {
                return true;
            }
            if (CommonConstants.PROVIDER_SIDE.equalsIgnoreCase(side)) {
                return false;
            }
        }
        return RpcContext.getServiceContext().getUrl() != null && RpcContext.getContext().isConsumerSide();
    }

    /**
     * 判断调用器 URL 是否显式标记为 Provider 侧
     *
     * @param invoker Dubbo 调用器
     * @return true 表示显式声明为 provider，false 表示 consumer 或未声明
     */
    public static boolean isExplicitProviderSide(Invoker<?> invoker) {
        return invoker != null
                && invoker.getUrl() != null
                && CommonConstants.PROVIDER_SIDE.equalsIgnoreCase(
                        invoker.getUrl().getParameter(CommonConstants.SIDE_KEY));
    }

    /**
     * 解析调用器真实服务接口全限定名（支持 {@link GenericService} 优先回落至 URL 接口名，无法识别时返回 {@code null}）
     *
     * @param invoker Dubbo 调用器
     * @return 服务接口全限定名，无法解析时返回 {@code null}
     */
    public static String resolveInterfaceName(Invoker<?> invoker) {
        if (invoker == null) {
            return null;
        }
        Class<?> interfaceClass = invoker.getInterface();
        if (interfaceClass != null && !GenericService.class.equals(interfaceClass)) {
            return interfaceClass.getName();
        }
        if (invoker.getUrl() != null && StringUtils.hasText(invoker.getUrl().getServiceInterface())) {
            return invoker.getUrl().getServiceInterface();
        }
        return interfaceClass != null ? interfaceClass.getName() : null;
    }

    /**
     * 解析用于日志展示与异常消息的服务名称（无法识别时回落为 {@code "UnknownService"}）
     *
     * @param invoker Dubbo 调用器
     * @return 非空服务展示名
     */
    public static String resolveServiceName(Invoker<?> invoker) {
        String interfaceName = resolveInterfaceName(invoker);
        return StringUtils.hasText(interfaceName) ? interfaceName : "UnknownService";
    }

    /**
     * 从当前线程 {@link MDC} 提取有效 {@code traceId}（过滤空白与字面量 {@code "null"}）
     *
     * @return 有效 TraceId，不存在或无效时返回 {@code null}
     */
    public static String resolveMdcTraceId() {
        String traceId = MDC.get("traceId");
        if (isValidAttachmentValue(traceId)) {
            return traceId.trim();
        }
        traceId = MDC.get(MessageHeaderConstants.TRACE_ID);
        return isValidAttachmentValue(traceId) ? traceId.trim() : null;
    }

    /**
     * 按 MDC -&gt; Invocation 附件 -&gt; RpcContext 附件顺序提取有效 {@code traceId}（用于可观测性诊断日志，缺省回落 {@code "N/A"}）
     *
     * @param invocation   当前 RPC 调用元数据
     * @param consumerSide 是否为消费端
     * @return 有效 TraceId 或 {@code "N/A"}
     */
    public static String resolveTraceId(Invocation invocation, boolean consumerSide) {
        String traceId = resolveMdcTraceId();
        if (traceId != null) {
            return traceId;
        }
        String[] keys = {MessageHeaderConstants.TRACE_ID, "traceId"};
        if (invocation != null) {
            for (String key : keys) {
                String value = findValidAttachment(invocation, key);
                if (value != null) {
                    return value;
                }
            }
        }
        var attachment = consumerSide ? RpcContext.getClientAttachment() : RpcContext.getServerAttachment();
        if (attachment != null) {
            for (String key : keys) {
                String value = attachment.getAttachment(key);
                if (isValidAttachmentValue(value)) {
                    return value.trim();
                }
                value = attachment.getAttachment(key.toLowerCase(Locale.ROOT));
                if (isValidAttachmentValue(value)) {
                    return value.trim();
                }
            }
        }
        return "N/A";
    }

    private static String findValidAttachment(Invocation invocation, String key) {
        String value = invocation.getAttachment(key);
        if (isValidAttachmentValue(value)) {
            return value.trim();
        }
        value = invocation.getAttachment(key.toLowerCase(Locale.ROOT));
        return isValidAttachmentValue(value) ? value.trim() : null;
    }

    /**
     * 判断附件值或上下文值是否有效（非空白且非字面量 {@code "null"}）
     *
     * @param value 待校验值
     * @return true 表示有效，false 表示为空、空白或 {@code "null"}
     */
    public static boolean isValidAttachmentValue(String value) {
        return StringUtils.hasText(value) && !"null".equalsIgnoreCase(value.trim());
    }

    /**
     * 判断 {@link Invocation} 中指定附件是否与 {@link RpcContext#getClientAttachment()} 中的残留值完全同源镜像
     *
     * @param invocation 当前 RPC 调用对象
     * @param key        附件键名
     * @return true 表示两者均为有效值且去除空白后相等，用于识别 {@code ConsumerContextFilter} 拷贝的跨调用残留
     */
    public static boolean isEchoedClientAttachment(Invocation invocation, String key) {
        if (invocation == null || !StringUtils.hasText(key)) {
            return false;
        }
        String clientVal = RpcContext.getClientAttachment().getAttachment(key);
        String invVal = invocation.getAttachment(key);
        return isValidAttachmentValue(clientVal)
                && isValidAttachmentValue(invVal)
                && clientVal.trim().equals(invVal.trim());
    }

    /**
     * 按当前上下文值对消费端出站受管附件执行前置对称对齐
     * <p>
     * 当上下文值有效且非字面量 {@code "null"} 时覆写写入 {@link RpcContext#getClientAttachment()} 与 {@link Invocation}；
     * 当上下文为空、空白或 {@code "null"} 时主动从两端移除该受管键，杜绝同线程跨调用残留。
     *
     * @param invocation 当前 RPC 调用对象
     * @param key        受管附件键名
     * @param value      当前线程上下文提取值
     */
    public static void reconcileOutboundAttachment(Invocation invocation, String key, String value) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        if (!isValidAttachmentValue(value)) {
            removeOutboundAttachment(invocation, key);
            return;
        }
        setOutboundAttachment(invocation, key, value.trim());
    }

    /**
     * 向 {@link RpcContext#getClientAttachment()} 与 {@link Invocation} 对称写入出站附件
     *
     * @param invocation 当前 RPC 调用对象
     * @param key        附件键名
     * @param value      附件值
     */
    public static void setOutboundAttachment(Invocation invocation, String key, String value) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }
        RpcContext.getClientAttachment().setAttachment(key, value);
        if (invocation instanceof RpcInvocation rpcInvocation) {
            rpcInvocation.setAttachment(key, value);
            return;
        }
        if (invocation != null) {
            Map<String, Object> objectAttachments = invocation.getObjectAttachments();
            if (objectAttachments != null) {
                try {
                    objectAttachments.put(key, value);
                } catch (UnsupportedOperationException e) {
                    log.debug("[DUBBO-ATTACHMENT] 跳过对只读 Invocation 写入受管出站键 '{}': {}", key, e.getMessage());
                }
            }
        }
    }

    /**
     * 从 {@link RpcContext#getClientAttachment()} 与 {@link Invocation} 中对称移除指定受管附件键
     *
     * @param invocation 当前 RPC 调用对象
     * @param key        受管附件键名
     */
    public static void removeOutboundAttachment(Invocation invocation, String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        RpcContext.getClientAttachment().removeAttachment(key);
        if (invocation != null) {
            Map<String, Object> objectAttachments = invocation.getObjectAttachments();
            if (objectAttachments != null && objectAttachments.containsKey(key)) {
                try {
                    objectAttachments.remove(key);
                } catch (UnsupportedOperationException e) {
                    log.debug("[DUBBO-ATTACHMENT] 跳过从只读 Invocation 移除受管出站键 '{}': {}", key, e.getMessage());
                }
            }
        }
    }

    /**
     * 从 {@link RpcContext#getClientAttachment()} 与 {@link Invocation} 中批量对称移除指定受管附件键
     *
     * @param invocation 当前 RPC 调用对象
     * @param keys       受管附件键名集合
     */
    public static void removeOutboundAttachments(Invocation invocation, String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        for (String key : keys) {
            removeOutboundAttachment(invocation, key);
        }
    }

    /**
     * 仅从 {@link RpcContext#getClientAttachment()} 中批量移除指定受管附件键（保留非受管业务自定义附件）
     *
     * @param keys 待清理的受管键名集合
     */
    public static void removeClientAttachments(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        var clientAttachment = RpcContext.getClientAttachment();
        for (String key : keys) {
            if (StringUtils.hasText(key)) {
                clientAttachment.removeAttachment(key);
            }
        }
    }
}
