# 微服务核心依赖基线选型与代际升级策略

## Context

`ateng-cloud` 作为分布式高可用微服务基础设施套件，同时横跨 Spring Boot、Spring Cloud、Spring Cloud Alibaba (SCA) 与 Apache Dubbo 四大中间件生态。上游底座严格单向消费已发布的 `ateng-boot-bom:1.1.0`（锁定 Spring Boot `3.3.13`）。在微服务依赖管理中，若盲目追求最新主版本（如跨入 Spring Cloud 2024.x），会因 Spring Boot 3.4 / Spring 6.2 强行侵入导致容器初始化崩溃与字节码冲突；若停留在早期小版本（如 2023.0.3 / Dubbo 3.3.0），又会遭遇早期版本在网关 Netty 堆外内存泄露、Triple 协议流式传输及 Nacos 客户端断网重连死锁等已知的严重生产缺陷。

## Decision

确立微服务技术栈在 Spring Boot 3.3.x 时代的核心强绑定基线矩阵：

| 框架 / 组件 | 锁定版本 | 核心依赖与关键收益 | 升级约束 |
| :--- | :--- | :--- | :--- |
| **Spring Boot** | `3.3.13` | 继承 `ateng-boot-bom:1.1.0` 统一底座 | 严格对齐单体底座 |
| **Spring Cloud** | `2023.0.6` | Gateway `4.1.9`、OpenFeign `4.1.5`、CircuitBreaker `3.1.5` | 严禁跨代升级至 `2024.0.x` (因其强绑 Boot 3.4+) |
| **Spring Cloud Alibaba** | `2023.0.3.4` | Nacos Client `2.4.3`、Sentinel `1.8.8`、Seata `2.1.0` | 锁定 2023.0.3 系列最新补丁 |
| **Apache Dubbo** | `3.3.6` | Triple (HTTP/2) 协议稳定性增强、Netty 4 线程模型优化 | 锁定 3.3 系列最新补丁版，严禁使用 `.0` 首发版 |

## Consequences

1. **消除生产高危暗坑**：Gateway `4.1.9` 彻底修复了早期 Netty HTTP/2 stream 内存泄露与动态路由热刷新死锁；Nacos Client `2.4.3` 彻底修复了容器销毁时线程池句柄泄露与长连接重试假死。
2. **绝对依赖契约**：微服务版本全域收口于 `ateng-cloud-bom` 集中纳管，下游业务工程严禁在子模块中私自覆盖或声明零散第三方版本。
3. **升级演进边界**：后续仅允许在各分支既定代际内平滑升级微版本补丁（Patch Releases）；若需向 Spring Cloud 2024+ 演进，必须等待 `ateng-boot` 底座整体升级至 Spring Boot 3.4+ 后方可发起重大版本重构。
