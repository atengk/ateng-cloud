package io.github.atengk.cloud.sample.nacos.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.nacos.event.NacosConnectionEvent;
import io.github.atengk.cloud.nacos.template.NacosTemplate;
import io.github.atengk.cloud.sample.nacos.config.DemoInstanceSubscriptionService;
import io.github.atengk.cloud.sample.nacos.event.DemoConnectionEventListener;
import io.github.atengk.cloud.sample.nacos.model.ConnectionEventVO;
import io.github.atengk.cloud.sample.nacos.model.InstanceSubscriptionStatusVO;
import io.github.atengk.cloud.sample.nacos.model.InstanceVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * NacosTemplate 运维与服务发现功能演示接口
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/nacos/ops")
public class NacosOpsDemoController {

    private final NacosTemplate nacosTemplate;
    private final ObjectProvider<DemoConnectionEventListener> connectionEventListenerProvider;
    private final ObjectProvider<DemoInstanceSubscriptionService> instanceSubscriptionServiceProvider;

    public NacosOpsDemoController(NacosTemplate nacosTemplate,
                                  ObjectProvider<DemoConnectionEventListener> connectionEventListenerProvider,
                                  ObjectProvider<DemoInstanceSubscriptionService> instanceSubscriptionServiceProvider) {
        this.nacosTemplate = nacosTemplate;
        this.connectionEventListenerProvider = connectionEventListenerProvider;
        this.instanceSubscriptionServiceProvider = instanceSubscriptionServiceProvider;
    }

    /**
     * 查询指定服务的健康可用实例列表
     */
    @GetMapping("/instances")
    public Result<List<InstanceVO>> getInstances(@RequestParam(defaultValue = "sample-cloud-nacos") String serviceName) {
        List<InstanceVO> list = nacosTemplate.getInstances(serviceName).stream()
                .map(InstanceVO::fromInstance)
                .collect(Collectors.toList());
        return Result.success(list != null ? list : Collections.emptyList());
    }

    /**
     * 查询同机房亲和优先的健康实例列表
     */
    @GetMapping("/same-zone")
    public Result<List<InstanceVO>> getSameZoneInstances(@RequestParam(defaultValue = "sample-cloud-nacos") String serviceName) {
        List<InstanceVO> list = nacosTemplate.getSameZoneInstances(serviceName).stream()
                .map(InstanceVO::fromInstance)
                .collect(Collectors.toList());
        return Result.success(list != null ? list : Collections.emptyList());
    }

    /**
     * 获取当前服务节点在 Nacos 注册的自身实例信息
     */
    @GetMapping("/me")
    public Result<InstanceVO> getMyInstance() {
        return nacosTemplate.getMyInstance()
                .map(InstanceVO::fromInstance)
                .map(Result::success)
                .orElseGet(() -> Result.success(null));
    }

    /**
     * 检测目标服务是否触发过载保护阈值
     */
    @GetMapping("/protection")
    public Result<Boolean> isUnderProtection(@RequestParam(defaultValue = "sample-cloud-nacos") String serviceName) {
        return Result.success(nacosTemplate.isUnderProtection(serviceName));
    }

    /**
     * 动态设置指定服务实例启用/禁用状态
     */
    @PostMapping("/instance/status")
    public Result<Void> setInstanceEnabled(@RequestParam String serviceName,
                                          @RequestParam String ip,
                                          @RequestParam int port,
                                          @RequestParam boolean enabled) {
        nacosTemplate.setInstanceEnabled(serviceName, ip, port, enabled);
        return Result.success();
    }

    /**
     * 动态调节指定服务实例权重
     */
    @PostMapping("/instance/weight")
    public Result<Void> updateInstanceWeight(@RequestParam String serviceName,
                                            @RequestParam String ip,
                                            @RequestParam int port,
                                            @RequestParam double weight) {
        nacosTemplate.updateInstanceWeight(serviceName, ip, port, weight);
        return Result.success();
    }

    /**
     * 动态注册当前实例元数据
     */
    @PostMapping("/metadata")
    public Result<Void> registerMetadata(@RequestParam String key, @RequestParam String value) {
        nacosTemplate.registerMetadata(key, value);
        return Result.success();
    }

    /**
     * 动态移除当前实例元数据
     */
    @DeleteMapping("/metadata")
    public Result<Void> removeMetadata(@RequestParam String key) {
        nacosTemplate.removeMetadata(key);
        return Result.success();
    }

    /**
     * 主动将当前服务挂牌上线
     */
    @PostMapping("/online")
    public Result<Void> markOnline() {
        nacosTemplate.markOnline();
        return Result.success();
    }

    /**
     * 主动触发当前服务优雅下线排空
     */
    @PostMapping("/offline")
    public Result<Void> markOffline() {
        nacosTemplate.markOffline();
        return Result.success();
    }

    /**
     * 获取最近一次 Nacos 长连接事件状态
     */
    @GetMapping("/connection-event")
    public Result<ConnectionEventVO> getConnectionEvent() {
        DemoConnectionEventListener listener = connectionEventListenerProvider.getIfAvailable();
        if (listener == null) {
            return Result.success(new ConnectionEventVO(0, "NONE", "UNKNOWN", "N/A"));
        }
        NacosConnectionEvent lastEvent = listener.getLastEvent();
        ConnectionEventVO vo = new ConnectionEventVO(
                listener.getEventCount(),
                lastEvent != null ? lastEvent.getModuleType().name() : "NONE",
                lastEvent != null ? lastEvent.getStatus().name() : "CONNECTED",
                lastEvent != null ? String.valueOf(lastEvent.getServerAddress()) : "N/A"
        );
        return Result.success(vo);
    }

    /**
     * 获取最近一次实例编程式订阅通知状态
     */
    @GetMapping("/instance-subscription")
    public Result<InstanceSubscriptionStatusVO> getInstanceSubscriptionStatus() {
        DemoInstanceSubscriptionService service = instanceSubscriptionServiceProvider.getIfAvailable();
        if (service == null) {
            return Result.success(new InstanceSubscriptionStatusVO(0, Collections.emptyList(), ""));
        }
        return Result.success(service.getStatusSnapshot());
    }
}
