package io.github.atengk.cloud.sample.seata.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.seata.model.dto.PurchaseOrderDTO;
import io.github.atengk.cloud.sample.seata.model.vo.AccountStockVO;
import io.github.atengk.cloud.sample.seata.model.vo.PurchaseOrderVO;
import io.github.atengk.cloud.sample.seata.service.AccountService;
import io.github.atengk.cloud.sample.seata.service.OrderService;
import io.github.atengk.cloud.sample.seata.service.StorageService;
import jakarta.validation.Valid;
import org.apache.seata.core.context.RootContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AT 模式采购提单分布式事务演示控制器
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Validated
@RestController
@RequestMapping("/sample/cloud/seata/purchase")
public class SeataPurchaseOrderController {

    private static final Logger log = LoggerFactory.getLogger(SeataPurchaseOrderController.class);

    private final OrderService orderService;
    private final StorageService storageService;
    private final AccountService accountService;

    public SeataPurchaseOrderController(OrderService orderService,
                                        StorageService storageService,
                                        AccountService accountService) {
        this.orderService = orderService;
        this.storageService = storageService;
        this.accountService = accountService;
    }

    /**
     * 正常提交采购订单（AT 模式三节点分布式事务提交）
     *
     * @param dto 采购提单入参
     * @return 提单成功视图对象
     */
    @PostMapping("/submit")
    public Result<PurchaseOrderVO> submitPurchaseOrder(@Valid @RequestBody PurchaseOrderDTO dto) {
        log.info("接收采购提单请求: 用户 [{}], 商品 [{}], 数量 [{}]",
                dto.getUserId(), dto.getCommodityCode(), dto.getCount());

        Long orderId = orderService.createOrder(
                dto.getUserId(),
                dto.getCommodityCode(),
                dto.getCount(),
                Boolean.TRUE.equals(dto.getForceRollback())
        );

        PurchaseOrderVO vo = new PurchaseOrderVO();
        vo.setOrderId(orderId);
        vo.setUserId(dto.getUserId());
        vo.setCommodityCode(dto.getCommodityCode());
        vo.setCount(dto.getCount());
        vo.setOrderMoney(dto.getCount() * OrderService.DEFAULT_UNIT_PRICE);
        vo.setStockRemaining(storageService.getStock(dto.getCommodityCode()));
        vo.setBalanceRemaining(accountService.getBalance(dto.getUserId()));
        vo.setXid(RootContext.getXID() != null ? RootContext.getXID() : "HERMETIC_LOCAL_TX");
        vo.setStatus("COMMITTED");

        return Result.success(vo);
    }

    /**
     * 模拟采购提单异常并触发两阶段全局回滚
     *
     * @param dto 采购提单入参
     * @return 响应结果（异常被全局拦截器转译为失败 Result）
     */
    @PostMapping("/submit-rollback")
    public Result<PurchaseOrderVO> submitPurchaseOrderWithRollback(@Valid @RequestBody PurchaseOrderDTO dto) {
        log.info("接收模拟回滚采购提单请求: 用户 [{}], 商品 [{}], 数量 [{}]",
                dto.getUserId(), dto.getCommodityCode(), dto.getCount());

        // 强制设置模拟回滚标记，落库后抛出受管业务异常验证两阶段原子回滚
        orderService.createOrder(dto.getUserId(), dto.getCommodityCode(), dto.getCount(), true);

        return Result.success();
    }

    /**
     * 查询指定商品当前库存与指定用户当前余额
     *
     * @param userId        用户 ID
     * @param commodityCode 商品编码
     * @return 当前库存与余额视图对象
     */
    @GetMapping("/stock-and-balance")
    public Result<AccountStockVO> getStockAndBalance(@RequestParam("userId") String userId,
                                                    @RequestParam("commodityCode") String commodityCode) {
        int stock = storageService.getStock(commodityCode);
        int balance = accountService.getBalance(userId);

        return Result.success(new AccountStockVO(userId, balance, commodityCode, stock));
    }
}
