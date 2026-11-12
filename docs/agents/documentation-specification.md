# 微服务工业级文档与样例工程标准规范 (Cloud Documentation & Sample Specification)

## 1. 核心定位与设计哲学 (Mission & Scope)

在 `ateng-cloud` 微服务基础设施框架中，**Starter 文档、根目录门面（`README.md` 与 `llms.txt`）与样例工程（Sample）是连接微服务通用治理能力与业务开发者（及 AI 编程助手）的第一生命线**。本规范确立 `ateng-cloud` 所有微服务子模块文档（`ateng-cloud-*/README.md`）、仓库根目录门面与 AI 索引及配套样例工程（`ateng-cloud-samples/sample-cloud-*`）的工业级标准、Sample-First 双向锚定体系与 **13 项交付前强制自检清单**，作为代码审查（`/code-review`）的一票否决红线（Red Card Block）。

---

## 2. 微服务 Starter 模块五维 `README.md` 规范 (The Starter Document Invariant)

微服务子模块根目录下的 `README.md` 是该模块对外唯一权威的**“全功能契约真理源”**。每个微服务 Starter 的 `README.md` 必须覆盖 **五维生产就绪交付模型**（① 定位与架构拓扑、② 100% 全景速查矩阵、③ 核心能力领域大章与三要素闭环、④ 生产避坑与容灾治理指南、⑤ 完整 YAML 配置属性字典）。

### 2.1 标准大纲骨架 (Standard Outline Architecture)

```markdown
# ateng-cloud-<module> <微服务中文功能全称>

> 一句话核心价值宣言：阐明核心微服务技术栈与面向的企业级高可用分布式生产场景。

---

## 0. 特性与公共 API 全景速查矩阵 (100% 覆盖门面类、方法签名、注解、SPI 与核心配置)

## 1. 模块定位与 Maven 坐标

## 2. 核心架构与设计不变量 (Mermaid 微服务调用拓扑 / 容灾防线 / 核心原理)

## 3. <业务能力领域大章一> (按领域聚合收敛)
   ### 3.1 <核心子特性 A>
   ### 3.2 <核心子特性 B>

## 4. <业务能力领域大章二> (按领域聚合收敛)
   ...

## N-1. 生产环境避坑与高可用调优指南

## N. 完整 YAML 配置属性清单 (全量速查附录)
```

### 2.2 章节“三要素就近闭环”铁律 (Co-located 3-Element Invariant)

**【红线阻断项】** 严禁出现“正文只讲 Java 代码怎么调用，而关键微服务配置却扔在几百行后文末”的脱节现象！每个具体的微服务特性章节必须在**当前视口内**实现以下“三要素”自洽闭环：

1. **本节专属 YAML 配置片段**：仅展示当前微服务特性所需的关键配置项（含 Nacos 配置中心或本地基础配置），标配中文注释与生产合理默认值，键名与 `@ConfigurationProperties` 类字段逐字对齐。
2. **面向实战的业务 Java 示例**：展示如何通过注解或微服务门面调用该特性；代码必须 100% 逆向提取自已在 Sample 中跑通的真实微服务业务代码。
3. **核心机制与微服务边界防御说明**：显式讲透底层机制（如连接超时、写接口读超时禁重试、双模解包、受管降级 `FallbackFactory`、四维上下文穿透、流量染色标头透传等）与生产注意事项。

> **注**：文档末尾的《完整 YAML 配置属性清单》仅作为全量属性的集中速查字典，**严禁**作为该模块配置的唯一暴露点。

### 2.3 “能力领域大章”收敛架构 (Capability Domain Aggregation)

**【红线阻断项】** 严禁因开发批次不同而在文档末尾随意追加零碎小章节！必须按微服务技术领域或业务主线将同类功能收敛为**一级大章**，并在大章下合理划分二级小节（例如：`第 3 章：声明式 HTTP 客户端与弹性容灾` 下设 `3.1 客户端声明式接口定义`、`3.2 双模智能剥壳与因果转译`、`3.3 写接口禁重试与读超时熔断降级`）。

### 2.4 真实微服务业务语义场景驱动 (Real-World Business Semantics)

**【红线阻断项】** 严禁将单元测试中的测试桩代码（如 `key1`、`value1`、`RPC_SUCCESS`、`MOCK_FALLBACK`）复制到文档或 Sample 中！必须演化为真实高频微服务生产拓扑：
- **服务注册与配置**（`nacos`）：电商动态运费费率热更（`FreightRateProperties`）、流量无损上下线状态机探测；
- **声明式远程调用**（`feign`）：订单服务跨进程调用商品中心查询库存详情（`ProductStockVO`）、写接口读超时禁重试与受管降级；
- **高性能 RPC 撮合**（`dubbo`）：高频交易撮合与清结算服务（`TradeBillingDubboService`）、Triple/TCP 双协议与四维上下文透传；
- **云原生网关接入**（`gateway`）：统一公网入口动态路由、Sa-Token 集中鉴权签发 `Same-Token`、Netty 2MB 内存护栏；
- **流量防卫与降级**（`sentinel`）：秒杀接口 QPS 限流、三方支付接口慢调用/异常率熔断、Nacos 5 大规则持久化；
- **全链路流量染色**（`traffic`）：三层复合灰度路由（蓝绿强锁 $\rightarrow$ `X-Traffic-Tag` 染色 $\rightarrow$ 权重金丝雀 $\rightarrow$ 基线保底）；
- **分布式事务协调**（`seata`）：跨境电商提单（订单 + 库存 + 账户）AT/TCC 双核驱动与 TCC 防悬挂屏障。

### 2.5 Markdown 排版完整性与防断裂铁律 (Markdown Integrity Guard)

1. **代码块闭合与防嵌套撕裂**：所有代码块必须显式声明语言标识符（`java`、`yaml`、`xml`、`bash`、`mermaid`）并严格成对闭合；若外层需包裹内层代码块，外层必须使用四反引号 ```` ``` ````。
2. **块级元素空行隔离**：标题、列表、代码块、引用块（`>`）与 Markdown 表格前后**必须保留一个空行**。
3. **表格结构严整**：表头与对齐分隔行（`| :--- |`）列数必须严格一致；单元格内严禁直接硬回车换行（多行内容使用 `<br>`），单元格内包含管道符 `|` 时必须转义为 `\|`。
4. **Mermaid 图表语法安全**：节点文本包含括号 `()`、方括号 `[]`、斜杠 `/`、冒号 `:` 时，**必须使用双引号包裹节点标签**（如 `A["Gateway 网关 (WebFlux)"]`）。
5. **文档内零绝对路径**：文档间互链 100% 使用相对路径（`./`、`../`），严禁写入 `file:///` 或本地盘符路径。

---

### 2.6 根目录门面文档 (`Root Storefront README.md`) 与 AI 索引 (`llms.txt`) 边界规范

根目录 [`README.md`](../../README.md) 与 [`llms.txt`](../../llms.txt) 是面向分布式架构选型者与外部 AI 编程助手的**“第一入口与机读全景地图”**，严禁堆砌内部流程细节或冗长开发规约：

1. **门面与发版脚本兼容守卫**：根目录 `README.md` 快速起步片段中的 `<artifactId>ateng-cloud-bom</artifactId>` 必须紧邻下一行 `<version>...</version>`，且上游 `ateng-boot-bom` 声明版本与根 `pom.xml` 保持一致，保障 [`scripts/release.sh`](../../scripts/release.sh) 自动升级发版与基线可验证。
2. **完整模块与 Sample 双向直达**：门面导航须 100% 覆盖全部 `ateng-cloud-*` Starter 与 8 个 `ateng-cloud-samples/sample-cloud-*` 体验沙箱（含 `traffic -> sample-cloud-traffic-gray` 与 `all -> sample-cloud-smoke-test`），并前置 AI 上下文入口表与微服务 `.cursorrules` 提示卡。
3. **机读 AI 索引 (`llms.txt`) 三铁律**：
   - **纯仓内相对路径**：遵循 [llmstxt.org](https://llmstxt.org/) 分组规范，100% 使用 `./...` 相对路径，**严禁跨仓库硬编码相对链接（如 `../ateng-boot`）**或绝对盘符路径（生态联动通过文字说明表达）。
   - **免硬编码版本号**：`llms.txt` 严禁写死 Spring Boot / Cloud / Alibaba / Dubbo / `ateng-boot-bom` 或自身发版版本号，统一引导 AI 从根 [`pom.xml`](../../pom.xml) 与 [`ateng-cloud-bom/README.md`](../../ateng-cloud-bom/README.md) 读取唯一版本真源。
   - **高危分布式红线前置**：首屏集中声明“网关纯 WebFlux 禁 `ateng-boot-web`”、“Nacos 禁 `@RefreshScope`”、“Feign 写接口禁重试”、“四维上下文 + `X-Traffic-Tag` + `Same-Token` + `XID` 透传与对称清理”等强约束，降低 AI 幻觉率。

---

## 3. 微服务 Sample 1:1 样例工程规范 (The Sample Engineering Invariant)

每个功能型微服务 Starter `ateng-cloud-<module>` 严格 1:1 对应 `ateng-cloud-samples/sample-cloud-<module>`（其中 `ateng-cloud-traffic` 对应 `sample-cloud-traffic-gray`，`ateng-cloud-all` 对应 `sample-cloud-smoke-test`，全量拓扑以根 `pom.xml` 与 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md) 为准）。各 Sample 间严格遵守 **零共享代码（Zero-Shared-Code）** 与 `<maven.deploy.skip>true</maven.deploy.skip>` 非分发隔离铁律。

### 3.1 全面 Web 体验化与网关响应式纯洁特例

1. **标准 Servlet 微服务 Sample 统一引入 Web 底座**：除网关外的所有 Sample（`nacos`、`feign`、`dubbo`、`sentinel`、`traffic-gray`、`seata`、`smoke-test`）统一在 `pom.xml` 中引入 `io.github.atengk:ateng-boot-web`，启动即可通过 HTTP/cURL 交互体验。
2. **【网关响应式纯洁性特例】**：`sample-cloud-gateway` 运行在 Spring WebFlux / Netty 响应式底座上，**100% 严禁引入 `ateng-boot-web`（Servlet 阻塞栈）**，通过响应式路由、Filter 与 `WebTestClient` 提供端到端体验与验证。
3. **多 Controller 领域拆分**：标准 Servlet 微服务 Sample 必须按能力领域拆分为多个高内聚 `@RestController`（严禁单一臃肿上帝 Controller），路由统一采用 `/sample/cloud/<module>/<feature>/<action>`，出参统一显式返回强类型 `Result<T>`。
4. **远程中间件双模联调**：
   - 默认模式依托 `@Profile("!local")` 的 `@TestConfiguration` 内存桩/Mock 驱动 100% 离线自洽（`spring.cloud.nacos.discovery.enabled=false`、`spring.cloud.nacos.config.enabled=false`）；
   - Sample 标配 `application.yml`（声明 `spring.config.import: "optional:classpath:application-local.yml"`）与大写占位符模板 `application-local.example.yml`；当本地存在 `application-local.yml`（已被 `.gitignore` 忽略）并以 `-Dspring.profiles.active=local` 启动时，直连真实 Nacos/Sentinel/Dubbo/Seata 集群并在 `@AfterEach` 清理测试数据。

### 3.2 Sample 模块 `README.md` 四段式极简体验卡模板

Sample 自身的 `README.md` 统一定位为**“启动与接口体验卡”**，严禁重复堆砌 Starter 的全景矩阵与底层原理，严格采用以下四段式模板：

````markdown
# sample-cloud-<module> <微服务场景全称>体验工程

> 专注于演示与自验 `<module>` 核心微服务特性在真实分布式场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-<module>
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线内存桩与自洽配置，无需启动外部 Nacos/Sentinel/Seata 中间件即可启动运行与跑通测试；
- **Local 真实模式**：复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串，通过 `-Dspring.profiles.active=local` 启动或执行测试（该文件已被 Git 忽略）。

---

## 3. 核心 Controller 接口与一键体验

| 功能场景 | Controller / 路由端点 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **<业务场景一>** | `<XxxController>` | `GET /sample/cloud/<module>/<feature>/<action>` | `curl "http://localhost:8080/sample/cloud/<module>/<feature>/<action>"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-<module>,ateng-cloud-samples/sample-cloud-<module> -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-cloud-<module> README](../../ateng-cloud-<module>/README.md)。
````

---

## 4. Sample-First 逆向研发流与 13 项准出自检清单 (Audit Checklist & Red Card Gate)

任何新微服务特性的研发或存量特性的重构，必须严格遵循 **Sample 真实场景编码 $\rightarrow$ 多 Controller 暴露 $\rightarrow$ 双模测试全绿 $\rightarrow$ 1:1 逆向提取至 Starter 五维 `README.md` $\rightarrow$ 更新 Sample 四段式体验卡** 的单向流水线。

### 交付前 13 项强制自检清单 (Pre-Delivery Audit Checklist)

在每次任务交付前或执行 `/code-review` 时，必须逐项核对以下 13 项判据（任一未通过直接触发 Red Card 阻断）：

- [ ] **1. 网关响应式与 Servlet 物理隔离**：`ateng-cloud-gateway` 与 `sample-cloud-gateway` 100% 纯 WebFlux/Netty 运行时，零 `ateng-boot-web` / `spring-boot-starter-web` 混入。
- [ ] **2. 全链路四维上下文与灰度标签对称清理**：Feign/Dubbo/MQ/Gateway 跨线程或跨进程拦截器完整透传 $\langle\text{MDC traceId}, \text{userId}, \text{tenantId}, \text{ignoreTenant}\rangle$ 及 `X-Traffic-Tag` / Seata `XID`，并在出口 `finally` 块 100% 清理。
- [ ] **3. Feign 防重试雪崩与双模解包**：非幂等写请求（POST/PUT/PATCH）读超时绝对禁止自动重试；支持 `Result<T>` 与裸 `DTO` 自适应解包。
- [ ] **4. 零信任 `Same-Token` 与 `@RefreshScope` 禁令**：网关签发并清洗外部伪造标头，下游强校验 `Same-Token`；Nacos 统一使用 `spring.config.import`，严禁在 `@Service`/`@Component` 标注 `@RefreshScope`。
- [ ] **5. 九道工业级成熟度防线**：对称门面、边界 Fail-Fast、确定性资源释放、宿主中立、无状态与受管线程池（耗时 I/O 锁使用 `ReentrantLock` 防虚拟线程 Pinning）、`Duration`/`DataSize` 强类型配置、保留原始 `cause` 链的 `BusinessException`、复用 `ateng-boot-core` 原语、对抗性单测。
- [ ] **6. 传输层与持久层绝对解耦（Zero-Entity-Leakage）**：Controller 与 RPC 契约严禁直接暴露持久层 ORM `Entity`，入参 Request/DTO/Command，出参显式 `Result<VO>`。
- [ ] **7. BOM 依赖集中统管与上游版本对齐**：子模块 `pom.xml` 零硬编码 `<version>`，所有版本收敛于根 `pom.xml` 或 `ateng-cloud-bom/pom.xml`，单向锁定正式版 `ateng-boot-bom`。
- [ ] **8. 全自洽离线单测三铁律**：测试显式关闭外部 Nacos/Sentinel/Seata 强连接（`enabled=false`），依托 `@Profile("!local")` 内存桩秒级全绿；修改 ThreadLocal/MDC/TrafficContext 必须在 `@AfterEach` 100% 清理。
- [ ] **9. 远程中间件双模联调就绪**：Sample 标配 `application-local.example.yml` 模板（大写占位符、零真实机密）；若本地已配置 `application-local.yml`，已在 `local` Profile 下完成联调与 `@AfterEach` 脏数据清理（无环境则标注 `[豁免：无可用环境]`）。
- [ ] **10. Sample 1:1 场景全覆盖与多 Controller 体验**：Starter 所有公开 API、注解、配置与拦截器在 `sample-cloud-<module>` 中 100% 覆盖；除网关外统一引入 `ateng-boot-web` 并按领域拆分多 Controller，Sample 间零共享代码且标配 `<maven.deploy.skip>true</maven.deploy.skip>`。
- [ ] **11. Sample-First 五维文档与三要素闭环**：Starter `README.md` 包含 100% 覆盖的全景速查矩阵，各特性章节在当前屏幕内实现“专属 YAML + 真实 Java 代码 + 容灾原理”三要素闭环，按领域大章收敛，Sample `README.md` 采用四段式体验卡。
- [ ] **12. Markdown 排版完整性与零绝对路径**：代码块闭合、块级元素空行隔离、表格列数严整、Mermaid 特殊字符节点加双引号，文档内 100% 使用相对路径互链。
- [ ] **13. 领域模型与阶梯二测试双绿**：子模块 `CONTEXT.md`、`CONTEXT-MAP.md` 与 [`docs/adr/README.md`](../adr/README.md) 同步对齐；执行 `mvn test -pl <changed-module>,ateng-cloud-samples/sample-cloud-<module> -am -T 1C -nsu -B`（核心/BOM 改动含 `sample-cloud-smoke-test`）100% PASS。
