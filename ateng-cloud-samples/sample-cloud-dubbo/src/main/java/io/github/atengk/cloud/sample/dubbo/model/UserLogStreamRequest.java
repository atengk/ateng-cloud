package io.github.atengk.cloud.sample.dubbo.model;

import jakarta.validation.constraints.NotBlank;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户日志流式拉取请求传输对象
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class UserLogStreamRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务操作行为标识
     */
    @NotBlank(message = "业务操作行为不能为空")
    private String action;

    /**
     * 限制返回条数
     */
    private Integer limit;

    public UserLogStreamRequest() {
    }

    public UserLogStreamRequest(String action, Integer limit) {
        this.action = action;
        this.limit = limit;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
