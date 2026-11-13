package io.github.atengk.cloud.sample.seata.controller;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.cloud.sample.seata.model.vo.AsyncXidPropagationVO;
import io.github.atengk.cloud.seata.code.SeataResultCode;
import io.github.atengk.cloud.seata.constant.SeataConstants;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 受管异步线程池 XID 与 MDC 上下文穿透（Async XID Propagation）演示控制器
 *
 * @author Ateng
 * @since 2026-11-13
 */
@RestController
@RequestMapping("/sample/cloud/seata/async")
public class SeataAsyncPropagationController {

    private static final Logger log = LoggerFactory.getLogger(SeataAsyncPropagationController.class);
    private static final long ASYNC_WAIT_SECONDS = 5L;

    /**
     * 演示主线程绑定 XID 后通过受管异步线程池自动快照恢复 XID 与 MDC 黄金关联
     *
     * @param xid 可选全局事务 XID（为空时自动生成演示 XID）
     * @return 异步工作线程上下文穿透结果视图对象
     */
    @PostMapping("/propagate")
    public Result<AsyncXidPropagationVO> propagateAsyncXid(@RequestParam(value = "xid", required = false) String xid) {
        String effectiveXid = StringUtils.hasText(xid)
                ? xid
                : "127.0.0.1:8091:async-xid-" + System.currentTimeMillis();

        boolean manuallyBound = false;
        String previousMdcXid = MDC.get(SeataConstants.MDC_KEY_XID);
        if (!RootContext.inGlobalTransaction()) {
            RootContext.bind(effectiveXid);
            manuallyBound = true;
        }
        String masterXid = RootContext.getXID();
        MDC.put(SeataConstants.MDC_KEY_XID, masterXid);
        log.info("主线程发起受管异步 XID 穿透演示: masterXid=[{}]", masterXid);

        try {
            CompletableFuture<AsyncXidPropagationVO> future = AsyncUtil.supply(() -> {
                String workerXid = RootContext.getXID();
                String workerMdcXid = MDC.get(SeataConstants.MDC_KEY_XID);
                boolean propagated = Objects.equals(masterXid, workerXid) && Objects.equals(masterXid, workerMdcXid);
                return new AsyncXidPropagationVO(
                        masterXid,
                        Thread.currentThread().getName(),
                        workerXid,
                        workerMdcXid,
                        propagated
                );
            });
            return Result.success(future.get(ASYNC_WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(SeataResultCode.SEATA_TRANSACTION_FAILED, "受管异步任务等待被中断", ex);
        } catch (TimeoutException | ExecutionException ex) {
            throw new BusinessException(SeataResultCode.SEATA_TRANSACTION_FAILED, "受管异步任务 XID 穿透执行失败", ex);
        } finally {
            if (StringUtils.hasText(previousMdcXid)) {
                MDC.put(SeataConstants.MDC_KEY_XID, previousMdcXid);
            } else {
                MDC.remove(SeataConstants.MDC_KEY_XID);
            }
            if (manuallyBound && RootContext.inGlobalTransaction()) {
                RootContext.unbind();
            }
        }
    }
}
