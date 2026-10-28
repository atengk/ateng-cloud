package io.github.atengk.cloud.sample.feign.model;

/**
 * 用户测试视图对象
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class UserVO {

    private Long id;
    private String username;

    public UserVO() {
    }

    public UserVO(Long id, String username) {
        this.id = id;
        this.username = username;
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
}
