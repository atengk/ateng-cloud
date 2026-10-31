package com.alibaba.csp.sentinel.slots.block;

/**
 * 单元测试专用的 Sentinel BlockException 模拟类
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class BlockException extends Exception {
    public BlockException(String message) {
        super(message);
    }
}
