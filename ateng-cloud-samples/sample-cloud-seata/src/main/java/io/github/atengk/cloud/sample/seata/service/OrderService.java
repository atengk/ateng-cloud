package io.github.atengk.cloud.sample.seata.service;

import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 订单服务（业务发起方，编排 Order ➔ Storage ➔ Account 三节点分布式事务）
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final JdbcTemplate jdbcTemplate;
    private final StorageService storageService;
    private final AccountService accountService;

    public OrderService(JdbcTemplate jdbcTemplate, StorageService storageService, AccountService accountService) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageService = storageService;
        this.accountService = accountService;
    }

    /**
     * 创建订单全流程分布式事务
     *
     * @param userId        用户 ID
     * @param commodityCode 商品编码
     * @param orderCount    订购数量
     * @return 订单 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(String userId, String commodityCode, int orderCount) {
        int orderMoney = orderCount * 100; // 单价 100
        log.info("开启下单事务 (XID: [{}]): 用户 [{}], 商品 [{}], 数量 [{}], 金额 [{}]",
                RootContext.getXID(), userId, commodityCode, orderCount, orderMoney);

        // 1. 扣减库存
        storageService.deduct(commodityCode, orderCount);

        // 2. 扣减账户余额
        accountService.debit(userId, orderMoney);

        // 3. 创建订单记录
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO order_tbl (user_id, commodity_code, count, money) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, userId);
            ps.setString(2, commodityCode);
            ps.setInt(3, orderCount);
            ps.setInt(4, orderMoney);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        Long orderId = key != null ? key.longValue() : 1L;
        log.info("订单创建成功，订单 ID: [{}]", orderId);
        return orderId;
    }

    public int getOrderCount(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM order_tbl WHERE user_id = ?",
                Integer.class,
                userId
        );
        return count != null ? count : 0;
    }

    public void cleanOrders() {
        jdbcTemplate.update("DELETE FROM order_tbl");
    }
}
