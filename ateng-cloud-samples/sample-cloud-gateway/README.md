# sample-cloud-gateway 样例工程体验卡

面向企业级高可用微服务架构的 Spring Cloud 纯响应式 API 网关体验工程，基于 Spring WebFlux 与 Netty 事件循环全无锁响应式构建（绝对物理隔离 Servlet 阻塞栈）。

---

## 1. 快速启动与服务端口

- **默认独立端口**：`9000`
- **主启动类**：`io.github.atengk.cloud.sample.gateway.SampleGatewayApplication`

### 本地编译与启动命令
```bash
# 1. 快速编译本模块及依赖
mvn clean compile -pl ateng-cloud-gateway,ateng-cloud-samples/sample-cloud-gateway -am -T 1C -nsu -B

# 2. 启动网关体验工程 (默认端口 9000，纯响应式 WebFlux/Netty 架构)
mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-gateway
```

---

## 2. 运行环境与双模切换

| 运行模式 | 激活方式 | 特性说明与适用场景 |
| :--- | :--- | :--- |
| **默认离线自洽模式** | 默认直接启动（无额外参数） | 显式声明 `spring.cloud.nacos.discovery.enabled: false` 与 `config.enabled: false`，基于 `forward:/` 协议内部内存转发，零外部网络与中间件依赖，极速自验与 CI 守护。 |
| **Local 直连 Nacos 模式** | `mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-gateway -Dspring-boot.run.profiles=local` | 弹性探测 `application-local.yml`（受 `.gitignore` 保护不提交），无缝接管为连接物理运行的 Nacos 注册中心与配置中心。 |

---

## 3. 核心 Controller 接口与一键体验 cURL 表格

所有业务接口均统一暴露于 `/api/**`，出参统配强类型 `Result<T>`。

| 业务领域分类 | 接口路径与方法 | 核心治理语义说明 | 一键体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **接入认证与同源凭证** | `POST /api/auth/login` | 模拟用户登录，签发 Sa-Token 会话令牌 | `curl -X POST "http://localhost:9000/api/auth/login?username=ateng&userId=10001&tenantId=tenant-001"` |
| **接入认证与同源凭证** | `GET /api/auth/user-info` | 校验登录态，受保护接口（未登录 401 阻断） | `curl -H "satoken: <TOKEN>" "http://localhost:9000/api/auth/user-info"` |
| **接入认证与同源凭证** | `GET /api/auth/inspect-context` | 验证网关向下游注入 `Same-Token` 零信任同源凭据 | `curl -H "satoken: <TOKEN>" "http://localhost:9000/api/auth/inspect-context"` |
| **动态路由与金丝雀灰度** | `GET /api/route/definitions` | 全量查询网关当前生效的动态路由定义列表 | `curl "http://localhost:9000/api/route/definitions"` |
| **动态路由与金丝雀灰度** | `POST /api/route/refresh` | 触发网关路由定义增量热更与内存快照刷新 | `curl -X POST "http://localhost:9000/api/route/refresh"` |
| **动态路由与金丝雀灰度** | `GET /api/order/dispatch` | 订单分发与全链路流量染色（按标头/Query 决议泳道） | `curl -H "X-Traffic-Tag: canary-v2" "http://localhost:9000/api/order/dispatch?orderId=ORD-1001"` |
| **动态路由与金丝雀灰度** | `GET /api/order/fallback` | 模拟灰度实例全部下线，平滑无损降级回退至基线 | `curl "http://localhost:9000/api/order/fallback?orderId=ORD-1002"` |
| **边缘安全与开放平台** | `POST /api/open/merchant/sync-order`| 开放平台商户订单同步（HMAC-SHA256 签名与防重放） | `curl -X POST -H "Content-Type: application/json" -H "X-App-Id: sample-app" -H "X-Timestamp: <TS>" -H "X-Nonce: <NONCE>" -H "X-Signature: <SIG>" -d '{"orderId":"ORD-001","merchantId":"MCH-01","amount":199.50,"channel":"ALIPAY"}' "http://localhost:9000/api/open/merchant/sync-order"` |
| **边缘安全与开放平台** | `GET /api/open/merchant/query-order`| 开放平台订单查询（GET 规范签名校验） | `curl -H "X-App-Id: sample-app" -H "X-Timestamp: <TS>" -H "X-Nonce: <NONCE>" -H "X-Signature: <SIG>" "http://localhost:9000/api/open/merchant/query-order?orderId=ORD-001"` |
| **边缘安全与开放平台** | `POST /api/security/payload-guard` | 载荷防爆硬防御探测（超过 2MB 快速熔断 413） | `curl -X POST -H "Content-Type: application/octet-stream" --data-binary "@test.bin" "http://localhost:9000/api/security/payload-guard"` |
| **边缘安全与开放平台** | `GET /api/security/ip-guard` | IP 边缘黑白名单过滤探测（黑名单即刻 403 阻断） | `curl -H "X-Forwarded-For: 203.0.113.50" "http://localhost:9000/api/security/ip-guard"` |
| **边缘安全与开放平台** | `GET /api/security/path-guard` | URI 目录穿越防卫与多斜杠规范化折叠放行 | `curl "http://localhost:9000///api///security/path-guard"` |
| **边缘安全与开放平台** | `GET /api/security/inspect-edge-headers` | 检验入站敏感标头物理剥离清洗与真实 IP 倒推解析 | `curl -H "X-User-Id: forged-999" -H "X-Forwarded-For: 1.1.1.1, 203.0.113.195" "http://localhost:9000/api/security/inspect-edge-headers"` |
| **微服务弹性容灾** | `GET /api/resilience/query-inventory` | 库存查询读请求建连失败弹性重试 1 次恢复成功 | `curl "http://localhost:9000/api/resilience/query-inventory?skuCode=SKU-IPHONE-16"` |
| **微服务弹性容灾** | `POST /api/resilience/create-order` | 订单创建非幂等写操作建连失败绝对零重试防雪崩 | `curl -X POST "http://localhost:9000/api/resilience/create-order"` |
| **微服务弹性容灾** | `GET /api/resilience/seckill-spike` | 秒杀突发流量限流触发 HTTP 429 与 `Retry-After: 1` | `curl -i "http://localhost:9000/api/resilience/seckill-spike"` |
| **微服务弹性容灾** | `GET /api/resilience/simulate-node-restart` | 节点滚动重启网络中断转译为 HTTP 502 Bad Gateway | `curl "http://localhost:9000/api/resilience/simulate-node-restart?type=premature"` |
| **微服务弹性容灾** | `GET /api/resilience/order-events` | 订单实时物流状态 Server-Sent Events 流式零缓冲直通 | `curl -N -H "Accept: text/event-stream" "http://localhost:9000/api/resilience/order-events"` |
| **网关运维全景诊断** | `GET /actuator/atengGateway` | 四维全景只读健康诊断报告（路由、灰度、安全、加签） | `curl "http://localhost:9000/actuator/atengGateway"` |

---

## 4. 全真自动化回归命令

在提交代码前或流水线中，通过下述精准依赖加速测试命令执行全量自验：

```bash
mvn test -pl ateng-cloud-gateway,ateng-cloud-samples/sample-cloud-gateway -am -T 1C -nsu -B
```
