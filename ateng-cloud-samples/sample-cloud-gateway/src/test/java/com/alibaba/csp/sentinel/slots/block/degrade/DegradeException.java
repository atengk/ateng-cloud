package com.alibaba.csp.sentinel.slots.block.degrade;

import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * 单元测试专用的 Sentinel DegradeException 模拟类
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class DegradeException extends BlockException {
    public DegradeException(String message) {
        super(message);
    }
}
