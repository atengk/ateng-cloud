package io.github.atengk.cloud.feign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign GZIP 响应压缩请求拦截器
 * 为出站请求自动挂载 Accept-Encoding: gzip, deflate 标头，告知对端支持接收 GZIP 压缩流
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignAcceptGzipEncodingInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        if (!template.headers().containsKey("Accept-Encoding")) {
            template.header("Accept-Encoding", "gzip", "deflate");
        }
    }
}
