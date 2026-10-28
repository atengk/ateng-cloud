package io.github.atengk.cloud.sample.dubbo.api;

/**
 * 金丝雀灰度与标签路由演示 RPC 契约接口
 *
 * @author Ateng
 * @since 2026-10-28
 */
public interface GrayEchoRpcService {

    /**
     * 回声测试方法
     *
     * @param message 请求文本
     * @return 响应结果 (附带实例标识前缀)
     */
    String echo(String message);
}
