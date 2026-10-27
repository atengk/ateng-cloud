package io.github.atengk.cloud.sample.nacos.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.nacos.metadata.NacosMetadataProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Nacos 实例元数据查看与自验演示接口
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/nacos")
public class NacosMetadataDemoController {

    private final NacosMetadataProvider metadataProvider;

    public NacosMetadataDemoController(NacosMetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    /**
     * 获取当前服务节点注入的所有元数据键值快照
     *
     * @return 包含版本、环境、启动时间戳、预热时长及业务自定义标签的元数据映射表
     */
    @GetMapping("/metadata")
    public Result<Map<String, String>> getMetadata() {
        return Result.success(metadataProvider.getMetadata());
    }
}
