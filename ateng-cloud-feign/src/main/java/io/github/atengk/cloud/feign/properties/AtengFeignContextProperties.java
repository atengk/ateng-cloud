package io.github.atengk.cloud.feign.properties;

import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

/**
 * Feign 全链路四维上下文透传与零信任防穿透配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class AtengFeignContextProperties {

    /**
     * 是否开启全链路上下文传播与治理总开关（控制消费端出站透传拦截器及服务端 Servlet 入站还原/Same-Token 防穿透守卫），默认 true
     */
    private boolean enabled = true;

    /**
     * 是否开启服务端 Servlet 入站上下文自动提取还原与确定性清理，默认 true
     */
    private boolean inboundEnabled = true;

    /**
     * 是否开启服务端入站 Same-Token 零信任防穿透强校验，默认 false
     */
    private boolean checkSameToken = false;

    /**
     * 开启入站 Same-Token 校验时豁免放行的 Ant 风格请求路径白名单
     */
    private List<String> excludePaths = new ArrayList<>(List.of("/actuator/**", "/error", "/favicon.ico"));

    /**
     * 服务端 Servlet 入站上下文过滤器注册优先级（默认排在 TraceFilter 与 TrafficContextFilter 之后）
     */
    private int filterOrder = Ordered.HIGHEST_PRECEDENCE + 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 是否开启服务端入站上下文自动提取还原与清理
     *
     * @return true 开启，false 关闭
     */
    public boolean isInboundEnabled() {
        return inboundEnabled;
    }

    /**
     * 设置是否开启服务端入站上下文自动提取还原与清理
     *
     * @param inboundEnabled 开关状态
     */
    public void setInboundEnabled(boolean inboundEnabled) {
        this.inboundEnabled = inboundEnabled;
    }

    /**
     * 是否开启服务端入站 Same-Token 零信任强校验
     *
     * @return true 开启，false 关闭
     */
    public boolean isCheckSameToken() {
        return checkSameToken;
    }

    /**
     * 设置是否开启服务端入站 Same-Token 零信任强校验
     *
     * @param checkSameToken 开关状态
     */
    public void setCheckSameToken(boolean checkSameToken) {
        this.checkSameToken = checkSameToken;
    }

    /**
     * 获取入站 Same-Token 校验白名单放行路径列表
     *
     * @return Ant 风格路径白名单列表
     */
    public List<String> getExcludePaths() {
        return excludePaths;
    }

    /**
     * 设置入站 Same-Token 校验白名单放行路径列表
     *
     * @param excludePaths Ant 风格路径白名单列表
     */
    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths != null ? new ArrayList<>(excludePaths) : new ArrayList<>();
    }

    /**
     * 获取入站上下文过滤器注册优先级
     *
     * @return 过滤器顺序值
     */
    public int getFilterOrder() {
        return filterOrder;
    }

    /**
     * 设置入站上下文过滤器注册优先级
     *
     * @param filterOrder 过滤器顺序值
     */
    public void setFilterOrder(int filterOrder) {
        this.filterOrder = filterOrder;
    }
}
