package io.github.atengk.cloud.nacos.context;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link NacosContextBarrier} 三元上下文快照隔离屏障单元测试
 *
 * @author Ateng
 * @since 2026-11-11
 */
class NacosContextBarrierTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("空白后台线程执行屏障：自动生成 traceId 注入，出口 100% 强制清空用户、租户与 ignoreTenant 状态")
    void testBarrierIsolatesEmptyWorkerThread() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        AtomicReference<String> insideTraceId = new AtomicReference<>();

        NacosContextBarrier.run(() -> {
            insideTraceId.set(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY));
            // 故意在回调内部污染三元上下文
            UserContextHolder.setUserId("polluted-user-001");
            TenantContextHolder.setTenantId("polluted-tenant-999");
            TenantContextHolder.setIgnoreTenant(true);
            MDC.put(NacosContextBarrier.MDC_TRACE_ID_KEY, "dirty-trace-id");
        });

        assertThat(insideTraceId.get()).isNotBlank();
        assertThat(insideTraceId.get()).hasSize(32).doesNotContain("-");

        // 退出屏障后必须彻底恢复为洁净空状态
        assertThat(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY)).isNull();
        assertThat(UserContextHolder.getUserId()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
    }

    @Test
    @DisplayName("已有业务请求线程执行屏障：延续并允许覆盖自定义 traceId，出口精确恢复原始三元上下文快照")
    void testBarrierRestoresCallerSnapshotOnNormalAndExceptionalExit() {
        MDC.put(NacosContextBarrier.MDC_TRACE_ID_KEY, "caller-trace-888");
        UserContextHolder.setUserId("caller-user-1001");
        TenantContextHolder.setTenantId("caller-tenant-2002");
        TenantContextHolder.setIgnoreTenant(true);

        // 1. 正常返回并验证传递 customTraceId 覆盖以及回调内恶意清空后的还原能力
        String result = NacosContextBarrier.call("custom-sub-trace-001", () -> {
            assertThat(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY)).isEqualTo("custom-sub-trace-001");
            assertThat(UserContextHolder.getUserId()).isEqualTo("caller-user-1001");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("caller-tenant-2002");
            assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();

            UserContextHolder.clear();
            TenantContextHolder.clear();
            MDC.clear();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY)).isEqualTo("caller-trace-888");
        assertThat(UserContextHolder.getUserId()).isEqualTo("caller-user-1001");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("caller-tenant-2002");
        assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();

        // 2. 回调内篡改上下文并抛出异常，依然 100% 对称还原
        assertThatThrownBy(() -> NacosContextBarrier.run(() -> {
            assertThat(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY)).isEqualTo("caller-trace-888");
            UserContextHolder.setUserId("temp-other-user");
            TenantContextHolder.setTenantId("temp-other-tenant");
            TenantContextHolder.setIgnoreTenant(false);
            throw new IllegalStateException("Simulated callback crash");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated callback crash");

        assertThat(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY)).isEqualTo("caller-trace-888");
        assertThat(UserContextHolder.getUserId()).isEqualTo("caller-user-1001");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("caller-tenant-2002");
        assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
    }

    @Test
    @DisplayName("callWithTrace 透传激活 traceId 并在异常与正常出口对称清理还原")
    void testCallWithTracePassesActiveTraceId() {
        MDC.clear();
        String generatedTrace = NacosContextBarrier.callWithTrace(traceId -> {
            assertThat(traceId).hasSize(32).doesNotContain("-");
            assertThat(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY)).isEqualTo(traceId);
            return traceId;
        });
        assertThat(generatedTrace).hasSize(32);
        assertThat(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY)).isNull();

        MDC.put(NacosContextBarrier.MDC_TRACE_ID_KEY, "existing-trace-xyz");
        String reusedTrace = NacosContextBarrier.callWithTrace(traceId -> traceId);
        assertThat(reusedTrace).isEqualTo("existing-trace-xyz");
        assertThat(MDC.get(NacosContextBarrier.MDC_TRACE_ID_KEY)).isEqualTo("existing-trace-xyz");
    }
}
