package com.alibaba.csp.sentinel.slots.block.authority;

import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * 单元测试专用的 Sentinel AuthorityException 模拟类
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class AuthorityException extends BlockException {
    public AuthorityException(String message) {
        super(message);
    }
}
