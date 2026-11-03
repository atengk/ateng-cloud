package io.github.atengk.cloud.sample.sentinel.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.sentinel.model.security.SecurityOrderVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 边缘安全授权与 RESTful 路径清洗防爆场景控制器
 *
 * <p>展示 RESTful 动态 URL 归一化清洗防 OOM、多级请求调用来源解析与黑白名单访问控制、以及白名单免检旁路机制。</p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/sentinel/security")
public class SentinelSecurityAuthorityController {

    /**
     * RESTful 动态路径订单详情查询接口
     *
     * <p>路径中的数字 ID 会被 AtengUrlCleaner 自动收敛归一化为 /sample/cloud/sentinel/security/orders/{id}，
     * 避免因高基数可变 URL 导致 Sentinel 资源监控树（ClusterNode）无界内存泄漏与 JVM OOM。</p>
     *
     * @param orderId 订单 ID
     * @return 订单详情与收敛后的资源标识
     */
    @GetMapping("/orders/{id}")
    public Result<SecurityOrderVO> getOrderById(@PathVariable("id") Long id) {
        SecurityOrderVO vo = new SecurityOrderVO(
                id,
                "ORD-SEC-" + id,
                new BigDecimal("299.00"),
                "/sample/cloud/sentinel/security/orders/{id}"
        );
        return Result.success(vo);
    }

    /**
     * 安全管理控制台接口 (来源授权控制)
     *
     * <p>由 AtengRequestOriginParser 解析请求标头 (X-Origin-Service / X-Gateway-From)，
     * 并由 Sentinel AuthorityRule 实施白名单/黑名单鉴权，未授权请求拦截返回 HTTP 429 与 AUTHORITY_BLOCK(4205)。</p>
     *
     * @return 管理端通行凭证
     */
    @GetMapping("/admin/dashboard")
    public Result<String> adminDashboard() {
        return Result.success("admin-dashboard-access-granted");
    }

    /**
     * 免检白名单公开健康探测接口
     *
     * <p>命中 url-clean.exclude-patterns 规则，直接绕过 Sentinel Entry 监控树创建，零系统开销。</p>
     *
     * @return 存活响应
     */
    @GetMapping("/public/ping")
    public Result<String> publicPing() {
        return Result.success("public-pong");
    }
}
