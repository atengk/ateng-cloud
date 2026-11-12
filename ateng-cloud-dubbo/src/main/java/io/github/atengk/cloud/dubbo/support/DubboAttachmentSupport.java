package io.github.atengk.cloud.dubbo.support;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

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
