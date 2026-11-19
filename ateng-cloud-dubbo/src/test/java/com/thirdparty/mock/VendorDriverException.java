package com.thirdparty.mock;

/**
 * 模拟位于非白名单第三方包（非 java./javax./jakarta./io.github.atengk.）的底层驱动未受检异常，
 * 仅用于单元测试验证 {@code AtengDubboExceptionFilter} 的不可信 cause 剥离逻辑。
 *
 * @author Ateng
 * @since 2026-11-19
 */
public class VendorDriverException extends RuntimeException {

    public VendorDriverException(String message) {
        super(message);
    }

    public VendorDriverException(String message, Throwable cause) {
        super(message, cause);
    }
}
