package io.github.atengk.cloud.sample.seata.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.seata.model.dto.TccDebitDTO;
import io.github.atengk.cloud.sample.seata.model.vo.TccActionVO;
import io.github.atengk.cloud.sample.seata.service.AccountService;
import io.github.atengk.cloud.seata.tcc.TccFenceBarrier;
import io.github.atengk.cloud.seata.tcc.TccFenceCleanResult;
import io.github.atengk.cloud.seata.tcc.TccFenceLogCleanScheduler;
import io.github.atengk.cloud.seata.tcc.TccFenceRecord;
import jakarta.validation.Valid;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCC 业务防卫屏障（TccFenceBarrier）演示控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Validated
@RestController
@RequestMapping("/sample/cloud/seata/tcc")
public class SeataTccFenceController {

    private static final Logger log = LoggerFactory.getLogger(SeataTccFenceController.class);
    private static final AtomicLong BRANCH_ID_SEQ = new AtomicLong(2000L);

    private final AccountService accountService;
    private final TccFenceBarrier tccFenceBarrier;
    private final TccFenceLogCleanScheduler cleanScheduler;

    public SeataTccFenceController(AccountService accountService,
                                   TccFenceBarrier tccFenceBarrier,
                                   TccFenceLogCleanScheduler cleanScheduler) {
        this.accountService = accountService;
        this.tccFenceBarrier = tccFenceBarrier;
        this.cleanScheduler = cleanScheduler;
    }

    /**
     * TCC 一阶段 Try 准备扣减操作
     *
     * @param dto TCC 扣减入参
     * @return 执行响应视图对象
     * @throws Throwable 业务异常或防卫异常
     */
    @PostMapping("/prepare")
    public Result<TccActionVO> prepare(@Valid @RequestBody TccDebitDTO dto) throws Throwable {
        String xid = resolveXid(dto);
        Long branchId = resolveBranchId(dto);
        BusinessActionContext context = createActionContext(xid, branchId, dto.getUserId(), dto.getMoney());

        log.info("执行 TCC 一阶段 Try: 用户 [{}], 金额 [{}], 分支 [{}#{}]",
                dto.getUserId(), dto.getMoney(), xid, branchId);

        accountService.prepareDebitTcc(context, dto.getUserId(), dto.getMoney());

        return Result.success(new TccActionVO(
                xid,
                branchId,
                dto.getUserId(),
                "TRIED",
                accountService.getBalance(dto.getUserId()),
                "TCC 一阶段 Try 准备成功，已预扣款并插入 TRIED 防卫记录"
        ));
    }

    /**
     * TCC 二阶段 Confirm 提交确认操作
     *
     * @param dto TCC 扣减入参
     * @return 执行响应视图对象
     */
    @PostMapping("/commit")
    public Result<TccActionVO> commit(@Valid @RequestBody TccDebitDTO dto) {
        String xid = resolveXid(dto);
        Long branchId = resolveBranchId(dto);
        BusinessActionContext context = createActionContext(xid, branchId, dto.getUserId(), dto.getMoney());

        log.info("执行 TCC 二阶段 Confirm: 用户 [{}], 分支 [{}#{}]", dto.getUserId(), xid, branchId);
        boolean success = accountService.commit(context);

        return Result.success(new TccActionVO(
                xid,
                branchId,
                dto.getUserId(),
                "COMMITTED",
                accountService.getBalance(dto.getUserId()),
                success ? "TCC 二阶段 Confirm 提交成功" : "Confirm 失败"
        ));
    }

    /**
     * TCC 二阶段 Cancel 补偿回滚操作
     *
     * @param dto TCC 扣减入参
     * @return 执行响应视图对象
     */
    @PostMapping("/rollback")
    public Result<TccActionVO> rollback(@Valid @RequestBody TccDebitDTO dto) {
        String xid = resolveXid(dto);
        Long branchId = resolveBranchId(dto);
        BusinessActionContext context = createActionContext(xid, branchId, dto.getUserId(), dto.getMoney());

        log.info("执行 TCC 二阶段 Cancel: 用户 [{}], 分支 [{}#{}]", dto.getUserId(), xid, branchId);
        boolean success = accountService.rollback(context);

        return Result.success(new TccActionVO(
                xid,
                branchId,
                dto.getUserId(),
                "ROLLBACKED",
                accountService.getBalance(dto.getUserId()),
                success ? "TCC 二阶段 Cancel 补偿成功，资金已安全回退" : "Cancel 失败"
        ));
    }

    /**
     * 演示空回滚防卫（未执行 Try 前收到 Cancel 回滚通知）
     *
     * @param dto TCC 扣减入参
     * @return 执行响应视图对象
     */
    @PostMapping("/empty-rollback")
    public Result<TccActionVO> emptyRollback(@Valid @RequestBody TccDebitDTO dto) {
        String xid = resolveXid(dto);
        Long branchId = resolveBranchId(dto);
        BusinessActionContext context = createActionContext(xid, branchId, dto.getUserId(), dto.getMoney());

        log.info("执行 TCC 空回滚防卫演示: 用户 [{}], 分支 [{}#{}]", dto.getUserId(), xid, branchId);

        // 未执行 Try，直接调用 Cancel
        boolean success = accountService.rollback(context);

        String recordStatus = tccFenceBarrier.getRepository()
                .findByXidAndBranchId(xid, branchId)
                .map(TccFenceRecord::getStatus)
                .map(Enum::name)
                .orElse("UNKNOWN");

        return Result.success(new TccActionVO(
                xid,
                branchId,
                dto.getUserId(),
                recordStatus,
                accountService.getBalance(dto.getUserId()),
                success ? "空回滚防卫成功：已插入 SUSPENDED 标记并静默跳过业务补偿" : "空回滚防卫失败"
        ));
    }

    /**
     * 演示业务悬挂防卫（Cancel 先行到达，随后到达的延迟 Try 被拒绝）
     *
     * @param dto TCC 扣减入参
     * @return 执行响应视图对象
     * @throws Throwable 预期抛出 TccFenceHangingException (5110)
     */
    @PostMapping("/hanging-defense")
    public Result<TccActionVO> hangingDefense(@Valid @RequestBody TccDebitDTO dto) throws Throwable {
        String xid = resolveXid(dto);
        Long branchId = resolveBranchId(dto);
        BusinessActionContext context = createActionContext(xid, branchId, dto.getUserId(), dto.getMoney());

        log.info("执行 TCC 业务悬挂防卫演示: 用户 [{}], 分支 [{}#{}]", dto.getUserId(), xid, branchId);

        // 1. Cancel 先行到达，触发空回滚占位插入 SUSPENDED
        accountService.rollback(context);

        // 2. 延迟 Try 随后到达，防卫屏障应刚性拦截并抛出 TccFenceHangingException
        accountService.prepareDebitTcc(context, dto.getUserId(), dto.getMoney());

        return Result.success(new TccActionVO(
                xid,
                branchId,
                dto.getUserId(),
                "UNEXPECTED",
                accountService.getBalance(dto.getUserId()),
                "未被拦截（非预期）"
        ));
    }

    /**
     * 演示二阶段幂等防卫（重复 Confirm 放行）
     *
     * @param dto TCC 扣减入参
     * @return 执行响应视图对象
     * @throws Throwable 业务异常
     */
    @PostMapping("/duplicate-commit")
    public Result<TccActionVO> duplicateCommit(@Valid @RequestBody TccDebitDTO dto) throws Throwable {
        String xid = resolveXid(dto);
        Long branchId = resolveBranchId(dto);
        BusinessActionContext context = createActionContext(xid, branchId, dto.getUserId(), dto.getMoney());

        log.info("执行 TCC 重复提交幂等防卫演示: 用户 [{}], 分支 [{}#{}]", dto.getUserId(), xid, branchId);

        // 1. 正常执行一阶段 Try
        accountService.prepareDebitTcc(context, dto.getUserId(), dto.getMoney());

        // 2. 第一次 Confirm
        boolean firstCommit = accountService.commit(context);

        // 3. 第二次 Confirm（模拟重试）
        boolean secondCommit = accountService.commit(context);

        return Result.success(new TccActionVO(
                xid,
                branchId,
                dto.getUserId(),
                "COMMITTED",
                accountService.getBalance(dto.getUserId()),
                (firstCommit && secondCommit) ? "重复提交幂等放行成功，状态保持 COMMITTED 且资金不重复扣减" : "Confirm 异常"
        ));
    }

    /**
     * 触发过期 TCC 防卫日志分级生命周期清理（终态与 SUSPENDED 差异化保留策略）
     *
     * @return 本次分级清理删除统计结果
     */
    @PostMapping("/clean")
    public Result<TccFenceCleanResult> cleanExpiredFenceLogs() {
        log.info("触发 TCC 防卫日志分级生命周期清理");
        return Result.success(cleanScheduler.clean());
    }

    private String resolveXid(TccDebitDTO dto) {
        if (StringUtils.hasText(dto.getXid())) {
            return dto.getXid();
        }
        String contextXid = RootContext.getXID();
        if (StringUtils.hasText(contextXid)) {
            return contextXid;
        }
        return "127.0.0.1:8091:20261103" + (System.currentTimeMillis() % 1000000);
    }

    private Long resolveBranchId(TccDebitDTO dto) {
        if (dto.getBranchId() != null && dto.getBranchId() > 0) {
            return dto.getBranchId();
        }
        return BRANCH_ID_SEQ.incrementAndGet();
    }

    private BusinessActionContext createActionContext(String xid, Long branchId, String userId, Integer money) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("money", money);
        return new BusinessActionContext(xid, String.valueOf(branchId), params);
    }
}
