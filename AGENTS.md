# ateng-cloud 研发指南与 Agent 行为指引

## 1. 项目定位与技术基线
`ateng-cloud` 是面向生产就绪的 Spring Cloud 2023 / Spring Cloud Alibaba 微服务治理与分布式架构 Starter 套件，单向锁定并复用 `ateng-boot-bom` 底座（ADR-0001、ADR-0002；全量索引见 [`docs/adr/README.md`](./docs/adr/README.md)）。

- **运行与编译**：JDK 17 LTS（字节码 17，兼容 JDK 21+ 虚拟线程运行时）
- **核心基线**：`ateng-boot-bom:1.1.0`（Spring Boot `3.3.13`）、Spring Cloud `2023.0.x`、Spring Cloud Alibaba `2023.0.x`、Apache Dubbo `3.3.x`
- **命名坐标**：GroupId `io.github.atengk`，基础包名 `io.github.atengk.cloud.*`，配置前缀 `ateng.cloud.*`

---

## 2. 微服务专属铁律与核心编码契约

### 2.1 七大微服务架构专属铁律
1. **网关响应式与 Servlet 物理隔离（ADR-0006）**：`ateng-cloud-gateway` 与 `sample-cloud-gateway` 基于 Spring Cloud Gateway（WebFlux / Netty）纯响应式底座，**100% 严禁引入 `ateng-boot-web` 或 `spring-boot-starter-web`（Servlet 阻塞栈）**；其余标准 Servlet 微服务按需引入 `ateng-boot-web`。
2. **全链路四维上下文与灰度/事务标头对称穿透（ADR-0004、ADR-0005、ADR-0008、ADR-0009）**：跨进程通信（Feign `RequestInterceptor`、Dubbo `Filter`、RocketMQ、Gateway `GlobalFilter`）双向透传四维快照 $\langle\text{MDC traceId}, \text{userId}, \text{tenantId}, \text{ignoreTenant}\rangle$ 及 `X-Traffic-Tag`（`TrafficContextHolder`）、Seata `XID`，并在出口 `finally` **100% 强制清理**。
3. **Feign 自适应解包与写接口禁重试防雪崩（ADR-0004）**：支持下游 `Result<T>` 与裸 `DTO` 双模自适应解包（非 200 业务码转译为 `BusinessException`）；**非幂等写请求（POST/PUT/PATCH）读取超时（Read Timeout）绝对禁止自动重试**，仅允许连接建立超时（Connect Timeout）对幂等 GET 受控重试。
4. **零信任内网防穿透 `Same-Token` 闭环（ADR-0006）**：网关入口强制清洗客户端伪造内部标头，完成 Sa-Token 鉴权后向下游签发 `Same-Token`；内部微服务默认开启 `sa-token.same-token.check=true` 强校验。
5. **Nacos 现代配置加载与 `@RefreshScope` 禁令（ADR-0003）**：统一采用 `spring.config.import: optional:nacos:...` 协议（严禁 `bootstrap.yml` 强依赖）；动态配置绑定至 `@ConfigurationProperties` Bean，**严禁在业务 `@Service`/`@Component`/`@RestController` 单例标注 `@RefreshScope`**；停机遵循“先摘除 Nacos 注册 $\rightarrow$ 排空飞行请求 $\rightarrow$ 销毁容器”无损时序。
6. **网关内存护栏与大文件 S3 预签名直传旁路（ADR-0006）**：网关默认锁定 `spring.codec.max-in-memory-size: 2MB` 防堆外 OOM；大文件严禁经网关或微服务内存中转，统一走 `ateng-boot-storage` S3 预签名 URL 直传。
7. **分布式可观测性与 W3C 追踪收敛**：链路追踪 100% 复用 `ateng-boot-observability`（Micrometer Tracing + OpenTelemetry W3C `traceparent`），严禁引入 Sleuth。

### 2.2 核心编码与自动装配契约
- **底座优先与零臃肿**：100% 依托 JDK 17、Spring 6 及 `ateng-boot-core` 原语（`UserContextHolder`、`TenantContextHolder`、`JsonUtil`、`RetryUtil`、`BatchSliceUtil`、`IdUtil`、`FileUtil`、`ValidatorUtil`、`BusinessAssert`、`IBaseEnum`），严禁引入 `hutool-all`、`guava` 或重复造轮子。
- **显式响应与传输隔离（Zero-Entity-Leakage）**：Controller 显式返回 `Result<T>`（严禁 `@ResponseBodyAdvice`）；Controller 与 Feign/Dubbo RPC 契约 **100% 严禁接收或返回持久层 ORM `Entity`**，入参用 Request/DTO/Command（批量列表用 `ValidList<E>`），出参用 VO/Response。
- **无状态并发与虚拟线程防 Pinning**：公共单例与 Filter/Interceptor 保持无状态；异步并发强制接入 Core 受管线程池与 `ContextCopyingTaskDecorator`；**涉及网络 I/O、RPC 或耗时临界区的同步统一使用 `ReentrantLock` 替代 `synchronized`，杜绝 JDK 21+ 虚拟线程 Carrier Pinning**。
- **防御性自动装配四铁律**：① 统配 `@AutoConfiguration` 并唯一注册于 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（严禁 `spring.factories`）；② 标配 `@ConditionalOnClass`/`@ConditionalOnProperty` 守卫；③ 公共 `@Bean` 标配 `@ConditionalOnMissingBean`；④ 显式标注 `@AutoConfigureAfter`/`@AutoConfigureBefore`。
- **BOM 统管与强类型配置**：依赖版本唯一在根 `pom.xml` 或 `ateng-cloud-bom/pom.xml` 的 `<dependencyManagement>` 纳管，子模块免配 `<version>`；配置类定义于 `io.github.atengk.cloud.*.properties`，标配 `@ConfigurationProperties(prefix = "ateng.cloud.*")` 与 JavaDoc，**时间强制使用 `Duration`，容量强制使用 `DataSize`**，嵌套对象标配 `@NestedConfigurationProperty`；新建类头标配 `@author Ateng` 与 `@since YYYY-MM-DD`。

---

## 3. 工业级代码成熟度九道防线与审查红线
以下九道防线、7 大微服务铁律及传输实体隔离（Zero-Entity-Leakage）为 `/code-review` **一票否决红牌阻断项**：

1. **对称语义完备性**：门面与拦截器必须具备完备对称语义（注入/提取、加锁/解锁、注册/注销、染色/清理、开启/回滚对称），严禁半残门面。
2. **分层防卫与快速失败**：传输边界 JSR-303 校验；核心方法与驱动交互前通过前置卫语句（判空、判负、超限、非法标头与状态）Fail-Fast。
3. **确定性资源清理**：网络连接、响应流（`Feign.Response`、`InputStream`）、Netty `DataBuffer`、ThreadLocal/MDC/`TrafficContextHolder`/Seata `RootContext` 必须在 `try-with-resources` 或 `finally` 释放。
4. **环境宿主中立**：严禁硬编码系统路径分隔符或固定网卡 IP，路径统一经由 `FileUtil` 清洗。
5. **严格无状态与受管并发**：单例严禁使用非线程安全容器或可变字段；**严禁私自使用 `Executors` 或 `new Thread()`**；锁释放遵循“当前线程持有判断后在 `finally` 释放”。
6. **生产就绪配置与防悬挂**：提供高并发合理默认值；RPC、连接池、重试与队列等待必须显式配置超时（`Duration`）与重试上限，严禁无界挂起。
7. **因果异常与驱动解耦**：严禁吞咽异常或 Log-and-Throw；边界拦截底层异常（`FeignException`、`RpcException`、`BlockException`、`TransactionException`）并转译为 `BusinessException(IResultCode, message, cause)`，**100% 保留原始 `cause` 链**。
8. **原生优先与底座收敛**：坚持 JDK 17 与 Spring 6 原生算力优先，跨模块原语强制复用 `ateng-boot-core`。
9. **对抗性极限单测**：除 Happy Path 外，单测须覆盖空入参、超时熔断、上下文清理、失败补偿、并发竞争与配置开闭场景。

---

## 4. 质量门禁、双模测试与文档/样例闭环
- **三阶梯测试准出**（纯文档 `*.md` 修改豁免执行 Maven 测试）：
  1. **阶梯一（日常自验）**：`mvn test -pl <changed-module> -am -T 1C -nsu -B`
  2. **阶梯二（提交前准出）**：`mvn test -pl <changed-module>,ateng-cloud-samples/sample-cloud-<module> -am -T 1C -nsu -B`（核心/BOM 改动追加 `ateng-cloud-samples/sample-cloud-smoke-test`，ADR-0010）
  3. **阶梯三（推送后 CI 守护）**：推送后通过 `gh run watch <run-id>` 监控 Linux/Windows 及 JDK 17 & 21 全矩阵至全绿。
- **全自洽单测三铁律**：① 显式关闭外部中间件强连接（`spring.cloud.nacos.discovery.enabled=false`、`config.enabled=false` 等），默认依托内存桩/Mock 100% 离线通过；② 修改 `UserContextHolder`、`TenantContextHolder`、`TrafficContextHolder`、Seata `RootContext`、MDC 等上下文必须在 `@AfterEach` 清理；③ 临时文件 IO 强制使用 `@TempDir`。
- **远程中间件双模联调**：① Sample 复用单套断言，离线桩 `@TestConfiguration` 标注 `@Profile("!local")`（激活 `local` 直连真实集群）；② `.gitignore` 忽略 `**/application-local*.yml`，Sample 标配模板 `application-local.example.yml` 与 `spring.config.import: "optional:classpath:application-local.yml"`；③ 本地已配 `application-local.yml` 时须在 `local` 测通并在 `@AfterEach` 清理数据（无环境标记 `[豁免：无可用环境]`）。
- **Sample 1:1 正交与 Sample-First 五维文档铁律**（详见 **[`docs/agents/documentation-specification.md`](./docs/agents/documentation-specification.md)**）：
  - **1:1 沙箱与非分发隔离**：每个 `ateng-cloud-<module>` 对应 `ateng-cloud-samples/sample-cloud-<module>`（`traffic` 对应 `sample-cloud-traffic-gray`，`all` 对应 `sample-cloud-smoke-test`，见根 `pom.xml` 与 [`CONTEXT-MAP.md`](./CONTEXT-MAP.md)）；Sample 间零共享代码，标配 `<maven.deploy.skip>true</maven.deploy.skip>` 与 `-DskipSamples` 隔离；
  - **多 Controller 与网关特例**：除 `sample-cloud-gateway` 保持纯 WebFlux 外，其余 Sample 引入 `ateng-boot-web` 并拆分多个 `@RestController`（路由 `/sample/cloud/<module>/<feature>/<action>`，返回 `Result<T>`），Sample `README.md` 采用四段式体验卡；先完成 Sample 实测全绿，再 1:1 逆向提取至 Starter 五维 `README.md`（100% 全景矩阵、三要素闭环、领域大章收敛、排版防裂）。
- **多上下文与 ADR 防膨胀**：根目录唯一维护 [`CONTEXT-MAP.md`](./CONTEXT-MAP.md)（严禁建根 `CONTEXT.md`），子模块维护纯词汇表 `ateng-cloud-*/CONTEXT.md`；仅在孵化新模块或重大重构时新建 ADR，存量优化在既有 ADR 增量修订。

---

## 5. 工程协同与 Git 流水线
- **双分支、上游锁定与显式两级授权**：`main` 只读发布，`dev` 活跃开发（在 `main` 须主动 `git checkout dev`）；上游 `ateng-boot-bom` 锁定正式版（严禁跨仓 `SNAPSHOT`）；**未收到用户明确的“提交/commit”或“推送/push”指令前，修改默认停留在工作区，严禁擅自提交或推送**；暂存须显式指定文件路径（严禁 `git add .` 或 `-A`）；提交执行：`bash scripts/commit.sh -t <type> -s <scope> -m "<主说明>" [-l "<细项>"] [-c <issue>] [-n] [-p] -y`。
- **推送后 CI 守护与发版**：推送后通过 `gh run list -L 1` 与 `gh run watch <run-id>` 跟踪 CI 至全绿；发版只能在干净同步的 `main` 分支通过 `bash scripts/release.sh` 发起。

---

## 6. Agent 执行状态机与专职规约路由表
- **双模状态机与停止判据**：① **阶段 1~3（认领/探查 → TDD 与 Sample-First 实现 → 阶梯二测试、13 项自检与 `/code-review` 双绿）**：未获授权提交推送前，完成阶段 3 后**必须停留在工作区汇报结果**；② **阶段 4~5（`scripts/commit.sh` 提交推送 → `gh run watch` 守护全绿 → `gh issue comment -F` 关单）**：仅在收到显式指令时触发。
- **专职规约前置唤醒路由表**：触及以下分支时，**必须在操作前调用 `read_file` 读取对应规约**：

| 触发分支 / 任务场景 | 必读权威规约文档 | 核心完成判据 |
| :--- | :--- | :--- |
| **编写/修改 `README.md` 或 Sample** | **[`docs/agents/documentation-specification.md`](./docs/agents/documentation-specification.md)** | 多 Controller/WebFlux 全绿、1:1 逆向提取、全景矩阵 100% 覆盖、通过 **13 项准出自检清单** |
| **从 0 到 1 孵化全新微服务 Starter** | **[`docs/agents/modular-extensibility.md`](./docs/agents/modular-extensibility.md)** | 交付“工业级六件套”（标杆对标、BOM 统管、底座复用、1:1 Sample、五维文档、Domain/ADR） |
| **对存量模块执行 1 到 N 体检或扩容** | **[`docs/agents/module-hardening-and-expansion.md`](./docs/agents/module-hardening-and-expansion.md)** | 执行四阶段流水线，阶段一输出 `module-health-check.md` 后**必须停下等待人工审批** |
| **探索模块边界或查阅/新增 ADR** | **[`docs/agents/domain.md`](./docs/agents/domain.md)** & **[`CONTEXT-MAP.md`](./CONTEXT-MAP.md)** | 对齐子模块 `CONTEXT.md` 术语与 `Avoid Terms`，同步维护 `CONTEXT-MAP.md` 与 [`docs/adr/README.md`](./docs/adr/README.md) |
| **操作 GitHub Issue / PR 或 `gh`** | **[`docs/agents/issue-tracker.md`](./docs/agents/issue-tracker.md)** | PowerShell `"@me"` 加引号、长文本强制 `-F <file>`、遵守五阶段状态机与 Git 授权边界 |
| **评估或修改 Issue 状态标签** | **[`docs/agents/triage-labels.md`](./docs/agents/triage-labels.md)** | 准确映射 `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix` |
| **执行版本发布或排查发版流水线** | **[`docs/agents/release-workflow.md`](./docs/agents/release-workflow.md)** | 确认 `main` 纯净同步且上游 BOM 为正式版，使用 `bash scripts/release.sh` 与非分发防线 |
