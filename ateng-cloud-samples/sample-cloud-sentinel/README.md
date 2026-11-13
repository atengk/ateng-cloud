# sample-cloud-sentinel 流量防卫与熔断降级体验工程

`ateng-cloud-sentinel` 核心能力端到端 1:1 自验交互沙箱与集成验证工程（父 POM 已声明 `maven.deploy.skip=true`，禁止对外发布）。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`
- **应用服务名**：`sample-cloud-sentinel`
- **本地启动命令**：

```bash
mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-sentinel
```

---

## 2. 运行环境与双模切换

1. **离线纯本地自洽模式（默认）**：`src/main/resources/application.yml` 已默认关闭外部 Nacos 注册/配置依赖，内置 `RemotePaymentMockProviderController` 本地支付仿真提供者，启动后即可直接通过 HTTP 与自动化测试体验全链路防卫能力。
2. **远程集群双模联调模式（`local` Profile）**：复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml`（已被 `.gitignore` 忽略，严禁提交 Git），填入真实环境连接参数后，通过 `--spring.profiles.active=local` 激活，容器通过 `optional:classpath:application-local.yml` 增量加载并直连外部 Nacos 与 Sentinel Dashboard 联调（联调结束后及时清理测试规则）。

---

## 3. 核心 Controller 接口与一键体验

> 下表全部端到端交互路径均对应工程内真实 Controller 与模拟提供者（`RemotePaymentMockProviderController` 挂载于 `/sample/cloud/sentinel/provider/payment/*`，专职为 OpenFeign 支付客户端模拟超时/500 故障，无需手动单独联调）。

| 功能场景 | Controller / 路由端点 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **秒杀下单入站流控** | `SentinelSecKillFlowController#createOrder` | `POST /sample/cloud/sentinel/seckill/order` | `curl -X POST http://localhost:8080/sample/cloud/sentinel/seckill/order -H "Content-Type: application/json" -d "{\"userId\":1001,\"productId\":2001,\"count\":1}"` |
| **热点商品参数限流** | `SentinelSecKillFlowController#getProductDetail` | `GET /sample/cloud/sentinel/seckill/product/{productId}` | `curl http://localhost:8080/sample/cloud/sentinel/seckill/product/1001` |
| **VIP 通道切面逃逸兜底** | `SentinelSecKillFlowController#vipChannel` | `GET /sample/cloud/sentinel/seckill/vip-channel` | `curl http://localhost:8080/sample/cloud/sentinel/seckill/vip-channel` |
| **Feign 写扣款受管降级** | `SentinelPaymentDegradeController#pay` | `POST /sample/cloud/sentinel/payment/pay?simulateTimeout=true` | `curl -X POST "http://localhost:8080/sample/cloud/sentinel/payment/pay?simulateTimeout=true" -H "Content-Type: application/json" -d "{\"orderNo\":\"ORD-001\",\"amount\":99.00,\"channel\":\"ALIPAY\"}"` |
| **Feign 读状态柔性保底** | `SentinelPaymentDegradeController#queryStatus` | `GET /sample/cloud/sentinel/payment/status/{payNo}?simulateFail=true` | `curl "http://localhost:8080/sample/cloud/sentinel/payment/status/PAY-001?simulateFail=true"` |
| **Web 异常自动熔断桥接** | `SentinelPaymentDegradeController#circuitBreaker` | `GET /sample/cloud/sentinel/payment/circuit-breaker?fail=true` | `curl "http://localhost:8080/sample/cloud/sentinel/payment/circuit-breaker?fail=true"` |
| **Web 普通业务异常豁免** | `SentinelPaymentDegradeController#circuitBreaker` | `GET /sample/cloud/sentinel/payment/circuit-breaker?businessFail=true` | `curl "http://localhost:8080/sample/cloud/sentinel/payment/circuit-breaker?businessFail=true"` |
| **RESTful 动态路径归一化** | `SentinelSecurityAuthorityController#getOrderById` | `GET /sample/cloud/sentinel/security/orders/{id}` | `curl http://localhost:8080/sample/cloud/sentinel/security/orders/987654` |
| **多维调用源黑白名单鉴权** | `SentinelSecurityAuthorityController#adminDashboard` | `GET /sample/cloud/sentinel/security/admin/dashboard` | `curl -H "X-Origin-Service: trusted-caller" http://localhost:8080/sample/cloud/sentinel/security/admin/dashboard` |
| **免检白名单旁路探活** | `SentinelSecurityAuthorityController#publicPing` | `GET /sample/cloud/sentinel/security/public/ping` | `curl http://localhost:8080/sample/cloud/sentinel/security/public/ping` |
| **全景健康与规则自省** | `SentinelPanoramaEndpoint#report` | `GET /actuator/sentinel-panorama` | `curl http://localhost:8080/actuator/sentinel-panorama` |

---

## 4. 全真自动化回归命令

执行阶梯二联调自动化测试（不依赖任何外部中间件，CI 与本地离线双绿准出门禁）：

```bash
mvn test -pl ateng-cloud-sentinel,ateng-cloud-samples/sample-cloud-sentinel -am -T 1C -nsu -B
```

> [!NOTE]
> 如需查看配置属性全量字典、九道防线核心机制解析与最佳实践指南，请返回查阅 Starter 主文档：[`ateng-cloud-sentinel/README.md`](../../ateng-cloud-sentinel/README.md)。
