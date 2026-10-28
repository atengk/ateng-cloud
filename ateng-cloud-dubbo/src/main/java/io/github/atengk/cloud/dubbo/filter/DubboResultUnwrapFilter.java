package io.github.atengk.cloud.dubbo.filter;

/**
 * Dubbo 消费端双模契约校验过滤器（已废弃，请使用 {@link DubboContractAssertFilter}）
 *
 * @author Ateng
 * @since 2026-10-28
 * @deprecated 请使用 {@link DubboContractAssertFilter}
 */
@Deprecated(since = "1.0.0", forRemoval = false)
public class DubboResultUnwrapFilter extends DubboContractAssertFilter {
}
