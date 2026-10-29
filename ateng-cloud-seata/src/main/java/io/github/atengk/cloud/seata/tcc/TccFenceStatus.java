package io.github.atengk.cloud.seata.tcc;

import java.util.Objects;

/**
 * TCC Fence 本地事务分支执行状态枚举
 * <p>
 * 状态码与 Apache Seata CommonFenceConstant 原生常量严格 1:1 对齐：
 * <ul>
 *     <li>1: TRIED - 一阶段已执行 (Tried)</li>
 *     <li>2: COMMITTED - 二阶段已提交 (Committed)</li>
 *     <li>3: ROLLBACKED - 二阶段已回滚 (Rollbacked)</li>
 *     <li>4: SUSPENDED - 空回滚防悬挂标记 (Suspended)</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum TccFenceStatus {

    /**
     * 一阶段 Try 已成功执行
     */
    TRIED(1, "一阶段已执行 (Tried)"),

    /**
     * 二阶段 Confirm 已成功提交
     */
    COMMITTED(2, "二阶段已提交 (Committed)"),

    /**
     * 二阶段 Cancel 已成功回滚
     */
    ROLLBACKED(3, "二阶段已回滚 (Rollbacked)"),

    /**
     * 空回滚防悬挂标记（Cancel 先于 Try 到达时记录，阻断后续 Try 执行）
     */
    SUSPENDED(4, "空回滚防悬挂标记 (Suspended)");

    private final int code;
    private final String description;

    TccFenceStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 判定当前状态是否为不可逆终态
     *
     * @return 若为 COMMITTED、ROLLBACKED 或 SUSPENDED 返回 true
     */
    public boolean isFinal() {
        return this == COMMITTED || this == ROLLBACKED || this == SUSPENDED;
    }

    /**
     * 判定是否允许合法状态转移至目标状态
     *
     * @param target 目标状态
     * @return 若合法返回 true
     */
    public boolean canTransitionTo(TccFenceStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        if (this == TRIED) {
            return target == COMMITTED || target == ROLLBACKED;
        }
        return false;
    }

    /**
     * 根据数字状态码获取对应枚举
     *
     * @param code 状态码
     * @return TccFenceStatus 枚举实例
     * @throws IllegalArgumentException 未知状态码时抛出
     */
    public static TccFenceStatus fromCode(int code) {
        for (TccFenceStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的 TCC Fence 状态码: " + code);
    }
}
