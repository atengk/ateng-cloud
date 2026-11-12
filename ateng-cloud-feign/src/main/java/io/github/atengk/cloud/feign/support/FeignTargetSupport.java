package io.github.atengk.cloud.feign.support;

import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import io.github.atengk.cloud.feign.properties.AtengFeignClientProperties;
import io.github.atengk.cloud.feign.properties.AtengFeignProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Feign 客户端目标标识解析与隔离配置查找支持类
 * <p>
 * 统一规范全组件（配置同步、自适应解码、防雪崩重试与载荷防爆）的客户端名称解析优先级：
 * 1. 优先读取 {@link FeignClient#contextId()}；
 * 2. 其次读取 {@link FeignClient#value()} / {@link FeignClient#name()}；
 * 3. 最后回退至 Feign 运行时目标名 {@link Target#name()}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-12
 */
public final class FeignTargetSupport {

    /**
     * 默认响应报文大小硬防爆阈值（10MB）
     */
    public static final long DEFAULT_MAX_RESPONSE_SIZE_BYTES = 10 * 1024 * 1024L;

    /**
     * 默认慢调用告警阈值（2s）
     */
    public static final Duration DEFAULT_SLOW_CALL_THRESHOLD = Duration.ofSeconds(2);

    private FeignTargetSupport() {
    }

    /**
     * 从 Feign HTTP 响应关联的请求上下文解析有序候选客户端名称列表
     *
     * @param response Feign 响应对象
     * @return 按优先级排序且去重的候选名称列表（可能为空列表）
     */
    public static List<String> resolveCandidateNames(Response response) {
        if (response == null || response.request() == null) {
            return Collections.emptyList();
        }
        return resolveCandidateNames(response.request().requestTemplate());
    }

    /**
     * 从 Feign 请求模板解析有序候选客户端名称列表
     *
     * @param template Feign 请求模板
     * @return 按优先级排序且去重的候选名称列表（可能为空列表）
     */
    public static List<String> resolveCandidateNames(RequestTemplate template) {
        if (template == null) {
            return Collections.emptyList();
        }
        Target<?> target = template.feignTarget();
        Class<?> targetType = resolveTargetType(template);
        String targetName = target != null ? target.name() : null;
        return resolveCandidateNames(targetType, null, targetName);
    }

    /**
     * 综合目标接口类型、显式上下文 ID 与运行时目标名称，构建有序去重的候选客户端名称列表
     *
     * @param targetType        Feign 客户端接口类型
     * @param explicitContextId 显式传入的 contextId（如来自 FeignClientFactoryBean）
     * @param targetName        Feign 运行时目标名称
     * @return 不可变且按优先级排序的候选名称列表
     */
    public static List<String> resolveCandidateNames(Class<?> targetType, String explicitContextId, String targetName) {
        Set<String> candidates = new LinkedHashSet<>(4);
        addIfResolved(candidates, explicitContextId);

        if (targetType != null) {
            FeignClient feignClient = AnnotatedElementUtils.findMergedAnnotation(targetType, FeignClient.class);
            if (feignClient != null) {
                addIfResolved(candidates, feignClient.contextId());
                addIfResolved(candidates, feignClient.value());
                addIfResolved(candidates, feignClient.name());
            }
        }

        addIfResolved(candidates, targetName);
        return candidates.isEmpty() ? Collections.emptyList() : List.copyOf(candidates);
    }

    /**
     * 从有序候选客户端名称列表中提取最高优先级的主客户端名称
     *
     * @param candidateNames 有序候选名称列表
     * @return 主客户端名称，列表为空则返回 null
     */
    public static String resolvePrimaryClientName(List<String> candidateNames) {
        return candidateNames == null || candidateNames.isEmpty() ? null : candidateNames.get(0);
    }

    /**
     * 按候选名称优先级顺序查找单客户端独立配置属性
     *
     * @param properties     全局 Feign 配置属性
     * @param candidateNames 有序候选名称列表
     * @param extractor      单客户端配置字段提取函数
     * @param <T>            目标配置值类型
     * @return 首个非空的配置值，全部未命中则返回 null
     */
    public static <T> T resolveClientSetting(AtengFeignProperties properties,
                                             List<String> candidateNames,
                                             Function<AtengFeignClientProperties, T> extractor) {
        if (properties == null || properties.getClients() == null
                || candidateNames == null || candidateNames.isEmpty() || extractor == null) {
            return null;
        }
        Map<String, AtengFeignClientProperties> clients = properties.getClients();
        for (String candidate : candidateNames) {
            AtengFeignClientProperties clientProps = clients.get(candidate);
            if (clientProps != null) {
                T value = extractor.apply(clientProps);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 解析自适应契约解包开关状态（按候选优先级匹配单客户端覆盖与排除名单，最后回退全局开关）
     *
     * @param properties     全局 Feign 配置属性
     * @param candidateNames 有序候选名称列表
     * @return true 表示启用自适应解包，false 表示禁用
     */
    public static boolean resolveClientAdaptiveUnwrap(AtengFeignProperties properties, List<String> candidateNames) {
        if (properties == null) {
            return false;
        }
        List<String> excludeClients = properties.getAdaptiveUnwrap() != null
                && properties.getAdaptiveUnwrap().getExcludeClients() != null
                ? properties.getAdaptiveUnwrap().getExcludeClients()
                : Collections.emptyList();
        Map<String, AtengFeignClientProperties> clients = properties.getClients();

        if (candidateNames != null) {
            for (String candidate : candidateNames) {
                if (clients != null) {
                    AtengFeignClientProperties clientProps = clients.get(candidate);
                    if (clientProps != null && clientProps.getAdaptiveUnwrap() != null) {
                        return clientProps.getAdaptiveUnwrap();
                    }
                }
                if (excludeClients.contains(candidate)) {
                    return false;
                }
            }
        }

        return properties.getAdaptiveUnwrap() != null && properties.getAdaptiveUnwrap().isEnabled();
    }

    /**
     * 解析响应报文大小硬防爆上限（按候选优先级匹配单客户端 maxResponseSize，未配置回退全局 payload.maxResponseSize）
     *
     * @param properties     全局 Feign 配置属性
     * @param candidateNames 有序候选名称列表
     * @return 允许的最大响应字节数
     * @throws IllegalArgumentException 当显式配置的阈值小于或等于 0 时抛出
     */
    public static long resolveMaxResponseSizeBytes(AtengFeignProperties properties, List<String> candidateNames) {
        DataSize clientSize = resolveClientSetting(properties, candidateNames, AtengFeignClientProperties::getMaxResponseSize);
        DataSize effectiveSize = clientSize != null
                ? clientSize
                : (properties != null && properties.getPayload() != null ? properties.getPayload().getMaxResponseSize() : null);
        if (effectiveSize != null) {
            long bytes = effectiveSize.toBytes();
            if (bytes <= 0) {
                throw new IllegalArgumentException("Feign max-response-size 必须大于 0 字节，当前配置: " + effectiveSize);
            }
            return bytes;
        }
        return DEFAULT_MAX_RESPONSE_SIZE_BYTES;
    }

    /**
     * 解析当前客户端有效慢调用告警阈值（按候选优先级匹配单客户端 slowCallThreshold，未配置回退全局 slowCallThreshold）
     *
     * @param properties     全局 Feign 配置属性
     * @param candidateNames 有序候选名称列表
     * @return 生效的慢调用阈值（为 null、0 或负数时表示关闭）
     */
    public static Duration resolveSlowCallThreshold(AtengFeignProperties properties, List<String> candidateNames) {
        Duration clientThreshold = resolveClientSetting(properties, candidateNames, AtengFeignClientProperties::getSlowCallThreshold);
        if (clientThreshold != null) {
            return clientThreshold;
        }
        if (properties != null) {
            return properties.getSlowCallThreshold();
        }
        return DEFAULT_SLOW_CALL_THRESHOLD;
    }

    /**
     * 解析响应关联的接口类型（优先读取方法元数据，其次回退 Target 类型）
     *
     * @param template Feign 请求模板
     * @return 接口 Class，无法提取则返回 null
     */
    public static Class<?> resolveTargetType(RequestTemplate template) {
        if (template == null) {
            return null;
        }
        if (template.methodMetadata() != null && template.methodMetadata().targetType() != null) {
            return template.methodMetadata().targetType();
        }
        if (template.feignTarget() != null) {
            return template.feignTarget().type();
        }
        return null;
    }

    private static void addIfResolved(Set<String> candidates, String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return;
        }
        String trimmed = rawName.trim();
        // 跳过未解析的占位符表达式（如 ${service.name}），交由后续已解析的 targetName 兜底
        if (trimmed.contains("${") && trimmed.contains("}")) {
            return;
        }
        candidates.add(trimmed);
    }
}
