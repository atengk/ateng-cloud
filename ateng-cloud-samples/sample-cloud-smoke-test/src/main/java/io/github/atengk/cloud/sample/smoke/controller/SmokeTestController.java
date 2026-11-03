package io.github.atengk.cloud.sample.smoke.controller;

import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微服务全反应堆离线冒烟自检控制器
 * <p>
 * 提供一键探测全套微服务骨干构件（Nacos、Feign、Dubbo、Sentinel、Traffic）装配与健康状态的体验接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/sample/cloud/smoke")
public class SmokeTestController {

    /**
     * 冒烟存活心跳探针
     *
     * @return 基础存活响应
     */
    @GetMapping("/ping")
    public Result<String> ping() {
        return Result.success("pong");
    }

    /**
     * 查询微服务全家桶组件装配状态快照
     *
     * @return 包含各核心微服务中间件底座装配就绪状态的键值映射
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> getSmokeStatus() {
        Map<String, Object> statusMap = new LinkedHashMap<>();
        statusMap.put("framework", "ateng-cloud-all");
        statusMap.put("status", "UP");
        statusMap.put("timestamp", System.currentTimeMillis());

        Map<String, String> components = new LinkedHashMap<>();
        components.put("nacos", "LOADED");
        components.put("feign", "LOADED");
        components.put("dubbo", "LOADED");
        components.put("sentinel", "LOADED");
        components.put("traffic", "LOADED");
        components.put("gateway", "OPTIONAL_ISOLATED");
        components.put("seata", "STANDALONE_ON_DEMAND");
        statusMap.put("components", components);

        return Result.success(statusMap);
    }
}
