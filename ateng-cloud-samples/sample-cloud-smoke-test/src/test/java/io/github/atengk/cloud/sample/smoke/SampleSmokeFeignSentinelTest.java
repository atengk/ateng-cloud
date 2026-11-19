package io.github.atengk.cloud.sample.smoke;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.smoke.client.SmokeFallbackFeignClient;
import io.github.atengk.cloud.sample.smoke.client.SmokeProductFeignClient;
import io.github.atengk.cloud.sample.smoke.model.SmokeProductVO;
import io.github.atengk.cloud.sentinel.code.SentinelResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 全家桶（ateng-cloud-all）Feign 与 Sentinel 默认共存集成冒烟回归测试
 * <p>
 * 验证在默认 {@code feign.sentinel.enabled=true} 下容器正常启动（无 {@link UnsupportedOperationException}），
 * 且裸 DTO / {@link Result} 自适应解包、非 200 转 {@link BusinessException}（含远端 {@code traceId}）与
 * {@code AtengFallbackFactory} 熔断降级全部正常工作。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "ateng.cloud.feign.httpclient.socket-timeout=15000ms"
        }
)
@DirtiesContext
class SampleSmokeFeignSentinelTest {

    private static final int SERVER_PORT = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void registerDynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> SERVER_PORT);
        registry.add("dubbo.protocol.port", TestSocketUtils::findAvailableTcpPort);
    }

    @Autowired
    private SmokeProductFeignClient productFeignClient;

    @Autowired
    private SmokeFallbackFeignClient fallbackFeignClient;

    @Test
    @DisplayName("默认 feign.sentinel.enabled=true 下 Feign 裸 DTO 与 Result<T> 双模自适应解包正常")
    void testFeignWithSentinelEnabledAdaptiveUnwrap() {
        SmokeProductVO bareDto = productFeignClient.getBareDto(101L);
        assertThat(bareDto).isNotNull();
        assertThat(bareDto.id()).isEqualTo(101L);
        assertThat(bareDto.name()).isEqualTo("Smoke-Bare-Product-101");

        SmokeProductVO unwrapped = productFeignClient.getUnwrappedResult(202L);
        assertThat(unwrapped).isNotNull();
        assertThat(unwrapped.id()).isEqualTo(202L);
        assertThat(unwrapped.name()).isEqualTo("Smoke-Result-Product-202");

        Result<SmokeProductVO> rawResult = productFeignClient.getRawResult(303L);
        assertThat(rawResult.isSuccess()).isTrue();
        assertThat(rawResult.getData().id()).isEqualTo(303L);

        // 验证外层 SentinelProxyInvocationHandler 未破坏 Object.equals 自反性与 hashCode/toString 契约
        assertThat(productFeignClient).isEqualTo(productFeignClient);
        assertThat(productFeignClient).isNotEqualTo(fallbackFeignClient);
        assertThat(productFeignClient.toString()).isNotBlank();
    }

    @Test
    @DisplayName("无 fallback 的 SentinelFeign 调用非 200 Result 直接抛出剥壳后的 BusinessException 并保留 traceId")
    void testFeignWithSentinelEnabledUnwrapsBusinessException() {
        BusinessException ex = assertThrows(BusinessException.class, () -> productFeignClient.getBusinessError());
        assertThat(ex.getCode()).isEqualTo(4221);
        assertThat(ex.getMsg()).isEqualTo("库存不足");
        assertThat(ex.getTraceId()).isEqualTo("smoke-biz-trace-001");
    }

    @AfterEach
    void clearSentinelRules() {
        FlowRuleManager.loadRules(Collections.emptyList());
        DegradeRuleManager.loadRules(Collections.emptyList());
    }

    @Test
    @DisplayName("挂载 AtengFallbackFactory 的 Feign 客户端：业务异常原样透传、流控返回 4201、熔断触发 DEGRADE 4202")
    void testFeignWithSentinelFallbackFactory() {
        // 1. 业务失败（DecodeException 内嵌 BusinessException）被解包并原样透传，保留 traceId
        BusinessException bizEx = assertThrows(BusinessException.class, () -> fallbackFeignClient.triggerBusinessError());
        assertThat(bizEx.getCode()).isEqualTo(4221);
        assertThat(bizEx.getMsg()).isEqualTo("库存不足");
        assertThat(bizEx.getTraceId()).isEqualTo("smoke-biz-trace-001");

        // 2. 下游 500 标准 Result 错误先由 AtengFeignErrorDecoder 转译（含 traceId），第二次触发熔断规则后转为 DEGRADE 4202
        String serverErrorResource = "GET:http://127.0.0.1:" + SERVER_PORT + "/sample/cloud/smoke/feign/server-error";
        DegradeRule degradeRule = new DegradeRule(serverErrorResource)
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT)
                .setCount(0)
                .setMinRequestAmount(1)
                .setStatIntervalMs(60000)
                .setTimeWindow(10);
        DegradeRuleManager.loadRules(List.of(degradeRule));

        BusinessException upstream500 = assertThrows(BusinessException.class, () -> fallbackFeignClient.triggerServerError());
        assertThat(upstream500.getCode()).isEqualTo(500);
        assertThat(upstream500.getTraceId()).isEqualTo("smoke-err-trace-002");

        BusinessException degradeEx = assertThrows(BusinessException.class, () -> fallbackFeignClient.triggerServerError());
        assertThat(degradeEx.getCode()).isEqualTo(SentinelResultCode.DEGRADE.getCode());

        // 3. 命中客户端 FlowRule 时通过 AtengFallbackFactory 映射为 FLOW_LIMIT 4201
        FlowRule flowRule = new FlowRule(serverErrorResource)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(0);
        FlowRuleManager.loadRules(List.of(flowRule));

        BusinessException flowEx = assertThrows(BusinessException.class, () -> fallbackFeignClient.triggerServerError());
        assertThat(flowEx.getCode()).isEqualTo(SentinelResultCode.FLOW_LIMIT.getCode());
    }
}
