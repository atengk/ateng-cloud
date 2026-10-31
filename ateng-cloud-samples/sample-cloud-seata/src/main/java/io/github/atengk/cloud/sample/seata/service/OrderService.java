package io.github.atengk.cloud.sample.seata.service;

import io.github.atengk.cloud.seata.fallback.SeataFallbackDefense;
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

    /**
     * 默认商品单价（测试沙箱固定单价 100 元）
     */
    public static final int DEFAULT_UNIT_PRICE = 100;

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
        int orderMoney = orderCount * DEFAULT_UNIT_PRICE;
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
        if (key == null) {
            throw new IllegalStateException("订单创建失败，未获取到自增主键");
        }
        Long orderId = key.longValue();
        log.info("订单创建成功，订单 ID: [{}]", orderId);
        return orderId;
    }

    /**
     * 下单流程（编排下游远程调用并挂载 Sentinel 降级强制防卫契约）
     *
     * @param userId             用户 ID
     * @param commodityCode      商品编码
     * @param orderCount         订购数量
     * @param downstreamSupplier 模拟下游远程调用逻辑
     * @return 订单 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createOrderWithDownstream(String userId, String commodityCode, int orderCount, Runnable downstreamSupplier) {
        int orderMoney = orderCount * DEFAULT_UNIT_PRICE;
        log.info("开启带下游调用的下单事务 (XID: [{}]): 用户 [{}], 商品 [{}], 数量 [{}]",
                RootContext.getXID(), userId, commodityCode, orderCount);

        // 1. 本地扣减库存
        storageService.deduct(commodityCode, orderCount);

        // 2. 本地创建订单记录
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

        // 3. 执行下游远程调用（带 Fallback 降级防卫）
        try {
            if (downstreamSupplier != null) {
                downstreamSupplier.run();
            }
        } catch (Throwable cause) {
            // 触发 Sentinel 降级防卫：活跃全局事务下强制抛出 BusinessException 阻断假提交；无事务普通调用静默放行
            SeataFallbackDefense.failOnActiveTransaction("下游外部账户扣费服务", cause);
            log.info("无分布式全局事务，下游调用平滑降级执行");
        }

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("订单创建失败，未获取到自增主键");
        }
        return key.longValue();
    }

    /**
     * 查询指定用户的订单总数
     *
     * @param userId 用户 ID
     * @return 订单总数（保底返回 0）
     */
    public int getOrderCount(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM order_tbl WHERE user_id = ?",
                Integer.class,
                userId
        );
        return count != null ? count : 0;
    }

    /**
     * 清空所有订单数据（测试环境数据隔离清理）
     */
    public void cleanOrders() {
        jdbcTemplate.update("DELETE FROM order_tbl");
    }
}
