package com.alibaba.csp.sentinel.slots.system;

import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * 单元测试专用的 Sentinel SystemBlockException 模拟类
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SystemBlockException extends BlockException {
    public SystemBlockException(String message) {
        super(message);
    }
}
