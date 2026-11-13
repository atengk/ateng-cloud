package io.github.atengk.cloud.sample.seata.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.seata.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 高并发 @GlobalLock 全局排他锁演示控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Validated
@RestController
@RequestMapping("/sample/cloud/seata/lock")
public class SeataGlobalLockController {

    private static final Logger log = LoggerFactory.getLogger(SeataGlobalLockController.class);

    private final StorageService storageService;

    public SeataGlobalLockController(StorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * 演示声明式 @GlobalLock 校验商品库存（防脏写与脏读，免去 undo_log 开销）
     *
     * @param commodityCode 商品编码
     * @param count         需求数量
     * @return 库存是否充裕
     */
    @GetMapping("/declarative-check")
    public Result<Boolean> declarativeCheck(@RequestParam("commodityCode") String commodityCode,
                                           @RequestParam(value = "count", defaultValue = "1") Integer count) {
        log.info("执行声明式 @GlobalLock 库存校验: 商品 [{}], 数量 [{}]", commodityCode, count);
        boolean hasStock = storageService.checkAndLockStockDeclarative(commodityCode, count);
        return Result.success(hasStock);
    }

    /**
     * 演示编程式 SeataGlobalLockTemplate 在并发冲突下执行自旋退避并安全扣减库存
     *
     * @param commodityCode 商品编码
     * @param count         扣减数量
     * @return 扣减结果
     * @throws Throwable 执行异常
     */
    @PostMapping("/programmatic-update")
    public Result<Boolean> programmaticUpdate(@RequestParam("commodityCode") String commodityCode,
                                             @RequestParam(value = "count", defaultValue = "1") Integer count) throws Throwable {
        log.info("执行编程式 SeataGlobalLockTemplate#executeWithRetry 退避重试扣减库存: 商品 [{}], 数量 [{}]", commodityCode, count);
        boolean success = storageService.updateStockProgrammatic(commodityCode, count);
        return Result.success(success);
    }
}
