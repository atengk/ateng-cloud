# Gateway WebFlux 响应式底座、零信任安全与动态治理架构设计

## Context

作为微服务分布式集群唯一的南北向公网流量入口，API 网关承担着全域流量接入、集中鉴权、动态路由调度、契约文档聚合及边界安全防护的核心职责。在生产落地与演进过程中，传统网关设计面临以下深层次架构隐患：

1. **阻塞技术栈与运行时污染 (Servlet vs Reactive)**：若网关不慎混入 Servlet 阻塞栈（如引入 `ateng-boot-web`），或在 Netty 事件循环中执行阻塞 IO，将迅速打满 EventLoop 线程池导致整网假死崩溃；
2. **零信任边界穿透与假冒伪造风险 (Header Forgery & Inner Penetration)**：若网关未实施入站敏感标头清洗，公网恶意客户端可伪造内部受信标头（如 `X-User-Id: 1` 或伪造 `Same-Token`）进行越权渗透；微服务若缺乏内部签名拦截，攻击者直连服务内网物理端口将引发系统沦陷；
3. **静态路由僵化与配置热更丢包 (Route Reload Flapping)**：将路由静态写死在本地配置文件需重启生效；若动态更新时缺乏有效性校验与多源合并兜底，配置语法错误会导致全网路由瞬间断裂；
4. **超大报文导致 Netty 堆外直接内存爆炸 (Payload Bomb)**：大文件、大报文若流经网关内存，会急剧消耗 Netty DirectMemory 引发 OOM，破坏网关高并发吞吐基石；
5. **反应式非阻塞模型下的链路追踪丢失 (Reactive MDC Loss)**：在 Reactor 响应式非阻塞执行链中，跨线程分片执行会导致传统 ThreadLocal 式 MDC 严重丢失与跨请求脏读；
6. **生产契约暴露与多重跨域冲突 (OpenAPI Leak & CORS Duplication)**：微服务文档缺乏生产隔离会导致核心 API 泄露；网关与微服务双重写入 CORS 会导致浏览器端跨域拦截失败。

## Decision

确立 `ateng-cloud-gateway` 核心架构机制与生产防线：

1. **WebFlux 纯响应式原生底座与物理隔离铁律 (Reactive vs Servlet Invariant)**：
   - 坚决杜绝引入 `ateng-boot-web`，仅强依赖 `ateng-boot-core` 与 `spring-cloud-starter-gateway`，100% 基于 Spring WebFlux 与 Netty 事件循环无锁实现；
   - 基础构件解耦：`ateng-cloud-nacos` 作为编译期可选依赖（`optional: true`），通过 `@ConditionalOnClass(NacosConfigManager.class)` 平滑探测；当类路径存在 Nacos 时自动激活动态路由与服务发现，未引入时安全降级为本地静态路由，消除强绑定锁死。
   - 严禁在网关主链路上执行阻塞操作，所有上下文与拦截机制全面契合 Project Reactor 响应式流规范。

2. **外部标头强制清洗与零信任同源防御 (Header Sanitization & Same-Token Guard)**：
   - 装配最高优先级 `GatewayHeaderSanitizeFilter`：请求入站时无条件物理剥离外部传入的所有内部受信标头（`X-User-Id`、`X-Tenant-Id`、`X-Ignore-Tenant`、`Same-Token` 等）；
   - 集中挂载 Sa-Token 反应式鉴权过滤器（`SaTokenGatewayFilter`），内置系统白名单并支持 `ateng.cloud.gateway.security.exclude-paths` 属性扩展，网关聚焦全局身份认证与租户信息提取，业务接口细粒度权限校验下放至下游微服务；
   - 校验合法后由网关从受信登录态动态重注 `X-User-Id` 与 `X-Tenant-Id`，并使用 `SaSameUtil.getToken()` 动态签发内部同源数字凭证 `Same-Token`；未携带合法签名的直连内网流量被微服务直接 401 熔断。

3. **Nacos 动态路由双轨持久化、快照容错与冷启兜底 (Dynamic Route Hot-Reload & Snapshot Guard)**：
   - 实现 `RouteDefinitionRepository` SPI，默认持久化载体为 Nacos `gateway-routes.json`（支持属性自定义扩展）；
   - **双轨配置无感热更**：既支持独立的 `gateway-routes.json` 专用路由管理，又支持微服务主配置文件中 `spring.cloud.gateway.routes` 发生变更时通过 `NacosConfigRefreshEngine` 自动广播 `RefreshRoutesEvent` 热更，双轨皆可零停机生效；
   - 内存前置语法语义校验与快照守卫（Keep Last-Known Good Snapshot）：收到 Nacos 推送后若配置格式破损或存在非法断言/过滤器，记录 ERROR 报警日志并坚决保留上一份有效内存路由快照，杜绝误操作导致全网 404；
   - 校验通过后在内存中完成无感原子替换并发布 `RefreshRoutesEvent`，更新全过程零丢包、无需重启网关；
   - 采用多源合并策略：系统启动时加载本地 YAML 静态路由作为兜底（如监控端点），Nacos 动态配置加载后按 `routeId` 合并覆盖。

4. **双重语义对齐的响应式统一全局异常处理 (Status-Aligned Global Exception)**：
   - 实现 `ErrorWebExceptionHandler`（`GatewayGlobalExceptionHandler`），将 404（路由/实例未找到）、401（未登录）、403（无权限）、413（报文超限）、503/504（下游连接失败/超时）统一转译为 `Result<Void>` JSON 响应，并与 `CommonResultCode` 精确对齐；
   - 若过滤器抛出受管 `BusinessException`，100% 保真透传其业务错误码与描述；
   - 遵循 HTTP 物理状态码与 Result 语义双重对齐契约（HTTP 状态码与 Result.code 严格保持一致），让浏览器、CDN 与前端 Axios 拦截器获得确定性协议响应；
   - 生产环境对 500 未知系统崩溃强制脱敏，屏蔽底层错误堆栈并输出统一友好文案，同时在响应头与响应体中绑定 `X-Trace-Id`。

5. **Netty 2MB 载荷硬防爆与 S3 预签名旁路直传 (Payload Bomb Defense & S3 Bypass)**：
   - 全局强制将 Netty `spring.codec.max-in-memory-size` 收敛为 `2MB`，绝不提供任何放宽例外后门，坚守 Netty 堆外直接内存安全红线；
   - 超过 2MB 的报文在 Netty 接收层直接截断并格式化返回 HTTP 413；大文件、视频与大报表上传 100% 依托 `ateng-boot-storage` S3 预签名旁路直传。

6. **基于 Reactor ContextView 的响应式无锁链路追踪与流量染色 (Reactive Trace & Traffic Tag)**：
   - 实现高优先级 `GatewayTraceFilter`：提取或生成分布式追踪唯一标识 `X-Trace-Id`，写入出站下游请求头；
   - 流量染色入口（Traffic Tag Ingress）：按“HTTP 标头 `X-Traffic-Tag` 优先，URL 参数 `traffic_tag` 兜底”提取灰度标识，规范重注入下游请求头；
   - 绑定 Reactor `ContextView` 与 `ServerWebExchange` 属性，通过响应式钩子提供日志追踪支持，出站响应头自动回写 `X-Trace-Id`；严禁常驻底层静态 ThreadLocal，彻底杜绝 EventLoop 线程污染。

7. **SpringDoc 动态微服务契约聚合与三模生产守卫 (Multi-Mode OpenAPI Guard)**：
   - 集成 `springdoc-openapi-starter-webflux-ui`，统一文档入口为 `/docs`（兼容 `/swagger-ui.html`），自动通过 Nacos 注册表发现各微服务 `/v3/api-docs` 并生成一站式聚合下拉菜单；
   - 安全链物理解耦：在 Sa-Token 白名单中放行 `/docs/**` 与 API 文档资源，由独立的 `OpenApiGuardFilter` 实施安全管控；
   - 内置可插拔安全守卫（`ateng.cloud.gateway.openapi.guard-mode`）：支持 `DISABLED`（生产一键 404 切断）、`BASIC`（HTTP Basic Auth 保护）、`SA_TOKEN`（需持有管理员登录态与权限）及 `PUBLIC`（开发免密直访）。

8. **单点收口 CORS 与响应头出站自动去重 (CORS Header Deduplication)**：
   - 跨域策略集中收敛于网关单点治理，出站管道默认装配 `DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST`，彻底杜绝下游重复写入导致的跨域报错。

9. **真实客户端 IP 安全解析与反向代理防欺诈 (Trusted Proxy Hop & Real IP)**：
   - 实现 `ClientIpResolver`，支持基于配置的可信反向代理跳数（`ateng.cloud.gateway.security.trusted-proxy-count: 1`）从右往左安全倒推解析真实客户端 IP；
   - 杜绝直接轻信伪造的 `X-Forwarded-For` 导致的 IP 欺诈，并将解析出的权威 IP 注入 `ServerWebExchange.getAttributes()` 供限流与审计消费。

10. **分布式多维令牌桶限流与标准 429 契约转译 (Multi-Dimensional Rate Limiting)**：
    - 内置基于 Redis 的三大标准 KeyResolver（`IpKeyResolver` 防爬虫、`UserKeyResolver` 防单用户打爆、`ApiKeyResolver` 护热点接口）；
    - 拦截限流触发的 HTTP 429，统一输出规范 JSON：HTTP 429 + `Result.fail(429, "当前访问过于频繁，请稍后重试")`，杜绝返回裸纯文本。

11. **路由级弹性超时防级联死锁与流式协议无感穿透 (Timeouts & Streaming Passthrough)**：
    - 设定生产超时双阶基线（默认 `connect-timeout: 3s`, `response-timeout: 10s`），支持在路由元数据中按服务独立放宽；
    - 对 SSE（`text/event-stream`）与分块传输严格执行流式零缓冲转发（Streaming Passthrough），原生支持 `ws://` 与 `wss://` 长连接握手与双向心跳透传。

12. **动态 IP 黑白名单多级防御 SPI (IP Guard SPI)**：
    - 提供 `GatewayIpFilter`，支持配置 IP/CIDR 黑名单（直接 403 熔断）与白名单；
    - 提供 `IpFilterProvider` SPI，支持开发者扩展基于 Redis/Nacos 的动态黑名单下发，命中黑名单时在网关层立即阻断，节省下游微服务算力。

13. **响应式无阻塞访问日志审计与慢请求度量 (Reactive Access Audit)**：
    - 提供可配置的 `GatewayAccessLogFilter`，过滤健康检查端点；
    - 依托 Reactor `doFinally` 异步度量耗时，结构化单行输出 `[ACCESS]` 核心指标（方法、URI、状态码、耗时、真实 IP、userId、traceId），内置超过 3000ms 慢请求 WARN 告警。

14. **多租户安全入站解析与防跨越不变量 (Multi-Tenancy Ingress Resolution)**：
    - 鉴权优先：已登录请求强制使用 Token 内部绑定的真实 `tenantId` 覆盖任何入站头，杜绝越权串租户；
    - 开放/白名单流量受控解析：仅在 `ateng.cloud.gateway.tenant.allow-header: true` 时允许读取入站 `X-Tenant-Id`，否则强制置空。

15. **URI 规范化与目录穿越攻击快速熔断 (Path Traversal & Normalization Defense)**：
    - 实现 `GatewayPathValidationFilter`：自动规范化多重斜杠（`///` $\to$ `/`）；
    - 凡解码后包含 `..`、`%2e%2e` 或包含空字节（`\0`）的非法越权请求，直接以 HTTP 400 Bad Request 熔断阻断，记录攻击警告日志，严禁向内部微服务转发。

16. **网关层写请求绝对禁止重试与建连失败弹性重试 (Gateway Downstream Retry Storm Defense)**：
    - 遵循“写请求绝对禁止重试不变量”：对 POST、PUT、PATCH、DELETE 等非幂等写请求，无论发生读取超时还是任何异常，坚决零重试（Retry = 0）；
    - 仅对纯读请求（GET、HEAD）且发生 `ConnectTimeoutException`（TCP 握手尚未建立）时，允许在不同服务实例间受控重试 1 次，根除重试风暴与资损风险。

17. **等保三级与 OWASP 安全响应头强化 (Security Headers Hardening)**：
    - 出站管道默认装配 `GatewaySecurityHeadersFilter`（支持一键开闭与定制）；
    - 自动注入 `X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`、`X-XSS-Protection: 1; mode=block`、`Strict-Transport-Security` 及 `Referrer-Policy`，满足金融政企等保三级安全合规。

18. **开放接口防篡改签名与防重放攻击防线 SPI (API Sign & Nonce Replay Defense SPI)**：
    - 提供可选的 `GatewaySignGuardFilter`，针对开放路由实施时间戳（Timestamp）时钟偏差校验、Redis Nonce 唯一性防重放及 HMAC-SHA256 签名防篡改校验；
    - 提供 `SignSecretProvider` SPI 支持按 `appId` 动态获取签名私钥。

19. **网关复合负载均衡责任链与金丝雀灰度无损降级 (Gateway Composite LoadBalancing Chain)**：
    - 定制扩展反应式负载均衡提供器 `GrayServiceInstanceListSupplier`：提取请求中的 `X-Traffic-Tag`，精准过滤匹配 Nacos 实例元数据（`metadata.tag`）；
    - 弹性兜底策略：当灰度节点全部下线时，若 `fallback-to-default=true`（默认），平滑降级调度至基线默认实例，杜绝全网 503 故障；未染色普通流量优先锁定基线实例；
    - **责任链嵌套与拓扑时序不变量（Gray First）**：遵循“业务隔离级别高于物理机房亲和”铁律，装配链严格按 `DiscoveryClient` $\to$ `GrayServiceInstanceListSupplier`（版本池隔离） $\to$ `ZonePreferenceServiceInstanceListSupplier`（可用区就近） $\to$ `Caching` 嵌套；
    - **无损下线缓存时效收敛**：默认将网关 `spring.cloud.loadbalancer.cache.ttl` 压缩至 `3s`，确保实例注销后秒级感知，杜绝 35 秒本地缓存滞后导致调用已下线节点；
    - **流量染色决议扩展点 (TrafficTagResolver SPI)**：标配 `@ConditionalOnMissingBean` 暴露 `TrafficTagResolver`，支持基于登录态、租户 ID 或动态业务规则定制入站打标；
    - **Nacos 智能加权与预热调度无缝协同**：底层实例选择器无缝接入 `NacosWarmupLoadBalancer`，全面支持 Nacos 控制台静态权重（`nacos.weight`）、运维 `0` 权重摘流隔离、基于 Nginx SWRR 算法的 JVM 冷启动平滑预热升权调度，以及通过 `InstanceWeightCalculator` 实现的自适应资源降权。

20. **100% 离线自洽 1:1 场景工程 (Hermetic Sample Sandbox)**：
    - 在 `ateng-cloud-samples/sample-cloud-gateway` 配套专属响应式沙箱，基于 `WebTestClient` + 内置响应式 Mock 终结点 + 内存动态路由推演，在脱网（`nacos.enabled=false`）环境下秒级跑通全特性回归。

## Consequences

1. **运行时纯洁与高性能**：网关完全基于响应式事件循环驱动，零阻塞线程，内存开销低且吞吐量达到极致。
2. **零信任内网防穿透与防伪造**：入站清洗抹平外部伪造头，下游同源签名拦截旁路直连，构筑坚不可摧的纵深安全防线。
3. **运维与治理弹性闭环**：动态路由免重启热更、Netty 载荷零放宽硬防爆、多微服务 OpenAPI 统一聚合与多模生产守卫，提供开箱即用的工业级网关体验。
