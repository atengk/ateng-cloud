package com.thirdparty.mock;

/**
 * 模拟位于非白名单第三方包（非 java./javax./jakarta./io.github.atengk.）的底层驱动未受检异常，
 * 用于 sample-cloud-dubbo Triple RPC 跨进程序列化与 cause 剥离端到端回归测试。
 *
 * @author Ateng
 * @since 2026-11-19
 */
public class VendorDriverException extends RuntimeException {

    public VendorDriverException(String message) {
        super(message);
    }
}
