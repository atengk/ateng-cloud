# 采用多仓物理隔离与单向锁定 ateng-boot-bom 底座架构

## Context

在规划 Spring Cloud 与 Apache Dubbo 微服务治理能力时，面临将其作为子模块追加在 `ateng-boot` 单体底座仓库内，还是建立完全独立的微服务代码仓库的技术路线抉择。单体应用并不需要 Spring Cloud 的庞大依赖，强行合并会导致单体底座依赖膨胀与发布节奏耦合。

## Decision

确立 `ateng-cloud` 为完全独立的独立代码仓库，单向依赖消费已发布的 `ateng-boot-bom`（基线锁定 `1.0.0` Release 版本），坚决杜绝依赖快照版本或双向循环依赖。

## Consequences

1. **单体纯洁性保障**：`ateng-boot` 维持其作为轻量、零臃肿单体基础设施底座的定位，免受微服务复杂依赖侵入。
2. **正交复用**：`ateng-cloud` 100% 复用 `ateng-boot-core` 与 `ateng-boot-observability` 沉淀的四维上下文、序列化与追踪门面，避免跨项目重复造轮子。
3. **独立演进**：微服务组件可独立遵循自身的发版周期与 CI/CD 流程演进。
