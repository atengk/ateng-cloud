package com.alibaba.csp.sentinel.slots.block.flow;

import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * 单元测试专用的 Sentinel FlowException 模拟类
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class FlowException extends BlockException {
    public FlowException(String message) {
        super(message);
    }
}
