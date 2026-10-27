# 微服务模块孵化与扩展通用标准 (Modular Extensibility SOP)

本指南规范 `ateng-cloud` 孵化新微服务 Starter 模块与扩展生态的标准流程（0 到 1 阶段），确保所有微服务通用构件具备统一的工程质量、架构一致性与纯净度。

*(注：新模块孵化完成后，后续的存量加固、深度重构与全场景功能补齐，请遵循 [微服务模块加固与全场景演进规范](./module-hardening-and-expansion.md)。)*

---

## 1. 工业级六件套闭环门禁 (The 6-Piece Gate)

无论扩展何种新微服务 Starter（如 Feign、Dubbo、Gateway、Sentinel、Traffic、Seata 等），一律遵循“工业级六件套”闭环门禁：

1. **行业全场景前置对标 (Scenario Benchmarking Gate)**：
   - 孵化新模块前，必须对标微服务主流开源标杆（Spring Cloud Alibaba、Apache Dubbo 3.3、Spring Cloud 官方实现等），梳理出排名前 80%~90% 的企业级典型微服务高并发生产场景；
   - 明确新模块首期需要支撑的核心场景矩阵与扩展点 SPI，杜绝孵化出只有一两个玩具方法的“半残” Starter。
2. **BOM 统管版本 (BOM-First Invariant)**：
   - 在根 `pom.xml` 与 `ateng-cloud-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管新模块及其外部依赖版本；
   - 子模块声明依赖严禁配置裸 `<version>` 标签，上游 `ateng-boot` 依赖统一通过导入已发布的 `ateng-boot-bom` 正式版解耦集成。
3. **专属自包含文档 (Sample-First 逆向提取)**：
   - 子模块根目录标配专属 `README.md`，文档中的所有 Java 代码与 YAML 示例必须 100% 逆向提取自对应 Sample 中已跑通的真实端到端场景；
   - 严格遵循“五维生产就绪交付模型”，核心显要位置 100% 收录全景速查矩阵（收录所有门面、公开方法、注解与配置项）。
4. **全自动自验用例与 1:1 场景工程 (100% Feature Coverage)**：
   - 在 `ateng-cloud-samples/sample-cloud-<scenario>` 配套专属 1:1 对应的场景工程，新模块的所有公开 API、核心注解与配置项必须 100% 在 Sample 中具备端到端可运行用例。
5. **底座原语优先复用 (Core-First Reuse Gate)**：
   - 新增微服务中间件涉及上下文持有者、JSON 序列化、分批切片、退避重试或敏感脱敏等需求时，强制 100% 优先复用 `ateng-boot-core` 与 `ateng-boot-observability` 已就绪的基础设施与标准件；
   - 坚决杜绝在子模块内私造工具类或引入第三方重复全量依赖。
6. **微服务专属架构红线对齐 (Microservice Invariants Gate)**：
   - 严格遵循网关响应式与 Servlet 物理隔离、全链路四维上下文穿透、Feign 读超时禁重试、Same-Token 零信任拦截、Nacos 现代加载及 100% 离线自洽单测铁律。

---

## 2. 架构契约与自动装配规范

1. **九道成熟度防线**：新模块编码必须全面对齐全局 [`AGENTS.md`](../../AGENTS.md) 确立的九道成熟度防线与 7 大微服务专属铁律。
2. **防御性自动装配**：
   - 装配类统配 `@AutoConfiguration`，统一且只能注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`；
   - 对外暴露的公共 `@Bean` 必须标配 `@ConditionalOnMissingBean`；
   - 配置属性统配 `Duration`（时间）与 `DataSize`（容量），嵌套配置标配 `@NestedConfigurationProperty`；
   - 存在装配时序依赖时，显式标注 `@AutoConfigureAfter` 或 `@AutoConfigureBefore`。

---

## 3. 微服务场景化样例工程设计准则 (Sample Design Standards)

为保障多模块演化时的极速本地测试准出与环境无关性，样例工程遵循以下准则：

- **1:1 独立场景沙箱**：每个微服务 Starter 严格对应一个专属的 `sample-cloud-<scenario>`（如 `sample-cloud-nacos`、`sample-cloud-feign`、`sample-cloud-gateway` 等），彻底杜绝跨 Starter 混合的胖样例；
- **响应式 vs 阻塞式分层分明**：
  - **网关场景**（`sample-cloud-gateway`）：纯响应式 Netty 运行时，配套 `WebTestClient` 端到端集成测试；
  - **业务服务场景**（`sample-cloud-feign`、`sample-cloud-dubbo` 等）：标准 Servlet 运行时，配套 `MockMvc` / `@SpringBootTest` 跑通真实 RPC 与上下文传递流程；
- **100% 纯内存/离线自洽**：单测配置中显式声明 `spring.cloud.nacos.discovery.enabled: false` 与 `spring.cloud.nacos.config.enabled: false`，默认依托纯内存 Mock 运行端到端测试，保障 `mvn test` 无网络环境秒级通过；
- **自包含与零共享**：各场景工程完全独立自洽，严禁在样例工程之间建立依赖；
- **非分发属性声明**：样例工程自身标配声明 `<maven.deploy.skip>true</maven.deploy.skip>`，杜绝将测试工程封包发布至 Maven Central。

---

## 4. 极速本地构建验证

新模块开发完成后，必须执行多线程精准加速命令执行端到端自验，保障 100% PASS：

```bash
mvn test -pl <new-module>,ateng-cloud-samples/sample-cloud-<scenario> -am -T 1C -nsu -B
```
