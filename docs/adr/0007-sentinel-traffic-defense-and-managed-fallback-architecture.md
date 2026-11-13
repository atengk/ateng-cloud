# Sentinel 流量防卫、受管熔断降级与 Nacos 动态持久化架构设计

## Context

在分布式微服务架构中，突发高并发流量冲击、非预期慢调用与级联超时常引发严重的服务雪崩。原生开源组件在企业级生产落地中常面临以下深层次痛点：

1. **阻断异常契约脱节与脏数据扩散**：原生 Sentinel 在拦截流量时默认输出不友好的裸文本，未与微服务全局标准的强类型 `Result<Void>` 响应契约对齐，且丢失 HTTP 物理状态码对齐（429 Too Many Requests / 503 Service Unavailable）；且在响应已被上游提交（`response.isCommitted()`）时容易引发次生 `IllegalStateException`；
2. **RESTful 动态路径导致资源监控树内存无界泄漏**：在 Spring MVC 中，带路径参数的 RESTful 接口（如 `/api/v1/orders/1001`）若未收敛清洗，将被作为不同资源统计，导致 Sentinel 内部资源树发生无界膨胀，最终引发 JVM 堆内存 OOM 崩溃；此外缺乏对监控探针（如 `/actuator/**`、Swagger/OpenAPI 等）的排除机制；
3. **OpenFeign 降级工厂异常误抹平与次生 NPE**：OpenFeign 开启 Sentinel 后，`SentinelInvocationHandler` 会将下游已解码的 `BusinessException` 及客户端 `FlowException` 一并送入 `FallbackFactory`。若通用降级工厂无差别包装为 `DEGRADE (4202)` 会抹平下游业务语义；且当降级钩子对集合/可选容器返回 `null` 或调用接口 `default` 方法时易违背空安全保底原则；
4. **全局异常切面吞噬导致 Web 熔断失效与因果链黑盒**：Spring MVC 全局 `@RestControllerAdvice` 提前消化异常后，`SentinelWebInterceptor#afterCompletion` 收到的 `ex` 恒为 `null`，导致生产环境 Web 接口异常比例与异常数熔断静默失效；且阻断快照若缺失 `traceId` 与调用源 `origin`，线上排障无法串联全链路日志；
5. **@SentinelResource 注解切面逃逸与出站降级状态码脱节**：业务层标注 `@SentinelResource` 未配 `blockHandler`（含反射/代理包装异常）或出站 Feign 抛出携带 `SentinelResultCode` 的受管异常时，若未统一拦截对齐 HTTP 429/503，会导致误报 500 或物理状态码失真；
6. **Feign 联动的隐性开关暗坑**：若开发者漏配 `feign.sentinel.enabled=true`，Feign 客户端在故障时将彻底静默失效，超时直接裸抛 FeignException；
7. **规则易失、语义脏数据穿透与配置持久化繁琐**：Sentinel 规则默认驻留内存，重启即失；且在 Nacos 推送的规则 JSON 存在语法失误或核心语义非法（如字段名写错导致 `resource=null`、负数阈值 `count < 0`）时，若直接覆盖内存会导致有效规则被清空或替换为残废规则；
8. **Nacos 连接参数配置冗余割裂**：微服务通常已在 Spring Cloud 原生配置了 Nacos 连接凭据，但 Sentinel 规则持久化往往需要二次配置连接，存在配置冗余与环境不同步暗坑；
9. **运行时规则黑盒与缺乏全景可观测性**：线上 SRE 无法直观探测微服务内存中 5 大规则的加载快照、已启用 Nacos 数据源的真实订阅状态、Rule Fail-Safe 容错拦截记录以及含全链路上下文的实时阻断指标。

## Decision

确立 `ateng-cloud-sentinel` 核心架构机制与生产防线：

1. **专注 Servlet / Spring MVC 纯净正交底座**：
   - 严格与 WebFlux 响应式网关（`ateng-cloud-gateway`）及 Dubbo RPC 物理隔离，保持深模块正交性。
2. **独立错误码体系、退避响应头与物理状态码双重对齐 (Block Dual-Alignment Contract)**：
   - 在 `ResultCodeSegment.NETWORK_RPC` (4200~4299) 区段建立强类型 `SentinelResultCode`（细分 `FLOW_LIMIT`、`DEGRADE`、`PARAM_FLOW_LIMIT`、`SYSTEM_BLOCK`、`AUTHORITY_BLOCK`）；
   - 提供 `AtengBlockExceptionHandler` 与 `SentinelBlockExceptionAdvice`，触发阻断时物理对齐 HTTP 429 / 503 状态码，注入 `X-Sentinel-Block-Rule` 及可选 `Retry-After` 退避响应头，并输出标准 `Result.fail(code, msg)` JSON 报文。
3. **RESTful 路径归一化与资源树防爆 (Resource Tree Explosion Defense)**：
   - 提供 `AtengUrlCleaner`，仅在当前请求路径匹配且路由模板非泛化通配符（`/**`）时提取 Spring MVC `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`，并对模板二次校验白名单；
   - 统一归一化非根路径尾部斜杠，内置 36 位 UUID、24/32 位 Hex 哈希串与纯数字收敛，并支持配置预编译的有序 `custom-patterns` 正则规则；
   - 支持 `exclude-patterns` 白名单机制（默认收敛 `/actuator/**`、`/v3/api-docs/**` 等探针），命中时返回空字符串，跳过 Sentinel 资源创建。
4. **多维调用源解析器 (Multi-Dimensional Origin Resolution)**：
   - 提供 `AtengRequestOriginParser`，按 `X-Origin-Service` $\rightarrow$ `X-Gateway-Caller` $\rightarrow$ 可选多租户上下文（`TenantContextHolder`，带 `tenant:` 命名空间前缀） $\rightarrow$ 客户端真实 IP $\rightarrow$ 默认值 优先级解析请求调用方，支撑服务级、网关级与租户级授权与流控。
5. **受管 Feign 降级工厂基类 (Managed Fallback Factory)**：
   - 提供抽象基类 `AtengFallbackFactory<T>`：① 若 `cause` 已是下游普通 `BusinessException` 则原样透传；② 若包含 `BlockException` 则按具体规则类型精准映射为对应 `SentinelResultCode` 并源头联动 `SentinelBlockCounter`；③ 其余网络异常转译为携带原始 `cause` 的 `BusinessException(SentinelResultCode.DEGRADE)`；④ 放行接口 `default` 方法，并对 `List`/`Set`/`Map`/`Optional` 的空降级返回自动执行空集合/`Optional.empty()` 安全保底。
6. **注解切面逃逸防御与 Web 异常熔断追踪桥接 (Aspect Escape Defense & Exception Trace Bridge)**：
   - `SentinelBlockExceptionAdvice` 全局拦截 `@SentinelResource` 逃逸的 `BlockException`（含 `UndeclaredThrowableException` 与 `InvocationTargetException` 包装）以及携带 `SentinelResultCode` 的 `BusinessException`，统一对齐 HTTP 429/503 且通过防重守卫避免重复计数；
   - 提供 `ExceptionTraceBridge`，在全局 `@RestControllerAdvice` 消化异常前自动将非豁免的系统异常喂入 `Tracer.trace(ex)`（默认豁免 `BlockException`、Spring 4xx 参数校验/协议异常与常规 `BusinessException`，支持自定义 `ignore-exceptions`），根治 Web 接口异常比例/异常数熔断静默失效问题。
7. **Feign 熔断防呆自动装配 (Foolproof Feign Activation)**：
   - 通过 `EnvironmentPostProcessor` 探测到 Classpath 存在 OpenFeign 时，默认激活 `feign.sentinel.enabled=true`，支持业务显式声明 false 覆盖。
8. **Nacos 5 大规则全家桶开箱即用持久化 (Full-Spectrum Nacos Rule Bridge)**：
   - 完整覆盖 FlowRule、DegradeRule、ParamFlowRule、SystemRule、AuthorityRule 5 大规则，统一规范为 `${dataIdPrefix}-{type}.json` 契约，支持按需启停与动态无感热加载。
9. **规则防暴毙容错防御 (Rule Fail-Safe Invariant)**：
   - 在 `RuleListConverter` 中实施语法与语义双重防卫：当 Nacos 推送的 JSON 格式非法，或反序列化后的规则存在 `null` 元素、非系统规则 `resource` 为空、阈值 `count < 0` 等语义错误时，立即抛出异常阻断更新并保持最后已知有效规则（Last Known Good Rules），同时原子累加 Fail-Safe 拦截计数与错误摘要。
10. **Nacos 连接参数四级平滑自适应继承 (Nacos Connection Auto-Inheritance)**：
    - 确立 `ateng.cloud.sentinel.nacos-rules.*` 显式配置 $\rightarrow$ `spring.cloud.nacos.config.*` $\rightarrow$ `spring.cloud.nacos.discovery.*` $\rightarrow$ 默认值 的四级继承时序，彻底消除重复配置。
11. **Sentinel 全景健康与动态规则自省端点 (Sentinel Panorama Diagnostics Endpoint)**：
    - 在存在 Actuator 时自动暴露只读端点 `/actuator/sentinel-panorama`，一屏汇聚基础信息、精确过滤的已启用 Nacos 数据源订阅状态与 Fail-Safe 容错统计、5 大规则内存快照、清洗器与多维溯源配置，以及包含 `traceId` 与 `origin` 的轻量阻断事件快照。
12. **阻断响应已提交状态前置防卫 (Committed Response Defense)**：
    - 在 `AtengBlockExceptionHandler` 写入响应前置断言 `response.isCommitted()`，若已提交则记录告警并安全跳过，防止次生异常污染堆栈。

## Consequences

- 业务微服务接入 `ateng-cloud-sentinel` 即可获得端到端规范化的流量防卫与熔断降级能力；
- 统一了阻断异常的外部 HTTP 状态与 JSON 错误码规范，消除了 500 误报警与资源树 OOM 隐患；
- Feign 客户端熔断具备双模自适应契约安全保障与因果链可追溯性；
- 规则解析具备防暴毙容错机制与零冗余 Nacos 参数继承能力；
- 具备开箱即用的运维自省全景可观测性端点；
- 单测与 CI 构建 100% 离线自洽，无外部中间件强依赖。
