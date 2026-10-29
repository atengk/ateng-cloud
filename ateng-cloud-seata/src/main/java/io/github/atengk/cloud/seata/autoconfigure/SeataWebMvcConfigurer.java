package io.github.atengk.cloud.seata.autoconfigure;

import io.github.atengk.cloud.seata.constant.SeataConstants;
import io.github.atengk.cloud.seata.interceptor.SeataWebHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Seata WebMVC 拦截器注册配置类
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class SeataWebMvcConfigurer implements WebMvcConfigurer {

    private final SeataWebHandlerInterceptor seataWebHandlerInterceptor;

    public SeataWebMvcConfigurer(SeataWebHandlerInterceptor seataWebHandlerInterceptor) {
        this.seataWebHandlerInterceptor = seataWebHandlerInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(seataWebHandlerInterceptor)
                .addPathPatterns("/**")
                .order(SeataConstants.WEB_INTERCEPTOR_ORDER);
    }
}
