package io.github.atengk.cloud.sample.dubbo.exception;

/**
 * 模拟第三方底层数据库驱动私有未受检异常
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class CustomDatabaseException extends RuntimeException {

    public CustomDatabaseException(String message) {
        super(message);
    }
}
