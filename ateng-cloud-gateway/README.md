# ateng-cloud-gateway

面向高可用微服务架构的 Spring Cloud 纯响应式 API 网关基础设施 Starter，基于 Spring WebFlux 与 Netty 事件循环全无锁响应式构建（绝对物理隔离 Servlet 阻塞栈）。

开箱集成 Netty 2MB 载荷硬防爆、Sa-Token 零信任集中鉴权与 Same-Token 内部受信凭据签发、Nacos 动态路由无感原子热更与快照容错、金丝雀灰度无损降级回退、分布式多维限流与 429 统一转译、OWASP 等保三级合规安全响应头强化与 CORS 标头去重、微服务契约文档四模守卫、轻量响应式访问日志审计及开放接口防篡改签名。

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

---

### 2. 核心公共 API 与扩展 SPI 速查表

| 类 / 接口 | 方法签名 | 返回类型 | 功能说明与生产语义 | 默认/保底行为 |
| :--- | :--- | :--- | :--- | :--- |
| `IpFilterProvider` | `getBlacklist()` | `List<String>` | 获取当前生效的 IP 黑名单规则列表（支持 CIDR） | 默认实现读取配置属性 `ip-blacklist` |
| `IpFilterProvider` | `getWhitelist()` | `List<String>` | 获取当前生效的 IP 白名单规则列表（支持 CIDR） | 默认实现读取配置属性 `ip-whitelist` |
| `SignSecretProvider` | `getSecret(appId)` | `String` | 根据应用唯一标识 AppId 获取签名密钥明文 | 默认基于 `InMemorySignSecretProvider` 字典 |
| `GatewayNonceValidator` | `validateAndStore(nonce, ttl)` | `boolean` | 校验并记录 Nonce 唯一性，防御重放攻击 | 默认基于 `InMemoryGatewayNonceValidator` 滑动窗口 |
| `TrafficTagResolver` | `resolveTag(exchange)` | `String` | 根据进站上下文决议全链路灰度染色标签 | 默认实现 `DefaultTrafficTagResolver`（Header > Query） |
| `IpKeyResolver` | `resolve(exchange)` | `Mono<String>` | 基于客户端真实公网 IP 进行限流解析（`@Primary` 默认解析器） | 保底返回 `"127.0.0.1"` |
| `UserKeyResolver` | `resolve(exchange)` | `Mono<String>` | 基于 Sa-Token 登录用户 ID 进行限流解析 | 未登录平滑降级为 `"anonymous:" + IP` |
| `ApiKeyResolver` | `resolve(exchange)` | `Mono<String>` | 基于请求接口路径与客户端 IP 双维度进行限流解析 | 返回 `"path:clientIp"` 复合键 |
| `ClientIpResolver` | `resolveClientIp(req, hops)` | `String` | 从右向左安全倒推解析真实公网客户端 IP | 兜底返回物理 TCP 对端 IP 或 `"127.0.0.1"` |
| `IpMatcher` | `match(pattern, ip)` | `boolean` | 校验指定 IP 是否匹配单个 IP 或 CIDR 网段 | 格式异常时安全拦截返回 `false` |
| `GatewayRouteValidator` | `validateRouteDefinitions(routes)` | `void` | 统一对路由列表执行深度语法语义防卫校验（ID唯一、URI有效、谓词过滤器非空） | 校验失败抛出 `IllegalArgumentException` |
| `GatewayRouteRefreshListener` | `refreshRoutesFromEnvironment(keys)` | `boolean` | 响应环境变量变更事件增量热绑 `spring.cloud.gateway.routes`，触发路由刷新与快照守卫 | 无需刷新返回 `true`，校验失败回退返回 `false` |
| `NacosRouteDefinitionRepository` | `getRouteDefinitions()` | `Flux<RouteDefinition>` | 响应式流式读取当前生效的全量路由定义 | 离线返回空 Flux，不阻塞容器启动 |
| `NacosRouteDefinitionRepository` | `save(route)` | `Mono<Void>` | 内存动态追加或覆盖指定路由定义并发布刷新事件 | 操作完成后原子广播刷新 |
| `NacosRouteDefinitionRepository` | `delete(routeId)` | `Mono<Void>` | 内存动态移除指定路由定义并发布刷新事件 | 找不到时静默完成 |
| `GatewayPanoramaEndpoint` | `report()` | `GatewayPanoramaReport` | 读取微秒级内存原子快照，聚合输出网关四维全景健康与动态路由诊断状态 | 零外部网络 IO，快照异常平滑标记 DEGRADED |

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
| `ateng.cloud.gateway.security.ip-blacklist` | `List<String>` | `[]` | 封禁的客户端 IP 或 CIDR 子网列表 |
| `ateng.cloud.gateway.security.ip-whitelist` | `List<String>` | `[]` | 允许通行的客户端 IP 或 CIDR 子网列表（非空时仅白名单通行） |
| `ateng.cloud.gateway.security.sa-token-enabled` | `boolean` | `true` | 是否激活 Sa-Token 网关层集中鉴权 |
| `ateng.cloud.gateway.security.same-token-enabled` | `boolean` | `true` | 是否向下游微服务自动签署注入内部同源受信凭证（`Same-Token`） |
| `ateng.cloud.gateway.security.exclude-paths` | `List<String>` | `[]` | 鉴权白名单开放路径列表（支持 AntPath，如 `/api/auth/login`） |
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
| `ateng.cloud.gateway.access-log.exclude-paths` | `List<String>` | `[/actuator/**, /favicon.ico]` | 排除访问日志记录的路径列表 |
| `ateng.cloud.gateway.sign.enabled` | `boolean` | `false` | 是否开启开放接口 API 数字签名防篡改与 Nonce 防重放 |
| `ateng.cloud.gateway.sign.timestamp-threshold` | `Duration` | `5m` | 签名时间戳允许的最大时钟偏差窗口 |
| `ateng.cloud.gateway.sign.include-paths` | `List<String>` | `[]` | 需进行签名校验的目标路径列表（支持 AntPath，为空对全量生效） |
| `ateng.cloud.gateway.sign.exclude-paths` | `List<String>` | `[]` | 签名校验排除放行路径列表（支持 AntPath） |
| `ateng.cloud.gateway.tenant.enabled` | `boolean` | `true` | 是否开启入站多租户标头解析与防跨越隔离 |
| `ateng.cloud.gateway.tenant.header-name` | `String` | `X-Tenant-Id` | 入站请求租户标识标头名称 |

---

## 维度一：最小起步体验

### 1. 引入依赖
在网关微服务工程的 `pom.xml` 中引入 `ateng-cloud-gateway`（依赖版本由父 POM 或 `ateng-cloud-bom` 统一纳管，子工程无需填写版本号）：

```xml
<dependencies>
    <!-- ateng-cloud 响应式微服务网关起步依赖 (纯 WebFlux 栈，严禁引入 ateng-boot-web) -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-cloud-gateway</artifactId>
    </dependency>

    <!-- Nacos 服务注册发现与动态路由配置支持 (可选) -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-cloud-nacos</artifactId>
    </dependency>

    <!-- Spring Cloud 负载均衡器 -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-loadbalancer</artifactId>
    </dependency>
</dependencies>
```

### 2. 最小 YAML 配置 (100% 逆向提取自 sample-cloud-gateway)
在 `src/main/resources/application.yml` 中声明网关路由与基础安全参数：

```yaml
server:
  port: 8080

spring:
  application:
    name: sample-cloud-gateway
  cloud:
    gateway:
      routes:
        - id: user-service-route
          uri: lb://user-service
          predicates:
            - Path=/api/user/**
          filters:
            - StripPrefix=1
        - id: order-service-route
          uri: lb://order-service
          predicates:
            - Path=/api/order/**
          filters:
            - StripPrefix=1
            - AtengRetry=1

ateng:
  cloud:
    gateway:
      enabled: true
      max-in-memory-size: 2MB
      security:
        enabled: true
        sa-token-enabled: true
        same-token-enabled: true
        exclude-paths:
          - "/api/auth/login"
          - "/api/public/**"
          - "/docs/**"
          - "/actuator/**"
      traffic:
        enabled: true
        fallback-to-default: true
      security-headers:
        enabled: true
```

### 3. 主启动类
标准 Spring Boot 3 响应式启动类，开箱即用：

```java
package io.github.atengk.cloud.sample.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SampleGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleGatewayApplication.class, args);
    }
}
```

---

## 维度二：生产扩展接管

框架遵循防御性装配原则，所有对外暴露的核心组件均标配 `@ConditionalOnMissingBean` 与清晰 SPI 扩展点，业务系统可按需零侵入接管。

### 1. 动态接管 IP 黑白名单提供者 (`IpFilterProvider`)
默认从配置文件读取 `ip-blacklist`。生产环境可通过实现 `IpFilterProvider` 接入 Redis 或安全风控中心：

```java
package io.github.atengk.cloud.sample.gateway.config;

import io.github.atengk.cloud.gateway.spi.IpFilterProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class CustomIpFilterConfiguration {

    @Bean
    public IpFilterProvider customIpFilterProvider() {
        return new IpFilterProvider() {
            @Override
            public List<String> getBlacklist() {
                // 生产环境可从 Redis 动态读取最新被封禁的恶意 IP / CIDR 网段
                return List.of("192.168.100.1", "10.99.0.0/16");
            }

            @Override
            public List<String> getWhitelist() {
                // 生产环境可配置特定内网网段直通白名单
                return List.of("10.0.0.0/8");
            }
        };
    }
}
```

### 2. 动态接管开放平台签名密钥提供者 (`SignSecretProvider`)
开启 `ateng.cloud.gateway.sign.enabled=true` 时，框架通过 `SignSecretProvider` SPI 获取 AppId 对应的 Secret：

```java
package io.github.atengk.cloud.sample.gateway.config;

import io.github.atengk.cloud.gateway.spi.SignSecretProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CustomSignSecretConfiguration {

    @Bean
    public SignSecretProvider customSignSecretProvider() {
        return appId -> {
            // 生产环境根据 AppId 从数据库或密钥管理系统 KMS 动态拉取对应 Secret
            if ("open-partner-01".equals(appId)) {
                return "prod-secret-key-abcdef123456";
            }
            return null; // 返回 null 则网关自动 Fail-Fast 401 阻断
        };
    }
}
```

### 3. 多维分布式限流键解析器自定义
框架提供 `ipKeyResolver`（`@Primary` 客户端真实 IP）、`userKeyResolver`（Sa-Token 用户 ID）与 `apiKeyResolver`（Path + IP 复合键）。在路由过滤器中声明指定 Bean 即可切换维度：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: sensitive-pay-route
          uri: lb://payment-service
          predicates:
            - Path=/api/pay/**
          filters:
            - name: RequestRateLimiter
              args:
                key-resolver: "#{@apiKeyResolver}"
                redis-rate-limiter.replenishRate: 10
                redis-rate-limiter.burstCapacity: 20
```

### 4. 自定义流量染色决议器 (`TrafficTagResolver` SPI)

当需要结合登录用户身份、租户 ID 或自定义业务规则对入站请求动态打标，或强制过滤未授权外部伪造染色标头时，可通过注册自定义 `TrafficTagResolver` Bean 优雅接管：

```java
@Configuration(proxyBeanMethods = false)
public class CustomTrafficTagResolverConfiguration {

    @Bean
    public TrafficTagResolver customTrafficTagResolver() {
        return exchange -> {
            if (exchange == null || exchange.getRequest() == null) {
                return null;
            }
            // 1. 业务自定义规则：根据用户等级或租户执行动态染色
            String userTier = exchange.getRequest().getHeaders().getFirst("X-User-Tier");
            if ("VIP".equalsIgnoreCase(userTier)) {
                return "vip-lane";
            }
            // 2. 非 VIP 请求返回 null，网关将自动物理剥离其可能私自伪造的 X-Traffic-Tag 标头
            return null;
        };
    }
}
```

---

## 维度三：核心生产边界与非功能语义

### 1. Netty 2MB 载荷硬防爆与 S3 预签名直传架构 (Payload Bomb Defense)
- **底层防御机制**：通过 `CodecCustomizer` 在 Netty 事件循环层将请求体最大缓存限制为 `2MB`（可通过 `ateng.cloud.gateway.max-in-memory-size` 调整）。当攻击者尝试上传超大恶意载荷时，Netty 抛出 `DataBufferLimitException`；
- **异常收敛转译**：`GatewayGlobalExceptionHandler` 捕获该异常，立即拦截并转译为 `HTTP 413 Payload Too Large`，返回标准 JSON 结构：
  ```json
  {
    "code": 413,
    "msg": "请求报文超出最大允许限制 (2MB)",
    "traceId": "74aab7f8d86c446390c08c0203ea9d9e"
  }
  ```
- **大文件旁路直传架构**：大文件、视频、数据报表绝对禁止流经网关或微服务内存中转。业务应依托 `ateng-boot-storage` 获取 S3 预签名直传 URL（Presigned URL），客户端直连对象存储上传，彻底规避网关内存溢出假死。

### 2. 写请求绝对禁止重试不变量 (Anti-Snowballing Retry Invariant)
- **非幂等写操作零重试**：`AtengRetryGatewayFilterFactory` 强制限制对于 `POST`、`PUT`、`PATCH`、`DELETE` 等写操作，底层发生任何异常时重试次数严密锁定为 `retries = 0`，坚决杜绝下游微服务重复落库与并发死锁；
- **纯读请求弹性容错**：仅针对 `GET`、`HEAD` 等幂等读操作，在发生网络建连未建立超时（`ConnectTimeoutException`、`ConnectException`）时允许弹性重试 1 次；一旦发生读超时（`SocketTimeoutException`，表明数据已发送且下游正在执行），同样严格禁止重试，防御级联雪崩。

### 3. 零信任内网防穿透与 Same-Token 阻断 (Same-Token Fail-Fast)
- **南北向流量集中鉴权**：网关作为系统唯一公网暴露入口，对外执行 Sa-Token 鉴权，校验通过后向下游请求头注入内部专用受信凭据 `Same-Token`；
- **内部服务零信任校验**：下游微服务强制开启 `sa-token.same-token.check=true`。凡绕过网关直连内部微服务私网端口的非法流量，因未携带网关签发的同源签名凭证，直接 Fail-Fast 401 阻断。

### 4. 动态路由双轨无感原子热更与快照容错 (Dual-Track Snapshot Guard)
- **双轨配置无感热更体系**：
  - **Track A（独立路由数据源）**：通过 `NacosRouteDefinitionRepository` 监听独立文件（默认 `gateway-routes.json`），支持独立管理、反向持久化及发布 `RefreshRoutesEvent`；
  - **Track B（微服务主配置热绑）**：通过 `GatewayRouteRefreshListener` 动态监听 `${spring.application.name}.yaml` 主配置中的 `spring.cloud.gateway.routes` 环境变量变更与 Nacos `SafeConfigRefreshEvent`，增量重绑后自动驱动路由表原子刷新，彻底消除修改主配置路由后必须重启网关的心智负担；
- **双轨快照守卫容错与多源共存**：统一由 `GatewayRouteValidator` 实施前置深度语法与语义校验（ID 唯一、URI 协议头合规、谓词与过滤器非空）。当任一轨道配置破损或语法非法时，立即拦截并坚决保留上一份有效路由快照（Last-Known Good Snapshot），静态路由、独立路由与主配置路由多源协同共存，更新过程零丢包。

### 5. 金丝雀灰度染色与无损降级回退 (Canary Gray & Fallback)
- **权威染色解析与伪造清洗**：`GatewayTrafficTagFilter` 依托 `TrafficTagResolver` SPI 决议权威染色标签，挂载至上下文（`ATTR_TRAFFIC_TAG`）并向下游微服务透明传递；若未决议出标签，自动物理剥离外部伪造的 `X-Traffic-Tag`，杜绝未授权链路穿透；
- **平滑容灾降级**：`GrayServiceInstanceListSupplier` 精准匹配实例 `metadata.tag`。当灰度实例全部下线或未配置对应标签时，在 `fallback-to-default=true` 下自动降级调度至基线默认实例，杜绝 503 报警。

### 6. 分布式限流 429 统一转译与 Retry-After
- **标准化响应体**：`GatewayRateLimitResponseFilter` 拦截下游或过滤器产生的 HTTP 429 状态，格式化输出企业标准 `Result.fail(429, "当前访问过于频繁，请稍后重试")`，并附带 `Retry-After: 1` 响应头，引导客户端指数退避。

### 7. 复合智能负载均衡责任链与 3 秒缓存时效收敛 (Composite LoadBalancer & 3s TTL)
- **复合责任链拓扑装配顺序**：
  网关层通过 `GrayLoadBalancerClientConfiguration` 构建高可用自洽责任链：
  `DiscoveryClient` ➔ `Caching` (3s) ➔ `GrayServiceInstanceListSupplier` ➔ `ZonePreferenceServiceInstanceListSupplier` ➔ `NacosWarmupLoadBalancer`；
- **业务隔离级优先于拓扑亲和级**：
  调度引擎严格执行分层治理：先按 `X-Traffic-Tag` 锁定目标版本池（灰度隔离），再在版本池内部优先筛选同机房健康可用节点（机房亲和）；当灰度节点全宕且 `fallback-to-default=true` 时，平滑降级至基线默认实例（杜绝 503 报警）；普通未染色流量坚决隔离不落入金丝雀节点；
- **3 秒实例缓存 TTL 极速感知**：
  通过 `AtengGatewayEnvironmentPostProcessor` 默认向 Environment 注入 `spring.cloud.loadbalancer.cache.ttl: 3s`，规避 Spring Cloud 默认 35s 缓存导致的滚动发布下线流量黑洞与节点感知滞后。

---

## 维度四：物理排版与代码全真验证

以下示例代码与测试用例 100% 逆向提取自 `sample-cloud-gateway` 中已验证通过的端到端集成测试源码：

### 1. 载荷硬防爆集成测试 (断言 HTTP 413)
```java
@Test
@DisplayName("测试 Netty 2MB 载荷硬限制：超大报文 (>2MB) 直接熔断拦截为 HTTP 413 Result")
void testPayloadBombBlockedWith413() {
    // 构造 2.5MB 超限载荷 (超过网关 2MB 硬限制)
    byte[] oversizedPayload = new byte[2500000];

    webTestClient.post()
            .uri("/api/mock/upload-echo")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .bodyValue(oversizedPayload)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
            .expectHeader().exists(GatewayConstants.X_TRACE_ID)
            .expectBody()
            .jsonPath("$.code").isEqualTo(413)
            .jsonPath("$.msg").value(containsString("2MB"))
            .jsonPath("$.traceId").isNotEmpty();
}
```

### 2. OWASP 等保安全响应头强化测试
```java
@Test
@DisplayName("测试 OWASP 等保三级合规安全响应头出站自动强化")
void testSecurityHeadersInjected() {
    webTestClient.get()
            .uri("/api/mock/hello")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectHeader().valueEquals("X-Frame-Options", "SAMEORIGIN")
            .expectHeader().valueEquals("X-XSS-Protection", "1; mode=block")
            .expectHeader().valueEquals("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
            .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin")
            .expectHeader().exists(GatewayConstants.X_TRACE_ID);
}
```

### 3. 全链路灰度染色与降级测试
```java
@Test
@DisplayName("测试 X-Traffic-Tag 标头染色透明透传至下游微服务")
void testTrafficTagHeaderPropagation() {
    webTestClient.get()
            .uri("/api/mock/echo-headers")
            .header(GatewayConstants.X_TRAFFIC_TAG, "canary-v2")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(200)
            .jsonPath("$.data['x-traffic-tag']").isEqualTo("canary-v2");
}
```

### 4. 写请求绝对零重试防雪崩测试
```java
@Test
@DisplayName("测试防雪崩重试铁律：POST 写请求建连失败绝对零重试 (执行次数=1)")
void testWriteMethodZeroRetryOnFailure() {
    webTestClient.post()
            .uri("/api/mock/retry-counter")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            .expectBody()
            .jsonPath("$.code").isEqualTo(503);

    // 强断言：非幂等写操作绝对禁止重试，执行次数严密锁定为 1
    assertEquals(1, mockTestController.getPostRetryCount(), "非幂等写请求绝对禁止重试");
}
```

### 5. 主配置动态路由热更新与快照守卫测试
```java
@Test
@DisplayName("测试主配置动态路由：环境变量增量热绑 (404 -> 200)、畸变路由快照守卫容错与双轨多源共存")
void testMainConfigRouteHotReloadAndSnapshotGuard() {
    // 1. 模拟外部配置刷新向 Spring Environment 注入主配置路由定义
    TestPropertyValues.of(
            "spring.cloud.gateway.routes[0].id=mock-test-route",
            "spring.cloud.gateway.routes[0].uri=forward:/mock/hello",
            "spring.cloud.gateway.routes[0].predicates[0]=Path=/api/mock/**",
            "spring.cloud.gateway.routes[1].id=main-hot-route",
            "spring.cloud.gateway.routes[1].uri=forward:/mock/hello",
            "spring.cloud.gateway.routes[1].predicates[0]=Path=/dynamic/main-hot/**"
    ).applyTo(environment);

    // 广播 EnvironmentChangeEvent 事件
    applicationEventPublisher.publishEvent(new EnvironmentChangeEvent(
            Set.of("spring.cloud.gateway.routes[1].id", "spring.cloud.gateway.routes[1].uri")));

    // 2. 强断言：主配置动态路由原子生效 (404 -> 200)
    webTestClient.get()
            .uri("/dynamic/main-hot/test")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(200)
            .jsonPath("$.data").isEqualTo("gateway-ok");

    // 3. 模拟推送非法路由，强断言快照守卫拒绝脏配置，现有路由依然正常提供服务
    TestPropertyValues.of(
            "spring.cloud.gateway.routes[0].id=mock-test-route",
            "spring.cloud.gateway.routes[0].uri=forward:/mock/hello",
            "spring.cloud.gateway.routes[0].predicates[0]=Path=/api/mock/**",
            "spring.cloud.gateway.routes[1].id=corrupted-route",
            "spring.cloud.gateway.routes[1].uri=/no-scheme-uri",
            "spring.cloud.gateway.routes[1].predicates[0]=Path=/dynamic/main-hot/**"
    ).applyTo(environment);

    applicationEventPublisher.publishEvent(new EnvironmentChangeEvent(
            Set.of("spring.cloud.gateway.routes[1].uri")));

    webTestClient.get()
            .uri("/dynamic/main-hot/test")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(200)
            .jsonPath("$.data").isEqualTo("gateway-ok");
}
```

### 6. 复合智能负载均衡 HTTP 边界端到端集成测试 (金丝雀染色、同机房亲和、灰度全下线降级与预热)
```java
@Test
@DisplayName("端到端断言：携带灰度标签的外部 HTTP 请求精准命中同机房灰度实例")
void testHttpBoundaryGrayTagMatchesSameZoneCanaryInstance() {
    webTestClient.get()
            .uri("/mock/lb/gray-precision/instance-info")
            .header(GatewayConstants.X_TRAFFIC_TAG, "v2")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(200)
            .jsonPath("$.data.instanceId").isEqualTo("srv-gray-az1");
}

@Test
@DisplayName("端到端断言：灰度节点全宕时，平滑降级调度至基线默认实例 (HTTP 200，杜绝 503 报警)")
void testHttpBoundaryGrayAllDownFallsBackToSameZoneBaseline() {
    webTestClient.get()
            .uri("/mock/lb/gray-fallback/instance-info")
            .header(GatewayConstants.X_TRAFFIC_TAG, "v2")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.code").isEqualTo(200)
            .jsonPath("$.data.instanceId").isEqualTo("srv-base-az1");
}

@Test
@DisplayName("端到端断言：普通未染色流量绝对隔离不落入金丝雀节点")
void testHttpBoundaryUncoloredRequestNeverTouchesCanary() {
    for (int i = 0; i < 10; i++) {
        webTestClient.get()
                .uri("/mock/lb/baseline-isolation/instance-info")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo(200)
                .jsonPath("$.data.instanceId").isEqualTo("srv-base-az1");
    }
}
```

### 7. 网关全景健康与动态路由运维诊断端点 (Actuator atengGateway)

#### 配置开启与路径白名单
在 `application.yml` 中暴露端点，并加入网关边缘安全白名单：
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,atengGateway

ateng:
  cloud:
    gateway:
      security:
        exclude-paths:
          - /actuator/**
```

#### 端点访问与强断言测试
```java
@Test
@DisplayName("测试网关全景运维诊断端点 (/actuator/atengGateway) 返回 200 与四维全景结构化报告")
void testGatewayPanoramaEndpointReturns200AndStructuredReport() {
    webTestClient.get()
            .uri("/actuator/atengGateway")
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
            // 强断言：绝不泄漏具体内网 IP 与私钥
            .jsonPath("$.edgeSecurity.ipBlacklist").doesNotExist()
            .jsonPath("$.edgeSecurity.ipWhitelist").doesNotExist()
            .jsonPath("$.signGuard.enabled").isEqualTo(true)
            .jsonPath("$.signGuard.bodyEnabled").isEqualTo(true);
}
```

#### 诊断响应 JSON 样例
```json
{
  "status": "UP",
  "gatewayId": "sample-cloud-gateway",
  "timestamp": 1793412690000,
  "dynamicRoute": {
    "enabled": true,
    "dualTrackEnabled": true,
    "totalRouteCount": 2,
    "nacosRoute": {
      "enabled": true,
      "dataId": "gateway-routes.json",
      "group": "DEFAULT_GROUP",
      "routeCount": 0,
      "snapshotHealthy": true,
      "lastRefreshTime": null,
      "lastRefreshSuccess": null,
      "lastRefreshError": null
    },
    "mainConfigRoute": {
      "routeCount": 2,
      "defaultFilterCount": 0,
      "snapshotHealthy": true,
      "lastRefreshTime": 1793412689500,
      "lastRefreshSuccess": true,
      "lastRefreshError": null
    }
  },
  "grayLoadBalancer": {
    "enabled": true,
    "fallbackToDefault": true,
    "cacheTtl": "3s",
    "zone": null
  },
  "edgeSecurity": {
    "enabled": true,
    "saTokenEnabled": true,
    "pathValidationEnabled": true,
    "headerSanitizeEnabled": true,
    "clientIpEnabled": true,
    "ipFilterEnabled": true,
    "ipBlacklistCount": 2,
    "ipWhitelistCount": 0,
    "sanitizeHeadersCount": 5
  },
  "signGuard": {
    "enabled": true,
    "bodyEnabled": true,
    "timestampThreshold": "5m",
    "includePathsCount": 1,
    "excludePathsCount": 0
  }
}
```
