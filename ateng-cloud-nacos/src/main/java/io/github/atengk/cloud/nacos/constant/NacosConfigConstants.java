package io.github.atengk.cloud.nacos.constant;

/**
 * Nacos 动态配置中心通用常量与三层配置继承拓扑规约
 *
 * <p>定义标准 Nacos 配置导入协议前缀、默认分组以及全网公共基线、
 * 中间件共享与微服务私有三层配置 Data ID 推荐命名规约。</p>
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class NacosConfigConstants {

    private NacosConfigConstants() {
    }

    /**
     * Nacos 配置导入协议前缀
     */
    public static final String PREFIX_NACOS_IMPORT = "nacos:";

    /**
     * Nacos 弹性可选配置导入协议前缀
     */
    public static final String PREFIX_OPTIONAL_NACOS_IMPORT = "optional:nacos:";

    /**
     * 默认配置分组名
     */
    public static final String DEFAULT_GROUP = "DEFAULT_GROUP";

    /**
     * 第一层：全网公共基线配置推荐 Data ID
     */
    public static final String DATA_ID_COMMON_BASELINE = "shared-common.yaml";

    /**
     * 第二层：中间件共享配置推荐 Data ID
     */
    public static final String DATA_ID_MIDDLEWARE_SHARED = "shared-infrastructure.yaml";

    /**
     * 第三层：构建微服务私有配置标准 Data ID
     *
     * @param appName 微服务应用名（不可为空）
     * @param profile 激活的环境 profile（可为空，如 dev, test, prod）
     * @return 格式化后的 Data ID（如 order-service-dev.yaml 或 order-service.yaml）
     */
    public static String buildServiceDataId(String appName, String profile) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("appName must not be blank");
        }
        if (profile != null && !profile.isBlank()) {
            return appName + "-" + profile.trim() + ".yaml";
        }
        return appName.trim() + ".yaml";
    }
}
