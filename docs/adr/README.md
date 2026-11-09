# 架构决策记录 (Architecture Decision Records - ADR)

本目录收录了 `ateng-cloud` 微服务基础设施套件在演进过程中的关键架构决策记录（ADR）。每份记录详细阐述了当时的技术背景、面临的分布式痛点、备选方案权衡以及最终确立的架构红线。

## 决策矩阵总览 (Decision Matrix)

| 编号 | 决策标题 | 状态 | 核心收益与权衡 | 核心关联模块 | 详细文档 |
| :--- | :--- | :---: | :--- | :--- | :---: |
| **0001** | **多仓物理隔离与单向锁定 `ateng-boot-bom` 底座架构** | 🟢 已采纳 (Accepted) | 物理剥离 `ateng-boot` 单体底座与 `ateng-cloud` 微服务套件，单向消费发布态 `ateng-boot-bom`，杜绝跨仓 SNAPSHOT 污染与职责耦合 | `ateng-cloud-bom`, 全域模块 | [查看](./0001-multi-repo-and-upstream-bom-baseline.md) |
| **0002** | **微服务核心依赖基线选型与代际升级策略** | 🟢 已采纳 (Accepted) | 锚定 Spring Boot `3.3.13`、Spring Cloud `2023.0.x`、Spring Cloud Alibaba `2023.0.x` 与 Dubbo `3.3.x` 工业黄金组合 | `ateng-cloud-bom` | [查看](./0002-microservice-dependency-baseline-and-upgrade-strategy.md) |
| **0003** | **Nacos 流量无损上下线与配置安全热更架构设计** | 🟢 已采纳 (Accepted) | 采用现代 `spring.config.import` 协议，实现优雅停机先摘流后等待排空，严禁在 `@Service` 滥用 `@RefreshScope` | `ateng-cloud-nacos` | [查看](./0003-nacos-zero-downtime-and-safe-hot-reload-architecture.md) |
| **0004** | **Feign 声明式 HTTP 通信、自适应解包与防雪崩重试架构设计** | 🟢 已采纳 (Accepted) | 支持 `Result<T>` 与裸 `DTO` 双模自适应解包，默认关闭非幂等写请求读取超时重试，阻断重试风暴 | `ateng-cloud-feign` | [查看](./0004-feign-adaptive-unwrap-and-retry-storm-defense.md) |
| **0005** | **Dubbo 3.3 高性能 RPC、四维上下文透传与双模契约架构设计** | 🟢 已采纳 (Accepted) | 支持 Triple (HTTP/2) 与原生 TCP 双协议共存，双向 Filter 全自动透传四维上下文与灰度标签並严格 `finally` 清理 | `ateng-cloud-dubbo` | [查看](./0005-dubbo-triple-context-propagation-and-contract-architecture.md) |
| **0006** | **Gateway WebFlux 响应式底座、零信任安全与动态治理架构设计** | 🟢 已采纳 (Accepted) | 坚守 WebFlux/Netty 纯响应式底座（严禁引入 Servlet 依赖），网关收口 Sa-Token 鉴权并签发 `Same-Token` 与 2MB 内存护栏 | `ateng-cloud-gateway` | [查看](./0006-gateway-reactive-architecture-and-zero-trust-defense.md) |
| **0007** | **Sentinel 流量防卫、受管熔断降级与 Nacos 动态持久化架构设计** | 🟢 已采纳 (Accepted) | 统一 `FallbackFactory` 受管降级保留原始 `Throwable`，内置 REST 路径聚合清洗防内存打爆与 Nacos 5 大规则持久化 | `ateng-cloud-sentinel` | [查看](./0007-sentinel-traffic-defense-and-managed-fallback-architecture.md) |
| **0008** | **流量染色、泳道隔离与金丝雀灰度路由架构设计** | 🟢 已采纳 (Accepted) | 构建 `TrafficContextHolder` 与三层复合灰度调度责任链（蓝绿强锁 $\rightarrow$ Header 染色 $\rightarrow$ 权重金丝雀 $\rightarrow$ 基线保底） | `ateng-cloud-traffic` | [查看](./0008-traffic-gray-routing-and-lane-isolation-architecture.md) |
| **0009** | **分布式事务增强、TCC 防悬挂屏障与跨进程协同架构设计** | 🟢 已采纳 (Accepted) | 融合 Seata AT/TCC 双核驱动，提供 TCC 空回滚/防悬挂/幂等屏障，通过 Feign/Dubbo 透传 XID 并协同动态数据源假切源守卫 | `ateng-cloud-seata` | [查看](./0009-seata-distributed-transaction-and-tcc-fence-architecture.md) |
| **0010** | **微服务全家桶冒烟准出门禁与 CI 敏捷测试矩阵架构设计** | 🟢 已采纳 (Accepted) | 建立 `ateng-cloud-all` 聚合装配与 `sample-cloud-smoke-test` 全家桶冒烟工程，零衝突点亮微服务核心组件 | `ateng-cloud-all`, `ateng-cloud-samples` | [查看](./0010-microservice-all-in-one-smoke-test-and-ci-agile-matrix-architecture.md) |

---

## 编写与维护规范 (ADR Governance & Anti-Inflation)

1. **防膨胀铁律（Anti-Inflation Rule）**：仅当发生**从 0 到 1 孵化新微服务模块、引入全新分布式协议/中间件、或推翻既有跨模块架构契约**时，才允许新建独立 ADR 文件（按序递增四位编号 `NNNN-kebab-case-title.md`）。
2. **存量优先修订（Update Over Create）**：针对既有微服务模块的常规特性扩展、拦截器加固、配置项调优或局部重构，**严禁新建碎片化 ADR**；必须优先在所属模块的既有 ADR 文档中增量修订（更新决策章节或演进记录），并同步维护本索引表与 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)。
3. **单一职责与真实追溯**：每份 ADR 聚焦一个核心架构领域，正文中引用的配置前缀、类名与模块路径必须与当前代码库保持一致。
