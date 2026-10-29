package io.github.atengk.cloud.traffic.context;

import org.springframework.util.StringUtils;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 流量染色与泳道状态上下文快照
 *
 * <p>封装当前线程关联的流量标签、所属泳道、匹配的动态规则及扩展元数据。</p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TrafficContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 流量染色标签 (如 canary, gray-v2)
     */
    private final String tag;

    /**
     * 所属泳道名称 (可选)
     */
    private final String laneName;

    /**
     * 命中的动态分流规则标识 (可选)
     */
    private final String matchedRule;

    /**
     * 扩展泳道元数据
     */
    private final Map<String, Object> metadata;

    public TrafficContext(String tag) {
        this(tag, null, null, null);
    }

    public TrafficContext(String tag, String laneName, String matchedRule, Map<String, Object> metadata) {
        this.tag = StringUtils.hasText(tag) ? tag.trim() : null;
        this.laneName = StringUtils.hasText(laneName) ? laneName.trim() : null;
        this.matchedRule = StringUtils.hasText(matchedRule) ? matchedRule.trim() : null;
        if (metadata == null || metadata.isEmpty()) {
            this.metadata = Collections.emptyMap();
        } else {
            this.metadata = Collections.unmodifiableMap(new HashMap<>(metadata));
        }
    }

    public static TrafficContext of(String tag) {
        return new TrafficContext(tag);
    }

    public static TrafficContext of(String tag, String laneName, String matchedRule) {
        return new TrafficContext(tag, laneName, matchedRule, null);
    }

    public String getTag() {
        return tag;
    }

    public String getLaneName() {
        return laneName;
    }

    public String getMatchedRule() {
        return matchedRule;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public boolean hasTag() {
        return StringUtils.hasText(tag);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrafficContext that = (TrafficContext) o;
        return Objects.equals(tag, that.tag)
                && Objects.equals(laneName, that.laneName)
                && Objects.equals(matchedRule, that.matchedRule)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, laneName, matchedRule, metadata);
    }

    @Override
    public String toString() {
        return "TrafficContext{" +
                "tag='" + tag + '\'' +
                ", laneName='" + laneName + '\'' +
                ", matchedRule='" + matchedRule + '\'' +
                ", metadata=" + metadata +
                '}';
    }
}
