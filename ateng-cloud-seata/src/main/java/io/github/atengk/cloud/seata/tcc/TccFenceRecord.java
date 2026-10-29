package io.github.atengk.cloud.seata.tcc;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * TCC Fence 本地分支事务日志记录实体
 * <p>
 * 实体字段严格对齐 Seata 官方 {@code tcc_fence_log} 数据表规范：
 * 包含 {@code xid}、{@code branch_id}、{@code action_name}、{@code status}、
 * {@code gmt_create} 与 {@code gmt_modified}。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
public class TccFenceRecord {

    private String xid;
    private Long branchId;
    private String actionName;
    private TccFenceStatus status;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;

    public TccFenceRecord() {
    }

    public TccFenceRecord(String xid, Long branchId, String actionName, TccFenceStatus status) {
        this.xid = Objects.requireNonNull(xid, "xid 必须非空");
        this.branchId = Objects.requireNonNull(branchId, "branchId 必须非空");
        this.actionName = actionName != null ? actionName : "";
        this.status = Objects.requireNonNull(status, "status 必须非空");
        this.gmtCreate = LocalDateTime.now();
        this.gmtModified = this.gmtCreate;
    }

    public String getXid() {
        return xid;
    }

    public void setXid(String xid) {
        this.xid = xid;
    }

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String actionName) {
        this.actionName = actionName;
    }

    public TccFenceStatus getStatus() {
        return status;
    }

    public void setStatus(TccFenceStatus status) {
        this.status = status;
    }

    public LocalDateTime getGmtCreate() {
        return gmtCreate;
    }

    public void setGmtCreate(LocalDateTime gmtCreate) {
        this.gmtCreate = gmtCreate;
    }

    public LocalDateTime getGmtModified() {
        return gmtModified;
    }

    public void setGmtModified(LocalDateTime gmtModified) {
        this.gmtModified = gmtModified;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TccFenceRecord that = (TccFenceRecord) o;
        return Objects.equals(xid, that.xid) && Objects.equals(branchId, that.branchId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(xid, branchId);
    }

    @Override
    public String toString() {
        return "TccFenceRecord{" +
                "xid='" + xid + '\'' +
                ", branchId=" + branchId +
                ", actionName='" + actionName + '\'' +
                ", status=" + status +
                ", gmtCreate=" + gmtCreate +
                ", gmtModified=" + gmtModified +
                '}';
    }
}
