package io.github.atengk.cloud.seata.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Seata 多租户内部表自动排除处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
class SeataTenantLineHandlerWrapperTest {

    @Test
    @DisplayName("测试 Seata 内部表自动忽略多租户条件注入")
    void testSeataInternalTablesIgnored() {
        TenantLineHandler mockDelegate = mock(TenantLineHandler.class);
        SeataTenantLineHandlerWrapper wrapper = new SeataTenantLineHandlerWrapper(mockDelegate);

        // 验证标准表名
        assertThat(wrapper.ignoreTable("undo_log")).isTrue();
        assertThat(wrapper.ignoreTable("tcc_fence_log")).isTrue();

        // 验证大写表名
        assertThat(wrapper.ignoreTable("UNDO_LOG")).isTrue();
        assertThat(wrapper.ignoreTable("TCC_FENCE_LOG")).isTrue();

        // 验证带反引号的表名
        assertThat(wrapper.ignoreTable("`undo_log`")).isTrue();
        assertThat(wrapper.ignoreTable("`tcc_fence_log`")).isTrue();
        assertThat(wrapper.ignoreTable("\"undo_log\"")).isTrue();
    }

    @Test
    @DisplayName("测试普通业务表正常委托给被装饰的 TenantLineHandler")
    void testBusinessTableDelegation() {
        TenantLineHandler mockDelegate = mock(TenantLineHandler.class);
        when(mockDelegate.ignoreTable("t_order")).thenReturn(false);
        when(mockDelegate.ignoreTable("sys_dict")).thenReturn(true);

        SeataTenantLineHandlerWrapper wrapper = new SeataTenantLineHandlerWrapper(mockDelegate);

        assertThat(wrapper.ignoreTable("t_order")).isFalse();
        assertThat(wrapper.ignoreTable("sys_dict")).isTrue();

        verify(mockDelegate).ignoreTable("t_order");
        verify(mockDelegate).ignoreTable("sys_dict");
    }

    @Test
    @DisplayName("测试租户 ID 与租户列名完全委托底层处理器")
    void testTenantIdAndColumnDelegation() {
        TenantLineHandler mockDelegate = mock(TenantLineHandler.class);
        Expression mockExpr = new LongValue(1001L);
        when(mockDelegate.getTenantId()).thenReturn(mockExpr);
        when(mockDelegate.getTenantIdColumn()).thenReturn("tenant_id");

        SeataTenantLineHandlerWrapper wrapper = new SeataTenantLineHandlerWrapper(mockDelegate);

        assertThat(wrapper.getTenantId()).isEqualTo(mockExpr);
        assertThat(wrapper.getTenantIdColumn()).isEqualTo("tenant_id");
        assertThat(wrapper.getDelegate()).isSameAs(mockDelegate);
    }

    @Test
    @DisplayName("测试 SeataTenantLineHandlerPostProcessor 后置包装逻辑")
    void testPostProcessor() {
        SeataTenantLineHandlerPostProcessor postProcessor = new SeataTenantLineHandlerPostProcessor();
        TenantLineHandler mockDelegate = mock(TenantLineHandler.class);

        // 正常包装未包装的处理器
        Object processed = postProcessor.postProcessAfterInitialization(mockDelegate, "tenantLineHandler");
        assertThat(processed).isInstanceOf(SeataTenantLineHandlerWrapper.class);
        assertThat(((SeataTenantLineHandlerWrapper) processed).getDelegate()).isSameAs(mockDelegate);

        // 避免重复包装已包装的实例
        Object processedAgain = postProcessor.postProcessAfterInitialization(processed, "tenantLineHandler");
        assertThat(processedAgain).isSameAs(processed);

        // 非 TenantLineHandler 实例原样返回
        String stringBean = "otherBean";
        assertThat(postProcessor.postProcessAfterInitialization(stringBean, "stringBean")).isSameAs(stringBean);
    }

    @Test
    @DisplayName("测试自定义 TCC 防卫表名动态排除与多租户忽略保护")
    void testCustomTccFenceTableIgnored() {
        TenantLineHandler mockDelegate = mock(TenantLineHandler.class);
        when(mockDelegate.ignoreTable("t_order")).thenReturn(false);

        SeataTenantLineHandlerWrapper wrapper = new SeataTenantLineHandlerWrapper(mockDelegate, "my_custom_tcc_fence");

        // 默认内部表仍受保护
        assertThat(wrapper.ignoreTable("undo_log")).isTrue();
        assertThat(wrapper.ignoreTable("tcc_fence_log")).isTrue();

        // 自定义表名及其各种格式自动忽略
        assertThat(wrapper.ignoreTable("my_custom_tcc_fence")).isTrue();
        assertThat(wrapper.ignoreTable("MY_CUSTOM_TCC_FENCE")).isTrue();
        assertThat(wrapper.ignoreTable("`my_custom_tcc_fence`")).isTrue();
        assertThat(wrapper.ignoreTable("\"my_custom_tcc_fence\"")).isTrue();

        // 普通业务表正常委托
        assertThat(wrapper.ignoreTable("t_order")).isFalse();
    }

    @Test
    @DisplayName("测试 SeataTenantLineHandlerPostProcessor 携带自定义表名包装")
    void testPostProcessorWithCustomTable() {
        SeataTenantLineHandlerPostProcessor postProcessor = new SeataTenantLineHandlerPostProcessor("custom_fence_table");
        assertThat(postProcessor.getCustomTccTableName()).isEqualTo("custom_fence_table");

        TenantLineHandler mockDelegate = mock(TenantLineHandler.class);
        Object processed = postProcessor.postProcessAfterInitialization(mockDelegate, "tenantLineHandler");
        assertThat(processed).isInstanceOf(SeataTenantLineHandlerWrapper.class);

        SeataTenantLineHandlerWrapper wrapper = (SeataTenantLineHandlerWrapper) processed;
        assertThat(wrapper.ignoreTable("custom_fence_table")).isTrue();
        assertThat(wrapper.ignoreTable("`custom_fence_table`")).isTrue();
        assertThat(wrapper.ignoreTable("undo_log")).isTrue();
    }
}
