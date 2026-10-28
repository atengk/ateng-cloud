package io.github.atengk.cloud.sample.dubbo.model;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * 用户信息 RPC 传输对象
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class UserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一主键 ID
     */
    private Long id;

    /**
     * 用户登录账号名称
     */
    private String username;

    /**
     * 用户安全电子邮箱
     */
    private String email;

    public UserVO() {
    }

    public UserVO(Long id, String username, String email) {
        this.id = id;
        this.username = username;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserVO userVO = (UserVO) o;
        return Objects.equals(id, userVO.id) && Objects.equals(username, userVO.username) && Objects.equals(email, userVO.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, username, email);
    }

    @Override
    public String toString() {
        return "UserVO{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                '}';
    }
}
