# Context Map

## Contexts

- [Nacos](./ateng-cloud-nacos/CONTEXT.md) — 微服务动态注册发现、元数据治理与 Nacos 现代配置中心集成
- [Feign](./ateng-cloud-feign/CONTEXT.md) — 声明式 HTTP/RPC 客户端通信、四维上下文透传、双模自适应解包与防重试雪崩

## Relationships

- **Nacos → All Microservices**: 提供基础服务实例寻址表与全域动态配置源
- **Feign → Cross-Service RPC**: 承载微服务间声明式 HTTP 同步调用，消费 Nacos 实例路由并透明透传四维上下文
- **Boot Base → Cloud Ecosystem**: `ateng-cloud` 单向消费 `ateng-boot-bom` 作为通用单体基础设施底座
