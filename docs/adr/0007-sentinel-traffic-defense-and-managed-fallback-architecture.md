# Sentinel 流量防卫、受管熔断降级与 Nacos 动态持久化架构设计

## Context

在分布式微服务架构中，突发高并发流量冲击、非预期慢调用与级联超时常引发严重的服务雪崩。原生开源组件在企业级生产落地中常面临以下深层次痛点：

1. **阻断异常契约脱节与脏数据扩散**：原生 Sentinel 在拦截流量时默认输出不友好的裸文本，未与微服务全局标准的强类型 `Result<Void>` 响应契约对齐，且丢失 HTTP 物理状态码对齐（429 Too Many Requests / 503 Service Unavailable）；且在响应已被上游提交（`response.isCommitted()`）时容易引发次生 `IllegalStateException`；
2. **RESTful 动态路径导致资源监控树内存无界泄漏**：在 Spring MVC 中，带路径参数的 RESTful 接口（如 `/api/v1/orders/1001`）若未收敛清洗，将被作为不同资源统计，导致 Sentinel 内部资源树发生无界膨胀，最终引发 JVM 堆内存 OOM 崩溃；此外缺乏对监控探针（如 `/actuator/**`、Swagger/OpenAPI 等）的排除机制；
3. **OpenFeign 降级工厂类型不兼容与次生 NPE**：Feign 客户端可能声明返回 `Result<T>`，也可能声明直接返回业务 POJO `T`。通用降级工厂若强行包装会导致 `ClassCastException`，直接返回 `null` 则违背空安全保底原则；
4. **因果链丢失与排查黑盒**：原生降级逻辑常吞掉真实网络与阻断异常，导致调用链路无法归因是下游熔断还是自身流控；
5. **@SentinelResource 注解切面逃逸与误报 500**：业务层标注 `@SentinelResource` 时，若未显式编写 `blockHandler`，抛出的 `BlockException` 会穿透并被 Spring 全局异常处理器误判为 500 系统未知错误；
6. **Feign 联动的隐性开关暗坑**：若开发者漏配 `feign.sentinel.enabled=true`，Feign 客户端在故障时将彻底静默失效，超时直接裸抛 FeignException；
7. **规则易失与配置持久化繁琐**：Sentinel 规则默认驻留内存，应用重启即丢失；生产环境手动编写数据源绑定代码繁琐且易出错；且在 Nacos 推送的规则 JSON 存在语法失误时，直接返回空列表会导致运行中全量规则被瞬间清空，引发流量瞬间击穿；
8. **Nacos 连接参数配置冗余割裂**：微服务通常已在 Spring Cloud 原生配置了 Nacos 连接凭据，但 Sentinel 规则持久化往往需要二次配置连接，存在配置冗余与环境不同步暗坑；
9. **运行时规则黑盒与缺乏全景可观测性**：线上 SRE 无法直观探测微服务内存中 5 大规则的加载快照、Nacos 动态监听状态与实时阻断频次。

## Decision

确立 `ateng-cloud-sentinel` 核心架构机制与生产防线：

1. **专注 Servlet / Spring MVC 纯净正交底座**：
   - 严格与 WebFlux 响应式网关（`ateng-cloud-gateway`）及 Dubbo RPC 物理隔离，保持深模块正交性。
2. **独立错误码体系与物理状态码双重对齐 (Block Dual-Alignment Contract)**：
   - 在 `ResultCodeSegment.NETWORK_RPC` (4200~4299) 区段建立强类型 `SentinelResultCode`（细分 `FLOW_LIMIT`、`DEGRADE`、`PARAM_FLOW_LIMIT`、`SYSTEM_BLOCK`、`AUTHORITY_BLOCK`）；
   - 提供 `AtengBlockExceptionHandler`，触发阻断时物理对齐 HTTP 429 / 503 状态码，并输出标准 `Result.fail(code, msg)` JSON 报文。
3. **RESTful 路径归一化与资源树防爆 (Resource Tree Explosion Defense)**：
   - 提供 `AtengUrlCleaner`，最高优先级提取 Spring MVC 路由模板（`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`，如 `/orders/{id}`）；保底采用正则收敛数字与 UUID，根治内存泄漏。
   - 支持 `exclude-patterns` 白名单机制（默认收敛 `/actuator/**`、`/v3/api-docs/**` 等探针），命中时返回空字符串，跳过 Sentinel 资源创建。
4. **多级调用源解析器 (Multi-Level RequestOriginParser)**：
   - 提供 `AtengRequestOriginParser`，按 `X-Origin-Service` -> `X-Gateway-Caller` -> 客户端 IP 优先级解析请求调用方，支持精细化授权与流控。
5. **受管 Feign 降级工厂基类 (Managed Fallback Factory)**：
   - 提供抽象基类 `AtengFallbackFactory<T>`，默认通过动态代理将熔断/网络异常完整转译为携带原始因果链的语义化 `BusinessException`；开放反射钩子支持开发者重写特定接口的柔性保底值。
6. **注解切面逃逸兜底防御 (Aspect Escape Defense)**：
   - 注入专职的 `@RestControllerAdvice` 兜底切面，全局拦截 `@SentinelResource` 逃逸的 `BlockException`，杜绝漏写 handler 导致系统 500 报警。
7. **Feign 熔断防呆自动装配 (Foolproof Feign Activation)**：
   - 通过 `EnvironmentPostProcessor` 探测到 Classpath 存在 OpenFeign 时，默认激活 `feign.sentinel.enabled=true`，支持业务显式声明 false 覆盖。
8. **Nacos 5 大规则全家桶开箱即用持久化 (Full-Spectrum Nacos Rule Bridge)**：
   - 一次性完整覆盖 FlowRule、DegradeRule、ParamFlowRule、SystemRule、AuthorityRule 5 大规则，统一规范为 `${dataIdPrefix}-{type}.json` 契约，支持动态无感热加载。
9. **规则防暴毙容错防御 (Rule Fail-Safe Invariant)**：
   - 在规则反序列化器中实施强防卫，当 Nacos 推送的 JSON 格式非法或解析报错时，严禁清空规则池；抛出异常阻断属性更新并保持最后已知有效规则（Last Known Good Rules），守住微服务底线安全。
10. **Nacos 连接参数四级平滑自适应继承 (Nacos Connection Auto-Inheritance)**：
    - 确立 `ateng.cloud.sentinel.nacos-rules.*` 显式配置 -> `spring.cloud.nacos.config.*` -> `spring.cloud.nacos.discovery.*` -> 默认值 的四级继承时序，彻底消除重复配置。
11. **Sentinel 全景健康与动态规则自省端点 (Sentinel Panorama Diagnostics Endpoint)**：
    - 在存在 Actuator 时自动暴露只读端点 `/actuator/sentinel-panorama`，一屏汇聚基础信息、Nacos 订阅状态、5 大规则内存快照、清洗器与来源解析器配置及轻量阻断事件统计。
12. **阻断响应已提交状态前置防卫 (Committed Response Defense)**：
    - 在 `AtengBlockExceptionHandler` 写入响应前置断言 `response.isCommitted()`，若已提交则记录告警并安全跳过，防止次生异常污染堆栈。

## Consequences

- 业务微服务接入 `ateng-cloud-sentinel` 即可获得端到端规范化的流量防卫与熔断降级能力；
- 统一了阻断异常的外部 HTTP 状态与 JSON 错误码规范，消除了 500 误报警与资源树 OOM 隐患；
- Feign 客户端熔断具备双模自适应契约安全保障与因果链可追溯性；
- 规则解析具备防暴毙容错机制与零冗余 Nacos 参数继承能力；
- 具备开箱即用的运维自省全景可观测性端点；
- 单测与 CI 构建 100% 离线自洽，无外部中间件强依赖。
