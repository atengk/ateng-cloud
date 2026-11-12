package io.github.atengk.cloud.dubbo.filter;

import io.github.atengk.cloud.dubbo.properties.AtengDubboProperties;
import io.github.atengk.cloud.dubbo.properties.AtengDubboTrafficProperties;
import io.github.atengk.cloud.dubbo.support.DubboAttachmentSupport;
import io.github.atengk.cloud.dubbo.support.DubboPropertiesHolder;
import io.github.atengk.cloud.dubbo.support.DubboTrafficPropagationSupport;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.BaseFilter;
import org.apache.dubbo.rpc.Constants;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Arrays;

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
@Activate(group = CommonConstants.CONSUMER, order = -8500)
public class DubboTrafficTagFilter implements Filter, BaseFilter.Listener {

    private static final Logger log = LoggerFactory.getLogger(DubboTrafficTagFilter.class);

    public static final String DEFAULT_TRAFFIC_TAG_HEADER = "X-Traffic-Tag";
    private static final String SHORT_TAG_KEY = "tag";
    private static final String DUBBO_TAG_FORCE_KEY = "dubbo.tagforce";
    private static final String TAG_FORCE_KEY = "tagforce";
    private static final String[] BASE_MANAGED_KEYS = {
            CommonConstants.TAG_KEY,
            SHORT_TAG_KEY,
            Constants.FORCE_USE_TAG,
            DUBBO_TAG_FORCE_KEY,
            TAG_FORCE_KEY,
            DEFAULT_TRAFFIC_TAG_HEADER
    };

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        var properties = DubboPropertiesHolder.get();
        if (shouldSkipConsumerTraffic(invoker, properties)) {
            return invoker.invoke(invocation);
        }

        String headerName = resolveHeaderName(properties);

        // 1. 提取当前线程上下文与请求自身携带有流量染色标记（禁止回退读取或镜像复活陈旧 ClientAttachment）
        var trafficSnapshot = DubboTrafficPropagationSupport.getSnapshot();
        String tag = trafficSnapshot != null && trafficSnapshot.hasTag()
                ? trafficSnapshot.tag()
                : resolveTrafficTag(invocation, headerName);

        // 2. 当前上下文与请求无有效流量染色标签时，前置对称清理全部受管路由出站键
        if (!DubboAttachmentSupport.isValidAttachmentValue(tag)) {
            clearOutboundRouteAttachments(invocation, headerName);
            return invoker.invoke(invocation);
        }

        String trimmedTag = tag.trim();
        AtengDubboTrafficProperties traffic = properties != null ? properties.getTraffic() : null;
        boolean tagForce = resolveTagForce(invocation, traffic);
        String tagForceStr = String.valueOf(tagForce);

        // 3. 强隔离 Fail-Fast 防线：先校验目标节点标签，不匹配则清理 ClientAttachment 与标签键并保留 Failover 重试所需的强制标记后阻断
        if (tagForce) {
            String providerTag = resolveProviderTag(invoker);
            if (!trimmedTag.equals(providerTag)) {
                DubboAttachmentSupport.removeOutboundAttachments(invocation, CommonConstants.TAG_KEY, SHORT_TAG_KEY);
                DubboAttachmentSupport.removeClientAttachments(getManagedKeys(headerName));
                throw new RpcException("No provider available for the service "
                        + DubboAttachmentSupport.resolveServiceName(invoker)
                        + " under tag " + trimmedTag + " (tagforce=true, providerTag=" + providerTag + ")");
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[DUBBO-TRAFFIC] 注入金丝雀标签路由: tag={}, tagForce={}, service={}, method={}",
                    trimmedTag, tagForce, DubboAttachmentSupport.resolveServiceName(invoker), invocation.getMethodName());
        }

        // 4. 对称写入 Dubbo 标签路由键、平滑降级标记与全链路透传请求头
        DubboAttachmentSupport.setOutboundAttachment(invocation, CommonConstants.TAG_KEY, trimmedTag);
        DubboAttachmentSupport.setOutboundAttachment(invocation, SHORT_TAG_KEY, trimmedTag);
        DubboAttachmentSupport.setOutboundAttachment(invocation, Constants.FORCE_USE_TAG, tagForceStr);
        DubboAttachmentSupport.setOutboundAttachment(invocation, DUBBO_TAG_FORCE_KEY, tagForceStr);
        DubboAttachmentSupport.setOutboundAttachment(invocation, TAG_FORCE_KEY, tagForceStr);
        DubboAttachmentSupport.setOutboundAttachment(invocation, headerName, trimmedTag);

        return invoker.invoke(invocation);
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        cleanupConsumerClientAttachments(invoker);
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        cleanupConsumerClientAttachments(invoker);
    }

    private void cleanupConsumerClientAttachments(Invoker<?> invoker) {
        var properties = DubboPropertiesHolder.get();
        if (shouldSkipConsumerTraffic(invoker, properties)) {
            return;
        }
        DubboAttachmentSupport.removeClientAttachments(getManagedKeys(resolveHeaderName(properties)));
    }

    private boolean shouldSkipConsumerTraffic(Invoker<?> invoker, AtengDubboProperties properties) {
        return !isTrafficEnabled(properties)
                || DubboAttachmentSupport.isInjvmProtocol(invoker)
                || DubboAttachmentSupport.isExplicitProviderSide(invoker);
    }

    private boolean isTrafficEnabled(AtengDubboProperties properties) {
        if (properties != null && !properties.isEnabled()) {
            return false;
        }
        AtengDubboTrafficProperties traffic = properties != null ? properties.getTraffic() : null;
        return traffic == null || traffic.isEnabled();
    }

    private String resolveHeaderName(AtengDubboProperties properties) {
        AtengDubboTrafficProperties traffic = properties != null ? properties.getTraffic() : null;
        return (traffic != null && StringUtils.hasText(traffic.getHeaderName()))
                ? traffic.getHeaderName().trim() : DEFAULT_TRAFFIC_TAG_HEADER;
    }

    private String[] getManagedKeys(String headerName) {
        if (!StringUtils.hasText(headerName) || DEFAULT_TRAFFIC_TAG_HEADER.equals(headerName)) {
            return BASE_MANAGED_KEYS;
        }
        String[] keys = Arrays.copyOf(BASE_MANAGED_KEYS, BASE_MANAGED_KEYS.length + 1);
        keys[BASE_MANAGED_KEYS.length] = headerName;
        return keys;
    }

    private void clearOutboundRouteAttachments(Invocation invocation, String headerName) {
        DubboAttachmentSupport.removeOutboundAttachments(invocation, getManagedKeys(headerName));
    }

    private boolean resolveTagForce(Invocation invocation, AtengDubboTrafficProperties traffic) {
        if (invocation != null) {
            // 1. 优先采信仅在当前 Invocation 显式声明（非 ClientAttachment 镜像拷贝）的强隔离标记
            String explicitForce = findTagForceAttachment(invocation, true);
            if (explicitForce != null) {
                return Boolean.parseBoolean(explicitForce);
            }
            // 2. 若与 ClientAttachment 同源镜像：过滤前次 DubboTrafficTagFilter 未回调清理所遗留的成套路由快照，
            //    同时保留调用方通过 RpcContext.getClientAttachment() 合法发起的单次强隔离声明
            if (!isStaleRouteAttachmentEcho(invocation)) {
                String echoedForce = findTagForceAttachment(invocation, false);
                if (echoedForce != null) {
                    return Boolean.parseBoolean(echoedForce);
                }
            }
        }
        return traffic != null && traffic.isTagForce();
    }

    private String findTagForceAttachment(Invocation invocation, boolean requireNonEchoed) {
        String[] forceKeys = {Constants.FORCE_USE_TAG, DUBBO_TAG_FORCE_KEY, TAG_FORCE_KEY};
        for (String key : forceKeys) {
            String value = invocation.getAttachment(key);
            if (DubboAttachmentSupport.isValidAttachmentValue(value)
                    && (!requireNonEchoed || !DubboAttachmentSupport.isEchoedClientAttachment(invocation, key))) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isStaleRouteAttachmentEcho(Invocation invocation) {
        if (!DubboAttachmentSupport.isEchoedClientAttachment(invocation, CommonConstants.TAG_KEY)
                || !DubboAttachmentSupport.isEchoedClientAttachment(invocation, SHORT_TAG_KEY)
                || !DubboAttachmentSupport.isEchoedClientAttachment(invocation, Constants.FORCE_USE_TAG)
                || !DubboAttachmentSupport.isEchoedClientAttachment(invocation, DUBBO_TAG_FORCE_KEY)
                || !DubboAttachmentSupport.isEchoedClientAttachment(invocation, TAG_FORCE_KEY)) {
            return false;
        }
        String tagKeyVal = invocation.getAttachment(CommonConstants.TAG_KEY).trim();
        String shortTagVal = invocation.getAttachment(SHORT_TAG_KEY).trim();
        String forceVal = invocation.getAttachment(Constants.FORCE_USE_TAG).trim();
        String dubboTagForceVal = invocation.getAttachment(DUBBO_TAG_FORCE_KEY).trim();
        String tagForceVal = invocation.getAttachment(TAG_FORCE_KEY).trim();
        return tagKeyVal.equals(shortTagVal)
                && forceVal.equals(dubboTagForceVal)
                && dubboTagForceVal.equals(tagForceVal);
    }

    private String resolveTrafficTag(Invocation invocation, String headerName) {
        String tag = MDC.get(headerName);
        if (!DubboAttachmentSupport.isValidAttachmentValue(tag)) {
            tag = MDC.get(DEFAULT_TRAFFIC_TAG_HEADER);
        }
        if (!DubboAttachmentSupport.isValidAttachmentValue(tag)) {
            tag = MDC.get("trafficTag");
        }
        if (!DubboAttachmentSupport.isValidAttachmentValue(tag)) {
            tag = MDC.get("traffic-tag");
        }
        if (!DubboAttachmentSupport.isValidAttachmentValue(tag) && invocation != null) {
            String invTag = invocation.getAttachment(headerName);
            if (DubboAttachmentSupport.isValidAttachmentValue(invTag)
                    && !DubboAttachmentSupport.isEchoedClientAttachment(invocation, headerName)) {
                tag = invTag;
            }
        }
        return DubboAttachmentSupport.isValidAttachmentValue(tag) ? tag : null;
    }

    private String resolveProviderTag(Invoker<?> invoker) {
        if (invoker == null || invoker.getUrl() == null) {
            return null;
        }
        String providerTag = invoker.getUrl().getParameter(CommonConstants.TAG_KEY);
        return StringUtils.hasText(providerTag) ? providerTag : invoker.getUrl().getParameter(SHORT_TAG_KEY);
    }
}
