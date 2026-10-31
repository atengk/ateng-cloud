# Gateway Context

基于 Spring WebFlux 与 Netty 事件循环的响应式微服务 API 网关上下文，负责微服务全域流量准入、集中安全鉴权、零信任内部同源凭证签发、Nacos 动态路由原子热更、Netty 载荷硬防爆及多服务契约文档聚合治理。

## Language

**Header Sanitization**:
网关在执行鉴权与路由转发前，强制对外部请求传入的所有内部受信标头（`X-User-Id`、`X-Tenant-Id`、`X-Ignore-Tenant`、`Same-Token` 等）进行物理剥离与清空，仅由网关在校验合法后基于可信上下文重新生成的边界防御机制。
_Avoid_: 请求头清理, 标头过滤, 参数重置

**Zero-Trust Same-Token**:
网关集中鉴权通过后向下游受信服务转发时动态签发的内部同源数字签名凭证，用于阻断绕过网关直连内部微服务暴露端口的非法流量。
_Avoid_: 内部Token, 服务间鉴权头, 同源凭证

**Dynamic Route Hot-Reload**:
基于 Nacos 配置中心监听器实现的路由定义原子热替换机制，在感知到路由变更时发布事件刷新内存路由表，全过程无需重启网关且请求零丢包。
_Avoid_: 动态路由刷新, Nacos路由更新, 路由热加载

**Payload Bomb Defense**:
基于 Netty 堆外直接内存保护（默认 2MB）的入站报文硬防爆机制，对超过阈值的超大请求体在 Netty 接收层直接截断熔断并输出 HTTP 413，大文件坚决走对象存储预签名旁路直传。
_Avoid_: 报文限制, 内存防爆, 请求体大小拦截

**Reactive Trace Propagation**:
基于 Project Reactor 的 `ContextView` 绑定链路唯一标识并在请求转发与响应头间透传，严禁在 Netty EventLoop 线程常驻未清理的静态 ThreadLocal 导致线程污染的响应式链路追踪范式。
_Avoid_: 响应式MDC, WebFlux追踪, 线程变量传递

**Multi-Mode OpenAPI Guard**:
网关聚合下游微服务接口文档时的多模安全防护机制，支持 DISABLED（一键 404 切断）、BASIC（HTTP Basic 挑战）、SA_TOKEN（登录态权限守卫）与 PUBLIC（免密直访）的可插拔安全防线。
_Avoid_: Swagger守卫, 文档保护, 接口文档拦截

**Last-Known Good Snapshot**:
当配置中心推送的动态路由规则存在语法破损或非法断言过滤器时，网关前置校验拦截并坚决保留上一份有效内存路由的容错兜底机制。
_Avoid_: 路由快照兜底, 错误配置回滚, 上一次配置

**Traffic Tag Ingress**:
网关按“HTTP 标头优先、URL 参数兜底”识别外部流量染色标记，并在入站清洗后规范化注入下游请求头的全链路灰度入口解析机制。
_Avoid_: 灰度标提取, 染色入站解析, 泳道标签读取

**CORS Header Deduplication**:
网关出站管道默认装配的响应头去重过滤器，自动剔除下游微服务重复写入的跨域标头并保留网关权威单点标头，彻底杜绝浏览器端跨域多重标头报错。
_Avoid_: 跨域去重, 响应头过滤, CORS覆盖

**Trusted Proxy Hop**:
依靠配置的可信反向代理跳数（默认 1）从右往左安全倒推解析客户端真实公网 IP 的防御性解析机制，防止恶意客户端伪造 `X-Forwarded-For` 首部进行 IP 欺诈。
_Avoid_: 真实IP解析, XFF解析, IP获取

**Multi-Dimensional Rate Limiting**:
网关基于 Redis 令牌桶算法，在入站流量处按真实 IP、用户 ID 或 API 路径等多维 Key 实施的分布式流量限流防刷机制，触发时统一响应 HTTP 429。
_Avoid_: 接口限流, 访问频率限制, 网关防刷

**Streaming Passthrough**:
对 SSE（`text/event-stream`）、分块传输编码及 WebSocket 长连接执行零中间缓冲、随到随推的原生流式直通转发机制。
_Avoid_: 流式缓冲, 长连接转发, SSE代理

**IP Guard SPI**:
支持基于本地配置与外部动态源（Redis/Nacos）扩展的 IP 黑白名单多级防御 SPI，命中黑名单时在网关层直接 403 熔断。
_Avoid_: 黑白名单拦截, IP封禁, 访问控制过滤

**Reactive Access Audit**:
基于 Reactor 生命周期 `doFinally` 钩子异步计算并在请求结束时输出紧凑结构化访问日志与慢请求告警的非阻塞审计机制。
_Avoid_: 访问日志, 请求耗时统计, 网关日志记录

**Path Traversal Defense**:
网关在路由转发前对请求 URI 执行的严格规范化与恶意路径穿越拦截机制，自动折叠多斜杠，对包含 `..`、`%2e%2e` 或空字节的请求直接以 HTTP 400 熔断。
_Avoid_: 路径穿越拦截, 路径清洗, URI规范化

**Retry Storm Defense**:
针对非幂等写请求（POST/PUT 等）无论任何异常坚决实行零重试，仅对纯读请求（GET）在 TCP 握手建连失败时受控重试 1 次的网关级防雪崩重试纪律。
_Avoid_: 容错重试, 弹性重试机制, 失败轮询

**Security Headers Hardening**:
网关出站管道默认自动注入符合 OWASP 及等保合规规范的安全响应头（`X-Content-Type-Options`、`X-Frame-Options`、`X-XSS-Protection`、`HSTS` 等）的边界加固机制。
_Avoid_: 安全响应头, 响应头加固, 等保响应头

**API Sign & Replay Defense**:
基于时间戳偏差防偏、Redis Nonce 唯一性防重放及 HMAC-SHA256 签名校验的多层安全防御机制，防止公网开放接口参数被篡改或恶意重放。
_Avoid_: 签名校验, 接口验签, 防重放拦截

**Canary Gray LoadBalancing**:
网关基于请求上下文中的 `X-Traffic-Tag` 过滤 Nacos 实例元数据（`metadata.tag`）并执行精准分流，且在灰度节点全下线时自动弹性降级回退至基线默认实例的反应式负载均衡机制。
_Avoid_: 灰度负载均衡, 金丝雀路由, 标签路由

**Composite LoadBalancing Chain**:
网关构建的将金丝雀灰度隔离、可用区就近亲和与启动预热加权选择器按“业务隔离优先于拓扑亲和”次序动态嵌套的复合责任链调度机制。
_Avoid_: 组合负载均衡, 责任链负载, 混合路由

**Dynamic Traffic Tag Resolver**:
网关基于 `TrafficTagResolver` SPI 提供的支持从请求头、Query 参数及自定义业务维度（如租户 ID、用户白名单等）动态解析与计算流量染色标签的可插拔决策机制。
_Avoid_: 动态打标器, 灰度解析器, 染色策略

**Dual-Track Dynamic Route**:
网关同时支持从独立 Nacos 动态路由数据源（默认 `gateway-routes.json`）与微服务主配置文件（`spring.cloud.gateway.routes`）双轨并存监听与无感原子热更的路由治理机制，结合快照守卫容错与多源协同合并，彻底消除修改主配置路由后必须重启网关的心智负担。
_Avoid_: 双轨路由, 动态路由双源, 路由双监听

**Body Digest Sign Guard**:
网关在 2MB 硬防爆边界内，利用响应式缓存机制对 POST/PUT 请求体计算 SHA-256 摘要并混入规范签名串的防篡改防御机制。
_Avoid_: 请求体加签, Body验签, 报文哈希

**Sentinel Reactive Adapter**:
网关以非阻塞自适应探测方式，对 Sentinel 响应式流控、降级与系统保护阻断异常执行的统一强类型 Result 契约转译机制。
_Avoid_: 阿里限流适配, 哨兵熔断处理, Sentinel拦截

**Gateway Panorama Diagnostics**:
网关基于非阻塞 Actuator 端点暴露的动态路由、双轨快照、灰度负载及边缘安全装配的多维健康度诊断机制。
_Avoid_: 网关状态端点, 路由监控接口, Actuator路由检查

**Sanitized Query Audit**:
网关访问日志在记录请求 Query 参数时，自动对密码、令牌、密钥等敏感 Key 实施基于掩码的脱敏审计机制。
_Avoid_: 参数日志打印, Query脱敏, 请求参数审计

**Premature Close Adaptation**:
网关对下游微服务在滚动发布或偶发网络抖动时触发的 TCP 连接过早关闭（`PrematureCloseException` / `ClosedChannelException`）实施的统一精准 HTTP 502 Bad Gateway 响应式转译机制。
_Avoid_: 502转译, 连接中断处理, 对端断开拦截

**Deterministic Buffer Release**:
网关在对请求体执行验签或缓存过程中，针对验签失败提前熔断阻断场景构筑的 Netty 堆外直接内存确定性回收与引用安全释放机制。
_Avoid_: 内存释放, 缓存回收, DataBuffer清理

**Dual-Track Route Precedence**:
网关在微服务主配置与独立 Nacos 动态路由发生同名 `routeId` 碰撞时，确立的“独立动态路由权威覆盖、主配置基线兜底”的仲裁优先级机制。
_Avoid_: 路由冲突覆盖, 路由优先级, 动态路由仲裁

**Non-Blocking Telemetry Isolation**:
网关全景诊断端点与访问日志审计坚守的只读无锁、零网络阻塞、异常安全降级的绝对旁路观测隔离机制。
_Avoid_: 旁路容错, 审计隔离, 端点无锁
