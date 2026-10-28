package io.github.atengk.cloud.feign.support;

import cn.dev33.satoken.same.SaSameUtil;
import io.github.atengk.cloud.feign.spi.SameTokenProvider;

/**
 * 基于 Sa-Token 官方体系的 Same-Token 内部受信凭据提取实现
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class SaTokenSameTokenProvider implements SameTokenProvider {

    @Override
    public String getSameToken() {
        return SaSameUtil.getToken();
    }
}
