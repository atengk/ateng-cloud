package io.github.atengk.cloud.dubbo.properties;

import java.util.ArrayList;
import java.util.List;

/**
 * Dubbo 序列化安全防护配置属性
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class AtengDubboSerializeProperties {

    /**
     * 允许进行反序列化的自定义业务包路径白名单列表 (例如: com.company.biz.dto, com.company.biz.vo)
     */
    private List<String> allowPackages = new ArrayList<>();

    public List<String> getAllowPackages() {
        return allowPackages;
    }

    public void setAllowPackages(List<String> allowPackages) {
        this.allowPackages = allowPackages;
    }
}
