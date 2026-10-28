package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Dubbo 零信任内网防穿透与 Same-Token 校验过滤器
 * <p>
 * 在微服务架构中实现零信任防御与平滑条件探测：
 * 1. 消费端出站：探测 Sa-Token 环境，自动提取当前进程的 Same-Token 受信凭据并注入 RPC Attachment；
 * 2. 服务端入站：强制校验 Same-Token 凭据，若绕过网关直连内部微服务暴露端口则直接 401 Fail-Fast 阻断；
 * 3. 依赖解耦：若类路径无 Sa-Token，平滑静默降级，绝不抛出 ClassNotFoundException。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = {CommonConstants.CONSUMER, CommonConstants.PROVIDER}, order = -9000)
public class DubboSameTokenFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DubboSameTokenFilter.class);

    public static final String SAME_TOKEN_KEY = "Same-Token";
    public static final String SA_SAME_TOKEN_KEY = "SA-SAME-TOKEN";

    private static final boolean SA_TOKEN_PRESENT = ClassUtils.isPresent(
            "cn.dev33.satoken.same.SaSameUtil", DubboSameTokenFilter.class.getClassLoader());

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && (!properties.isEnabled()
                || (properties.getSameToken() != null && !properties.getSameToken().isEnabled()))) {
            return invoker.invoke(invocation);
        }

        if (!SA_TOKEN_PRESENT) {
            return invoker.invoke(invocation);
        }

        // 本地短路调用 (injvm 进程内内存直接调用，未暴露任何物理网络端口) 豁免零信任防穿透拦截
        if (invoker != null && invoker.getUrl() != null
                && "injvm".equalsIgnoreCase(invoker.getUrl().getProtocol())) {
            return invoker.invoke(invocation);
        }

        boolean isConsumer = isConsumerSide(invoker);
        if (isConsumer) {
            handleConsumer(invocation);
            return invoker.invoke(invocation);
        } else {
            handleProvider(invoker, invocation, properties);
            return invoker.invoke(invocation);
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
        try {
            // 若调用方已显式指定 Same-Token 标头键 (即便为空白，也服从调用方意志，供测试或模拟无受信直连)，则不予自动覆盖
            if (hasExplicitSameToken(invocation)) {
                return;
            }

            String token = SaTokenDelegate.getToken();
            if (StringUtils.hasText(token)) {
                RpcContext.getClientAttachment().setAttachment(SAME_TOKEN_KEY, token);
                RpcContext.getClientAttachment().setAttachment(SA_SAME_TOKEN_KEY, token);
                if (invocation instanceof RpcInvocation rpcInvocation) {
                    rpcInvocation.setAttachment(SAME_TOKEN_KEY, token);
                    rpcInvocation.setAttachment(SA_SAME_TOKEN_KEY, token);
                }
            }
        } catch (Throwable t) {
            log.warn("[DUBBO] 消费端提取 Same-Token 受信凭据异常: {}", t.getMessage());
        }
    }

    private boolean hasExplicitSameToken(Invocation invocation) {
        if (invocation.getAttachments() != null
                && (invocation.getAttachments().containsKey(SAME_TOKEN_KEY) || invocation.getAttachments().containsKey(SA_SAME_TOKEN_KEY))) {
            return true;
        }
        var clientAttachments = RpcContext.getClientAttachment().getObjectAttachments();
        return clientAttachments != null
                && (clientAttachments.containsKey(SAME_TOKEN_KEY) || clientAttachments.containsKey(SA_SAME_TOKEN_KEY));
    }

    private void handleProvider(Invoker<?> invoker, Invocation invocation, io.github.atengk.cloud.dubbo.properties.AtengDubboProperties properties) {
        if (properties == null || properties.getSameToken() == null || !properties.getSameToken().isCheck()) {
            return;
        }

        String token = getAttachment(invocation, SAME_TOKEN_KEY);
        if (!StringUtils.hasText(token)) {
            token = getAttachment(invocation, SA_SAME_TOKEN_KEY);
        }

        if (!StringUtils.hasText(token) || "null".equalsIgnoreCase(token.trim())) {
            log.warn("[DUBBO] 零信任防穿透拦截: 接口 [{}.{}] 未携带合法 Same-Token 受信凭据，阻断直连访问",
                    invoker.getInterface().getName(), invocation.getMethodName());
            throw new BusinessException(CommonResultCode.UNAUTHORIZED.getCode(),
                    "微服务内部调用未携带合法 Same-Token 受信凭据，拒绝非法访问");
        }

        boolean valid;
        try {
            valid = SaTokenDelegate.isValid(token);
        } catch (Throwable t) {
            log.warn("[DUBBO] 零信任 Same-Token 校验执行异常: {}", t.getMessage());
            valid = false;
        }

        if (!valid) {
            log.warn("[DUBBO] 零信任防穿透拦截: 接口 [{}.{}] 携带的 Same-Token 凭据校验无效或已过期: token={}",
                    invoker.getInterface().getName(), invocation.getMethodName(), token);
            throw new BusinessException(CommonResultCode.UNAUTHORIZED.getCode(),
                    "微服务内部调用 Same-Token 凭据校验无效或已过期，拒绝非法访问");
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

    /**
     * Sa-Token 延迟加载委托类，隔离外部未引入 Sa-Token 时的字节码解析
     */
    private static class SaTokenDelegate {
        static String getToken() {
            return cn.dev33.satoken.same.SaSameUtil.getToken();
        }

        static boolean isValid(String token) {
            return cn.dev33.satoken.same.SaSameUtil.isValid(token);
        }
    }
}
