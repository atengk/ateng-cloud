package io.github.atengk.cloud.sample.gateway.model;

import java.io.Serializable;

/**
 * 浏览器 SPA 无静态私钥加密模式个人信息响应 VO 视图对象
 *
 * @author Ateng
 * @since 2026-11-27
 */
public class SpaProfileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户展示名称
     */
    private String displayName;

    /**
     * 脱敏手机号
     */
    private String maskedPhone;

    /**
     * 会话模式标识
     */
    private String channel;

    public SpaProfileVO() {
    }

    public SpaProfileVO(String displayName, String maskedPhone, String channel) {
        this.displayName = displayName;
        this.maskedPhone = maskedPhone;
        this.channel = channel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getMaskedPhone() {
        return maskedPhone;
    }

    public void setMaskedPhone(String maskedPhone) {
        this.maskedPhone = maskedPhone;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
