# ateng-cloud 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线 (Project Baseline)
`ateng-cloud` 是面向企业级高可用分布式生产环境的 Spring Cloud & Apache Dubbo 双模微服务基础设施 Starter 套件，单向依赖消费已发布的 `ateng-boot-bom`（纯净单体基础设施底座），专注于打造垂直正交、开箱即用、无侵入的微服务通用治理能力。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 21+ 运行）
- **核心基线**：Spring Boot `3.3.13`，Spring Cloud `2023.0.x`，Spring Cloud Alibaba `2023.0.x`，Apache Dubbo `3.3.x`，ateng-boot-bom `1.0.0`
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.cloud.*`，配置前缀 `ateng.cloud.*`
- **仓库命名**：`ateng-cloud`（完全遵循极简命名法则，彻底摒弃 `-starter-` 中缀）

---

## 2. 核心架构与编码契约 (Architecture & Invariants)

微服务基础设施在生产环境中面临网络抖动、链路穿透、瞬时雪崩等极端挑战，确立以下微服务专属架构铁律与通用工程编码契约：

### 2.1 微服务 7 大专属架构铁律
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

### 2.2 核心通用编码契约
- **显式响应契约 (Explicit Contract)**：Controller 统一显式声明返回强类型 `Result<T>`，依托强类型契约自洽；坚决杜绝引入 `@ResponseBodyAdvice` 全局自动拦截包装。
- **零臃肿与底座优先 (Zero-Bloat & Core-First)**：
  - 100% 依托 JDK 17 与 Spring 6 原生能力，杜绝引入 `hutool-all`、`guava`、`commons-collections` 等重型全量第三方工具包；
  - 工具类就近内聚于归属模块，坚决杜绝垃圾桶模块；
  - **通用原语底座收敛**：跨中间件解耦的通用上下文（用户/租户）、序列化门面、数据脱敏、退避重试、分批切片等通用原语，强制优先复用已发布的 `ateng-boot-core` 与 `ateng-boot-observability`，杜绝在子模块重复造轮子。
- **分层双防校验体系 (Defense-in-Depth Validation)**：
  - **第一道防线（传输边界 / Controller）**：负责无状态语法与格式性校验，统一使用 JSR-303 注解声明以实现 Fail-Fast；批量列表入参强制使用 `ValidList<E>` 杜绝 Spring 默认漏检；分组校验统一使用 `ValidationGroups` 标准标记；
  - **第二道防线（业务边界 / Service）**：负责业务语义与状态不变量校验，统一推行 `BusinessAssert` 语义化断言；非 Web 边界消费使用 `ValidatorUtil.validate(obj)` 执行快速失败编程式防卫。
- **数据安全与“内存真明文”不变量 (In-Memory Plaintext Invariant)**：
  - 业务层与实体对象在内存中始终保持 100% 原始真实明文（严禁在业务逻辑中直接对实体属性打星号）；
  - 存储加密收敛于持久层（入库密文、出库明文），展示脱敏收敛于视图序列化/导出拦截器，彻底杜绝掩码脏数据写回数据库。
- **传输契约与持久层绝对解耦 (Transport Decoupling & Zero-Entity-Leakage)**：
  - Controller 传输边界 **100% 严禁接收或直接返回 ORM 实体（Entity）**；
  - 入参统一使用专用 Request / DTO / Command（挂载 JSR-303 格式校验），出参统一使用专用 VO / Response（分页统一入参 `PageQuery`，统一返回 `PageResult<VO>`）；
  - 实体与传输对象间严禁直接泄露未授权持久化字段或引发序列化漏洞；解耦转换由开发者在 Service / 接入边界完成纯净流式解耦。
- **防御性自动装配四道铁律 (Defensive Autoconfiguration Invariants)**：
  - **标准规范注册**：自动装配类统配 `@AutoConfiguration` 标注，统一且只能注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（严格遵循 Spring Boot 3 规范，严禁使用旧版 `spring.factories`）；
  - **缺配置平滑探测**：装配类标配 `@ConditionalOnClass` 与 `@ConditionalOnProperty` 守卫，必要时结合探测机制，严禁在未配置关键外部连接信息时盲目初始化导致启动崩溃；
  - **`@Bean` 级别缺省守卫**：每个对外暴露的公共 `@Bean` 方法必须显式标配 `@ConditionalOnMissingBean`，保障下游开发者声明同名或同类型 Bean 时能够平滑无缝接管；
  - **装配时序显式声明**：存在隐式依赖关系的自动装配类，必须显式标注 `@AutoConfigureAfter` 或 `@AutoConfigureBefore`，防御 Bean 初始化时序错乱；
  - Jackson 定制统一注入 `Jackson2ObjectMapperBuilderCustomizer`，严禁覆盖 `ObjectMapper` Bean。
- **BOM 依赖统管与向后兼容 (BOM-First & Backward Compatibility)**：
  - 依赖及其版本必须且只能在根 `pom.xml` 或 `ateng-cloud-bom/pom.xml` 的 `<dependencyManagement>` 集中纳管；各子模块 `pom.xml` 声明依赖时一律免配 `<version>` 标签；
  - 对外暴露的公共 API 签名、核心注解属性与依赖基线严守向后兼容。
- **空安全与保底返回 (Null Safety & Defensive Return)**：
  - 查询列表/集合无匹配时保底返回空集合（如 `Collections.emptyList()`），严禁返回 `null`；
  - 单实体查询无结果时底层允许返回 `null`，但调用方必须立即通过前置卫语句显式拦截防御。
- **强类型配置属性与元数据 (IDE-Friendly Properties & Strong Typing)**：
  - 配置属性类统一定义于 `io.github.atengk.cloud.*.properties`，标配 `@ConfigurationProperties(prefix = "ateng.cloud.*")`，字段提供完整 JavaDoc 说明与生产合理默认值，由 `spring-boot-configuration-processor` 自动编译生成 IDE 智能补全元数据；
  - **强类型时间与容量规范**：凡涉及超时、间隔与缓存 TTL 必须统一声明为 `java.time.Duration`；凡涉及文件、内存与缓冲容量必须统一声明为 `org.springframework.util.unit.DataSize`，彻底取缔无量纲裸 `Long`/`Integer`，消除单位歧义；
  - **嵌套配置显式注解**：复杂嵌套配置对象必须标配 `@NestedConfigurationProperty`，确保 IDE 补全元数据与属性层级绑定 100% 生效；
  - 新建源码与核心 API 标配清晰业务注释，类头部标注 `@author Ateng` 与 `@since YYYY-MM-DD`。

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
   - 凡涉及网络通信、连接池分配、消息重试与队列等待处，必须显式配置超时阈值（Timeout）与重试上限，严禁无超时无限挂起导致应用线程耗尽。
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
  在代码审查（`/code-review`）中，上述 **7 大微服务专属铁律**、**九道通用成熟度防线** 以及 **传输层实体隔离 (Zero-Entity-Leakage)** 被列为一票否决红线项。凡发现触碰任一红线，审查 Agent 直接出具 Red Card 阻断本次提交准出。

---

## 4. 质量门禁与全维文档闭环 (Quality Gates & Documentation Invariants)

- **三阶梯测试准出与并行加速 (Three-Tier Test Gate & Parallel Build)**：
  根目录 `.mvn/maven.config` 默认注入 `-T 1C -nsu` 加速配置，自动化环境统一附加 `-B` 确保日志流式纯净；严禁每次改动在本地无脑执行全项目串行单测：
  1. **阶梯一：特性日常自验（秒级极速反馈）**：单个模块改动时，采用多线程依赖树精准定向测试：
     ```bash
     mvn test -pl <changed-module> -am -T 1C -nsu -B
     ```
  2. **阶梯二：本地提交准出验收（精准端到端集成）**：准备提交前，执行变更模块与对应场景样例工程（`ateng-cloud-samples/sample-cloud-<scenario>`，1:1 精确对齐各 Starter，重大底层重构联动 `smoke-test`）定向联动验证，杜绝无脑全量回归：
     ```bash
     mvn test -pl <changed-module>,ateng-cloud-samples/sample-cloud-<scenario> -am -T 1C -nsu -B
     ```
  3. **阶梯三：全反应堆多 JDK 矩阵终极守护 (Post-Push CI Gate)**：全反应堆所有模块（100% 模块全集）在 Linux/Windows 及 JDK 17 & 21 矩阵下的全量回归，100% 交由 GitHub Actions CI 云端并行工作流异步守护；本地推送后通过 `gh run watch <run-id>` 闭环跟踪至全绿。
- **纯文档修改测试豁免 (Doc-Only Exemption)**：若本次变更仅涉及文档（如 `*.md`、纯文本或注释说明）而未改动任何业务源码、配置文件与工程依赖，一律免除执行本地 Maven 单元测试与场景准出验证，直接受控提交。

- **全自洽微服务单测三项铁律 (The 3 Hermetic Test Rules)**：
  1. **100% 离线自洽（零外部网络依赖）**：微服务单测严禁强依赖物理运行中的 Nacos、Sentinel 或注册中心；单测配置中显式声明 `spring.cloud.nacos.discovery.enabled: false` 与 `spring.cloud.nacos.config.enabled: false`，通过 Mock 或纯内存 TestDouble 实现离线极速运行；
  2. **临时资源绝对隔离**：单测涉及文件与磁盘 IO 时，强制使用 JUnit 5 原生 `@TempDir` 注入临时目录，测试结束后由引擎自动物理销毁；
  3. **上下文零污染**：任何涉及修改上下文（如 `UserContextHolder`、`TenantContextHolder`、`RpcContext`、MDC 等）的测试用例，必须在 `@AfterEach` 中无条件执行重置与清理，杜绝跨测试用例的线程脏读。

- **远程服务本地真实连接与双模自适应集成铁律 (Dual-Mode Integration Invariants)**：
  1. **单测试套件与双模自适应 (Dual-Mode Single-Suite)**：坚决杜绝在工程中编写和维护两套重复测试代码（严禁编写 `*LocalTest.java` 复制业务用例）；测试集中维护在场景样例工程中，离线 Mock/桩配置统一标配 `@Profile("!local")`，默认/CI 环境走离线自洽，激活 `local` 时让位原生 Starter 自动装配直连真实服务；
  2. **配置资产与零泄密物理隔离 (Resilient Import & Secret Hygiene)**：全局 `.gitignore` 强制忽略 `**/application-local*.yml` 等真实凭据文件；各 Sample 必须提供全大写语义占位符模板 `application-local.example.yml`；基准 `application.yml` 统一通过 `optional:classpath:application-local.yml` 弹性探测，确保 CI 静默跳过不崩溃；
  3. **真机强断言与确定性清理 (Real Assertion & Cleanup Invariant)**：激活 `local` 实测时，必须真实向外部服务发起交互并强断言成功；在 `@AfterEach` 中执行确定性补偿与资源清理；网络异常严禁静默吞掉；
  4. **环境缺失豁免规范 (Environment Exemption Rule)**：本地确无条件提供对应中间件时，在工单与 Audit Checklist 登记 `[豁免：无可用环境]`，以离线自洽测试准出。

- **五维生产就绪交付模型 (The 5D Production-Ready Invariant)**：
  子模块 `README.md` 面向真实生产接入者，严禁仅停留在简单 Demo 的自嗨罗列；任何新增或修改的功能特性，必须正交覆盖以下五个维度，缺一即阻断准出：
  1. **维度零：特性与公共 API 全景速查矩阵**：核心显要位置 100% 收录全景表格（收录所有门面类、公共方法、核心注解、配置属性 `@ConfigurationProperties` 与扩展 SPI）；
  2. **维度一：最小起步体验**：直击痛点的一句话概括、Nacos 最小导入配置与开箱即用的极简调用代码；
  3. **维度二：生产扩展接管**：清晰说明如何通过 SPI、`@ConditionalOnMissingBean` 或拦截器接管框架默认行为；
  4. **维度三：核心生产边界与非功能语义**：显式声明高并发、网络抖动、读写超时、熔断降级与异常转译的底层边界机制；
  5. **维度四：物理排版与代码全真验证**：示例代码必跑通并锚定单测、YAML 配置逐字对齐属性类、Markdown 代码块严格闭合无破损。

- **“Sample-First 研发闭环与 100% 场景实测铁律” (Sample-Before-Docs & 100% Feature Coverage Invariant)**：
  - **功能 100% 场景对齐刚性铁律**：模块实现的每一个对外公开功能（包括所有公开门面类及 Public 方法、声明式核心注解 `@*` 及其关键属性、配置属性类 `@ConfigurationProperties` 的配置项、扩展点 SPI），**必须 100% 在归属的 `sample-cloud-<scenario>` 中提供真实、自洽、端到端可运行的演示用例**；
  - **Sample-First 流程不变量（绝对前置工序）**：在编写或更新子模块 `README.md` 前，**必须先完成对应 Sample 场景用例的编写与实测验证并确保测试全绿**。严禁在尚未写出可运行 Sample 时凭空手敲文档！
  - **代码源头全真逆向提取**：`README.md` 中展示的 Java 示例代码、YAML 配置片段及执行结果，**必须 100% 直接从已跑通的 Sample 源码、配置及实测日志/响应中 1:1 逆向提取**。若某特性未在 Sample 中跑通，一律严禁写入文档！
  - **配置源头锚定**：YAML/Properties 配置片段必须 100% 与当前模块的 `@ConfigurationProperties` 字段逐字对齐；
  - **文档排版防裂守卫 (Markdown Integrity Guard)**：
    1. 表格单元格内若涉及泛型类型（如 `Result<T>`）、管道符或位运算（`|`），必须使用行内代码反引号包裹（如 `` `Result<T>` ``）或转义（`\|`），严禁导致表格破损错位；
    2. 嵌套代码块严格遵循反引号阶梯（外四反引号 ```` 嵌套内三反引号 ```）；
    3. 文档互链 100% 使用相对路径，严禁包含本地绝对路径（如 `D:/...`、`file:///`）与死链。

- **子模块与样例工程职责正交与 1:1 矩阵 (Submodule & Samples 1:1 Alignment)**：
  彻底废除跨组件混合大杂烩，建立微服务 Starter 与场景样例工程绝对的 1:1 映射关系：
  - `ateng-cloud-nacos` ↔ `sample-cloud-nacos`（演示服务注册发现、配置中心动态刷新与无损上下线状态机，零外部网络依赖）
  - `ateng-cloud-feign` ↔ `sample-cloud-feign`（演示四维上下文透传、双模自适应解包、防重试雪崩与熔断降级）
  - `ateng-cloud-dubbo` ↔ `sample-cloud-dubbo`（演示 Triple 协议、Filter 上下文注入与强契约解包）
  - `ateng-cloud-gateway` ↔ `sample-cloud-gateway`（演示 WebFlux 纯响应式网关、Netty 2MB 载荷硬防爆、动态路由热更新与 Sa-Token 鉴权）
  - `ateng-cloud-sentinel` ↔ `sample-cloud-sentinel`（演示统一受管降级工厂 AtengFallbackFactory 与流量防卫规则）
  - `ateng-cloud-traffic` ↔ `sample-cloud-traffic-gray`（演示 X-Traffic-Tag 流量染色穿透与金丝雀灰度平滑降级）
  - `ateng-cloud-all` ↔ `sample-cloud-smoke-test`（微服务通用 Starter 聚合一键起步与离线冒烟门禁）
  - 子模块 `README.md` 是该模块唯一权威的**“全功能契约真理源”**，承载完备的全景矩阵与五维交付规范；
  - 场景样例工程（`ateng-cloud-samples/sample-cloud-*`）是面向开发者的**“最小可运行沙箱与开箱体验工程”**，其代码专职作为文档代码片段的真实测试源头，两者代码 1:1 锚定。

- **重构防漂移与审查拦截 (Anti-Drift & Review Gate)**：修改核心 API 签名、类名或配置项时，必须全工程检索所属子模块 `README.md` 同步修正，杜绝陈旧失效代码；`/code-review` 强制将文档一致性、全景矩阵覆盖率与格式完整度列为红牌必检项。

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
  单次包含多项改动时须在正文中以 `-l` 逐项罗列；涉及工单闭环时使用 `-c <issue>` 自动生成 `Closes #N`，支持单/多工单；可用 `-n` 演练提交信息。
- **推送后流水线守护 (Post-Push CI Monitoring Gate)**：
  代码推送到远程后必须对 GitHub Actions CI 流水线实施闭环监控；通过 `gh run list -L 1` 获取最新工作流 Run ID 并执行 `gh run watch <run-id>` 持续跟踪，直到全量构建与单元测试矩阵（JDK 17 & 21）100% 绿色通过；若检测到流水线失败，必须立即分析排查、修复本地代码并重新提交推送监控，直至流水线彻底成功后方可交付。
- **主干发布与运维解耦 (Trunk-Based Release & Decoupling)**：
  执行版本发布或排查发版流水线时，遵循 [docs/agents/release-workflow.md](./docs/agents/release-workflow.md)；版本发布必须且只能在 `main` 主干分支发起，前置要求工作区 100% 干净且与远端同步，统一通过 `bash scripts/release.sh` 调度。

---

## 6. Agent 协议与工单流转规范 (Agent Protocols & SOP)

- **官方 CLI 与工单生命周期闭环 (CLI & Ticket Lifecycle)**：
  GitHub Issues、PR 审阅与 CI 交互一律通过官方 `gh` 命令行闭环。工单生命周期严格遵循五阶段状态机：
  1. **认领**：查阅工单并指派当前开发者（`gh issue edit <n> --add-assignee "@me"`）；
  2. **TDD 与 Sample-First 逆向闭环实现**：
     - **2.1 核心驱动与白盒单测**：测试驱动编码，全面遵循第 3 节“工业级代码成熟度九道防线”与 7 大微服务铁律，在模块内部编写完善的白盒与对抗性单元测试；
     - **2.2 Sample 端到端实测补全（Hard Gate）**：在归属的 `sample-cloud-<scenario>` 中编写真实端到端场景用例，100% 覆盖新增/修改的公开 API、核心注解、配置项与扩展 SPI，执行定向测试确保端到端全绿；
     - **2.3 全真逆向提取生成 `README.md`**：从已跑通的 Sample 源码、配置与实测输出中 1:1 逆向提取示例与结果，在所属子模块 `README.md` 中按“五维生产就绪交付模型”补齐全景矩阵、开箱代码、扩展定制接入、核心生产边界与实测输出契约；
  3. **准出、自检与双轴审查**：
     - 执行第 4 节阶梯二准出测试（纯文档改动免测）；
     - **《交付前成熟度与文档双向对齐清单 (Audit Checklist)》强制自检卡点**：
       - [ ] **Sample 100% 场景对齐**：模块新增/修改的所有公共 API 方法、核心注解、配置项与 SPI，是否已 100% 在归属的 `sample-cloud-<scenario>` 中提供端到端真实可运行用例？
       - [ ] **Sample-First 逆向闭环**：README.md 中的所有 Java 示例代码、YAML 配置与输出结果，是否 100% 提取自上述已验证跑通的 Sample 工程？
       - [ ] **全景速查矩阵**：模块新增/修改的所有公共 API 方法、配置项、核心注解与 SPI 是否 100% 入表？
       - [ ] **代码-文档真理一致性**：文档中展示的代码与 YAML 是否直接截取自已跑通的单测/Sample？方法名与参数签名是否完全对齐？
       - [ ] **九道成熟度防线自检**：是否通过对称门面、边界 Fail-Fast、确定性清理、超时防悬挂、驱动异常转译等全部 9 项检查？
       - [ ] **微服务专属铁律自检**：网关是否纯响应式（0 Servlet 依赖）、Feign 写接口读超时是否彻底禁重试、是否开启 Same-Token 内部防穿透与 Nacos 现代加载？
       - [ ] **单测环境隔离**：是否使用 `@TempDir`、在 `@AfterEach` 中清理上下文、100% 离线自洽（关闭外部 Nacos/Sentinel 物理网络依赖）？
       - [ ] **双模自适应与物理隔离 (Dual-Mode)**：是否在 `.gitignore` 中物理忽略 `**/application-local*.yml`、提供全大写语义占位符模板 `application-local.example.yml`、配置 `optional:` 弹性探测并在测试类中以 `@Profile("!local")` 自洽隔离？若本地连接真实环境，是否在激活 `local` 时跑通全真交互并在 `@AfterEach` 确定性清理？（确无环境需登记 `[豁免：无可用环境]`）
       - [ ] **传输契约隔离**：Controller 传输边界是否 100% 杜绝持久层实体（Entity）泄露？
     - **审查红牌阻断 (Red Card Gate)**：调用 `/code-review` 开展双轴审查；若审查发现模块公开功能在 Sample 中缺位、文档示例未经 Sample 真实跑通、文档漏写公开功能，或触碰成熟度红线，一律出具红牌阻断，强制返修直至双绿；
  4. **受控提交**：按第 5 节执行受控提交并推送（代码与文档同工单同提交原子交付）；
  5. **闭环**：守护 CI 监控至全绿，通过文件回写验收清单与提交哈希（`gh issue comment <n> -F <file>`），关闭工单（`gh issue close <n>`）。
- **工单操作与 CLI 避坑 (Issue Tracker & CLI)**：遵循 [docs/agents/issue-tracker.md](./docs/agents/issue-tracker.md)（含特殊字符引号包裹、`-F` 文件回写防截断及 Wayfinding 映射）。
- **分诊标签规范 (Triage Labels)**：遵循 [docs/agents/triage-labels.md](./docs/agents/triage-labels.md)（`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。
- **微服务模块孵化标准 (Modular Extensibility)**：遵循 [docs/agents/modular-extensibility.md](./docs/agents/modular-extensibility.md)（“工业级六件套”全场景对标与端到端纯内存自验门禁）。
- **微服务模块加固与演进标准 (Module Hardening)**：遵循 [docs/agents/module-hardening-and-expansion.md](./docs/agents/module-hardening-and-expansion.md)（四阶段流水线门禁与企业级全场景矩阵）。
- **领域词汇与架构决策 (Domain Docs)**：探索代码库、设计新领域实体或查阅架构决策时，阅读全局映射 [CONTEXT-MAP.md](./CONTEXT-MAP.md) 及各模块独立的 `CONTEXT.md`，架构选型遵循 [docs/adr/](./docs/adr/)，统一依托 `/domain-modeling` 技能受管推演。
