package io.github.atengk.cloud.sample.seata.model.vo;

import java.io.Serial;
import java.io.Serializable;

/**
 * 受管异步线程池 XID 与 MDC 穿透结果视图对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
public class AsyncXidPropagationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主线程绑定的全局事务 XID
     */
    private String masterXid;

    /**
     * 异步工作线程名称
     */
    private String workerThreadName;

    /**
     * 异步工作线程中恢复的 RootContext XID
     */
    private String workerXid;

    /**
     * 异步工作线程中同步写入的 SLF4J MDC xid
     */
    private String workerMdcXid;

    /**
     * 是否成功实现跨线程 XID 与 MDC 对称穿透
     */
    private boolean propagated;

    public AsyncXidPropagationVO() {
    }

    public AsyncXidPropagationVO(String masterXid,
                                 String workerThreadName,
                                 String workerXid,
                                 String workerMdcXid,
                                 boolean propagated) {
        this.masterXid = masterXid;
        this.workerThreadName = workerThreadName;
        this.workerXid = workerXid;
        this.workerMdcXid = workerMdcXid;
        this.propagated = propagated;
    }

    public String getMasterXid() {
        return masterXid;
    }

    public void setMasterXid(String masterXid) {
        this.masterXid = masterXid;
    }

    public String getWorkerThreadName() {
        return workerThreadName;
    }

    public void setWorkerThreadName(String workerThreadName) {
        this.workerThreadName = workerThreadName;
    }

    public String getWorkerXid() {
        return workerXid;
    }

    public void setWorkerXid(String workerXid) {
        this.workerXid = workerXid;
    }

    public String getWorkerMdcXid() {
        return workerMdcXid;
    }

    public void setWorkerMdcXid(String workerMdcXid) {
        this.workerMdcXid = workerMdcXid;
    }

    public boolean isPropagated() {
        return propagated;
    }

    public void setPropagated(boolean propagated) {
        this.propagated = propagated;
    }
}
