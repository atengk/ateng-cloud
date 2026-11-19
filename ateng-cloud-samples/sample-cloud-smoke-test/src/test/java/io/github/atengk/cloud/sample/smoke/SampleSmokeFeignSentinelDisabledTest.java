package io.github.atengk.cloud.sample.smoke;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.cloud.sample.smoke.client.SmokeProductFeignClient;
import io.github.atengk.cloud.sample.smoke.model.SmokeProductVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 全家桶显式关闭 {@code feign.sentinel.enabled=false} 时 Feign 客户端双态回归测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "feign.sentinel.enabled=false",
                "ateng.cloud.feign.httpclient.socket-timeout=15000ms"
        }
)
@DirtiesContext
class SampleSmokeFeignSentinelDisabledTest {

    private static final int SERVER_PORT = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> SERVER_PORT);
        registry.add("dubbo.protocol.port", TestSocketUtils::findAvailableTcpPort);
    }

    @Autowired
    private SmokeProductFeignClient productFeignClient;

    @Test
    @DisplayName("feign.sentinel.enabled=false 下 Feign 双模解包与 BusinessException 剥壳正常")
    void testFeignWithSentinelDisabled() {
        SmokeProductVO unwrapped = productFeignClient.getUnwrappedResult(404L);
        assertThat(unwrapped).isNotNull();
        assertThat(unwrapped.id()).isEqualTo(404L);

        BusinessException ex = assertThrows(BusinessException.class, () -> productFeignClient.getBusinessError());
        assertThat(ex.getCode()).isEqualTo(4221);
        assertThat(ex.getMsg()).isEqualTo("库存不足");
        assertThat(ex.getTraceId()).isEqualTo("smoke-biz-trace-001");
    }
}
