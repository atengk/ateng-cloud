package io.github.atengk.cloud.sample.traffic.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.cloud.sample.traffic.model.TrafficTraceReport;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * 微服务全链路多跳灰度拓扑演示与测试控制器
 * <p>
 * 拓扑结构：Gateway ➔ Service A ➔ Service B ➔ Service C
 *
 * @author Ateng
 * @since 2026-10-29
 */
@RestController
public class SampleTrafficController {

    private static final Logger log = LoggerFactory.getLogger(SampleTrafficController.class);

    @Autowired
    @Qualifier("loadBalancedRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 网关分发入口 (模拟公网或南北向网关调度至 Service A)
     */
    @GetMapping("/gateway/dispatch")
    public TrafficTraceReport gatewayDispatch(
            @RequestHeader(value = "X-Target-Instance", required = false) String targetInstanceHeader) {
        String currentTag = TrafficContextHolder.getTag();
        String currentUserId = UserContextHolder.getUserId();
        String currentTenantId = TenantContextHolder.getTenantId();

        log.info("[Gateway] 收到请求 [tag='{}', userId='{}', tenantId='{}']", currentTag, currentUserId, currentTenantId);

        // 调用下游 Service A
        TrafficTraceReport report = restTemplate.getForObject("http://service-a/service-a/process", TrafficTraceReport.class);
        if (report == null) {
            report = new TrafficTraceReport(currentTag, currentUserId, currentTenantId);
        }
        report.getVisitedNodes().add(0, "Gateway[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return report;
    }

    /**
     * Service A 业务处理入口 (调用 Service B)
     */
    @GetMapping("/service-a/process")
    public TrafficTraceReport serviceAProcess(
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[ServiceA] 处理请求 [tag='{}', instanceId='{}']", currentTag, instanceId);

        // 调用下游 Service B
        TrafficTraceReport report = restTemplate.getForObject("http://service-b/service-b/process", TrafficTraceReport.class);
        if (report == null) {
            report = new TrafficTraceReport(currentTag, UserContextHolder.getUserId(), TenantContextHolder.getTenantId());
        }
        report.getVisitedNodes().add(0, "ServiceA[inst=" + instanceId + ", tag=" + instanceTag + "]");
        return report;
    }

    /**
     * Service B 业务处理入口 (调用 Service C)
     */
    @GetMapping("/service-b/process")
    public TrafficTraceReport serviceBProcess(
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[ServiceB] 处理请求 [tag='{}', instanceId='{}']", currentTag, instanceId);

        // 调用下游 Service C
        TrafficTraceReport report = restTemplate.getForObject("http://service-c/service-c/process", TrafficTraceReport.class);
        if (report == null) {
            report = new TrafficTraceReport(currentTag, UserContextHolder.getUserId(), TenantContextHolder.getTenantId());
        }
        report.getVisitedNodes().add(0, "ServiceB[inst=" + instanceId + ", tag=" + instanceTag + "]");
        return report;
    }

    /**
     * Service C 业务终点入口 (叶子节点)
     */
    @GetMapping("/service-c/process")
    public TrafficTraceReport serviceCProcess(
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[ServiceC] 处理请求 [tag='{}', instanceId='{}']", currentTag, instanceId);

        TrafficTraceReport report = new TrafficTraceReport(currentTag, UserContextHolder.getUserId(), TenantContextHolder.getTenantId());
        report.addVisitedNode("ServiceC[inst=" + instanceId + ", tag=" + instanceTag + "]");
        return report;
    }
}
