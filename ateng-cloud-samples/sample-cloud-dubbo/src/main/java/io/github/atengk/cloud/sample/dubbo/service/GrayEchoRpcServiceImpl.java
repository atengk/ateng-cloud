package io.github.atengk.cloud.sample.dubbo.service;

import io.github.atengk.cloud.sample.dubbo.api.GrayEchoRpcService;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * 基线（默认无标签）回声服务实现
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DubboService
public class GrayEchoRpcServiceImpl implements GrayEchoRpcService {

    @Override
    public String echo(String message) {
        return "baseline:" + message;
    }
}
