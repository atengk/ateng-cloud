# 流量染色、泳道隔离与金丝雀灰度路由架构设计

## Context

在现代分布式微服务集群中，高可用发布（金丝雀发布、灰度测试、多特性分支并行演进）是保障生产业务平稳迭代的核心底座。然而，微服务全链路灰度治理在实际工程落地中普遍面临以下深层次痛点：

1. **南北向与东西向灰度断层与泳道泄漏 (Lane Leakage)**：
   网关通常能够基于 HTTP Header 将请求调度至微服务 A 的灰度节点；但微服务 A 随后通过 Feign 或 Spring Cloud LoadBalancer 调用微服务 B 时，底层选择器未感知流量标签，导致请求在第一跳即跌落至基线稳定实例，全链路灰度验证彻底断裂失效；
2. **规则模型缺失与 SpEL 远程执行隐患 (Rule Safety & Zero-RCE)**：
   缺少全域统一、纳秒级判定的轻量谓词规则引擎。若盲目引入 SpEL 或 Groovy 动态脚本引擎，极易在高并发网关及 RPC 核心链路上引发严重的 CPU 飙升与远程代码执行（RCE）安全漏洞；
3. **百分比切流横跳与状态震荡 (Session Oscillation)**：
   传统百分比抽样常采用纯随机数算法，导致同一终端用户在刷新页面或多步业务流程中，请求在灰度与基线实例之间反复横跳，引发数据版本冲突与体验劣化；
4. **跨跳泳道穿透缺失 (Lane Discontinuity)**：
   在长调用链路（$A \to B \to C \to D$）中，若中间服务 B 未发版（仅有基线实例），传统方案一旦发生降级就会永久抹除灰度标，导致下游已发布灰度版本的服务 C 无法接收灰度流量；
5. **容灾降级与金融级强隔离难以兼顾 (Resilience vs Strict Isolation)**：
   互联网场景要求灰度节点全部下线或故障时平滑降级回退基线以保障“零 503”，而金融交易场景则要求严禁跨泳道数据脏写、未命中灰度直接阻断（Fail-Fast）。

## Decision

确立 `ateng-cloud-traffic` 作为微服务全域流量治理、泳道隔离与灰度调度的核心底座：

1. **全域独立 Starter 定位与双模兼容 (Universal Traffic Starter)**：
   - 独立孵化 `ateng-cloud-traffic`，统管微服务全链路流量染色模型、泳道状态与灰度调度策略；
   - 核心规则模型与上下文仅依赖 `ateng-boot-core`，天然兼容 WebFlux 响应式网关与 Servlet 阻塞式业务微服务。
2. **双写双读流量上下文与生命周期管理 (Context Dual-Write & Async Propagation)**：
   - 构建 `TrafficContextHolder`，基于 `TransmittableThreadLocal` 存储当前请求的流量标签与泳道元数据；
   - 入站过滤器自动将标签同步双写至 SLF4J MDC（`X-Traffic-Tag`），保证业务日志天然带标；
   - 接入 `ateng-boot-core` 的 `AsyncContextSnapshotProvider` SPI 与 TTL，实现异步线程池任务 100% 上下文穿透；在 Filter 出口处的 `finally` 块中强制执行幂等清理，彻底杜绝线程复用污染。
3. **Spring Cloud LoadBalancer 三层复合责任链调度 (Three-Tier Routing Chain)**：
   - 实现 `TrafficTagServiceInstanceListSupplier` 实例过滤器；
   - 与 Nacos 同机房亲和及平滑启动预热构成严格的三层调度责任链：
     `TrafficTagServiceInstanceListSupplier` (灰度版本隔离) ➔ `ZonePreferenceServiceInstanceListSupplier` (机房亲和) ➔ `NacosWarmupLoadBalancer` (平滑预热加权轮询)；
   - 业务版本隔离级优先于物理机房亲和级，机房亲和级优先于启动预热加权级。
4. **轻量内聚规则引擎与一致性哈希粘性切流 (Sticky Hash & Lightweight Matcher)**：
   - 交付强类型 `TrafficRule` 与 `TrafficCondition` 模型，支持 `EXACT`、`IN`、`PREFIX`、`REGEX` 高效谓词；坚决杜绝引入 SpEL 等重量级脚本引擎；
   - 百分比抽样基于 `MurmurHash3` / 一致性哈希实现，支持指定 hash 依据（userId、IP、Cookie），保障同一实体在放量过程中的会话粘性稳定。
5. **Nacos 规则分级热更与离线韧性自洽 (Dynamic Rule Bridge & Hermetic Offline)**：
   - 动态监听应用专属规则 `${spring.application.name}-traffic-rules.json` 与全局通用规则 `traffic-rules.json`；
   - 内存采用 `AtomicReference<List<TrafficRule>>` 快照实现零锁原子无感热更；
   - 缺省韧性自检：在配置中心未开启或网络不可达时，自动捕获降级，不阻塞 Spring 容器启动并退化为纯 Header 透传模式，保障本地开发与 CI 离线自洽。
6. **平滑降级与跨跳泳道穿透保持 (Resilient Fallback & Lane Continuity)**：
   - 默认策略 `fallbackToDefault: true`：目标灰度实例列表为空时平滑降级至基线实例，杜绝 503 报警；
   - 强隔离策略 `strictLane: true`：未匹配灰度实例时直接抛出受管业务异常（Fail-Fast 503）；
   - 跨跳泳道穿透保持（Lane Continuity）：中间服务降级至基线后，维持入站上下文携带的原始 `TrafficTag`，后续向外出站调用继续优先检索该标签的下游实例。

## Consequences

- 彻底攻克微服务东西向 Feign / RestTemplate 调用的灰度断流问题，达成端到端闭环的全链路金丝雀灰度能力；
- 统一了流量染色规则模型与配置规范，消除了各中间件各自定义标签的碎片化乱象；
- 百分比分流具备会话粘性保障，消除了数据震荡与状态冲突；
- 兼顾互联网高可用容灾与金融级强隔离需求；
- 单测与 CI 构建 100% 离线自洽，无物理 Nacos 强依赖。
