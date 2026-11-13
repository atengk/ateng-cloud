package io.github.atengk.cloud.sentinel.fallback;

/**
 * 源头阻断计数标记契约
 *
 * <p>用于标识异常实例已在出站降级源头（如 {@link AtengFallbackFactory}）完成
 * {@code SentinelBlockCounter} 计数累加，供下游全局拦截切面执行幂等防重判定。</p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
public interface SentinelBlockTracked {

    /**
     * 判断当前异常是否已在源头完成阻断指标计数
     *
     * @return {@code true} 表示已计数，拦截切面应跳过重复累加
     */
    boolean isSourceCounted();
}
