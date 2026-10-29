package io.github.atengk.cloud.sample.seata.service;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.cloud.seata.lock.SeataGlobalLockTemplate;
import org.apache.seata.spring.annotation.GlobalLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存服务（提供 AT 模式库存扣减与 @GlobalLock 高并发轻量锁校验）
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SeataGlobalLockTemplate globalLockTemplate;

    public StorageService(JdbcTemplate jdbcTemplate, SeataGlobalLockTemplate globalLockTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.globalLockTemplate = globalLockTemplate;
    }

    public void initStock(String commodityCode, int count) {
        jdbcTemplate.update("MERGE INTO storage_tbl (commodity_code, count) KEY(commodity_code) VALUES (?, ?)", commodityCode, count);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deduct(String commodityCode, int count) {
        log.info("执行库存扣减: 商品 [{}], 扣减数量 [{}]", commodityCode, count);
        int updated = jdbcTemplate.update(
                "UPDATE storage_tbl SET count = count - ? WHERE commodity_code = ? AND count >= ?",
                count, commodityCode, count
        );
        BusinessAssert.isTrue(updated > 0, "库存不足，扣减失败: %s", commodityCode);
    }

    public int getStock(String commodityCode) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT count FROM storage_tbl WHERE commodity_code = ?",
                Integer.class,
                commodityCode
        );
        return stock != null ? stock : 0;
    }

    /**
     * 声明式 @GlobalLock 轻量全局排他锁防脏读
     *
     * @param commodityCode 商品编码
     * @param count         需求数量
     * @return 库存充裕返回 true
     */
    @GlobalLock
    public boolean checkAndLockStockDeclarative(String commodityCode, int count) {
        log.info("声明式 @GlobalLock 校验商品库存: [{}]", commodityCode);
        return getStock(commodityCode) >= count;
    }

    /**
     * 编程式 SeataGlobalLockTemplate 校验
     *
     * @param commodityCode 商品编码
     * @param count         需求数量
     * @return 库存充裕返回 true
     * @throws Throwable 执行异常
     */
    public boolean checkAndLockStockProgrammatic(String commodityCode, int count) throws Throwable {
        return globalLockTemplate.execute(() -> {
            log.info("编程式 GlobalLock 模板校验商品库存: [{}]", commodityCode);
            return getStock(commodityCode) >= count;
        });
    }
}
