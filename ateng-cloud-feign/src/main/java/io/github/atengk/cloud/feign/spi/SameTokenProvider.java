package io.github.atengk.cloud.feign.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部微服务受信凭据 (Same-Token) 获取接口 SPI
 * <p>
 * 用于在 Feign 出站请求时动态注入内部微服务防穿透凭据，并在服务端按需执行入站校验，
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

    /**
     * 校验入站请求携带的 Same-Token 受信凭据是否合法
     * <p>
     * 默认实现采用常量时间字节摘要比对，防范时序侧信道探测。
     * </p>
     *
     * @param token 入站请求携带的凭据令牌
     * @return true 表示校验通过，false 表示非法或无效
     */
    default boolean isValidSameToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String expected = getSameToken();
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8));
    }
}
