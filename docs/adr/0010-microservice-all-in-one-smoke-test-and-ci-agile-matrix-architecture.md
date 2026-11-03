# 微服务全家桶冒烟准出门禁与 CI 敏捷测试矩阵架构设计

## Context

在 `ateng-cloud` 微服务基础设施框架中，各 Starter 场景样例工程（`ateng-cloud-samples/sample-cloud-*`）遵循与 Starter 严格 1:1 解耦映射原则（包含 nacos、feign、dubbo、gateway、sentinel、traffic-gray、seata 独立沙箱与 smoke-test 全家桶冒烟工程）。

随着微服务治理特性的持续丰富，若在云端 GitHub Actions CI 多 JDK 矩阵（Ubuntu / Java 17 & 21 LTS）中对全反应堆无差别执行全量 Sample 回归，面临以下工程痛点：

1. **CI 测试反馈周期冗长**：
   每个微服务场景工程都需要启动内嵌 Web 容器（Tomcat/Netty）、初始化 Spring 上下文、加载配置与路由过滤链；若对全量 8 个 Sample 逐一启动集成测试，极易导致 CI 执行时间成倍膨胀，降低 PR 门禁流转与主干代码合并效率；
2. **场景工程与装配门禁职责混淆**：
   细分场景样例工程（如 `sample-cloud-feign`、`sample-cloud-traffic-gray`）的本质职责是作为**“面向业务开发者的 1:1 场景沙箱与文档代码源头”**，应在本地日常开发中通过“阶梯二：精准定向联动”（`-pl <changed-module>,sample-cloud-<module>`）秒级验证；
3. **全反应堆装配与冲突检测的核心落点**：
   各微服务 Starter（Nacos、Feign、Dubbo、Sentinel、Traffic）在同一个 Spring 容器中协同装配时，是否存在类路径冲突、条件装配漏检、上下文传递断流等致命问题，核心应由聚合全家桶 Starter（`ateng-cloud-all`）及其配套的冒烟工程（`sample-cloud-smoke-test`）承担唯一装配门禁；
4. **微服务特有的隔离依赖漏检风险 (Gateway & Seata Isolation)**：
   微服务体系有其特殊性：`ateng-cloud-gateway` 基于 WebFlux 纯响应式架构（遵循 Reactive vs Servlet 物理隔离铁律，在 `all` 中标配 `optional: true`），而 `ateng-cloud-seata` 保持独立按需依赖。若盲目执行 `mvn test -pl sample-cloud-smoke-test -am`，会导致 Gateway 与 Seata 因未在 `all` 必选依赖链上而被 CI 单元测试完全漏检。

---

## Decision

经由 `/grill-with-docs` 架构推演，正式确立**“微服务全家桶冒烟准出门禁与 CI 敏捷测试矩阵”**方案：

### 1. 确立 `sample-cloud-smoke-test` 为 CI 唯一样例门禁
- `sample-cloud-smoke-test` 专职负责检验全量骨干 Starter 无冲突自动装配、四维上下文快照及核心端到端主链路健康；
- 各细分场景 Sample（`sample-cloud-nacos` 等 7 个工程）从云端 CI 的常规全量扫描中解耦，保留在本地“阶梯二：定向联动验收”与开发者开箱体验中。

### 2. 精准 Reactor 目标编排与全 Starter 100% 守护
为了兼顾“跳过细分 Sample”与“绝不漏测任何 Starter（特别是 Gateway 与 Seata）”，CI 流水线（`.github/workflows/ci.yml`）采用 Maven 精准依赖拓扑指令：

```bash
mvn clean test -pl ateng-cloud-gateway,ateng-cloud-seata,ateng-cloud-samples/sample-cloud-smoke-test -am -B -T 1C -nsu
```

该指令驱动 Maven Reactor 精准解析出 **11 个核心模块全集**：
- **物料清单与底座**：`ateng-cloud`（根 POM）、`ateng-cloud-bom`；
- **全量微服务 Starter (100% 覆盖)**：`ateng-cloud-nacos`、`ateng-cloud-feign`、`ateng-cloud-traffic`、`ateng-cloud-dubbo`、`ateng-cloud-gateway`、`ateng-cloud-sentinel`、`ateng-cloud-seata`、`ateng-cloud-all`；
- **冒烟自检工程**：`ateng-cloud-samples`（二级 Parent POM）、`sample-cloud-smoke-test`。

### 3. 三阶梯测试准出体系分工对齐
- **阶梯一：特性日常自验（秒级极速反馈）**：单个模块改动，`mvn test -pl <changed-module> -am -T 1C -nsu`；
- **阶梯二：本地提交准出验收（精准端到端集成）**：提交前，执行变更模块与对应场景样例工程定向联动：`mvn test -pl <changed-module>,ateng-cloud-samples/sample-cloud-<scenario> -am -T 1C -nsu`，涉及全家桶联动 `smoke-test`；
- **阶梯三：全反应堆 CI 冒烟准出门禁 (Post-Push CI Gate)**：覆盖全量 Starter 严密单元测试 + 全家桶 `sample-cloud-smoke-test` 端到端集成，由 GitHub Actions 在 JDK 17 & 21 LTS 矩阵下异步守护。

---

## Consequences & Trade-offs

### 正向收益
1. **CI 流水线执行极速高效**：消除了 7 个细分微服务场景工程重复冷启动 Spring 容器的开销，显著提升了 GitHub Actions 并发周转率与 PR 准入流转速度；
2. **测试守护零遗漏**：通过精准包含 `ateng-cloud-gateway` 与 `ateng-cloud-seata`，守住了 100% Starter 单元测试覆盖率底线；
3. **架构语义清晰正交**：`Starter` 负责底层单元级与边界防卫自洽，`sample-cloud-smoke-test` 负责全家桶跨组件装配自洽，各场景 Sample 负责输出生产级代码范式。

### 妥协成本
- 若某个细分场景 Sample（如 `sample-cloud-sentinel` 中的特定演示 Controller）出现编译报错或配置失效，云端 CI 无法直接捕获，依托本地阶梯二定向联动及发布前回归把关。
