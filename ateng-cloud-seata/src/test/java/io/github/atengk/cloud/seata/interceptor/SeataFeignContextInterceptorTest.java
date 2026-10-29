package io.github.atengk.cloud.seata.interceptor;

import feign.RequestTemplate;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feign 客户端 XID 透传拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SeataFeignContextInterceptorTest {

    private final SeataFeignContextInterceptor interceptor = new SeataFeignContextInterceptor();

    @BeforeEach
    @AfterEach
    void cleanContext() {
        RootContext.unbind();
    }

    @Test
    @DisplayName("测试当前线程存在全局事务 XID 时自动注入 TX_XID 出站请求头")
    void testInjectXidHeaderWhenGlobalTransactionActive() {
        String testXid = "192.168.1.100:8091:123456789";
        RootContext.bind(testXid);

        RequestTemplate template = new RequestTemplate();
        template.target("http://user-service/api/users");

        interceptor.apply(template);

        assertThat(template.headers()).containsKey(SeataConstants.TX_XID_HEADER);
        Collection<String> xidHeaders = template.headers().get(SeataConstants.TX_XID_HEADER);
        assertThat(xidHeaders).containsExactly(testXid);
    }

    @Test
    @DisplayName("测试无全局事务活跃时出站请求不注入 TX_XID")
    void testDoNotInjectWhenNoGlobalTransaction() {
        RequestTemplate template = new RequestTemplate();
        template.target("http://user-service/api/users");

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(SeataConstants.TX_XID_HEADER);
    }

    @Test
    @DisplayName("测试出站请求已有 TX_XID 时不重复注入")
    void testDoNotDuplicateExistingHeader() {
        String existingXid = "192.168.1.100:8091:999999999";
        RootContext.bind("192.168.1.100:8091:111111111");

        RequestTemplate template = new RequestTemplate();
        template.target("http://user-service/api/users");
        template.header(SeataConstants.TX_XID_HEADER, existingXid);

        interceptor.apply(template);

        Collection<String> xidHeaders = template.headers().get(SeataConstants.TX_XID_HEADER);
        assertThat(xidHeaders).containsExactly(existingXid);
    }
}
