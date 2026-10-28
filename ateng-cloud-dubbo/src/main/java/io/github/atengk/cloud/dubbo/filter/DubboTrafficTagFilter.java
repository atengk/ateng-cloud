package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.cloud.dubbo.properties.AtengDubboTrafficProperties;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Constants;
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

/**
 * Dubbo 消费端金丝雀流量染色与标签路由平滑降级过滤器
 * <p>
 * 遵循微服务全链路流量染色与弹性治理铁律：
 * 1. 自动从当前 MDC 上下文或出站调用元数据中提取流量染色标记 (X-Traffic-Tag)；
 * 2. 将标签转化为 Dubbo 3 原生标签路由参数 (CommonConstants.TAG_KEY)，精准引导流量打向金丝雀灰度实例；
 * 3. 注入弹性兜底标记 (默认 dubbo.tagforce=false / dubbo.force.tag=false)，当灰度实例全下线或不存在时自动平滑降级至基线（无标签）实例；
 * 4. 强隔离 Fail-Fast 防线：若显式配置 tagForce=true，当目标实例未命中灰度标签时立即阻断，严禁流量越界。
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Activate(group = CommonConstants.CONSUMER, order = -9500)
public class DubboTrafficTagFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DubboTrafficTagFilter.class);

    public static final String DEFAULT_TRAFFIC_TAG_HEADER = "X-Traffic-Tag";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (properties != null && !properties.isEnabled()) {
            return invoker.invoke(invocation);
        }
        AtengDubboTrafficProperties traffic = properties != null ? properties.getTraffic() : null;
        if (traffic != null && !traffic.isEnabled()) {
            return invoker.invoke(invocation);
        }

        String headerName = (traffic != null && StringUtils.hasText(traffic.getHeaderName()))
                ? traffic.getHeaderName() : DEFAULT_TRAFFIC_TAG_HEADER;

        // 1. 提取流量染色标签 (优先 MDC，备选 RpcContext / Invocation attachment)
        String tag = resolveTrafficTag(invocation, headerName);

        // 2. 若存在有效流量染色标签，则注入 Dubbo 标签路由及降级策略
        if (StringUtils.hasText(tag) && !"null".equalsIgnoreCase(tag.trim())) {
            String trimmedTag = tag.trim();
            String forceTagParam = invocation.getAttachment(Constants.FORCE_USE_TAG);
            if (!StringUtils.hasText(forceTagParam)) {
                forceTagParam = RpcContext.getClientAttachment().getAttachment(Constants.FORCE_USE_TAG);
            }
            if (!StringUtils.hasText(forceTagParam)) {
                forceTagParam = invocation.getAttachment("dubbo.tagforce");
            }
            boolean tagForce = StringUtils.hasText(forceTagParam)
                    ? Boolean.parseBoolean(forceTagParam)
                    : (traffic != null && traffic.isTagForce());
            String tagForceStr = String.valueOf(tagForce);

            if (log.isDebugEnabled()) {
                log.debug("[DUBBO-TRAFFIC] 注入金丝雀标签路由: tag={}, tagForce={}, service={}, method={}",
                        trimmedTag, tagForce, invoker.getInterface().getName(), invocation.getMethodName());
            }

            // 注入 Dubbo 标签路由键
            setAttachment(invocation, CommonConstants.TAG_KEY, trimmedTag);
            setAttachment(invocation, "tag", trimmedTag);

            // 注入 Dubbo 强制标签标记与平滑降级标记
            setAttachment(invocation, Constants.FORCE_USE_TAG, tagForceStr);
            setAttachment(invocation, "dubbo.tagforce", tagForceStr);
            setAttachment(invocation, "tagforce", tagForceStr);

            // 同步确保全链路透传请求头存在
            setAttachment(invocation, headerName, trimmedTag);

            // 3. 强隔离校验 (Fail-Fast 防线)：若启用 tagForce=true，检验目标 invoker 标签；若目标不具备该标签则直接阻断
            if (tagForce) {
                String providerTag = invoker.getUrl().getParameter(CommonConstants.TAG_KEY);
                if (!StringUtils.hasText(providerTag)) {
                    providerTag = invoker.getUrl().getParameter("tag");
                }
                if (!trimmedTag.equals(providerTag)) {
                    throw new RpcException("No provider available for the service " + invoker.getInterface().getName()
                            + " under tag " + trimmedTag + " (tagforce=true, providerTag=" + providerTag + ")");
                }
            }
        }

        return invoker.invoke(invocation);
    }

    private String resolveTrafficTag(Invocation invocation, String headerName) {
        // 1. 检查 MDC
        String tag = MDC.get(headerName);
        if (!StringUtils.hasText(tag)) {
            tag = MDC.get(DEFAULT_TRAFFIC_TAG_HEADER);
        }
        if (!StringUtils.hasText(tag)) {
            tag = MDC.get("trafficTag");
        }
        if (!StringUtils.hasText(tag)) {
            tag = MDC.get("traffic-tag");
        }

        // 2. 检查 RpcContext ClientAttachment
        if (!StringUtils.hasText(tag)) {
            tag = RpcContext.getClientAttachment().getAttachment(headerName);
        }
        if (!StringUtils.hasText(tag)) {
            tag = RpcContext.getClientAttachment().getAttachment(CommonConstants.TAG_KEY);
        }

        // 3. 检查 Invocation Attachment
        if (!StringUtils.hasText(tag)) {
            tag = invocation.getAttachment(headerName);
        }
        if (!StringUtils.hasText(tag)) {
            tag = invocation.getAttachment(CommonConstants.TAG_KEY);
        }

        return tag;
    }

    private void setAttachment(Invocation invocation, String key, String value) {
        RpcContext.getClientAttachment().setAttachment(key, value);
        if (invocation instanceof RpcInvocation rpcInvocation) {
            rpcInvocation.setAttachment(key, value);
        }
    }
}
