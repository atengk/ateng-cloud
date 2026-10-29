package io.github.atengk.cloud.sample.seata.service;

import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.cloud.seata.tcc.SeataTccFenceTemplate;
import io.github.atengk.cloud.seata.tcc.TwoPhaseAction;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户服务（提供 AT 模式扣款与 TCC 模式防卫扣款）
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Service
public class AccountService implements TwoPhaseAction {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SeataTccFenceTemplate tccFenceTemplate;

    public AccountService(JdbcTemplate jdbcTemplate, SeataTccFenceTemplate tccFenceTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.tccFenceTemplate = tccFenceTemplate;
    }

    public void initAccount(String userId, int money) {
        jdbcTemplate.update("MERGE INTO account_tbl (user_id, money) KEY(user_id) VALUES (?, ?)", userId, money);
    }

    @Transactional(rollbackFor = Exception.class)
    public void debit(String userId, int money) {
        log.info("执行账户扣款: 用户 [{}], 扣减金额 [{}]", userId, money);
        int updated = jdbcTemplate.update(
                "UPDATE account_tbl SET money = money - ? WHERE user_id = ? AND money >= ?",
                money, userId, money
        );
        BusinessAssert.isTrue(updated > 0, "账户余额不足，扣款失败: %s", userId);
    }

    public int getBalance(String userId) {
        Integer balance = jdbcTemplate.queryForObject(
                "SELECT money FROM account_tbl WHERE user_id = ?",
                Integer.class,
                userId
        );
        return balance != null ? balance : 0;
    }

    /**
     * TCC 一阶段 Try：在 TCC Fence 屏障保护下执行扣款准备
     *
     * @param actionContext 业务上下文
     * @param userId        用户 ID
     * @param money         扣减金额
     * @return 准备成功返回 true
     * @throws Throwable 业务异常或防卫异常
     */
    public boolean prepareDebitTcc(BusinessActionContext actionContext, String userId, int money) throws Throwable {
        return tccFenceTemplate.executePrepare(actionContext, "AccountDebitTcc", () -> {
            log.info("TCC 一阶段 Try: 校验并扣减用户余额 [{}], 金额: [{}]", userId, money);
            int updated = jdbcTemplate.update(
                    "UPDATE account_tbl SET money = money - ? WHERE user_id = ? AND money >= ?",
                    money, userId, money
            );
            BusinessAssert.isTrue(updated > 0, "TCC 余额不足: %s", userId);
            return true;
        });
    }

    @Override
    public boolean commit(BusinessActionContext actionContext) {
        try {
            return tccFenceTemplate.executeCommit(actionContext, () -> {
                log.info("TCC 二阶段 Confirm: 确认用户扣款, 事务 [{}#{}]",
                        actionContext.getXid(), actionContext.getBranchId());
                return true;
            });
        } catch (Throwable t) {
            log.error("TCC 二阶段 Confirm 异常", t);
            return false;
        }
    }

    @Override
    public boolean rollback(BusinessActionContext actionContext) {
        try {
            return tccFenceTemplate.executeRollback(actionContext, () -> {
                String userId = (String) actionContext.getActionContext("userId");
                Object moneyObj = actionContext.getActionContext("money");
                Integer money = moneyObj instanceof Number ? ((Number) moneyObj).intValue() : null;
                log.info("TCC 二阶段 Cancel: 回滚补偿用户余额 [{}], 补回金额: [{}]", userId, money);
                if (userId != null && money != null) {
                    jdbcTemplate.update("UPDATE account_tbl SET money = money + ? WHERE user_id = ?", money, userId);
                }
                return true;
            });
        } catch (Throwable t) {
            log.error("TCC 二阶段 Cancel 异常", t);
            return false;
        }
    }
}
