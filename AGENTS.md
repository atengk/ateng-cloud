# ateng-cloud 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线 (Project Baseline)
`ateng-cloud` 是面向企业级高可用分布式生产环境的 Spring Cloud & Apache Dubbo 双模微服务基础设施 Starter 套件，单向依赖消费已发布的 `ateng-boot-bom`（纯净单体基础设施底座），专注于打造垂直正交、开箱即用、无侵入的微服务通用治理能力。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 21+ 运行）
- **核心基线**：Spring Boot `3.3.13`，Spring Cloud `2023.0.x`，Spring Cloud Alibaba `2023.0.x`，Apache Dubbo `3.3.x`，ateng-boot-bom `1.0.0`
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.cloud.*`，配置前缀 `ateng.cloud.*`
- **仓库命名**：`ateng-cloud`（完全遵循极简命名法则，彻底摒弃 `-starter-` 中缀）

---

## 2. 微服务核心架构不变量与铁律 (Microservice Architecture & Invariants)

微服务基础设施在生产环境中面临网络抖动、链路穿透、瞬时雪崩等极端挑战，确立以下 7 大不可逾越的微服务专属架构铁律：

1. **网关运行时纯洁性与物理隔离铁律 (Reactive vs Servlet Invariant)**：
   - `ateng-cloud-gateway` **绝对严禁引入** `ateng-boot-web`（Servlet 阻塞栈）及其任何关联依赖；
   - 网关仅依赖 `ateng-boot-core`，基于 Spring WebFlux 与 Netty 事件循环纯响应式实现；
   - 严禁在网关工程中配置任何 `ateng.boot.web.*` 阻塞配置项，杜绝阻塞 Netty EventLoop 线程引发整网假死。
2. **全链路四维上下文与流量染色透明透传 (4D Context & Traffic Tag Propagation)**：
   - 微服务跨进程通信（HTTP OpenFeign、Apache Dubbo 3.3 RPC、RocketMQ 消息）必须透明穿透四维上下文快照（$\langle\text{MDC traceId}, \text{userId}, \text{tenantId}, \text{ignoreTenant}\rangle$）与金丝雀灰度染色标记（`X-Traffic-Tag`）；
   - 在请求/消息出口处（`finally` 块）强制执行清理，彻底杜绝多租户跨越与线程池污染。
3. **Feign 双模自适应解包与读超时禁重试防雪崩 (Adaptive Unwrap & Retry Storm Defense)**：
   - Feign 客户端依托解码引擎实现智能剥壳，对端返回 `Result<T>` 时自动校验 `code==200` 并剥壳返回业务对象，报错自动转译为本地 `BusinessException`；
   - **写请求绝对禁止重试不变量**：对于非幂等写接口（POST/PUT），发生读超时（`SocketTimeoutException`）默认彻底关闭重试，坚决杜绝下游重复落库与并发死锁；仅对连接未建立超时（`ConnectTimeoutException`）允许弹性重试 1 次。
4. **零信任内网防穿透隔离 (Same-Token Fail-Fast Defense)**：
   - `ateng-cloud-gateway` 负责公网客户端集中鉴权，校验通过后向下游注入内部受信凭据；
   - 所有内部微服务强制开启 Sa-Token `Same-Token` 校验（`sa-token.same-token.check=true`），凡绕过网关直连内部微服务暴露端口的流量，直接 Fail-Fast 401 阻断。
5. **现代 Nacos 配置加载与 `@RefreshScope` 绝对禁令 (Bean Immutability Invariant)**：
   - 全域统一遵循 Spring Boot 3 原生 `spring.config.import` 规范，彻底废弃过时的 `bootstrap.yml`；
   - **严禁在 `@Service`、`@Component` 等承载实际业务逻辑与事务的单例 Bean 上标注 `@RefreshScope`**；配置热更严格收敛于 `@ConfigurationProperties` 纯属性类增量热绑，杜绝 Bean 物理销毁导致事务断裂与锁悬挂。
6. **大文件 S3 预签名旁路直传 (Payload Bomb Defense & S3 Presigned Bypass)**：
   - 网关层严格设置请求体上限（默认 `max-in-memory-size: 2MB`），对超大报文直接 413 熔断；
   - 大文件、视频与大报表上传坚决禁止流经网关或微服务内存中转，100% 依托 `ateng-boot-storage` S3 预签名直传（Presigned URL）直达对象存储桶。
7. **分布式追踪底座零冗余收敛 (Observability Convergence)**：
   - 坚决不在 `ateng-cloud` 建立空壳同名追踪模块，分布式调用链追踪全面依托 `ateng-boot-observability` 门面与 W3C `traceparent` 标准格式，消除不同 APM 协议壁垒。

---

## 3. 工业级代码成熟度九道防线与审查红线 (The 9 Robustness Invariants & Review Redlines)

立足于框架全局抽象与面向未来无限扩展新模块的工业级基准，确立以下九道不可逾越的通用代码成熟度防线。任何一条违规直接触发代码审查红牌阻断（One-Vote Red Card Block）：

1. **对称语义完备性 (Symmetric Facades Invariant)**：
   - 任何中间件底座或抽象执行门面（`*Template` / `*Executor` / `*Manager` 等），操作语义必须具备完备性与对称性（读写对称、增删对称、加解密对称、注册与注销对称、出入栈对称）；
   - 严禁设计“只管查不管改”或“只管分配不管回收”的半残门面，杜绝调用方被迫穿透底层 SPI 自行拼装缺失基础方法的低成熟度代码。
2. **分层防卫与快速失败 (Defense-in-Depth & Fail-Fast Invariant)**：
   - 传输层外围边界强制使用 JSR-303 注解实施无状态格式校验与 Fail-Fast；
   - 核心内部业务逻辑与驱动交互前，必须通过显式前置卫语句（断言非空、非负、边界超限、非法路径与非法状态）实施严密防卫，严禁放任脏数据与边界异常穿透至深层链路。
3. **确定性生命周期与资源清理 (Deterministic Lifecycle & Cleanup Invariant)**：
   - 凡涉及流（InputStream/OutputStream）、网络与数据库连接、临时文件句柄、Multipart 分片会话、ThreadLocal 上下文等瞬态物理资源，必须且只能在 `try-with-resources` 或 `finally` 块中完成绝对可靠的释放与清理；
   - 当遇到异常中止或取消时，必须执行确定性的补偿与清理逻辑，坚决杜绝句柄泄露、未闭合会话悬挂与磁盘临时膨胀。
4. **环境宿主中立与弹性防御 (Platform-Agnostic & Host Resilience Invariant)**：
   - 严禁硬编码操作系统相关的特定路径分隔符（如写死 `\` 或 `/`），必须使用标准路径抽象或安全清洗原语；
   - 主动防御并过滤不同宿主操作系统的隐性系统文件（如 Windows 的 `desktop.ini`、macOS 的 `.DS_Store`）及特殊路径字符，确保多端运行的一致性与弹性。
5. **严格无状态与并发纪律 (Strict Statelessness & Concurrency Safety Invariant)**：
   - 单例服务（`@Service`、`*Manager`、`*Template`）必须保持严格无状态，严禁使用非线程安全容器（如普通 `HashMap`、`ArrayList`、`SimpleDateFormat`）或实例成员变量共享可变业务状态；
   - **受管线程池铁律**：严禁私自使用 `Executors` 或 `new Thread()` 创建野线程；多线程并发必须且只能接入 Core 受管异步线程池设施（`AtengAsyncAutoConfiguration` / `ThreadPoolTaskExecutor`），统一挂载上下文装饰器；
   - **防御性锁释放范式**：使用并发锁（本地锁或分布式锁）时，必须严格遵循状态判断释放范式（仅当当前线程真实持有锁时才在 `finally` 触发释放），严禁无脑释放未持有的锁导致产生次生异常掩盖真实业务异常。
6. **生产就绪配置与防无界悬挂 (Safe Config & Production Defaults Invariant)**：
   - 所有配置属性必须提供符合高并发生产标准的合理默认值；
   - 凡涉及网络通信、连接池分配、消息重试与队列等待处，必须显式配置超时阈值（Timeout）与重试上限，严禁无超时无限挂起导致应用线程耗尽；
   - 时间统配 `java.time.Duration`，容量统配 `org.springframework.util.unit.DataSize`，嵌套配置标配 `@NestedConfigurationProperty`。
7. **因果异常体系与底层驱动解耦 (Causal Exceptions & SDK Decoupling Invariant)**：
   - 严禁吞咽异常（禁止空的 `catch` 块或仅打印不处理）；
   - 子模块对外暴露的公共 API 必须在模块边界完成底层驱动异常的受控拦截，将其转译为框架受管的语义化业务异常（`BusinessException`）并赋予清晰的错误码（`IResultCode`）；
   - **链式异常传递铁律**：转译异常时必须完整保留原始异常因果链（必须传递原始 `cause`）；恪守“要么就地降级补偿，要么转换为业务异常向外抛出”，严禁 Log-and-Throw 双重污染。
8. **原生优先与零臃肿底座收敛 (Zero-Bloat & Core Convergence Invariant)**：
   - 坚持纯原生标准库与 Spring 6 原生能力优先，坚决杜绝引入第三方全量工具包；
   - 跨中间件通用原语（上下文透传、序列化门面、敏感脱敏、安全工具、格式校验、重试退避等）强制优先复用已发布的 `ateng-boot-core` 与 `ateng-boot-observability`，严禁在子模块重复造轮子。
9. **对抗性极限用例守护 (Adversarial & Edge-Case Testing Invariant)**：
   - 单元测试严禁仅覆盖 Happy Path 玩具场景；
   - 核心逻辑必须设计对抗性测试用例（Adversarial Testing），全真覆盖空入参、边界溢出、异常分支、失败补偿清理、并发竞争与配置开闭测试用例。

- **审查红线阻断机制 (Review Redlines Gate)**：
  在代码审查（`/code-review`）中，上述 **7 大微服务专属铁律** 与 **九道通用成熟度防线** 被列为一票否决红线项。凡发现触碰任一红线，审查 Agent 直接出具 Red Card 阻断本次提交准出。

---

## 4. 质量门禁与全维文档闭环 (Quality Gates & Documentation Invariants)

- **三阶梯测试准出与并行加速 (Three-Tier Test Gate & Parallel Build)**：
  自动化构建与测试统一附加 `-T 1C -nsu -B` 确保日志流式纯净并实现 CPU 核心级并行加速；严禁每次改动在本地无脑执行全项目串行单测：
  1. **阶梯一：特性日常自验（秒级极速反馈）**：单个模块改动时，采用多线程依赖树精准定向测试：
     ```bash
     mvn test -pl <changed-module> -am -T 1C -nsu -B
     ```
  2. **阶梯二：本地提交准出验收（精准端到端集成）**：准备提交前，执行变更模块与对应场景样例工程定向联动验证，杜绝无脑全量回归：
     ```bash
     mvn test -pl <changed-module>,ateng-cloud-samples/sample-cloud-<scenario> -am -T 1C -nsu -B
     ```
  3. **阶梯三：全反应堆多 JDK 矩阵终极守护 (Post-Push CI Gate)**：全反应堆所有模块在 Linux/Windows 及 JDK 17 & 21 矩阵下的全量回归，100% 交由 GitHub Actions CI 云端并行工作流异步守护；本地推送后通过 `gh run watch <run-id>` 闭环跟踪至全绿。
- **纯文档修改测试豁免 (Doc-Only Exemption)**：若本次变更仅涉及文档（如 `*.md`、纯文本或注释说明）而未改动任何业务源码、配置文件与工程依赖，一律免除执行本地 Maven 单元测试与场景准出验证，直接受控提交。

- **全自洽微服务单测三项铁律 (The 3 Hermetic Test Rules)**：
  1. **100% 离线自洽（零外部网络依赖）**：微服务单测严禁强依赖物理运行中的 Nacos、Sentinel 或注册中心；单测配置中显式声明 `spring.cloud.nacos.discovery.enabled: false` 与 `spring.cloud.nacos.config.enabled: false`，通过 Mock 或纯内存 TestDouble 实现离线极速运行；
  2. **临时资源绝对隔离**：单测涉及文件与磁盘 IO 时，强制使用 JUnit 5 原生 `@TempDir` 注入临时目录，测试结束后由引擎自动物理销毁；
  3. **上下文零污染**：任何涉及修改上下文（如 `UserContextHolder`、`TenantContextHolder`、`RpcContext`、MDC 等）的测试用例，必须在 `@AfterEach` 中无条件执行重置与清理，杜绝跨测试用例的线程脏读。

- **五维生产就绪交付模型 (The 5D Production-Ready Invariant)**：
  子模块 `README.md` 面向真实生产接入者，严禁仅停留在简单 Demo 的自嗨罗列；任何新增或修改的功能特性，必须正交覆盖以下五个维度，缺一即阻断准出：
  1. **维度零：特性与公共 API 全景速查矩阵**：核心显要位置 100% 收录全景表格（收录所有门面类、公共方法、核心注解、配置属性 `@ConfigurationProperties` 与扩展 SPI）；
  2. **维度一：最小起步体验**：直击痛点的一句话概括、Nacos 最小导入配置与开箱即用的极简调用代码；
  3. **维度二：生产扩展接管**：清晰说明如何通过 SPI、`@ConditionalOnMissingBean` 或拦截器接管框架默认行为；
  4. **维度三：核心生产边界与非功能语义**：显式声明高并发、网络抖动、读写超时、熔断降级与异常转译的底层边界机制；
  5. **维度四：物理排版与代码全真验证**：示例代码必跑通并锚定单测、YAML 配置逐字对齐属性类、Markdown 代码块严格闭合无破损。

- **“Sample-First 研发闭环与 100% 场景实测铁律” (Sample-Before-Docs)**：
  - 子模块实现的每一个对外公开功能，**必须 100% 在归属的 `sample-cloud-<scenario>` 中提供真实、端到端可运行的场景用例**；
  - 在编写或更新子模块 `README.md` 前，**必须先完成对应 Sample 场景用例的编写与实测验证并确保测试全绿**；
  - `README.md` 中展示的 Java 代码、YAML 片段及执行结果，**必须 100% 直接从已跑通的 Sample 源码、配置及实测日志中 1:1 逆向提取**。

- **四维职责清晰正交与多上下文拓扑 (Multi-Context Invariant)**：
  - `README.md` 面向开发者，聚焦痛点与最小可用代码；
  - 本项目为多模块多上下文（Multi-Context）架构，**根目录严格且唯一维护 `CONTEXT-MAP.md` 专职维护系统拓扑与调用依赖，坚决严禁在根目录创建单体 `CONTEXT.md`**；
  - 领域词汇表严格且只能按限界上下文就近落盘于各子模块（`ateng-cloud-*/CONTEXT.md`），恪守“纯领域词汇表 (Glossary)”定位，只定义术语与 Avoid 负面词汇，严禁堆砌长篇实现细节；
  - `docs/adr/` 专职沉淀重大架构选型。
- **ADR 膨胀防线与合并收敛**：严格收紧独立 ADR 门槛，仅在孵化完整新中间件底座或重大协议契约重构时新建独立 ADR；存量模块优化一律优先在既有归属 ADR 增量修订。

---

## 5. 工程协同与 Git 流水线 (Git Workflow & Release)

- **双分支开发铁律 (Dual-Branch Policy: dev for Active Coding & main for Pure Release)**：
  本项目严格遵循 `main`（只读发布）与 `dev`（活跃开发）双分支演进模型；Agent 开展任何编码或修复工作前，必须确认当前处于 `dev` 分支；若检测到处于 `main`，必须主动执行 `git checkout dev` 切换至开发分支，严禁在 `main` 直接编辑业务代码；发版时严格收口于 `main` 分支。
- **上游依赖单向解耦与锁定 (BOM Baseline Lock)**：
  `ateng-cloud` 单向依赖上游已发布的 `ateng-boot-bom` 正式 Release 版本（基线对齐 `1.0.0`），坚决杜绝依赖上游的 SNAPSHOT 快照，确保微服务底座的稳定性。
- **精准路径暂存 (Safe Path Staging)**：
  暂存必须显式、精准指定具体的变更文件相对路径（如 `git add pom.xml scripts/commit.sh`），**严禁使用 `.` 或 `-A` 全量通配**，严禁在任何会话答复或建议命令中推介 `git add .`。
- **受控提交与脚本门面 (Explicit Git & Script Facade)**：
  仅当收到用户明确的“提交/commit/保存”指令时方可操作 Git，修改默认停留在工作区供审阅；执行提交时统一使用项目门面脚本：
  ```bash
  bash scripts/commit.sh -t <type> -s <scope> -m "<主说明>" [-l "<细项1>" -l "<细项2>"] [-c <issue>] [-n] [-p] -y
  ```
- **推送后流水线守护 (Post-Push CI Monitoring Gate)**：
  代码推送到远程后必须对 GitHub Actions CI 流水线实施闭环监控；通过 `gh run list -L 1` 获取最新工作流 Run ID 并执行 `gh run watch <run-id>` 持续跟踪，直到全量构建与单元测试矩阵（JDK 17 & 21）100% 绿色通过；若检测到流水线失败，必须立即分析排查、修复本地代码并重新提交推送监控，直至流水线彻底成功后方可交付。
- **主干发布与运维解耦 (Trunk-Based Release)**：
  遵循 [docs/agents/release-workflow.md](./docs/agents/release-workflow.md)；版本发布必须且只能在 `main` 主干分支发起，前置要求工作区 100% 干净且与远端同步，统一通过 `bash scripts/release.sh` 调度。

---

## 6. Agent 协议与工单流转规范 (Agent Protocols & SOP)

- **官方 CLI 与工单生命周期闭环 (CLI & Ticket Lifecycle)**：
  GitHub Issues、PR 审阅与 CI 交互一律通过官方 `gh` 命令行闭环。工单生命周期严格遵循五阶段状态机：
  1. **认领**：查阅工单并指派当前开发者（`gh issue edit <n> --add-assignee "@me"`）；
  2. **TDD 与 Sample-First 逆向闭环实现**：
     - 白盒对抗单测驱动核心代码实现；
     - 在对应的 `sample-cloud-<scenario>` 中补全端到端场景用例并实测全绿；
     - 1:1 逆向提取示例更新子模块 `README.md` 与全景矩阵；
  3. **准出、自检与双轴审查**：
     - 执行阶梯二准出测试（纯文档改动免测）；
     - 对照 7 大微服务铁律与九道通用成熟度防线执行严格自检；
     - 调用 `/code-review` 开展双轴审查，触碰红线直接返修；
  4. **受控提交**：通过项目门面 `bash scripts/commit.sh` 提交并推送；
  5. **闭环**：守护 CI 监控至全绿，通过文件回写验收清单（`gh issue comment <n> -F <file>`），关闭工单（`gh issue close <n>`）。
- **工单操作与 CLI 避坑 (Issue Tracker & CLI)**：遵循 [docs/agents/issue-tracker.md](./docs/agents/issue-tracker.md)。
- **分诊标签规范 (Triage Labels)**：遵循 [docs/agents/triage-labels.md](./docs/agents/triage-labels.md)。
- **微服务模块孵化标准 (Modular Extensibility)**：遵循 [docs/agents/modular-extensibility.md](./docs/agents/modular-extensibility.md)。
- **微服务模块加固与演进标准 (Module Hardening)**：遵循 [docs/agents/module-hardening-and-expansion.md](./docs/agents/module-hardening-and-expansion.md)。
- **领域词汇与架构决策 (Domain Docs)**：探索代码库、设计新领域实体或查阅架构决策时，阅读全局映射 [CONTEXT-MAP.md](./CONTEXT-MAP.md) 及各模块独立的 `CONTEXT.md`，架构选型遵循 [docs/adr/](./docs/adr/)，统一依托 `/domain-modeling` 技能受管推演。
