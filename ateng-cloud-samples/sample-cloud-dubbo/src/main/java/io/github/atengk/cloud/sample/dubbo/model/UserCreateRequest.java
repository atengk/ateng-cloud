package io.github.atengk.cloud.sample.dubbo.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建用户入参请求对象 (携带 JSR-303 格式校验规则)
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class UserCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户名 (非空防卫)
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 年龄 (下限防卫)
     */
    @NotNull(message = "年龄不能为空")
    @Min(value = 1, message = "年龄必须大于0")
    private Integer age;

    public UserCreateRequest() {
    }

    public UserCreateRequest(String username, Integer age) {
        this.username = username;
        this.age = age;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }
}
