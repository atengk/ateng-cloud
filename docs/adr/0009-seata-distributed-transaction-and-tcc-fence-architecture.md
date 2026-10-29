# 分布式事务增强、TCC 防卫屏障与跨进程协同架构设计

## Context

在分布式微服务与 SaaS 多租户分库架构中，跨微服务 RPC 调用（Feign / Dubbo）与跨物理数据库写入不可避免地打破了传统单体数据库的 ACID 本地事务边界。在实际生产落地中，分布式事务常面临以下核心痛点：

1. **场景选型失配与性能雪崩 (Pattern Mismatch & Lock Contention)**：
   高吞吐高并发场景下盲目使用全局强一致锁（如 Seata AT 模式全局行锁持有），极易在高频并发写操作中造成严重的数据库锁等待排队、TC 线程耗尽与全链路雪崩；
2. **两阶段 TCC 异常边缘风险 (Empty Rollback, Hanging & Duplicate Action)**：
   业务编写 TCC 分布式事务时，极易因网络抖动或超时遭遇“空回滚（未执行 Try 即先收到 Cancel）”、“业务悬挂（Cancel 先执行完毕后 Try 延迟到达）”以及“重试导致的重复提交（Confirm/Cancel 多次调用）”，导致账户资金虚增或数据严重错乱；
3. **数据源代理假切源与双锁冲突 (DataSource Proxy & False Switch Conflict)**：
   `ateng-boot-datasource` 框架内嵌了 `DynamicDatasourceGuard`（防范 Spring 单连接事务在同一个方法内假切源串库）。在微服务接入 Seata AT 模式时，若数据源代理层与切源守卫未做协同适配，会导致跨库或多租户物理分库场景被误判为非法切源从而触发误杀拦截；
4. **全链路 XID 穿透断流与异常因果链丢失 (XID Discontinuity & Causal Exception Loss)**：
   跨微服务 RPC 调用时若未透明透传全局事务唯一标识（XID），会导致下游微服务脱离全局事务管辖；若下游微服务抛出自定义受管业务异常（如库存不足 `BusinessException`），若事务协调切面未正确识别因果链，极易导致全局事务未正常回滚从而产生资损；
5. **异步线程池与多线程 XID 断流 (Async Thread Pool Loss)**：
   在 `@GlobalTransactional` 中通过受管线程池或异步执行器执行子任务时，因 `RootContext.getXID()` 默认保存在 `ThreadLocal` 中，异步子线程丢掉 XID，导致子线程操作脱离全局事务管辖；
6. **MyBatis-Plus 多租户插件与内部表致命冲突 (Tenant Plugin Collision)**：
   MyBatis-Plus 行级多租户插件（`TenantLineInnerInterceptor`）会自动为所有 SQL 拼接 `WHERE tenant_id = ...`，误将 Seata 的 `undo_log` 与 `tcc_fence_log` 当作普通业务表过滤，导致回滚或防悬挂 SQL 语法解析失败；
7. **单元测试与 CI 强依赖物理 TC 节点 (Hermetic Violation)**：
   传统 Seata 模块单元测试必须强依赖物理部署的 Seata Server (TC)，导致本地开发与云端 CI 矩阵因网络不可达或端口冲突频繁崩溃，违背 100% 离线自洽原则。

## Decision

确立 `ateng-cloud-seata` 作为微服务分布式事务协调与数据一致性基础设施 Starter：

1. **二元分流架构与按需独立 Starter 定位 (Bifurcated Model & Standalone Starter)**：
   - 贯彻架构蓝图 3.15 节分级分流矩阵：主干海量高并发业务强制推崇 `ateng-boot-rocketmq` 2PC 事务半消息最终一致性；`ateng-cloud-seata` 专注于中后台、跨服务结算与财务记账等强一致性场景；
   - 坚持按需依赖原则：`ateng-cloud-seata` 保持独立 Starter，**不**默认打包进入 `ateng-cloud-all` 一键起步包，防止纯无事务微服务引入冗余开销。
2. **依赖基线与生态官方对齐 (Dependency Baseline Alignment)**：
   - 遵循 Spring Cloud Alibaba `2023.0.3.4` 规范，依托 `com.alibaba.cloud:spring-cloud-starter-alibaba-seata` 与 Apache Seata `2.1.0` 稳定基线；
   - 与 Spring 6 / Spring Boot 3.3 原生响应式与 Servlet 体系保持兼容，完全对齐 BOM 统一依赖版本管控。
3. **AT 与 TCC 双核驱动及 TCC 业务防卫屏障 (Dual-Core AT/TCC & TCC Fence)**：
   - **AT 模式**：基于 `undo_log` 反向补偿机制，零业务侵入，由数据源代理自动解析 SQL 前后镜像；
   - **TCC 模式**：提供标准化 `@TwoPhaseBusinessAction` 接口模板，构建受管的 **TCC 业务防卫屏障（TCC Fence）**，依托本地事务表自动识别并刚性拦截“空回滚”、“业务悬挂”与“重复提交”，保障业务端实现真正的防重复与确定性防卫。
4. **Baomidou 动态数据源原生代理与事务守卫协同 (Dynamic DataSource Coexistence)**：
   - 100% 依托 Baomidou dynamic-datasource 内置的 `spring.datasource.dynamic.seata=true` 机制完成各物理连接池的 `SeataDataSourceProxy` 包装，杜绝私造代理切面；
   - 确立 `RootContext.getXID()` 与 `DynamicDatasourceGuard` 的协同放行语义，当处于 Seata 分布式全局事务中时，允许分布式跨库合法调用，消除误杀。
5. **四维上下文 + XID 全链路对称透传与因果回滚 (XID Propagation & Causal Rollback)**：
   - **Feign 链路**：出站通过 `FeignRequestInterceptor` 透明挂载 `TX_XID` 标头，入站通过 Web HandlerInterceptor 绑定 `RootContext.bind(xid)` 并在 `finally` 块中确定性解绑，与四维上下文（MDC traceId, userId, tenantId, ignoreTenant）及流量染色标（`X-Traffic-Tag`）完全对称；
   - **Dubbo 链路**：依托 `dubbo-seata-spring-boot-starter` 与 RpcContext 附件实现透明穿透；
   - **因果回滚契约**：Feign 自适应解包抛出的受管业务异常 `BusinessException` 默认纳入 `@GlobalTransactional` 自动回滚范围，Seata 底层协调异常统一转译为受管业务异常。
6. **异步多线程 XID 穿透与多租户表自动排除 (Async XID & Tenant Exclusion)**：
   - **异步线程穿透**：接入 `ateng-boot-core` 的 `AsyncContextSnapshotProvider` SPI，构建 `SeataAsyncContextSnapshotProvider`，自动将 `RootContext.getXID()` 跨线程透传至受管线程池，并在任务结束时安全解绑清理；
   - **多租户内部表排除**：自动装配 MyBatis-Plus 租户表忽略过滤器，硬编码排除 `undo_log` 与 `tcc_fence_log` 表，彻底解决租户条件误注入导致的 SQL 崩溃。
7. **高并发 `@GlobalLock` 一等公民与生产强类型调优 (GlobalLock & Safe Config)**：
   - 在全景矩阵中强推 `@GlobalLock` 作为高并发读写防脏读轻量解法，仅向 TC 校验排他锁而不生成 undo_log；
   - 在 `ateng.cloud.seata.*` 提供强类型配置：锁重试间隔 `retry-interval: Duration`（默认 50ms）、重试次数 `retry-times: int`（默认 30 次）、全局事务超时 `default-timeout: Duration`（默认 30s）。
8. **TC 断连刚性熔断防卫 (Fail-Fast Outage Defense)**：
   - 默认采用金融级刚性熔断策略（Fail-Fast）：当 TC 宕机或网络不可达时立即抛出受管业务异常阻断，严禁未经授权静默降级为本地事务单边落库造成数据错乱；提供显式配置项 `ateng.cloud.seata.degrade.enabled=false` 严格把关。
9. **100% 离线自洽测试与 1:1 经典样例工程 (Hermetic Offline Testing & Sample)**：
   - 单元测试默认离线运行，严密覆盖拦截器装配、状态机与 TCC 防卫逻辑，零物理 TC 强依赖；
   - 配套专属场景工程 `ateng-cloud-samples/sample-cloud-seata`，构建经典“Order 订单 ➔ Storage 库存 ➔ Account 余额”三微服务沙箱，提供 Mock 离线自洽用例与 `local` 双模真实实测。

## Consequences

- 规范了分布式事务的选型边界，彻底杜绝了高并发场景滥用 Seata 全局锁导致的系统雪崩；
- 彻底攻克了多线程异步任务 XID 丢失以及 MyBatis-Plus 行级多租户插件误拼内部表的深层隐患；
- 解决了 TCC 模式下空回滚、业务悬挂与幂等三大深坑痛点，业务研发只需聚焦业务 Try/Confirm/Cancel 逻辑；
- 实现了与 `ateng-boot-datasource` 多数据源底座的无缝契合与假切源守卫协同；
- 保障了跨 Feign 与 Dubbo 的 XID 全链路透明穿透与因果异常确定性回滚；
- 默认金融级刚性熔断策略保障了 TC 异常时不产生单边脏写资损；
- 100% 离线自洽测试守卫保障了 CI 流水线的高稳定与秒级准出。
