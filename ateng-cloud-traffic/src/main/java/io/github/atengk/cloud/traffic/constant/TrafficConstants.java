package io.github.atengk.cloud.traffic.constant;

/**
 * 全链路流量治理与金丝雀灰度常量定义
 *
 * @author Ateng
 * @since 2026-10-29
 */
public final class TrafficConstants {

    private TrafficConstants() {
    }

    /**
     * 流量染色标准 HTTP 标头名称
     */
    public static final String HEADER_TRAFFIC_TAG = "X-Traffic-Tag";

    /**
     * 流量染色标准 URL Query 参数名称
     */
    public static final String PARAM_TRAFFIC_TAG = "traffic_tag";

    /**
     * SLF4J MDC 流量标签键名（与标头统一对齐）
     */
    public static final String MDC_TRAFFIC_TAG = "X-Traffic-Tag";

    /**
     * Nacos 等注册中心服务实例元数据中存储灰度标签的键名
     */
    public static final String METADATA_TAG = "tag";

    /**
     * 兼容性服务实例元数据灰度标签键名：连字符格式
     */
    public static final String METADATA_TAG_HYPHEN = "traffic-tag";

    /**
     * 兼容性服务实例元数据灰度标签键名：点分格式
     */
    public static final String METADATA_TAG_DOT = "traffic.tag";

    /**
     * 兼容性服务实例元数据灰度标签键名：简写格式
     */
    public static final String METADATA_GRAY = "gray";

    /**
     * 默认基线稳定泳道标签
     */
    public static final String DEFAULT_TAG = "default";

    /**
     * 兼容性基线稳定泳道别名
     */
    public static final String TAG_BASELINE = "baseline";

    /**
     * 兼容性基线稳定泳道别名：无标签
     */
    public static final String TAG_NONE = "none";

    /**
     * 配置属性前缀
     */
    public static final String PROPERTIES_PREFIX = "ateng.cloud.traffic";
}
