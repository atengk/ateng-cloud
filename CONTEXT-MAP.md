# Context Map

## Contexts

- [Nacos](./ateng-cloud-nacos/CONTEXT.md) — 微服务动态注册发现、元数据治理与 Nacos 现代配置中心集成

## Relationships

- **Nacos → All Microservices**: 提供基础服务实例寻址表与全域动态配置源
- **Boot Base → Cloud Ecosystem**: `ateng-cloud` 单向消费 `ateng-boot-bom` 作为通用单体基础设施底座
