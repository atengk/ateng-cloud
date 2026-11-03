# ateng-cloud-gateway

面向企业级高可用微服务架构的 Spring Cloud 纯响应式 API 网关基础设施 Starter，单向依赖消费 `ateng-boot-bom`，基于 Spring WebFlux 与 Netty 事件循环全无锁响应式构建（绝对物理隔离 Servlet 阻塞栈）。

开箱集成 Netty 2MB 载荷硬防爆、Sa-Token 零信任集中鉴权与 Same-Token 内部同源受信凭证签发、Nacos 动态路由双轨无感原子热更与快照容错、金丝雀灰度无损降级回退、分布式多维限流与 429 统一转译、OWASP 等保三级合规安全响应头强化与 CORS 标头去重、微服务契约文档四模守卫、轻量响应式访问日志审计及开放接口防篡改签名。

---

## 维度零：特性与公共 API 全景速查矩阵

### 1. 核心类与过滤器全景速查表

| 类 / 接口 | 类型 | 执行阶段 / 优先级 | 核心职责与设计说明 | 默认装配条件 |
| :--- | :--- | :--- | :--- | :--- |
| `AtengGatewayAutoConfiguration` | 自动装配类 | 根配置容器 | 网关基础设施自动装配总入口，统管过滤器与编解码器 | `@ConditionalOnWebApplication(REACTIVE)` |
| `GatewayGlobalExceptionHandler` | 统一异常处理器 | `@Order(-2)` | 统一拦截网关层网络、路由、熔断与业务异常，转译为强类型 ``Result<Void>`` 且自动附加 `X-Trace-Id` | 标配 `@ConditionalOnMissingBean` |
| `GatewaySecurityHeadersFilter` | 响应式 WebFilter | `HIGHEST_PRECEDENCE` | 出站自动注入 OWASP 等保三级安全响应头（X-Frame-Options、HSTS 等），自动对 CORS 标头去重保留首项 | `security-headers.enabled=true` |
| `GatewayPathValidationFilter` | 全局过滤器 | `HIGHEST_PRECEDENCE + 1` | 严格校验 URI 规范性，Fail-Fast 阻断目录穿越（`../`）、非法特殊字符与空路径攻击 | `security.path-validation-enabled=true` |
| `GatewayTraceFilter` | 全局过滤器 | `HIGHEST_PRECEDENCE + 2` | 响应式无锁链路追踪 TraceId 生成（无横杠 32 位 UUID）与请求/响应双向透传 | `security.trace-enabled=true` |
| `GatewayHeaderSanitizeFilter` | 全局过滤器 | `HIGHEST_PRECEDENCE + 3` | 物理剥离入站请求伪造的内部受信标头（`X-User-Id`、`X-Tenant-Id`、`Same-Token` 等），杜绝标头走私欺诈 | `security.header-sanitize-enabled=true` |
| `GatewayClientIpFilter` | 全局过滤器 | `HIGHEST_PRECEDENCE + 4` | 依据可信代理跳数（`trusted-proxy-count`）从右往左安全倒推解析真实公网客户端 IP 并挂载至上下文 | `security.client-ip-enabled=true` |
| `GatewayIpFilter` | 全局过滤器 | `HIGHEST_PRECEDENCE + 5` | 支持 CIDR 子网与 IPv4/IPv6 精准黑白名单网络边缘安全防卫，黑名单直接阻断 | `security.ip-filter-enabled=true` |
| `OpenApiGuardFilter` | 响应式 WebFilter | `HIGHEST_PRECEDENCE + 6` | 拦截微服务契约文档路径，提供 DISABLED（404 熔断）、BASIC（认证挑战）、SA_TOKEN（权限鉴权）与 PUBLIC 四模守卫 | `openapi.enabled=true` |
| `SaTokenGatewayFilter` | 全局过滤器 | `ORDER_SA_TOKEN (-100)` | 反应式集中登录认证、白名单放行，校验通过向下游注入 `Same-Token` 内部受信凭据与 `X-User-Id` | `security.sa-token-enabled=true` |
| `GatewayTenantFilter` | 全局过滤器 | `ORDER_TENANT (-90)` | 解析租户标头并挂载上下文，防范未授权多租户跨越攻击 | `tenant.enabled=true` |
| `GatewayTrafficTagFilter` | 全局过滤器 | `ORDER_TRAFFIC_TAG (-80)` | 依托 `TrafficTagResolver` 决议流量染色标签；清洗剥离未授权伪造标头，向下游透传权威标签 | `traffic.enabled=true` |
| `TrafficTagResolver` | 扩展点 SPI | 依赖注入扩展 | 进站流量染色标签决议器扩展点，支持基于会话、租户或业务规则动态染色打标 | `@ConditionalOnMissingBean` 标配守卫 |
| `DefaultTrafficTagResolver` | SPI 默认实现 | 依赖注入扩展 | 默认染色解析器：优先读取标头（`X-Traffic-Tag`），兜底读取 Query 参数（`traffic_tag`） | 条件装配于 `traffic.enabled=true` |
| `GatewaySignGuardFilter` | 全局过滤器 | `ORDER_SA_TOKEN - 1` | 开放接口 API 签名防篡改、时间戳时钟防偏（5分钟窗口）与 Nonce 防重放校验 | `sign.enabled=true`（默认关闭） |
| `GatewayRateLimitResponseFilter`| 全局过滤器 | `Ordered.LOWEST_PRECEDENCE - 10` | 拦截分布式限流产生的 HTTP 429 状态，格式化为标准 Result JSON 响应体并附加 `Retry-After: 1` 标头 | `rate-limiter.enabled=true` |
| `GatewayAccessLogFilter` | 全局过滤器 | `LOWEST_PRECEDENCE` | 基于 Reactor `doFinally` 零阻塞异步度量往返耗时，结构化输出核心审计指标，慢请求打印 WARN 告警 | `access-log.enabled=true`（默认关闭） |
| `AtengRetryGatewayFilterFactory` | 过滤器工厂 | 路由配置 `AtengRetry=1` | 生产级防雪崩安全重试工厂：写请求严格零重试，仅读请求网络建连失败允许弹性重试 1 次 | 标配 `@ConditionalOnMissingBean` |
| `GatewayRouteRefreshListener` | 全局事件监听器 | 监听配置热更事件 | 监听微服务主配置环境变量变更与 Nacos 安全热更事件，增量热绑 `spring.cloud.gateway.routes` 并广播 `RefreshRoutesEvent` | 条件装配于 `dynamic-route.enabled=true` |
| `GatewayRouteValidator` | 校验工具类 | 静态方法调用 | 统一双轨动态路由深度语法语义防卫（ID 唯一、URI 协议头合规、谓词过滤器非空） | 校验失败抛出 `IllegalArgumentException` |
| `NacosRouteDefinitionRepository` | 动态路由仓库 | 依赖 Nacos Config | 基于 Nacos 动态监听独立路由配置（`gateway-routes.json`），校验合法后原子热加载并发布内存事件；配置破损触发快照兜底 | 条件装配于 `NacosConfigManager` |
| `GrayLoadBalancerClientConfiguration` | 负载均衡配置 | 客户端子上下文 | 针对微服务客户端装配复合责任链 (`DiscoveryClient` ➔ `Caching` (3s) ➔ `Gray` ➔ `ZonePreference` ➔ `NacosWarmupLoadBalancer`) | `@LoadBalancerClients` 默认规范配置 |
| `AtengGatewayEnvironmentPostProcessor` | 环境后置处理器 | 容器初始化阶段 | 注入 3s 实例缓存 TTL 与 `spring.cloud.loadbalancer.zone` 属性映射，消除配置覆盖与 Bean 冲突 | `EnvironmentPostProcessor` 自动装配 |
| `GrayServiceInstanceListSupplier`| 负载均衡提供者 | LoadBalancer 客户端 | 精准匹配 Nacos 实例 `metadata.tag`；灰度节点全部下线时在 `fallbackToDefault=true` 下平滑降级至基线实例 | `@LoadBalancerClients` 默认配置 |
| `ClientIpResolver` | 工具类 | 静态方法调用 | 提供权威从右向左代理倒推真实 IP 算法及端口号清洗能力 | 开箱即用工具类 |
| `IpMatcher` | 工具类 | 静态方法调用 | 支持 CIDR 子网段掩码与单 IP 的高效并发匹配 | 开箱即用工具类 |
| `GatewayPanoramaEndpoint` | Actuator 端点 | `@Endpoint(id = "atengGateway")` | 网关全景健康与动态路由只读运维诊断端点，聚合双轨路由、灰度负载、边缘安全、接口验签四维全景指标 | 依赖 Actuator 且 `@ConditionalOnMissingBean` |
| `GatewayPanoramaReport` | 报告模型 Record | 强类型数据传输 | 四维诊断聚合报告模型，包含 `dynamicRoute`、`grayLoadBalancer`、`edgeSecurity`、`signGuard` 快照 | 强类型自洽 Record |
| `GatewayQuerySanitizer` | 工具类 | 静态方法调用 | 提供响应式访问审计 Query 参数脱敏、截断与绝对旁路隔离（内置 12 类敏感词、256 字符上限、malformed 安全降级） | 开箱即用工具类 |

---

### 2. 核心公共 API 与扩展 SPI 速查表

| 类 / 接口 | 方法签名 | 返回类型 | 功能说明与生产语义 | 默认/保底行为 |
| :--- | :--- | :--- | :--- | :--- |
| `IpFilterProvider` | `getBlacklist()` | ``List<String>`` | 获取当前生效的 IP 黑名单规则列表（支持 CIDR） | 默认实现读取配置属性 `ip-blacklist` |
| `IpFilterProvider` | `getWhitelist()` | ``List<String>`` | 获取当前生效的 IP 白名单规则列表（支持 CIDR） | 默认实现读取配置属性 `ip-whitelist` |
| `SignSecretProvider` | `getSecret(appId)` | `String` | 根据应用唯一标识 AppId 获取签名密钥明文 | 默认基于 `InMemorySignSecretProvider` 字典 |
| `GatewayNonceValidator` | `validateAndStore(nonce, ttl)` | `boolean` | 校验并记录 Nonce 唯一性，防御重放攻击 | 默认基于 `InMemoryGatewayNonceValidator` 滑动窗口 |
| `TrafficTagResolver` | `resolveTag(exchange)` | `String` | 根据进站上下文决议全链路灰度染色标签 | 默认实现 `DefaultTrafficTagResolver`（Header > Query） |
| `IpKeyResolver` | `resolve(exchange)` | ``Mono<String>`` | 基于客户端真实公网 IP 进行限流解析（`@Primary` 默认解析器） | 保底返回 `"127.0.0.1"` |
| `UserKeyResolver` | `resolve(exchange)` | ``Mono<String>`` | 基于 Sa-Token 登录用户 ID 进行限流解析 | 未登录平滑降级为 `"anonymous:" + IP` |
| `ApiKeyResolver` | `resolve(exchange)` | ``Mono<String>`` | 基于请求接口路径与客户端 IP 双维度进行限流解析 | 返回 `"path:clientIp"` 复合键 |
| `ClientIpResolver` | `resolveClientIp(req, hops)` | `String` | 从右向左安全倒推解析真实公网客户端 IP | 兜底返回物理 TCP 对端 IP 或 `"127.0.0.1"` |
| `IpMatcher` | `match(pattern, ip)` | `boolean` | 校验指定 IP 是否匹配单个 IP 或 CIDR 网段 | 格式异常时安全拦截返回 `false` |
| `GatewayRouteValidator` | `validateRouteDefinitions(routes)` | `void` | 统一对路由列表执行深度语法语义防卫校验（ID唯一、URI有效、谓词过滤器非空） | 校验失败抛出 `IllegalArgumentException` |
| `GatewayRouteRefreshListener` | `refreshRoutesFromEnvironment(keys)` | `boolean` | 响应环境变量变更事件增量热绑 `spring.cloud.gateway.routes`，触发路由刷新与快照守卫 | 无需刷新返回 `true`，校验失败回退返回 `false` |
| `NacosRouteDefinitionRepository` | `getRouteDefinitions()` | ``Flux<RouteDefinition>`` | 响应式流式读取当前生效的全量路由定义 | 离线返回空 Flux，不阻塞容器启动 |
| `NacosRouteDefinitionRepository` | `save(route)` | ``Mono<Void>`` | 内存动态追加或覆盖指定路由定义并发布刷新事件 | 操作完成后原子广播刷新 |
| `NacosRouteDefinitionRepository` | `delete(routeId)` | ``Mono<Void>`` | 内存动态移除指定路由定义并发布刷新事件 | 找不到时静默完成 |
| `GatewayPanoramaEndpoint` | `report()` | `GatewayPanoramaReport` | 读取微秒级内存原子快照，聚合输出网关四维全景健康与动态路由诊断状态 | 零外部网络 IO，快照异常平滑标记 DEGRADED |
| `GatewayQuerySanitizer` | `sanitize(rawQuery, customMaskKeys)` | `String` | 安全解析并脱敏 Query 参数，超过 256 字符追加 `...(truncated)`，遇到畸形编码安全降级返回 `[malformed-query]` | 零异常穿透，保底返回 `-` |

---

### 3. 配置属性速查表 (`ateng.cloud.gateway.*`)

| 配置项 | 类型 | 默认值 | 详细业务说明 |
| :--- | :--- | :--- | :--- |
| `ateng.cloud.gateway.enabled` | `boolean` | `true` | 是否激活 ateng-cloud 响应式网关基础设施底座 |
| `ateng.cloud.gateway.max-in-memory-size` | `DataSize` | `2MB` | Netty 堆外直接内存请求体硬限制（超限直接 413 熔断，大文件走 S3 预签名直传） |
| `ateng.cloud.gateway.connect-timeout` | `Duration` | `3s` | 下游微服务默认连接超时阈值（快速失败） |
| `ateng.cloud.gateway.response-timeout` | `Duration` | `10s` | 下游微服务默认读取响应超时阈值 |
| `ateng.cloud.gateway.security.enabled` | `boolean` | `true` | 是否激活网关边缘安全综合防卫体系 |
| `ateng.cloud.gateway.security.path-validation-enabled` | `boolean` | `true` | 是否开启 URI 规范化与目录穿越防卫 |
| `ateng.cloud.gateway.security.trace-enabled` | `boolean` | `true` | 是否开启响应式全链路追踪 TraceId 生成与透传 |
| `ateng.cloud.gateway.security.header-sanitize-enabled` | `boolean` | `true` | 是否开启入站伪造受信标头物理剥离清洗 |
| `ateng.cloud.gateway.security.client-ip-enabled` | `boolean` | `true` | 是否开启客户端真实公网 IP 解析 |
| `ateng.cloud.gateway.security.trusted-proxy-count` | `int` | `1` | 前置反向代理层数（1 表示网关前仅有一层 Nginx/SLB） |
| `ateng.cloud.gateway.security.ip-filter-enabled` | `boolean` | `true` | 是否开启 IP 黑白名单防卫过滤器 |
| `ateng.cloud.gateway.security.ip-blacklist` | ``List<String>`` | `[]` | 封禁的客户端 IP 或 CIDR 子网列表 |
| `ateng.cloud.gateway.security.ip-whitelist` | ``List<String>`` | `[]` | 允许通行的客户端 IP 或 CIDR 子网列表（非空时仅白名单通行） |
| `ateng.cloud.gateway.security.sa-token-enabled` | `boolean` | `true` | 是否激活 Sa-Token 网关层集中鉴权 |
| `ateng.cloud.gateway.security.same-token-enabled` | `boolean` | `true` | 是否向下游微服务自动签署注入内部同源受信凭证（`Same-Token`） |
| `ateng.cloud.gateway.security.exclude-paths` | ``List<String>`` | `[]` | 鉴权白名单开放路径列表（支持 AntPath，如 `/api/auth/login`） |
| `ateng.cloud.gateway.dynamic-route.enabled` | `boolean` | `true` | 是否开启基于 Nacos 配置中心的动态路由监听与热更新 |
| `ateng.cloud.gateway.dynamic-route.data-id` | `String` | `gateway-routes.json` | 动态路由定义在 Nacos 中的 Data ID |
| `ateng.cloud.gateway.dynamic-route.group` | `String` | `DEFAULT_GROUP` | 动态路由在 Nacos 中的配置分组 Group |
| `ateng.cloud.gateway.dynamic-route.timeout` | `Duration` | `3s` | 从 Nacos 拉取初始动态路由的超时时限 |
| `ateng.cloud.gateway.traffic.enabled` | `boolean` | `true` | 是否激活全链路流量染色与金丝雀灰度路由 |
| `ateng.cloud.gateway.traffic.fallback-to-default` | `boolean` | `true` | 当灰度实例全下线时，是否自动平滑降级调度至基线默认实例（杜绝 503 报警） |
| `ateng.cloud.gateway.traffic.cache-ttl` | `Duration` | `3s` | 负载均衡实例列表本地缓存生存时间 TTL（敏捷感知节点变更） |
| `ateng.cloud.gateway.security-headers.enabled` | `boolean` | `true` | 是否开启出站 OWASP 等保三级合规安全响应头注入与 CORS 去重 |
| `ateng.cloud.gateway.security-headers.content-type-options` | `String` | `nosniff` | 防 MIME 类型嗅探安全标头 |
| `ateng.cloud.gateway.security-headers.frame-options` | `String` | `SAMEORIGIN` | 防点击劫持 iframe 嵌入标头 |
| `ateng.cloud.gateway.security-headers.xss-protection` | `String` | `1; mode=block` | 浏览器 XSS 过滤器阻断标头 |
| `ateng.cloud.gateway.security-headers.hsts` | `String` | `max-age=31536000; includeSubDomains` | 强制安全传输 HSTS 标头 |
| `ateng.cloud.gateway.security-headers.referrer-policy` | `String` | `strict-origin-when-cross-origin` | 来源跳转安全策略标头 |
| `ateng.cloud.gateway.openapi.enabled` | `boolean` | `true` | 是否激活微服务接口文档统一聚合与守卫 |
| `ateng.cloud.gateway.openapi.path` | `String` | `/docs` | 文档统一入口路径（访问时自动 302 重定向至目标路径） |
| `ateng.cloud.gateway.openapi.target-path` | `String` | `/swagger-ui.html` | 文档跳转目标路径 |
| `ateng.cloud.gateway.openapi.required-role` | `String` | `null` | SA_TOKEN 模式下要求的管理员角色（配置则校验 hasRole） |
| `ateng.cloud.gateway.openapi.required-permission` | `String` | `null` | SA_TOKEN 模式下要求的管理员权限（配置则校验 hasPermission） |
| `ateng.cloud.gateway.openapi.guard-mode` | `GuardMode` | `PUBLIC` | 文档安全守卫模式：`DISABLED`（404 熔断）、`BASIC`（弹窗挑战）、`SA_TOKEN`、`PUBLIC` |
| `ateng.cloud.gateway.openapi.basic-auth.username` | `String` | `admin` | BASIC 模式下的 HTTP Basic 认证用户名 |
| `ateng.cloud.gateway.openapi.basic-auth.password` | `String` | `admin` | BASIC 模式下的 HTTP Basic 认证密码 |
| `ateng.cloud.gateway.rate-limiter.enabled` | `boolean` | `true` | 是否开启 Redis 分布式限流多维解析器与 429 统一转译 |
| `ateng.cloud.gateway.access-log.enabled` | `boolean` | `false` | 是否开启轻量响应式访问日志审计 |
| `ateng.cloud.gateway.access-log.slow-threshold` | `Duration` | `3s` | 慢请求告警阈值（超过该阈值输出 WARN 级别慢请求日志） |
| `ateng.cloud.gateway.access-log.exclude-paths` | ``List<String>`` | `[/actuator/**, /favicon.ico]` | 排除访问日志记录的路径列表 |
| `ateng.cloud.gateway.access-log.include-query` | `boolean` | `false` | 是否在轻量响应式访问日志中记录 Query 参数（默认 false） |
| `ateng.cloud.gateway.access-log.mask-keys` | ``List<String>`` | `[]` | 自定义敏感 Query 参数名脱敏列表（不区分大小写，值替换为 `***`） |
| `ateng.cloud.gateway.sign.enabled` | `boolean` | `false` | 是否开启开放接口 API 数字签名防篡改与 Nonce 防重放 |
| `ateng.cloud.gateway.sign.body-enabled` | `boolean` | `false` | 是否开启开放接口请求体 (Request Body) SHA-256 摘要加签防篡改 |
| `ateng.cloud.gateway.sign.max-body-size` | `DataSize` | `2MB` | 允许加签的最大请求体容量（超过直接 413 载荷防爆熔断） |
| `ateng.cloud.gateway.sign.timestamp-threshold` | `Duration` | `5m` | 签名时间戳允许的最大时钟偏差窗口 |
| `ateng.cloud.gateway.sign.include-paths` | ``List<String>`` | `[]` | 需进行签名校验的目标路径列表（支持 AntPath，为空对全量生效） |
| `ateng.cloud.gateway.sign.exclude-paths` | ``List<String>`` | `[]` | 签名校验排除放行路径列表（支持 AntPath） |
| `ateng.cloud.gateway.tenant.enabled` | `boolean` | `true` | 是否开启入站多租户标头解析与防跨越隔离 |
| `ateng.cloud.gateway.tenant.header-name` | `String` | `X-Tenant-Id` | 入站请求租户标识标头名称 |

---

## 第 1 章：接入认证与零信任网关防穿透

网关作为微服务全集群唯一的公网暴露面，负责公网客户端集中鉴权；校验通过后向下游请求自动注入内部受信凭据 `Same-Token` 与用户上下文；内部微服务强制校验同源凭据，凡绕过网关直连内网端口的请求直接 Fail-Fast 401 阻断。

### 1.1 本特性专属 YAML 配置

```yaml
ateng:
  cloud:
    gateway:
      security:
        enabled: true
        sa-token-enabled: true
        same-token-enabled: true
        exclude-paths:
          - "/api/auth/login"
          - "/api/auth/inspect-context"
          - "/api/auth/logout"
sa-token:
  token-name: satoken
  timeout: 86400
  same-token:
    project-key: ateng-cloud-test-secret-key-123456
```

### 1.2 真实微服务业务 Java 代码范式

```java
@RestController
@RequestMapping("/sample/cloud/gateway/auth")
public class GatewayAuthController {

    @PostMapping("/login")
    public Mono<Result<AuthTokenVO>> login(
            @RequestParam(value = "username", defaultValue = "ateng") String username,
            @RequestParam(value = "userId", defaultValue = "10001") String userId,
            @RequestParam(value = "tenantId", defaultValue = "tenant-001") String tenantId) {
        StpUtil.login(userId);
        StpUtil.getSession().set("tenantId", tenantId);
        String tokenValue = StpUtil.getTokenValue();
        return Mono.just(Result.success(new AuthTokenVO(tokenValue, userId, tenantId, 86400L)));
    }

    @GetMapping("/user-info")
    public Mono<Result<UserInfoVO>> getUserInfo() {
        String loginId = (String) StpUtil.getLoginId();
        String tenantId = (String) StpUtil.getSession().get("tenantId");
        return Mono.just(Result.success(new UserInfoVO(loginId, "Ateng User", tenantId, List.of("ROLE_ADMIN"))));
    }

    @GetMapping("/inspect-context")
    public Mono<Result<GatewaySecurityContextVO>> inspectContext(ServerHttpRequest request) {
        String userId = request.getHeaders().getFirst(GatewayConstants.X_USER_ID);
        String tenantId = request.getHeaders().getFirst(GatewayConstants.X_TENANT_ID);
        String sameToken = request.getHeaders().getFirst(GatewayConstants.SAME_TOKEN);
        return Mono.just(Result.success(new GatewaySecurityContextVO(userId, tenantId, sameToken, true)));
    }
}
```

### 1.3 核心机制与微服务容灾边界说明

- **零信任内网防穿透铁律**：`SaTokenGatewayFilter` 在公网流量校验成功后向下游自动注入内部凭证 `Same-Token`；所有下游微服务强制开启 `sa-token.same-token.check=true`，凡绕过网关直连内网端口的非法流量，直接 Fail-Fast 401 阻断。
- **公网入站伪造标头物理剥离**：`GatewayHeaderSanitizeFilter` 在最前置网络流水线将公网客户端私自伪造的 `X-User-Id`、`X-Tenant-Id`、`Same-Token` 彻底剥离，杜绝标头走私欺诈。

---

## 第 2 章：现代动态路由热重载与双轨共存

遵循 Spring Boot 3 规范与 Nacos 现代配置加载，彻底废弃过时的 `bootstrap.yml`。提供 Track A 独立 Nacos 路由数据源与 Track B 微服务主配置热绑双轨体系，辅以深度语法语义防卫与快照容错守卫。

### 2.1 本特性专属 YAML 配置

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: order-dispatch
          uri: forward:/sample/cloud/gateway/route/order-dispatch
          predicates:
            - Path=/api/order/dispatch
        - id: route-definitions
          uri: forward:/sample/cloud/gateway/route/definitions
          predicates:
            - Path=/api/route/definitions

ateng:
  cloud:
    gateway:
      dynamic-route:
        enabled: true
        data-id: gateway-routes.json
        group: DEFAULT_GROUP
        timeout: 3s
```

### 2.2 真实微服务业务 Java 代码范式

```java
@RestController
@RequestMapping("/sample/cloud/gateway/route")
public class GatewayRouteTrafficController {

    private final RouteDefinitionLocator routeDefinitionLocator;
    private final ApplicationEventPublisher eventPublisher;

    public GatewayRouteTrafficController(RouteDefinitionLocator routeDefinitionLocator,
                                         ApplicationEventPublisher eventPublisher) {
        this.routeDefinitionLocator = routeDefinitionLocator;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping("/definitions")
    public Mono<Result<List<RouteDefinitionVO>>> getRouteDefinitions() {
        return routeDefinitionLocator.getRouteDefinitions()
                .map(rd -> new RouteDefinitionVO(
                        rd.getId(),
                        rd.getUri() != null ? rd.getUri().toString() : "",
                        rd.getOrder(),
                        rd.getPredicates() != null ? rd.getPredicates().stream().map(Object::toString).toList() : List.of()
                ))
                .collectList()
                .map(Result::success);
    }

    @PostMapping("/refresh")
    public Mono<Result<RouteRefreshResultVO>> refreshRoutes() {
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
        return routeDefinitionLocator.getRouteDefinitions().count()
                .map(count -> Result.success(new RouteRefreshResultVO("SUCCESS", "HEALTHY", count)));
    }
}
```

### 2.3 核心机制与微服务容灾边界说明

- **双轨快照守卫容错与多源共存**：`GatewayRouteValidator` 统一实施语法语义深度校验（ID 唯一、URI 协议头有效、谓词非空）。当 Nacos 推送畸形破损配置时，立即拦截并自动保留上一份有效快照（Last-Known Good Snapshot），保障现有生效路由稳定服务，更新过程零丢包。
- **免重启原子增量热更**：`GatewayRouteRefreshListener` 动态监听主配置环境变量变更与 Nacos 配置安全热更事件，增量重绑 `spring.cloud.gateway.routes` 并广播 `RefreshRoutesEvent`，彻底终结修改配置必须重启网关的历史痛点。

---

## 第 3 章：流量染色与金丝雀灰度分流治理

支持跨进程四维上下文与灰度染色标记透明透传，实现分层加权智能路由；在灰度节点全部下线时提供平滑无损降级回退机制，杜绝 503 生产报警。

### 3.1 本特性专属 YAML 配置

```yaml
ateng:
  cloud:
    gateway:
      traffic:
        enabled: true
        fallback-to-default: true
        cache-ttl: 3s
spring:
  cloud:
    loadbalancer:
      cache:
        ttl: 3s
```

### 3.2 真实微服务业务 Java 代码范式

```java
@Configuration(proxyBeanMethods = false)
public class CustomTrafficTagResolverConfiguration {

    @Bean
    public TrafficTagResolver customTrafficTagResolver() {
        return exchange -> {
            if (exchange == null || exchange.getRequest() == null) {
                return null;
            }
            // 业务自定义规则：根据用户 VIP 等级决议染色泳道
            String userTier = exchange.getRequest().getHeaders().getFirst("X-User-Tier");
            if ("VIP".equalsIgnoreCase(userTier)) {
                return "vip-lane";
            }
            // 非 VIP 请求返回 null，网关将自动物理剥离伪造的 X-Traffic-Tag 标头
            return null;
        };
    }
}
```

### 3.3 核心机制与微服务容灾边界说明

- **机房亲和性加权分流与无损降级**：网关构建复合责任链 (`DiscoveryClient` ➔ `Caching` (3s) ➔ `Gray` ➔ `ZonePreference` ➔ `NacosWarmupLoadBalancer`)。调度引擎先按 `X-Traffic-Tag` 锁定目标版本池，再在池内优先筛选同机房健康可用节点；灰度实例全宕且 `fallback-to-default=true` 时，平滑降级调度至基线实例，杜绝 503 宕机报错。
- **3 秒实例缓存 TTL 极速感知**：默认注入 `cache.ttl: 3s`，解决微服务滚动发布时下线节点感知迟滞引发的流量黑洞。

---

## 第 4 章：边缘安全防卫与开放平台接口加签

针对公网暴露面提供等保三级安全合规响应头出站强化、载荷防爆硬防御、目录穿越 Fail-Fast 400 阻断、IP 黑名单 403 阻断及开放平台 HMAC-SHA256 签名校验与防重放。

### 4.1 本特性专属 YAML 配置

```yaml
ateng:
  cloud:
    gateway:
      max-in-memory-size: 2MB
      sign:
        enabled: true
        body-enabled: true
        timestamp-threshold: 5m
        include-paths:
          - "/api/open/**"
      security:
        enabled: true
        trusted-proxy-count: 1
        exclude-paths:
          - "/api/open/**"
          - "/api/security/**"
        ip-blacklist:
          - "192.168.100.1"
          - "10.99.0.0/16"
      security-headers:
        enabled: true
```

### 4.2 真实微服务业务 Java 代码范式

```java
@RestController
@RequestMapping("/sample/cloud/gateway/security")
public class GatewaySecurityGuardController {

    @PostMapping("/open/merchant/sync-order")
    public Mono<Result<OpenApiOrderSyncVO>> syncMerchantOrder(
            @RequestBody(required = false) Mono<OpenApiOrderSyncDTO> bodyMono) {
        return bodyMono.map(dto -> Result.success(new OpenApiOrderSyncVO(
                "SYNC-" + dto.getOrderId(),
                dto.getOrderId(),
                "SYNCED",
                System.currentTimeMillis(),
                true
        ))).defaultIfEmpty(Result.success(new OpenApiOrderSyncVO(
                "SYNC-EMPTY", "EMPTY", "EMPTY_BODY", System.currentTimeMillis(), true)));
    }

    @PostMapping("/payload-guard")
    public Mono<Result<PayloadResultVO>> payloadGuard(@RequestBody(required = false) Mono<byte[]> bodyMono) {
        return bodyMono.map(bytes -> Result.success(new PayloadResultVO(bytes.length, 2 * 1024 * 1024L, true)))
                .defaultIfEmpty(Result.success(new PayloadResultVO(0, 2 * 1024 * 1024L, true)));
    }
}
```

```java
// 生产环境通过 SPI 注册密钥管理
@Bean
public SignSecretProvider sampleSignSecretProvider() {
    InMemorySignSecretProvider provider = new InMemorySignSecretProvider();
    provider.registerSecret("sample-app", "sample-secret-123456");
    return provider;
}
```

### 4.3 核心机制与微服务容灾边界说明

- **2MB 载荷硬防爆与 S3 预签名直传**：Netty 堆外直接内存请求体硬限制 2MB。大文件、视频绝对禁止流经网关内存，100% 依托 `ateng-boot-storage` 获取 S3 Presigned URL 旁路直传。超限报文直接拦截并返回 `HTTP 413 Payload Too Large`。
- **HMAC-SHA256 签名与堆外内存确定性释放**：基于 `ServerHttpRequestDecorator` 安全缓存 Body 并计算 SHA-256 摘要（参与签名串比对）；挂载 Reactor `doFinally` 确定性触发 `DataBufferUtils.release`，杜绝堆外内存泄漏。

---

## 第 5 章：微服务弹性容灾与防雪崩重试

恪守“写请求绝对禁止重试不变量”，防范网络抖动下的下游重复落库与重试风暴；同时对读请求提供弹性容错，对下游网络中断提供 HTTP 502 语义化统一转译。

### 5.1 本特性专属 YAML 配置

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: resilience-query-inventory
          uri: forward:/sample/cloud/gateway/resilience/query-inventory
          predicates:
            - Path=/api/resilience/query-inventory
          filters:
            - AtengRetry=1
        - id: resilience-create-order
          uri: forward:/sample/cloud/gateway/resilience/create-order
          predicates:
            - Path=/api/resilience/create-order
          filters:
            - AtengRetry=1
```

### 5.2 真实微服务业务 Java 代码范式

```java
@RestController
@RequestMapping("/sample/cloud/gateway/resilience")
public class GatewayResilienceController {

    private final AtomicInteger getRetryCounter = new AtomicInteger(0);
    private final AtomicInteger postRetryCounter = new AtomicInteger(0);

    @GetMapping("/query-inventory")
    public Mono<Result<InventoryStockVO>> queryInventory(
            @RequestParam(value = "skuCode", defaultValue = "SKU-IPHONE-16") String skuCode) throws Exception {
        int count = getRetryCounter.incrementAndGet();
        if (count == 1) {
            throw new ConnectException("模拟底层网络建连超时");
        }
        return Mono.just(Result.success(new InventoryStockVO(skuCode, 99, count, "SUCCESS_AFTER_RETRY")));
    }

    @PostMapping("/create-order")
    public Mono<Result<RetryStatVO>> createOrder() throws Exception {
        postRetryCounter.incrementAndGet();
        throw new ConnectException("模拟非幂等写操作底层建连失败，严格禁止重试");
    }

    @GetMapping("/simulate-node-restart")
    public Mono<Result<Void>> simulateNodeRestart(
            @RequestParam(value = "type", defaultValue = "premature") String type) {
        if ("closed".equalsIgnoreCase(type)) {
            return Mono.error(new java.nio.channels.ClosedChannelException());
        }
        return Mono.error(reactor.netty.http.client.PrematureCloseException.TEST_EXCEPTION);
    }
}
```

### 5.3 核心机制与微服务容灾边界说明

- **写请求绝对禁止重试铁律**：`AtengRetryGatewayFilterFactory` 强制锁定非幂等写请求（POST/PUT）重试次数为 0，底层失败执行次数必须为 1，阻断级联雪崩；仅针对 GET 读请求建连超时允许弹性重试 1 次（执行次数=2）。
- **滚动重启 502 Bad Gateway 统一转译**：当下游微服务滚动发布中途发生 Netty `PrematureCloseException` 或 `ClosedChannelException` 时，网关统一转译为 HTTP 502 规范 JSON 并绑定 TraceId，严禁泄漏底层网络堆栈。
- **限流 429 契约标准化**：拦截高并发限流触发的 HTTP 429，格式化输出统一 `Result.fail(429, "当前访问过于频繁，请稍后重试")`，并附带 `Retry-After: 1` 响应头引导退避。

---

## 第 6 章：原生响应式流式直通与接口文档守卫

提供基于 Server-Sent Events (SSE) 的实时低延迟打字机流式体验，以及针对微服务 OpenAPI / Swagger 文档的多模式安全防护。

### 6.1 本特性专属 YAML 配置

```yaml
ateng:
  cloud:
    gateway:
      openapi:
        enabled: true
        path: /docs
        target-path: /swagger-ui.html
        guard-mode: PUBLIC
```

### 6.2 真实微服务业务 Java 代码范式

```java
@RestController
@RequestMapping("/sample/cloud/gateway/resilience")
public class GatewayResilienceController {

    @GetMapping(value = "/order-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> orderEvents() {
        return Flux.range(0, 3)
                .delayElements(Duration.ofMillis(20))
                .map(seq -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(seq))
                        .event("order-track")
                        .data("chunk-" + seq)
                        .build());
    }
}
```

### 6.3 核心机制与微服务容灾边界说明

- **SSE 原生流式零缓冲直通**：利用响应式 Reactor 事件流，`text/event-stream` 随到随推，避免中间代理缓冲造成流式输出卡顿。
- **OpenAPI 四模守卫**：支持 `DISABLED`（生产 404 熔断屏蔽）、`BASIC`（HTTP 弹窗认证挑战）、`SA_TOKEN`（校验管理员角色/权限）与 `PUBLIC`（开发测试环境免密直连）四模策略。

---

## 第 7 章：响应式可观测性、安全审计与全景诊断

全链路分布式追踪底座零冗余收敛，基于 W3C 标准 TraceId 与响应式无锁访问日志审计，提供微秒级只读内存全景诊断端点。

### 7.1 本特性专属 YAML 配置

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,atengGateway

ateng:
  cloud:
    gateway:
      access-log:
        enabled: true
        slow-threshold: 3s
        include-query: true
        mask-keys:
          - customSecret
          - cvv
```

### 7.2 真实微服务业务 Java 代码范式与诊断样例

```java
@ParameterizedTest
@ValueSource(strings = {"/actuator/atengGateway", "/actuator/ateng-gateway"})
@DisplayName("测试网关全景诊断端点双命名别名路由均平滑返回 HTTP 200 与脱敏结构化报告")
void testGatewayPanoramaEndpointReturns200AndStructuredReport(String endpointUri) {
    webTestClient.get()
            .uri(endpointUri)
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.status").isEqualTo("UP")
            .jsonPath("$.gatewayId").isEqualTo("sample-cloud-gateway")
            .jsonPath("$.dynamicRoute.totalRouteCount").isNumber()
            .jsonPath("$.dynamicRoute.mainConfigRoute.snapshotHealthy").isEqualTo(true)
            .jsonPath("$.grayLoadBalancer.enabled").isEqualTo(true)
            .jsonPath("$.edgeSecurity.enabled").isEqualTo(true)
            .jsonPath("$.edgeSecurity.ipBlacklistCount").isEqualTo(2)
            .jsonPath("$.signGuard.enabled").isEqualTo(true);
}
```

诊断报告响应结构（截选）：
```json
{
  "status": "UP",
  "gatewayId": "sample-cloud-gateway",
  "dynamicRoute": {
    "enabled": true,
    "totalRouteCount": 15,
    "mainConfigRoute": {
      "snapshotHealthy": true,
      "lastRefreshSuccess": true
    }
  },
  "grayLoadBalancer": {
    "enabled": true,
    "fallbackToDefault": true,
    "cacheTtl": "3s"
  },
  "edgeSecurity": {
    "enabled": true,
    "ipBlacklistCount": 2,
    "ipWhitelistCount": 0
  },
  "signGuard": {
    "enabled": true,
    "bodyEnabled": true
  }
}
```

### 7.3 核心机制与微服务容灾边界说明

- **访问审计参数脱敏与防爆截断**：内置 12 类敏感参数名脱敏，超长 Query 超过 256 字符追加 `...(truncated)`，遇到非法字符平滑降级输出 `[malformed-query]`，绝不中断正常业务链路。
- **微秒级只读内存原子快照**：`/actuator/atengGateway` 耗时 <100 微秒，零外部网络 IO；规则仅输出数量统计，严禁泄漏具体 IP 与私钥明文。

---

## 第 8 章：生产全量配置属性速查附录

在微服务配置中心（Nacos）或本地 `application.yml` 中配置如下完整参数：

```yaml
server:
  port: 9000

spring:
  application:
    name: sample-cloud-gateway
  main:
    web-application-type: reactive
  cloud:
    gateway:
      routes:
        - id: auth-login
          uri: forward:/sample/cloud/gateway/auth/login
          predicates:
            - Path=/api/auth/login
        - id: order-dispatch
          uri: forward:/sample/cloud/gateway/route/order-dispatch
          predicates:
            - Path=/api/order/dispatch
        - id: open-sync-order
          uri: forward:/sample/cloud/gateway/security/open/merchant/sync-order
          predicates:
            - Path=/api/open/merchant/sync-order
        - id: resilience-query-inventory
          uri: forward:/sample/cloud/gateway/resilience/query-inventory
          predicates:
            - Path=/api/resilience/query-inventory
          filters:
            - AtengRetry=1
    loadbalancer:
      cache:
        ttl: 3s

ateng:
  cloud:
    gateway:
      enabled: true
      max-in-memory-size: 2MB
      connect-timeout: 3s
      response-timeout: 10s
      access-log:
        enabled: true
        slow-threshold: 3s
        include-query: true
        mask-keys:
          - customSecret
          - cvv
      sign:
        enabled: true
        body-enabled: true
        timestamp-threshold: 5m
        include-paths:
          - "/api/open/**"
      security:
        enabled: true
        trusted-proxy-count: 1
        exclude-paths:
          - "/api/open/**"
          - "/api/security/**"
          - "/sample/cloud/gateway/security/**"
          - "/api/resilience/**"
          - "/sample/cloud/gateway/resilience/**"
          - "/api/auth/login"
          - "/sample/cloud/gateway/auth/login"
          - "/api/auth/inspect-context"
          - "/sample/cloud/gateway/auth/inspect-context"
          - "/api/auth/logout"
          - "/sample/cloud/gateway/auth/logout"
          - "/api/order/**"
          - "/sample/cloud/gateway/route/**"
          - "/api/route/**"
          - "/api/lb/**"
          - "/actuator/**"
        ip-blacklist:
          - "192.168.100.1"
          - "10.99.0.0/16"
      traffic:
        enabled: true
        fallback-to-default: true
        cache-ttl: 3s
      security-headers:
        enabled: true
      openapi:
        enabled: true
        path: /docs
        target-path: /swagger-ui.html
        guard-mode: PUBLIC

sa-token:
  token-name: satoken
  timeout: 86400
  same-token:
    project-key: ateng-cloud-test-secret-key-123456

management:
  endpoints:
    web:
      exposure:
        include: health,info,atengGateway
```
