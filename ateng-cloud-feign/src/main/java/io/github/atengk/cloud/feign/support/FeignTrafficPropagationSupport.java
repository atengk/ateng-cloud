package io.github.atengk.cloud.feign.support;

import io.github.atengk.cloud.feign.constant.FeignHeaderConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feign 与全域流量治理底座 TrafficContextHolder 泳道元数据双向反射适配桥梁
 * <p>
 * 基于 {@link ClassUtils} 条件探测与反射委托，提供零编译期强依赖的双向流量上下文协同：
 * 1. 类路径缺失 {@code ateng-cloud-traffic} 时自动短路并平滑回退至 SLF4J MDC，绝不抛出 ClassNotFoundException；
 * 2. 消费端出站自动提取流量标签、所属泳道与匹配规则；
 * 3. 提供端入站对称还原，并在出口 {@code finally} 块按入站前快照精确恢复或幂等清理，杜绝容器线程池复用污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-12
 */
public final class FeignTrafficPropagationSupport {

    private static final Logger log = LoggerFactory.getLogger(FeignTrafficPropagationSupport.class);

    private static final String HOLDER_CLASS_NAME = "io.github.atengk.cloud.traffic.context.TrafficContextHolder";
    private static final String CONTEXT_CLASS_NAME = "io.github.atengk.cloud.traffic.context.TrafficContext";

    private static final List<String> ALL_TRAFFIC_MDC_KEYS = List.of(
            FeignHeaderConstants.TRAFFIC_TAG,
            FeignHeaderConstants.MDC_TRAFFIC_TAG,
            FeignHeaderConstants.MDC_TRAFFIC_TAG_KEBAB,
            FeignHeaderConstants.TRAFFIC_LANE,
            FeignHeaderConstants.MDC_TRAFFIC_LANE,
            FeignHeaderConstants.TRAFFIC_RULE,
            FeignHeaderConstants.MDC_TRAFFIC_RULE
    );

    private static final boolean TRAFFIC_AVAILABLE;
    private static final Method GET_CONTEXT_METHOD;
    private static final Method SET_CONTEXT_METHOD;
    private static final Method CLEAR_METHOD;
    private static final Method TRAFFIC_CONTEXT_OF_METHOD;
    private static final java.lang.reflect.Constructor<?> TRAFFIC_CONTEXT_FULL_CTOR;
    private static final Method GET_TAG_METHOD;
    private static final Method GET_LANE_NAME_METHOD;
    private static final Method GET_MATCHED_RULE_METHOD;
    private static final Method GET_METADATA_METHOD;

    static {
        boolean available = false;
        Method getContext = null;
        Method setContext = null;
        Method clear = null;
        Method of = null;
        java.lang.reflect.Constructor<?> fullCtor = null;
        Method getTag = null;
        Method getLaneName = null;
        Method getMatchedRule = null;
        Method getMetadata = null;

        ClassLoader classLoader = FeignTrafficPropagationSupport.class.getClassLoader();
        if (ClassUtils.isPresent(HOLDER_CLASS_NAME, classLoader) && ClassUtils.isPresent(CONTEXT_CLASS_NAME, classLoader)) {
            try {
                Class<?> holderClass = ClassUtils.forName(HOLDER_CLASS_NAME, classLoader);
                Class<?> contextClass = ClassUtils.forName(CONTEXT_CLASS_NAME, classLoader);

                getContext = holderClass.getMethod("getContext");
                setContext = holderClass.getMethod("setContext", contextClass);
                clear = holderClass.getMethod("clear");

                of = contextClass.getMethod("of", String.class, String.class, String.class);
                try {
                    fullCtor = contextClass.getConstructor(String.class, String.class, String.class, Map.class);
                    getMetadata = contextClass.getMethod("getMetadata");
                } catch (NoSuchMethodException ignored) {
                    // 低版本或精简结构仅保留三参工厂方法时平滑兼容
                }
                getTag = contextClass.getMethod("getTag");
                getLaneName = contextClass.getMethod("getLaneName");
                getMatchedRule = contextClass.getMethod("getMatchedRule");

                available = true;
            } catch (Throwable t) {
                log.debug("[FEIGN-TRAFFIC] 初始化 TrafficContextHolder 反射桥接失败，降级为 MDC 模式: {}", t.getMessage());
            }
        }

        TRAFFIC_AVAILABLE = available;
        GET_CONTEXT_METHOD = getContext;
        SET_CONTEXT_METHOD = setContext;
        CLEAR_METHOD = clear;
        TRAFFIC_CONTEXT_OF_METHOD = of;
        TRAFFIC_CONTEXT_FULL_CTOR = fullCtor;
        GET_TAG_METHOD = getTag;
        GET_LANE_NAME_METHOD = getLaneName;
        GET_MATCHED_RULE_METHOD = getMatchedRule;
        GET_METADATA_METHOD = getMetadata;
    }

    private FeignTrafficPropagationSupport() {
    }

    /**
     * 判断运行期类路径是否存在可用的 TrafficContextHolder
     *
     * @return true 表示可用，false 表示未引入流量治理模块
     */
    public static boolean isTrafficAvailable() {
        return TRAFFIC_AVAILABLE;
    }

    /**
     * 提取当前线程的流量染色与泳道元数据快照
     * <p>
     * 优先反射读取 {@code TrafficContextHolder}，不可用或为空时平滑回退探测 SLF4J MDC。
     * </p>
     *
     * @return 流量快照对象，无有效信息时返回 null
     */
    public static TrafficSnapshot getSnapshot() {
        Object contextObj = readCurrentRawContext();
        if (contextObj != null) {
            try {
                String tag = FeignContextHeaderSupport.normalize(
                        (String) (GET_TAG_METHOD != null ? GET_TAG_METHOD.invoke(contextObj) : null));
                String laneName = FeignContextHeaderSupport.normalize(
                        (String) (GET_LANE_NAME_METHOD != null ? GET_LANE_NAME_METHOD.invoke(contextObj) : null));
                String matchedRule = FeignContextHeaderSupport.normalize(
                        (String) (GET_MATCHED_RULE_METHOD != null ? GET_MATCHED_RULE_METHOD.invoke(contextObj) : null));
                // 兼容 TrafficContextHolder 已持有 tag 但仅通过 MDC 补充了 lane/rule 的情景
                if (laneName == null) {
                    laneName = resolveMdcLane();
                }
                if (matchedRule == null) {
                    matchedRule = resolveMdcRule();
                }
                if (tag != null || laneName != null || matchedRule != null) {
                    return new TrafficSnapshot(tag, laneName, matchedRule);
                }
            } catch (Throwable t) {
                log.debug("[FEIGN-TRAFFIC] 从 TrafficContextHolder 提取流量快照异常: {}", t.getMessage());
            }
        }

        String tag = resolveMdcTag();
        String laneName = resolveMdcLane();
        String matchedRule = resolveMdcRule();
        if (tag != null || laneName != null || matchedRule != null) {
            return new TrafficSnapshot(tag, laneName, matchedRule);
        }
        return null;
    }

    /**
     * 捕获入站前精确线程流量快照（包含原始 TrafficContext 实例及各关联 MDC 键原始值）
     *
     * @return 入站前状态快照，用于 {@code finally} 块无损对称还原
     */
    public static InboundTrafficSnapshot captureInboundSnapshot() {
        Object rawContext = readCurrentRawContext();
        Map<String, String> previousMdc = new LinkedHashMap<>(ALL_TRAFFIC_MDC_KEYS.size());
        for (String key : ALL_TRAFFIC_MDC_KEYS) {
            previousMdc.put(key, MDC.get(key));
        }
        return new InboundTrafficSnapshot(rawContext, previousMdc);
    }

    /**
     * 无损还原入站前精确线程流量状态及关联 MDC 键集合
     *
     * @param previous 入站前通过 {@link #captureInboundSnapshot()} 捕获的快照
     */
    public static void restoreInboundSnapshot(InboundTrafficSnapshot previous) {
        clear();
        if (previous == null) {
            return;
        }
        if (previous.rawContext() != null && TRAFFIC_AVAILABLE && SET_CONTEXT_METHOD != null) {
            try {
                SET_CONTEXT_METHOD.invoke(null, previous.rawContext());
            } catch (Throwable t) {
                log.debug("[FEIGN-TRAFFIC] 还原原始 TrafficContext 实例异常: {}", t.getMessage());
            }
        }
        if (previous.previousMdc() != null) {
            for (Map.Entry<String, String> entry : previous.previousMdc().entrySet()) {
                if (entry.getValue() != null) {
                    MDC.put(entry.getKey(), entry.getValue());
                } else {
                    MDC.remove(entry.getKey());
                }
            }
        }
    }

    /**
     * 入站还原流量染色与泳道上下文至当前线程与 MDC
     *
     * @param tag         流量染色标签
     * @param laneName    所属泳道名称
     * @param matchedRule 命中的动态分流规则标识
     */
    public static void restore(String tag, String laneName, String matchedRule) {
        String cleanTag = FeignContextHeaderSupport.normalize(tag);
        String cleanLane = FeignContextHeaderSupport.normalize(laneName);
        String cleanRule = FeignContextHeaderSupport.normalize(matchedRule);
        if (cleanTag == null && cleanLane == null && cleanRule == null) {
            return;
        }

        if (cleanTag != null && TRAFFIC_AVAILABLE && SET_CONTEXT_METHOD != null && TRAFFIC_CONTEXT_OF_METHOD != null) {
            try {
                Object existingMetadata = readCurrentMetadata();
                Object contextObj = (TRAFFIC_CONTEXT_FULL_CTOR != null && existingMetadata != null)
                        ? TRAFFIC_CONTEXT_FULL_CTOR.newInstance(cleanTag, cleanLane, cleanRule, existingMetadata)
                        : TRAFFIC_CONTEXT_OF_METHOD.invoke(null, cleanTag, cleanLane, cleanRule);
                SET_CONTEXT_METHOD.invoke(null, contextObj);
            } catch (Throwable t) {
                log.warn("[FEIGN-TRAFFIC] 还原 TrafficContextHolder 异常: {}", t.getMessage());
            }
        }

        syncMdc(cleanTag, cleanLane, cleanRule);
    }

    /**
     * 按流量快照对象还原当前线程流量上下文与 MDC
     *
     * @param snapshot 流量上下文快照
     */
    public static void restore(TrafficSnapshot snapshot) {
        if (snapshot != null && snapshot.isValid()) {
            restore(snapshot.tag(), snapshot.laneName(), snapshot.matchedRule());
        }
    }

    /**
     * 同步指定流量元数据至 MDC，并在某字段为 {@code null} 时显式清理对应历史残留键，防止新旧混合污染
     *
     * @param tag         流量染色标签
     * @param laneName    所属泳道名称
     * @param matchedRule 命中的动态分流规则标识
     */
    public static void syncMdc(String tag, String laneName, String matchedRule) {
        String cleanTag = FeignContextHeaderSupport.normalize(tag);
        String cleanLane = FeignContextHeaderSupport.normalize(laneName);
        String cleanRule = FeignContextHeaderSupport.normalize(matchedRule);

        if (cleanTag != null) {
            MDC.put(FeignHeaderConstants.TRAFFIC_TAG, cleanTag);
            MDC.put(FeignHeaderConstants.MDC_TRAFFIC_TAG, cleanTag);
            MDC.remove(FeignHeaderConstants.MDC_TRAFFIC_TAG_KEBAB);
        }

        updateOrRemoveMdcPair(FeignHeaderConstants.TRAFFIC_LANE, FeignHeaderConstants.MDC_TRAFFIC_LANE, cleanLane);
        updateOrRemoveMdcPair(FeignHeaderConstants.TRAFFIC_RULE, FeignHeaderConstants.MDC_TRAFFIC_RULE, cleanRule);
    }

    /**
     * 按入站前快照恢复当前线程的流量染色与泳道状态
     *
     * @param previous 入站前保存的快照，为 null 时彻底清理
     */
    public static void restoreSnapshot(TrafficSnapshot previous) {
        clear();
        restore(previous);
    }

    /**
     * 幂等清理当前线程的流量上下文与关联 MDC 标记
     */
    public static void clear() {
        if (TRAFFIC_AVAILABLE && CLEAR_METHOD != null) {
            try {
                CLEAR_METHOD.invoke(null);
            } catch (Throwable t) {
                log.debug("[FEIGN-TRAFFIC] 清理 TrafficContextHolder 异常: {}", t.getMessage());
            }
        }
        for (String key : ALL_TRAFFIC_MDC_KEYS) {
            MDC.remove(key);
        }
    }

    private static Object readCurrentRawContext() {
        if (!TRAFFIC_AVAILABLE || GET_CONTEXT_METHOD == null) {
            return null;
        }
        try {
            return GET_CONTEXT_METHOD.invoke(null);
        } catch (Throwable t) {
            log.debug("[FEIGN-TRAFFIC] 读取 TrafficContextHolder 异常: {}", t.getMessage());
            return null;
        }
    }

    private static Object readCurrentMetadata() {
        if (GET_METADATA_METHOD == null) {
            return null;
        }
        Object current = readCurrentRawContext();
        if (current == null) {
            return null;
        }
        try {
            return GET_METADATA_METHOD.invoke(current);
        } catch (Throwable t) {
            log.debug("[FEIGN-TRAFFIC] 读取 TrafficContext metadata 异常: {}", t.getMessage());
            return null;
        }
    }

    private static void updateOrRemoveMdcPair(String primaryKey, String aliasKey, String value) {
        if (value != null) {
            MDC.put(primaryKey, value);
            MDC.put(aliasKey, value);
        } else {
            MDC.remove(primaryKey);
            MDC.remove(aliasKey);
        }
    }

    private static String resolveMdcTag() {
        return FeignContextHeaderSupport.firstValidMdcValue(
                FeignHeaderConstants.TRAFFIC_TAG,
                FeignHeaderConstants.MDC_TRAFFIC_TAG,
                FeignHeaderConstants.MDC_TRAFFIC_TAG_KEBAB
        );
    }

    private static String resolveMdcLane() {
        return FeignContextHeaderSupport.firstValidMdcValue(
                FeignHeaderConstants.TRAFFIC_LANE,
                FeignHeaderConstants.MDC_TRAFFIC_LANE
        );
    }

    private static String resolveMdcRule() {
        return FeignContextHeaderSupport.firstValidMdcValue(
                FeignHeaderConstants.TRAFFIC_RULE,
                FeignHeaderConstants.MDC_TRAFFIC_RULE
        );
    }

    /**
     * 流量染色与泳道上下文不可变快照载体
     *
     * @param tag         流量染色标签
     * @param laneName    所属泳道名称
     * @param matchedRule 命中的动态分流规则标识
     */
    public record TrafficSnapshot(String tag, String laneName, String matchedRule) {

        /**
         * 是否携带有效流量染色标签
         *
         * @return true 表示存在有效标签
         */
        public boolean hasTag() {
            return StringUtils.hasText(tag);
        }

        /**
         * 快照是否包含任意有效流量元数据
         *
         * @return true 表示至少含有一项有效元数据
         */
        public boolean isValid() {
            return StringUtils.hasText(tag) || StringUtils.hasText(laneName) || StringUtils.hasText(matchedRule);
        }
    }

    /**
     * 入站前精确线程流量状态与 MDC 键快照，用于保护外层过滤器的原始对象引用及 MDC 洁净度
     *
     * @param rawContext  原始 {@code TrafficContext} 实例（可选，未引入流量模块时为 null）
     * @param previousMdc 入站前各流量 MDC 键原始映射
     */
    public record InboundTrafficSnapshot(Object rawContext, Map<String, String> previousMdc) {
    }
}
