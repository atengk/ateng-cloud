# Context Map

## Contexts

- [Nacos](./ateng-cloud-nacos/CONTEXT.md) — 微服务动态注册发现、元数据治理与 Nacos 现代配置中心集成
- [Feign](./ateng-cloud-feign/CONTEXT.md) — 声明式 HTTP/RPC 客户端通信、四维上下文透传、双模自适应解包与防重试雪崩
- [Dubbo](./ateng-cloud-dubbo/CONTEXT.md) — 高性能 RPC 通信与治理、Triple/TCP 双核协议、四维上下文透传与双模契约校验
- [Gateway](./ateng-cloud-gateway/CONTEXT.md) — WebFlux 纯响应式网关、Sa-Token 集中鉴权、零信任 Same-Token 签发、动态路由与文档聚合

## Relationships

- **Gateway → Internal Microservices**: 统一收口公网南北向流量，执行外部标头清洗与 Sa-Token 鉴权，向下游安全注入四维上下文与 Same-Token
- **Nacos → All Microservices**: 提供基础服务实例寻址表与全域动态配置源（包含 Gateway 动态路由定义）
- **Feign → Cross-Service RPC**: 承载微服务间声明式 HTTP 同步调用，消费 Nacos 实例路由并透明透传四维上下文
- **Dubbo → Cross-Service RPC**: 承载微服务间高性能二进制/Triple RPC 通信，与 Feign 正交共存，透明透传四维上下文与灰度标签
- **Boot Base → Cloud Ecosystem**: `ateng-cloud` 单向消费 `ateng-boot-bom` 作为通用单体基础设施底座
