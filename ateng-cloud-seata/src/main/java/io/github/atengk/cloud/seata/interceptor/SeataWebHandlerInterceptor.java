package io.github.atengk.cloud.seata.interceptor;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Web 入站请求 Seata 全局事务上下文生命周期拦截器
 * <p>
 * 在微服务入站请求处理前从请求头解析全局事务 XID 并绑定至当前线程 RootContext；
 * 在请求生命周期彻底结束（afterCompletion）时执行确定性解绑，杜绝 Web 容器线程池复用导致的上下文污染。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataWebHandlerInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SeataWebHandlerInterceptor.class);

    /**
     * 当前请求是否由当前拦截器绑定 XID 的上下文属性标识
     */
    public static final String BIND_XID_ATTRIBUTE = "ATENG_SEATA_BOUND_XID";

    /**
     * 入站前 MDC 原有 XID 上下文属性标识（用于请求结束时保真恢复）
     */
    public static final String PREVIOUS_MDC_XID_ATTRIBUTE = "ATENG_SEATA_PREVIOUS_MDC_XID";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String rpcXid = request.getHeader(SeataConstants.TX_XID_HEADER);
        if (!StringUtils.hasText(rpcXid)) {
            // 兼容性尝试读取原生 RootContext KEY_XID
            rpcXid = request.getHeader(RootContext.KEY_XID);
        }

        if (StringUtils.hasText(rpcXid)) {
            String currentXid = RootContext.getXID();
            if (currentXid == null) {
                String previousMdc = MDC.get(SeataConstants.MDC_KEY_XID);
                if (previousMdc != null) {
                    request.setAttribute(PREVIOUS_MDC_XID_ATTRIBUTE, previousMdc);
                }
                RootContext.bind(rpcXid);
                MDC.put(SeataConstants.MDC_KEY_XID, rpcXid);
                request.setAttribute(BIND_XID_ATTRIBUTE, rpcXid);
                log.debug("Seata Web 入站请求成功绑定全局事务 XID 与 MDC 黄金关联: [{}] (URI: [{}])", rpcXid, request.getRequestURI());
            } else if (!rpcXid.equalsIgnoreCase(currentXid)) {
                log.warn("Seata Web 入站请求携带 XID [{}] 与当前线程已绑定的 XID [{}] 不一致，跳过重新绑定",
                        rpcXid, currentXid);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String boundXid = (String) request.getAttribute(BIND_XID_ATTRIBUTE);
        if (StringUtils.hasText(boundXid)) {
            try {
                String currentXid = RootContext.getXID();
                if (boundXid.equals(currentXid)) {
                    log.debug("Seata Web 请求结束确定性解绑全局事务 XID: [{}]", boundXid);
                } else {
                    log.warn("Seata Web 请求结束时当前 XID [{}] 与入站绑定的 XID [{}] 不一致，执行强制安全解绑",
                            currentXid, boundXid);
                }
            } finally {
                RootContext.unbind();
                String previousMdc = (String) request.getAttribute(PREVIOUS_MDC_XID_ATTRIBUTE);
                if (previousMdc != null) {
                    MDC.put(SeataConstants.MDC_KEY_XID, previousMdc);
                } else {
                    MDC.remove(SeataConstants.MDC_KEY_XID);
                }
                request.removeAttribute(BIND_XID_ATTRIBUTE);
                request.removeAttribute(PREVIOUS_MDC_XID_ATTRIBUTE);
            }
        }
    }
}
