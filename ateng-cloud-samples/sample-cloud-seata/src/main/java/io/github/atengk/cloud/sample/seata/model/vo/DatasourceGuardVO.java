package io.github.atengk.cloud.sample.seata.model.vo;

import java.io.Serial;
import java.io.Serializable;

/**
 * 动态数据源假切源防卫执行结果视图对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
public class DatasourceGuardVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前事务绑定的初始数据源 Key
     */
    private String boundDatasource;

    /**
     * 目标切换的数据源 Key
     */
    private String targetDatasource;

    /**
     * 当前全局事务 XID
     */
    private String xid;

    /**
     * 防卫切面是否合法放行
     */
    private boolean allowed;

    /**
     * 执行结果说明
     */
    private String message;

    public DatasourceGuardVO() {
    }

    public DatasourceGuardVO(String boundDatasource,
                             String targetDatasource,
                             String xid,
                             boolean allowed,
                             String message) {
        this.boundDatasource = boundDatasource;
        this.targetDatasource = targetDatasource;
        this.xid = xid;
        this.allowed = allowed;
        this.message = message;
    }

    public String getBoundDatasource() {
        return boundDatasource;
    }

    public void setBoundDatasource(String boundDatasource) {
        this.boundDatasource = boundDatasource;
    }

    public String getTargetDatasource() {
        return targetDatasource;
    }

    public void setTargetDatasource(String targetDatasource) {
        this.targetDatasource = targetDatasource;
    }

    public String getXid() {
        return xid;
    }

    public void setXid(String xid) {
        this.xid = xid;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
