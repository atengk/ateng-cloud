package io.github.atengk.cloud.sample.seata.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.seata.model.vo.DatasourceGuardVO;
import io.github.atengk.cloud.sample.seata.service.SeataDatasourceGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态数据源假切源安全守卫（False Datasource Switch Guard）演示控制器
 *
 * @author Ateng
 * @since 2026-11-13
 */
@RestController
@RequestMapping("/sample/cloud/seata/datasource-guard")
public class SeataDatasourceGuardController {

    private static final Logger log = LoggerFactory.getLogger(SeataDatasourceGuardController.class);

    private final SeataDatasourceGuardService datasourceGuardService;

    public SeataDatasourceGuardController(SeataDatasourceGuardService datasourceGuardService) {
        this.datasourceGuardService = datasourceGuardService;
    }

    /**
     * 演示活跃全局分布式事务下 @DS("slave") 跨库切源合法放行
     *
     * @param xid 可选全局事务 XID
     * @return 切源放行结果视图对象
     */
    @PostMapping("/allow-in-global-tx")
    public Result<DatasourceGuardVO> allowInGlobalTransaction(@RequestParam(value = "xid", required = false) String xid) {
        log.info("演示活跃全局事务下声明式 @DS 跨库切源放行: xid=[{}]", xid);
        return Result.success(datasourceGuardService.executeSwitchInGlobalTx(xid));
    }

    /**
     * 演示普通 Spring 单连接事务下非法跨库 @DS("slave") 触发 5105 刚性阻断
     *
     * @return 响应结果（预期被全局异常处理器转译为 5105 错误码）
     */
    @PostMapping("/block-false-switch")
    public Result<DatasourceGuardVO> blockFalseSwitch() {
        log.info("演示 Spring 单连接事务下声明式 @DS 假切源阻断 (5105)");
        return Result.success(datasourceGuardService.executeFalseSwitchInLocalTx());
    }
}
