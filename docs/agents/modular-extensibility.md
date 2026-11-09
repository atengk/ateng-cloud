# 微服务模块孵化与扩展通用标准 (Modular Extensibility SOP)

本指南规范 `ateng-cloud` 从 0 到 1 孵化全新微服务 Starter 模块（`ateng-cloud-<module>`）及其配套样例工程（`ateng-cloud-samples/sample-cloud-<module>`）的标准流程。

*(注：既有存量模块从 1 到 N 的深度体检、能力补齐与加固重构，请遵循 [微服务模块加固与全场景演进规范](./module-hardening-and-expansion.md)。)*

---

## 1. 工业级六件套闭环门禁 (The 6-Piece Gate)

孵化任何新微服务 Starter，必须一次性完整交付以下“工业级六件套”：

1. **行业全场景前置对标 (Scenario Benchmarking Gate)**：
   - 对标微服务主流开源标杆（Spring Cloud 官方、Spring Cloud Alibaba、Apache Dubbo 3.3 等），梳理排名前 80%~90% 的企业级分布式高并发生产场景与 SPI 扩展点，杜绝孵化“半残”玩具模块。
2. **BOM 统管与模块拓扑注册 (BOM-First & Topology Gate)**：
   - 在根 `pom.xml` 与 `ateng-cloud-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管新模块及其第三方依赖版本（子模块 `pom.xml` 严禁硬编码 `<version>`）；
   - 将新 Starter 加入 `ateng-cloud-all/pom.xml`（响应式网关类模块除外），将新 Sample 加入 `ateng-cloud-samples/pom.xml` 并标配 `<maven.deploy.skip>true</maven.deploy.skip>`。
3. **底座原语复用与微服务架构对齐 (Core-First & Invariants Gate)**：
   - 100% 优先复用上游 `ateng-boot-core` 与 `ateng-boot-observability` 原语（`UserContextHolder`、`TenantContextHolder`、`JsonUtil`、`RetryUtil`、`IdUtil` 等）；
   - 严格对齐 [`AGENTS.md`](../../AGENTS.md) 确立的 **7 大微服务架构铁律**、**九道成熟度防线** 与 **防御性自动装配四铁律**（`AutoConfiguration.imports`、条件守卫、`@ConditionalOnMissingBean`、显式装配时序）。
4. **1:1 双模自洽样例工程 (Dual-Mode Sample Gate)**：
   - 在 `ateng-cloud-samples/sample-cloud-<module>` 建立 1:1 独立验证沙箱，严格遵循 [`documentation-specification.md`](./documentation-specification.md) 第 3 章规范（多 Controller 领域暴露、网关 WebFlux 纯净特例、`@Profile("!local")` 离线自洽桩 + `application-local.example.yml` 双模联调）。
5. **Sample-First 五维文档与体验卡 (5D Documentation Gate)**：
   - 先跑通 Sample 端到端测试，再按 [`documentation-specification.md`](./documentation-specification.md) 逆向提取生成 Starter 五维 `README.md`（100% 全景速查矩阵、三要素就近闭环、能力大章收敛）与 Sample 四段式体验卡 `README.md`。
6. **领域词汇表与 ADR 架构记录 (Domain & ADR Gate)**：
   - 按 [`domain.md`](./domain.md) 新建 `ateng-cloud-<module>/CONTEXT.md` 领域词汇表，新增 `docs/adr/NNNN-*.md` 架构决策记录，并同步注册至 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md) 与 [`docs/adr/README.md`](../adr/README.md)。

---

## 2. 阶梯二准出验证命令

新模块孵化完成后，必须执行阶梯二构建验证（含全家桶冒烟测试 `sample-cloud-smoke-test`）并通过 [`documentation-specification.md`](./documentation-specification.md) 中的 **13 项交付前强制自检清单**：

```bash
mvn test -pl ateng-cloud-<module>,ateng-cloud-samples/sample-cloud-<module>,ateng-cloud-samples/sample-cloud-smoke-test -am -T 1C -nsu -B
```
