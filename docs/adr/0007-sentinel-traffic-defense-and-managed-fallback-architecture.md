# Sentinel 流量防卫、受管熔断降级与 Nacos 动态持久化架构设计

## Context

在分布式微服务架构中，突发高并发流量冲击、非预期慢调用与级联超时常引发严重的服务雪崩。原生开源组件在企业级生产落地中常面临以下深层次痛点：

1. **阻断异常契约脱节与脏数据扩散**：原生 Sentinel 在拦截流量时默认输出不友好的裸文本，未与微服务全局标准的强类型 `Result<Void>` 响应契约对齐，且丢失 HTTP 物理状态码对齐（429 Too Many Requests / 503 Service Unavailable）；
2. **RESTful 动态路径导致资源监控树内存无界泄漏**：在 Spring MVC 中，带路径参数的 RESTful 接口（如 `/api/v1/orders/1001`）若未收敛清洗，将被作为不同资源统计，导致 Sentinel 内部资源树发生无界膨胀，最终引发 JVM 堆内存 OOM 崩溃；
3. **OpenFeign 降级工厂类型不兼容与次生 NPE**：Feign 客户端可能声明返回 `Result<T>`，也可能声明直接返回业务 POJO `T`。通用降级工厂若强行包装会导致 `ClassCastException`，直接返回 `null` 则违背空安全保底原则；
4. **因果链丢失与排查黑盒**：原生降级逻辑常吞掉真实网络与阻断异常，导致调用链路无法归因是下游熔断还是自身流控；
5. **@SentinelResource 注解切面逃逸与误报 500**：业务层标注 `@SentinelResource` 时，若未显式编写 `blockHandler`，抛出的 `BlockException` 会穿透并被 Spring 全局异常处理器误判为 500 系统未知错误；
6. **Feign 联动的隐性开关暗坑**：若开发者漏配 `feign.sentinel.enabled=true`，Feign 客户端在故障时将彻底静默失效，超时直接裸抛 FeignException；
7. **规则易失与配置持久化繁琐**：Sentinel 规则默认驻留内存，应用重启即丢失；生产环境手动编写数据源绑定代码繁琐且易出错。

## Decision

确立 `ateng-cloud-sentinel` 核心架构机制与生产防线：

1. **专注 Servlet / Spring MVC 纯净正交底座**：
   - 严格与 WebFlux 响应式网关（`ateng-cloud-gateway`）及 Dubbo RPC 物理隔离，保持深模块正交性。
2. **独立错误码体系与物理状态码双重对齐 (Block Dual-Alignment Contract)**：
   - 在 `ResultCodeSegment.NETWORK_RPC` (4200~4299) 区段建立强类型 `SentinelResultCode`（细分 `FLOW_LIMIT`、`DEGRADE`、`PARAM_FLOW_LIMIT`、`SYSTEM_BLOCK`、`AUTHORITY_BLOCK`）；
   - 提供 `AtengBlockExceptionHandler`，触发阻断时物理对齐 HTTP 429 / 503 状态码，并输出标准 `Result.fail(code, msg)` JSON 报文。
3. **RESTful 路径归一化与资源树防爆 (Resource Tree Explosion Defense)**：
   - 提供 `AtengUrlCleaner`，最高优先级提取 Spring MVC 路由模板（`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`，如 `/orders/{id}`）；保底采用正则收敛数字与 UUID，根治内存泄漏。
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

## Consequences

- 业务微服务接入 `ateng-cloud-sentinel` 即可获得端到端规范化的流量防卫与熔断降级能力；
- 统一了阻断异常的外部 HTTP 状态与 JSON 错误码规范，消除了 500 误报警与资源树 OOM 隐患；
- Feign 客户端熔断具备双模自适应契约安全保障；
- 单测与 CI 构建 100% 离线自洽，无外部中间件强依赖。
