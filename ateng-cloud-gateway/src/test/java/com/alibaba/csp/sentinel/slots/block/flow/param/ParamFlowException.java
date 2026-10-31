package com.alibaba.csp.sentinel.slots.block.flow.param;

import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * 单元测试专用的 Sentinel ParamFlowException 模拟类
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class ParamFlowException extends BlockException {
    public ParamFlowException(String message) {
        super(message);
    }
}
