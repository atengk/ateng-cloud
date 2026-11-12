# sample-cloud-gateway 响应式微服务 API 网关体验工程

> 专注于演示与自验 `ateng-cloud-gateway` 核心微服务网关特性（零信任安全鉴权、双轨动态路由、金丝雀灰度与复合负载均衡、弹性防雪崩重试、429 限流转译与有界防重放护栏）在纯响应式 WebFlux/Netty 场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`9000`（`application.yml` 测试默认使用随机端口 `0`，直接运行时可按需指定 `--server.port=9000`）
- **主启动类**：`io.github.atengk.cloud.sample.gateway.SampleGatewayApplication`
- **架构纯洁性**：100% 运行于 Spring WebFlux / Netty 响应式事件循环，**绝对物理隔离 `ateng-boot-web` / Servlet 依赖**
- **启动方式**：

```bash
mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-gateway -Dspring-boot.run.arguments="--server.port=9000"
```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：显式配置 `spring.cloud.nacos.discovery.enabled=false` 与 `config.enabled=false`，基于 Spring Cloud Gateway 原生 `forward:/` 内部转发路由与测试内存桩，无需启动外部 Nacos、Redis 或下游微服务集群即可秒级启动运行并跑通全量验证；
- **Local 真实模式**：复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实 Nacos 地址与认证凭据，通过 `-Dspring-boot.run.profiles=local`（或 `-Dspring.profiles.active=local`）启动与联调（`application-local.yml` 已被 `.gitignore` 忽略）。

---

## 3. 核心 Controller 接口与一键体验

> **说明**：网关作为分布式边缘反向代理层，对外统一暴露 `/api/**` 边缘路由端点；客户端请求经由网关全局过滤器链与路由断言后，在内部通过响应式 `forward:/` 转发至对应微服务领域的底层 Controller 契约。

| 功能场景 | 承接 Controller 契约 | 网关边缘代理请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **集中登录与令牌签发** | `GatewayAuthController#login` | `GET /api/auth/login` | `curl "http://localhost:9000/api/auth/login?userId=10001&tenantId=tenant-888"` |
| **受保护用户信息** | `GatewayAuthController#userInfo` | `GET /api/auth/user-info` | `curl -H "satoken: YOUR_TOKEN" "http://localhost:9000/api/auth/user-info"` |
| **Same-Token 与上下文回显** | `GatewayAuthController#inspectContext` | `GET /api/auth/inspect-context` | `curl -H "satoken: YOUR_TOKEN" "http://localhost:9000/api/auth/inspect-context"` |
| **登录会话注销** | `GatewayAuthController#logout` | `POST /api/auth/logout` | `curl -X POST -H "satoken: YOUR_TOKEN" "http://localhost:9000/api/auth/logout"` |
| **动态路由定义查询** | `GatewayRouteTrafficController#definitions` | `GET /api/route/definitions` | `curl "http://localhost:9000/api/route/definitions"` |
| **动态路由热更刷新** | `GatewayRouteTrafficController#refresh` | `POST /api/route/refresh` | `curl -X POST "http://localhost:9000/api/route/refresh"` |
| **全链路流量染色调度** | `GatewayRouteTrafficController#orderDispatch` | `GET /api/order/dispatch` | `curl -H "X-Traffic-Tag: canary-v2" "http://localhost:9000/api/order/dispatch?orderId=ORD-1001"` |
| **金丝雀全下线平滑降级** | `GatewayRouteTrafficController#canaryFallback` | `GET /api/order/fallback` | `curl "http://localhost:9000/api/order/fallback?orderId=ORD-1002"` |
| **开放平台 HMAC 加签同步** | `GatewaySecurityGuardController#syncMerchantOrder` | `POST /api/open/merchant/sync-order` | `curl -X POST -H "Content-Type: application/json" -H "X-App-Id: sample-app" -H "X-Timestamp: YOUR_TS" -H "X-Nonce: YOUR_NONCE" -H "X-Signature: YOUR_SIG" -d '{"orderId":"ORD-001","merchantId":"MCH-01","amount":199.50,"channel":"ALIPAY"}' "http://localhost:9000/api/open/merchant/sync-order"` |
| **开放平台 GET 签名校验** | `GatewaySecurityGuardController#queryMerchantOrder` | `GET /api/open/merchant/query-order` | `curl -H "X-App-Id: sample-app" -H "X-Timestamp: YOUR_TS" -H "X-Nonce: YOUR_NONCE" -H "X-Signature: YOUR_SIG" "http://localhost:9000/api/open/merchant/query-order?orderId=ORD-001"` |
| **2MB 载荷硬防爆探测** | `GatewaySecurityGuardController#payloadGuard` | `POST /api/security/payload-guard` | `curl -X POST -H "Content-Type: application/octet-stream" --data-binary "@test.bin" "http://localhost:9000/api/security/payload-guard"` |
| **IP 黑白名单防卫探测** | `GatewaySecurityGuardController#ipGuard` | `GET /api/security/ip-guard` | `curl -H "X-Forwarded-For: 203.0.113.50" "http://localhost:9000/api/security/ip-guard"` |
| **目录穿越与路径规范化** | `GatewaySecurityGuardController#pathGuard` | `GET /api/security/path-guard` | `curl "http://localhost:9000///api///security/path-guard"` |
| **标头剥离与真实 IP 解析** | `GatewaySecurityGuardController#inspectEdgeHeaders` | `GET /api/security/inspect-edge-headers` | `curl -H "X-User-Id: forged-999" -H "X-Forwarded-For: 1.1.1.1, 203.0.113.195" "http://localhost:9000/api/security/inspect-edge-headers"` |
| **读请求建连失败重试 1 次** | `GatewayResilienceController#queryInventory` | `GET /api/resilience/query-inventory` | `curl "http://localhost:9000/api/resilience/query-inventory?skuCode=SKU-IPHONE-16"` |
| **非幂等写请求零重试** | `GatewayResilienceController#createOrder` | `POST /api/resilience/create-order` | `curl -X POST "http://localhost:9000/api/resilience/create-order"` |
| **限流 HTTP 429 契约转译** | `GatewayResilienceController#seckillSpike` | `GET /api/resilience/seckill-spike` | `curl -i "http://localhost:9000/api/resilience/seckill-spike"` |
| **下游滚动重启 502 转译** | `GatewayResilienceController#simulateNodeRestart` | `GET /api/resilience/simulate-node-restart` | `curl "http://localhost:9000/api/resilience/simulate-node-restart?type=premature"` |
| **SSE 流式零缓冲直通** | `GatewayResilienceController#orderEvents` | `GET /api/resilience/order-events` | `curl -N -H "Accept: text/event-stream" "http://localhost:9000/api/resilience/order-events"` |
| **受管业务异常保真透传** | `GatewayResilienceController#businessError` | `GET /api/resilience/business-error` | `curl "http://localhost:9000/api/resilience/business-error"` |
| **系统未知异常脱敏兜底** | `GatewayResilienceController#serverError` | `GET /api/resilience/server-error` | `curl "http://localhost:9000/api/resilience/server-error"` |
| **全景健康与路由运维诊断** | `GatewayPanoramaEndpoint#report` | `GET /actuator/atengGateway` | `curl "http://localhost:9000/actuator/atengGateway"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-gateway,ateng-cloud-samples/sample-cloud-gateway -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-cloud-gateway README](../../ateng-cloud-gateway/README.md)。
