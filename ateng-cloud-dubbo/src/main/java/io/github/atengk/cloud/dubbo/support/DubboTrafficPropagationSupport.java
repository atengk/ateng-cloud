package io.github.atengk.cloud.dubbo.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * Dubbo 与全域流量治理底座 TrafficContextHolder 泳道元数据双向联动适配桥梁
 * <p>
 * 基于反射与 ClassUtils 条件探测，提供对 TrafficContextHolder 流量标签与泳道上下文的解耦支持：
 * 1. 零强依赖：类路径缺失 ateng-cloud-traffic 时自动短路并平滑回退至 MDC 模式，不抛出 ClassNotFoundException；
 * 2. 强类型穿透：消费端出站自动提取 TrafficContext 标签、泳道名与匹配规则，注入 RPC Attachment 并激活 Dubbo 标签路由；
 * 3. 确定性生命周期治理：服务端入站还原完整 TrafficContext，出口 finally 块中无条件幂等执行 clear()，杜绝多跳 RPC 线程池污染。
 *
 * @author Ateng
 * @since 2026-10-31
 */
public final class DubboTrafficPropagationSupport {

    private static final Logger log = LoggerFactory.getLogger(DubboTrafficPropagationSupport.class);

    /**
     * 流量染色标准 HTTP 标头与 RPC Attachment 键名
     */
    public static final String TRAFFIC_TAG = "X-Traffic-Tag";

    /**
     * 流量所属泳道名称 RPC Attachment 键名
     */
    public static final String TRAFFIC_LANE = "X-Traffic-Lane";

    /**
     * 命中的动态分流规则标识 RPC Attachment 键名
     */
    public static final String TRAFFIC_RULE = "X-Traffic-Rule";

    private static final boolean TRAFFIC_AVAILABLE;
    private static final Method GET_CONTEXT_METHOD;
    private static final Method SET_CONTEXT_METHOD;
    private static final Method CLEAR_METHOD;
    private static final Method TRAFFIC_CONTEXT_OF_METHOD;
    private static final Method GET_TAG_METHOD;
    private static final Method GET_LANE_NAME_METHOD;
    private static final Method GET_MATCHED_RULE_METHOD;

    static {
        boolean available = false;
        Method getContext = null;
        Method setContext = null;
        Method clear = null;
        Method of = null;
        Method getTag = null;
        Method getLaneName = null;
        Method getMatchedRule = null;

        ClassLoader classLoader = DubboTrafficPropagationSupport.class.getClassLoader();
        String holderClassName = "io.github.atengk.cloud.traffic.context.TrafficContextHolder";
        String contextClassName = "io.github.atengk.cloud.traffic.context.TrafficContext";

        if (ClassUtils.isPresent(holderClassName, classLoader) && ClassUtils.isPresent(contextClassName, classLoader)) {
            try {
                Class<?> holderClass = ClassUtils.forName(holderClassName, classLoader);
                Class<?> contextClass = ClassUtils.forName(contextClassName, classLoader);

                getContext = holderClass.getMethod("getContext");
                setContext = holderClass.getMethod("setContext", contextClass);
                clear = holderClass.getMethod("clear");

                of = contextClass.getMethod("of", String.class, String.class, String.class);
                getTag = contextClass.getMethod("getTag");
                getLaneName = contextClass.getMethod("getLaneName");
                getMatchedRule = contextClass.getMethod("getMatchedRule");

                available = true;
            } catch (Throwable t) {
                log.debug("[DUBBO-TRAFFIC] 初始化 TrafficContextHolder 反射方法失败，降级为不可用: {}", t.getMessage());
            }
        }

        TRAFFIC_AVAILABLE = available;
        GET_CONTEXT_METHOD = getContext;
        SET_CONTEXT_METHOD = setContext;
        CLEAR_METHOD = clear;
        TRAFFIC_CONTEXT_OF_METHOD = of;
        GET_TAG_METHOD = getTag;
        GET_LANE_NAME_METHOD = getLaneName;
        GET_MATCHED_RULE_METHOD = getMatchedRule;
    }

    private DubboTrafficPropagationSupport() {
    }

    /**
     * 判断当前运行环境类路径中是否存在可用的 TrafficContextHolder
     *
     * @return true 表示存在可用 Traffic 治理环境，false 表示不存在
     */
    public static boolean isTrafficAvailable() {
        return TRAFFIC_AVAILABLE;
    }

    /**
     * 提取当前线程的流量染色与泳道元数据快照
     * <p>
     * 优先读取 TrafficContextHolder；若不可用或为空则平滑回退探测 MDC。
     *
     * @return 流量快照对象，若无流量标签则返回 null
     */
    public static TrafficSnapshot getSnapshot() {
        if (TRAFFIC_AVAILABLE && GET_CONTEXT_METHOD != null) {
            try {
                Object contextObj = GET_CONTEXT_METHOD.invoke(null);
                if (contextObj != null && GET_TAG_METHOD != null) {
                    String tag = (String) GET_TAG_METHOD.invoke(contextObj);
                    String laneName = GET_LANE_NAME_METHOD != null ? (String) GET_LANE_NAME_METHOD.invoke(contextObj) : null;
                    String matchedRule = GET_MATCHED_RULE_METHOD != null ? (String) GET_MATCHED_RULE_METHOD.invoke(contextObj) : null;
                    if (StringUtils.hasText(tag)) {
                        return new TrafficSnapshot(tag.trim(), laneName, matchedRule);
                    }
                }
            } catch (Throwable t) {
                log.debug("[DUBBO-TRAFFIC] 从 TrafficContextHolder 提取流量上下文异常: {}", t.getMessage());
            }
        }

        // 平滑回退从 MDC 提取
        String tag = MDC.get(TRAFFIC_TAG);
        if (!StringUtils.hasText(tag)) {
            tag = MDC.get("trafficTag");
        }
        if (!StringUtils.hasText(tag)) {
            tag = MDC.get("traffic-tag");
        }
        if (StringUtils.hasText(tag)) {
            String laneName = MDC.get(TRAFFIC_LANE);
            String matchedRule = MDC.get(TRAFFIC_RULE);
            return new TrafficSnapshot(tag.trim(), laneName, matchedRule);
        }

        return null;
    }

    /**
     * 服务端入站还原流量染色与泳道上下文至当前线程
     *
     * @param tag         流量染色标签
     * @param laneName    所属泳道名称
     * @param matchedRule 命中的动态分流规则标识
     */
    public static void restore(String tag, String laneName, String matchedRule) {
        if (!StringUtils.hasText(tag) || "null".equalsIgnoreCase(tag.trim())) {
            return;
        }
        String trimmedTag = tag.trim();
        String trimmedLane = StringUtils.hasText(laneName) && !"null".equalsIgnoreCase(laneName.trim())
                ? laneName.trim() : null;
        String trimmedRule = StringUtils.hasText(matchedRule) && !"null".equalsIgnoreCase(matchedRule.trim())
                ? matchedRule.trim() : null;

        if (TRAFFIC_AVAILABLE && SET_CONTEXT_METHOD != null && TRAFFIC_CONTEXT_OF_METHOD != null) {
            try {
                Object contextObj = TRAFFIC_CONTEXT_OF_METHOD.invoke(null, trimmedTag, trimmedLane, trimmedRule);
                SET_CONTEXT_METHOD.invoke(null, contextObj);
                if (log.isDebugEnabled()) {
                    log.debug("[DUBBO-TRAFFIC] 成功还原入站 TrafficContext: tag={}, lane={}, rule={}",
                            trimmedTag, trimmedLane, trimmedRule);
                }
            } catch (Throwable t) {
                log.warn("[DUBBO-TRAFFIC] 还原 TrafficContextHolder 上下文异常: {}", t.getMessage());
            }
        }

        // 双向同步写入 MDC 确保日志全链路贯通
        MDC.put(TRAFFIC_TAG, trimmedTag);
        MDC.put("trafficTag", trimmedTag);
        if (trimmedLane != null) {
            MDC.put(TRAFFIC_LANE, trimmedLane);
        }
        if (trimmedRule != null) {
            MDC.put(TRAFFIC_RULE, trimmedRule);
        }
    }

    /**
     * 清理当前线程的流量染色与泳道上下文及 MDC 标记
     */
    public static void clear() {
        if (TRAFFIC_AVAILABLE && CLEAR_METHOD != null) {
            try {
                CLEAR_METHOD.invoke(null);
            } catch (Throwable t) {
                log.debug("[DUBBO-TRAFFIC] 清理 TrafficContextHolder 上下文异常: {}", t.getMessage());
            }
        }
        MDC.remove(TRAFFIC_TAG);
        MDC.remove("trafficTag");
        MDC.remove("traffic-tag");
        MDC.remove(TRAFFIC_LANE);
        MDC.remove("trafficLane");
        MDC.remove(TRAFFIC_RULE);
        MDC.remove("trafficRule");
    }

    /**
     * 流量染色与泳道状态上下文快照传输载体
     *
     * @param tag         流量染色标签
     * @param laneName    所属泳道名称 (可选)
     * @param matchedRule 命中的动态分流规则标识 (可选)
     */
    public record TrafficSnapshot(String tag, String laneName, String matchedRule) {

        public boolean hasTag() {
            return StringUtils.hasText(tag);
        }
    }
}
