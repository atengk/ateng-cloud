# Samples Domain Glossary

`ateng-cloud-samples` 是 `ateng-cloud` 微服务基础设施套件的 1:1 真实业务场景体验展厅与双模自洽联调验证沙箱。

## Ubiquitous Language

| Term | Definition |
| :--- | :--- |
| **SampleAggregator** | `ateng-cloud-samples` 聚合父模块，统管全部微服务 Starter 对应的 `sample-cloud-*` 独立验证沙箱，自身不产出业务代码。 |
| **SampleScenarioModule** | 与每个功能型微服务 Starter 严格 1:1 对应的独立可运行示例工程（`ateng-cloud-<module> ↔ sample-cloud-<module>`，其中 `ateng-cloud-traffic` 对应 `sample-cloud-traffic-gray`），各 Sample 间严格遵守零共享代码（Zero-Shared-Code）铁律。 |
| **GatewayReactivePurityInvariant** | 网关响应式底座纯粹性红线：`sample-cloud-gateway` 运行在 Spring Cloud Gateway (WebFlux / Netty) 响应式底座上，**100% 严禁引入 `ateng-boot-web`（Servlet 栈）**；其余标准 Servlet 微服务 Sample 统一引入 `ateng-boot-web`。 |
| **MultiControllerExperience** | 标准 Servlet 微服务 Sample 按核心能力领域拆分多个业务语义鲜明的 `@RestController`（路由规范 `/sample/cloud/<module>/<feature>/<action>`，显式返回 `Result<T>`），提供开箱即用的 HTTP 交互体验与四段式体验卡文档。 |
| **SampleCoverageInvariant** | Sample 100% 全量能力覆盖铁律：Starter 模块暴露的所有自动装配特性、跨进程拦截器、治理策略、核心门面 API 与自定义注解，必须在对应 Sample 的真实业务场景与自动化集成测试中 100% 实装覆盖。 |
| **SampleFirstPipeline** | Sample-First 文档与研发流水线：必须先在 `sample-cloud-<module>` 中完成真实微服务场景与双模单测验证全绿，再将经过验证的代码、配置与契约 1:1 逆向提取至 Starter 的五维 `README.md`。 |
| **DualModeVerificationSuite** | 远程中间件双模联调测试套件：复用同一套 `*IntegrationTest` 业务断言，默认离线模式通过标注 `@Profile("!local")` 的 `@TestConfiguration` 内存桩/Mock 驱动 100% 离线自洽；激活 `local` Profile（配合 `application-local.example.yml` 模板与被 Git 忽略的 `application-local.yml`）时自动卸载测试桩，直连真实 Nacos、Sentinel、Dubbo、Seata 等外部集群。 |
| **SampleSmokeTest** | `sample-cloud-smoke-test` 终极全家桶冒烟测试工程（对应 `ateng-cloud-all`），在单进程上下文中同时点亮 Nacos、Feign、Dubbo、Sentinel、Traffic、Seata 等核心组件，验证 Bean 装配零冲突与拦截器顺序协同（[ADR-0010](../docs/adr/0010-microservice-all-in-one-smoke-test-and-ci-agile-matrix-architecture.md)）。 |
| **NonPublishableModule** | 非分发模块隔离契约：`ateng-cloud-samples` 及其子模块仅作为内部沙箱与回归测试载体，强制配置 `<maven.deploy.skip>true</maven.deploy.skip>` 并受根 `pom.xml` 的 `-DskipSamples` Profile 控制，严禁发布至 Maven Central。 |

## Avoid Terms

- **Avoid**: `SharedSampleCommon` / 跨 Sample 共享工具包 — 违背各 Sample 独立复刻、零耦合自包含原则，使用 `Zero-Shared-Code` 独立沙箱替代。
- **Avoid**: 在 `sample-cloud-gateway` 中引入 `ateng-boot-web` 或 `spring-boot-starter-web` — 必然引发 Servlet 与 WebFlux 底座冲突报错，网关 Sample 必须遵循 `GatewayReactivePurityInvariant`。
- **Avoid**: `FooBarDemo` / 玩具桩示例 — 严禁在 Sample 源码或文档中使用 `foo`/`bar`/`test1` 等无意义命名，统一使用电商订单、库存扣减、跨境支付、灰度泳道等真实微服务领域语义。
