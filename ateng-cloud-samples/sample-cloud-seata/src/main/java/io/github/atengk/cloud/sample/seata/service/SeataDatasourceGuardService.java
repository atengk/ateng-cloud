package io.github.atengk.cloud.sample.seata.service;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.cloud.sample.seata.model.vo.DatasourceGuardVO;
import org.apache.seata.core.context.RootContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 动态数据源假切源安全守卫编排服务
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Service
public class SeataDatasourceGuardService {

    private final SeataSlaveDatasourceService slaveDatasourceService;

    public SeataDatasourceGuardService(SeataSlaveDatasourceService slaveDatasourceService) {
        this.slaveDatasourceService = slaveDatasourceService;
    }

    /**
     * 在开启 Seata 全局分布式事务（绑定 XID）的上下文中执行 @DS("slave") 跨库合法切换
     *
     * @param xid 全局事务唯一标识（为空时生成测试 XID）
     * @return 切源放行结果视图对象
     */
    @Transactional(rollbackFor = Exception.class)
    public DatasourceGuardVO executeSwitchInGlobalTx(String xid) {
        String effectiveXid = StringUtils.hasText(xid)
                ? xid
                : "127.0.0.1:8091:ds-guard-global-" + System.currentTimeMillis();
        boolean manuallyBound = false;
        if (!RootContext.inGlobalTransaction()) {
            RootContext.bind(effectiveXid);
            manuallyBound = true;
        }
        DynamicDataSourceContextHolder.push("master");
        try {
            String targetDs = slaveDatasourceService.executeOnSlave();
            return new DatasourceGuardVO(
                    "master",
                    targetDs,
                    RootContext.getXID(),
                    true,
                    "活跃全局分布式事务 XID 已激活，SeataDynamicDatasourceGuardAspect 合法放行 @DS(\"slave\") 跨库切源"
            );
        } finally {
            DynamicDataSourceContextHolder.clear();
            if (manuallyBound && RootContext.inGlobalTransaction()) {
                RootContext.unbind();
            }
        }
    }

    /**
     * 在普通 Spring 单物理连接事务（无全局 XID）中触发 @DS("slave") 非法跨库假切源
     * <p>
     * 预期被 {@code SeataDynamicDatasourceGuardAspect} 前置拦截并抛出 5105 业务异常。
     * </p>
     *
     * @return 正常情况下不应返回（将被 5105 异常阻断）
     */
    @Transactional(rollbackFor = Exception.class)
    public DatasourceGuardVO executeFalseSwitchInLocalTx() {
        String previousXid = RootContext.getXID();
        if (StringUtils.hasText(previousXid)) {
            RootContext.unbind();
        }
        DynamicDataSourceContextHolder.push("master");
        try {
            String targetDs = slaveDatasourceService.executeOnSlave();
            return new DatasourceGuardVO(
                    "master",
                    targetDs,
                    null,
                    true,
                    "未触发假切源阻断（请确认 guard-enabled 与 fail-on-false-switch 配置）"
            );
        } finally {
            DynamicDataSourceContextHolder.clear();
            if (StringUtils.hasText(previousXid)) {
                RootContext.bind(previousXid);
            }
        }
    }
}
