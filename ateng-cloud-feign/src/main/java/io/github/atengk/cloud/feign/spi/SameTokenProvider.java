package io.github.atengk.cloud.feign.spi;

/**
 * 内部微服务受信凭据 (Same-Token) 获取接口 SPI
 * <p>
 * 用于在 Feign 出站请求时动态注入内部微服务防穿透凭据，
 * 支持无侵入平滑接入 Sa-Token 或自定义受信凭据体系。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@FunctionalInterface
public interface SameTokenProvider {

    /**
     * 获取内部微服务受信凭据
     *
     * @return 受信凭据令牌字符串，无可用凭据时返回 null
     */
    String getSameToken();
}
