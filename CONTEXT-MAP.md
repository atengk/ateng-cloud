# Context Map

## Contexts

- [BOM](./ateng-cloud-bom/CONTEXT.md) — 微服务全域依赖版本物料清单、上游 `ateng-boot-bom` 基线统管与依赖收敛仲裁（[ADR-0001](./docs/adr/0001-multi-repo-and-upstream-bom-baseline.md), [ADR-0002](./docs/adr/0002-microservice-dependency-baseline-and-upgrade-strategy.md)）
- [Nacos](./ateng-cloud-nacos/CONTEXT.md) — 微服务动态注册发现、流量无损上下线、元数据治理与 Nacos 现代配置中心集成（[ADR-0003](./docs/adr/0003-nacos-zero-downtime-and-safe-hot-reload-architecture.md)）
- [Feign](./ateng-cloud-feign/CONTEXT.md) — 声明式 HTTP/RPC 客户端通信、双向四维上下文与灰度/事务上下文传播、双模自适应解包、防重试雪崩与慢调用治理（[ADR-0004](./docs/adr/0004-feign-adaptive-unwrap-and-retry-storm-defense.md)）
- [Dubbo](./ateng-cloud-dubbo/CONTEXT.md) — 高性能 RPC 通信与治理、Triple/TCP 双核协议、四维上下文透传、双模契约校验与慢调用可观测性（[ADR-0005](./docs/adr/0005-dubbo-triple-context-propagation-and-contract-architecture.md)）
- [Gateway](./ateng-cloud-gateway/CONTEXT.md) — WebFlux 纯响应式网关、Sa-Token 集中鉴权、零信任 Same-Token 签发、动态路由与文档聚合（[ADR-0006](./docs/adr/0006-gateway-reactive-architecture-and-zero-trust-defense.md)）
- [Sentinel](./ateng-cloud-sentinel/CONTEXT.md) — 流量防卫、熔断降级、受管降级工厂、Web 异常追踪桥接、路径防爆清洗、多维调用溯源与 Nacos 5 大规则全家桶持久化及全景自省诊断（[ADR-0007](./docs/adr/0007-sentinel-traffic-defense-and-managed-fallback-architecture.md)）
- [Traffic](./ateng-cloud-traffic/CONTEXT.md) — 全链路流量染色、泳道隔离、三级规则消解、100 桶粘性切流、三层复合调度责任链与 Nacos 动态切流热更（[ADR-0008](./docs/adr/0008-traffic-gray-routing-and-lane-isolation-architecture.md)）
- [Seata](./ateng-cloud-seata/CONTEXT.md) — 分布式事务增强、AT/TCC 双核驱动、TCC 防悬挂业务屏障、XID 全链路透传与假切源守卫协同（[ADR-0009](./docs/adr/0009-seata-distributed-transaction-and-tcc-fence-architecture.md)）
- [All](./ateng-cloud-all/CONTEXT.md) — 通用微服务骨干构件聚合全家桶、响应式网关隔离与裁剪边界（[ADR-0010](./docs/adr/0010-microservice-all-in-one-smoke-test-and-ci-agile-matrix-architecture.md)）
- [Samples](./ateng-cloud-samples/CONTEXT.md) — 1:1 真实微服务场景验证展厅（`sample-cloud-*`）、双模联调测试套件与全家桶冒烟工程（[ADR-0010](./docs/adr/0010-microservice-all-in-one-smoke-test-and-ci-agile-matrix-architecture.md)）

## Relationships

- **BOM → All Microservices & Cloud Starters**: 作为唯一权威物料清单，统管微服务全域版本基线，消除传递依赖冲突
- **All → Standard Business Microservices**: 为标准 Servlet 微服务提供一站式起步全家桶，聚合 Nacos、Feign、Dubbo、Sentinel、Traffic 与 Seata 骨干能力，网关保持可选隔离
- **Gateway → Internal Microservices**: 统一收口公网南北向流量，执行外部标头清洗与 Sa-Token 鉴权，向下游安全注入四维上下文与 Same-Token
- **Sentinel → All Servlet Microservices**: 为 WebMVC 接口提供流量过载防护与路径防爆，为 Feign 客户端提供受管熔断降级
- **Nacos → All Microservices**: 提供基础服务实例寻址表与全域动态配置源（包含 Gateway 动态路由定义与 Sentinel 动态规则）
- **Feign → Cross-Service RPC**: 承载微服务间声明式 HTTP 同步调用，消费 Nacos 实例路由并透明透传四维上下文与灰度标签
- **Dubbo → Cross-Service RPC**: 承载微服务间高性能二进制/Triple RPC 通信，与 Feign 正交共存，透明透传四维上下文与灰度标签
- **Traffic → All Microservices & Gateway**: 统管全域流量染色上下文（`TrafficContextHolder`）与动态分流规则，为 Spring Cloud LoadBalancer 供给三层灰度责任链并守护跨跳泳道穿透
- **Seata → All Microservices & Feign/Dubbo**: 为跨服务与跨库强一致性业务提供分布式事务协调，通过 Feign/Dubbo 对称透传 XID，与动态数据源假切源守卫无缝协同
- **Samples Matrix → All Cloud Starters**: 每个 Starter 对应独立 `sample-cloud-<module>` 沙箱与 `sample-cloud-smoke-test` 全家桶冒烟验证，严格遵守零共享代码与非分发隔离契约
- **Boot Base → Cloud Ecosystem**: `ateng-cloud` 单向消费 `ateng-boot-bom` 作为通用单体基础设施底座

## Architectural Decisions

全量架构决策记录索引、状态矩阵与维护规范统一收敛于 **[架构决策记录看板 (docs/adr/README.md)](./docs/adr/README.md)**。
