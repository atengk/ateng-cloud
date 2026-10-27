package io.github.atengk.cloud.sample.nacos.model;

import jakarta.validation.constraints.NotEmpty;

import java.io.Serializable;
import java.util.Set;

/**
 * 模拟热更请求传输 DTO（满足 JSR-303 参数格式校验防卫）
 *
 * @author Ateng
 * @since 2026-10-27
 */
public class RefreshRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 变更属性键集合
     */
    @NotEmpty(message = "变更键名集合不可为空")
    private Set<String> keys;

    public Set<String> getKeys() {
        return keys;
    }

    public void setKeys(Set<String> keys) {
        this.keys = keys;
    }
}
