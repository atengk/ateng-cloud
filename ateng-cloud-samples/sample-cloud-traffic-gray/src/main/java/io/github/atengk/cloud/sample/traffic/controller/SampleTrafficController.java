package io.github.atengk.cloud.sample.traffic.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sample.traffic.model.TrafficTraceReport;
import io.github.atengk.cloud.traffic.context.TrafficContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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

    private static final ParameterizedTypeReference<Result<TrafficTraceReport>> REPORT_TYPE_REF =
            new ParameterizedTypeReference<>() {};

    @Autowired
    @Qualifier("loadBalancedRestTemplate")
    private RestTemplate restTemplate;

    /**
     * 网关分发入口 (模拟公网或南北向网关调度至 Service A)
     */
    @GetMapping("/gateway/dispatch")
    public Result<TrafficTraceReport> gatewayDispatch() {
        String currentTag = TrafficContextHolder.getTag();
        String currentUserId = UserContextHolder.getUserId();
        String currentTenantId = TenantContextHolder.getTenantId();

        log.info("[Gateway] 收到请求 [tag='{}', userId='{}', tenantId='{}']", currentTag, currentUserId, currentTenantId);

        // 调用下游 Service A
        TrafficTraceReport report = invokeDownstream("http://service-a/service-a/process", currentTag);
        report.prependVisitedNode("Gateway[tag=" + (currentTag != null ? currentTag : "none") + "]");
        return Result.success(report);
    }

    /**
     * Service A 业务处理入口 (调用 Service B)
     */
    @GetMapping("/service-a/process")
    public Result<TrafficTraceReport> serviceAProcess(
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[ServiceA] 处理请求 [tag='{}', instanceId='{}']", currentTag, instanceId);

        // 调用下游 Service B
        TrafficTraceReport report = invokeDownstream("http://service-b/service-b/process", currentTag);
        report.prependVisitedNode("ServiceA[inst=" + instanceId + ", tag=" + instanceTag + "]");
        return Result.success(report);
    }

    /**
     * Service B 业务处理入口 (调用 Service C)
     */
    @GetMapping("/service-b/process")
    public Result<TrafficTraceReport> serviceBProcess(
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[ServiceB] 处理请求 [tag='{}', instanceId='{}']", currentTag, instanceId);

        // 调用下游 Service C
        TrafficTraceReport report = invokeDownstream("http://service-c/service-c/process", currentTag);
        report.prependVisitedNode("ServiceB[inst=" + instanceId + ", tag=" + instanceTag + "]");
        return Result.success(report);
    }

    /**
     * Service C 业务终点入口 (叶子节点)
     */
    @GetMapping("/service-c/process")
    public Result<TrafficTraceReport> serviceCProcess(
            @RequestHeader(value = "X-Selected-Instance-Id", required = false) String instanceId,
            @RequestHeader(value = "X-Selected-Instance-Tag", required = false) String instanceTag) {
        String currentTag = TrafficContextHolder.getTag();
        log.info("[ServiceC] 处理请求 [tag='{}', instanceId='{}']", currentTag, instanceId);

        TrafficTraceReport report = new TrafficTraceReport(currentTag, UserContextHolder.getUserId(), TenantContextHolder.getTenantId());
        report.addVisitedNode("ServiceC[inst=" + instanceId + ", tag=" + instanceTag + "]");
        return Result.success(report);
    }

    /**
     * 统一调用下游微服务并解包 Result<TrafficTraceReport>
     *
     * @param url        下游请求地址
     * @param currentTag 当前流量标签
     * @return 追踪报告
     */
    private TrafficTraceReport invokeDownstream(String url, String currentTag) {
        ResponseEntity<Result<TrafficTraceReport>> response = restTemplate.exchange(url, HttpMethod.GET, null, REPORT_TYPE_REF);
        Result<TrafficTraceReport> body = response.getBody();
        if (body != null && body.getData() != null) {
            return body.getData();
        }
        return new TrafficTraceReport(currentTag, UserContextHolder.getUserId(), TenantContextHolder.getTenantId());
    }
}
