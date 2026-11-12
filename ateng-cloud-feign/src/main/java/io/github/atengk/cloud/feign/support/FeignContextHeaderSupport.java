package io.github.atengk.cloud.feign.support;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/**
 * Feign 上下文标头与 MDC 值规范化共享辅助工具
 * <p>
 * 统一收敛空值、纯空白及 {@code "null"} 脏字面量防御性过滤逻辑，避免多处桥接器与过滤器重复实现。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-12
 */
public final class FeignContextHeaderSupport {

    private FeignContextHeaderSupport() {
    }

    /**
     * 规范化上下文文本值，剔除 {@code null}、纯空白字符以及忽略大小写的 {@code "null"} 脏字面量
     *
     * @param value 待规范化的原始文本
     * @return 去除首尾空白后的有效文本，非法或空值返回 {@code null}
     */
    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    /**
     * 按候选键优先级顺序读取并规范化 SLF4J MDC 中的首个有效值
     *
     * @param keys 候选 MDC 键名序列
     * @return 首个有效规范化值，全为空或非法时返回 {@code null}
     */
    public static String firstValidMdcValue(String... keys) {
        if (keys == null || keys.length == 0) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String value = normalize(MDC.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
