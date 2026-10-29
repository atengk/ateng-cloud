package io.github.atengk.cloud.seata.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * Feign 客户端 Seata 全局事务 XID 透明透传拦截器
 * <p>
 * 在发起微服务跨进程声明式 HTTP 调用时，自动感知当前线程绑定的 Seata 全局事务 ID（XID），
 * 并以标准标头 (TX_XID) 注入出站请求中，确保下游微服务透明加入同一全局事务。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataFeignContextInterceptor implements RequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SeataFeignContextInterceptor.class);

    @Override
    public void apply(RequestTemplate template) {
        String xid = RootContext.getXID();
        if (StringUtils.hasText(xid)) {
            // 防御性去重：若出站模板中尚未包含 TX_XID 标头则透明注入
            if (!template.headers().containsKey(SeataConstants.TX_XID_HEADER)) {
                template.header(SeataConstants.TX_XID_HEADER, xid);
                log.debug("Seata Feign 出站调用透明注入全局事务 XID 标头: [{}] -> [{}]",
                        xid, template.url());
            }
        }
    }
}
