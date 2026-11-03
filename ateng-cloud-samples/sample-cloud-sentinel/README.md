# sample-cloud-sentinel 流量防卫与熔断降级微服务场景体验工程

> 专注于演示与自验 `ateng-cloud-sentinel` 核心流量管控、RESTful 路径防爆清洗、受管 Feign 熔断降级、Nacos 动态规则与全景自省诊断能力在真实分布式拓扑场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中自定义修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中直接运行 SampleSentinelApplication.java 的 main 方法
  # 方式二：Maven 命令行启动
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-sentinel
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式 (离线自洽)**：内置提供者与自洽离线规则，无需物理启动外部 Nacos 注册配置中心或 Sentinel 控制台，即可秒级启动工程并跑通全部端到端测试；
- **Local 真实模式 (直连物理集群)**：
  - 若需连接真实物理 Nacos 注册中心/配置中心或 Sentinel Dashboard 控制台，复制 `src/main/resources/application-local.example.yml` 为 `src/main/resources/application-local.yml` 并填写真实连接凭据；
  - 启动时指定激活参数 `-Dspring.profiles.active=local` 即可自动生效（该本地配置文件已被 `.gitignore` 物理忽略，CI 零泄露风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验三大业务领域控制器与全景自省诊断端点的功能：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **秒杀提单 Web 入站 QPS 限流** | `SentinelSecKillFlowController` | `POST /sample/cloud/sentinel/seckill/order` | `curl -s -X POST "http://localhost:8080/sample/cloud/sentinel/seckill/order" -H "Content-Type: application/json" -d "{\"userId\":10001,\"productId\":1001,\"count\":1}"` |
| **热门商品详情热点参数流控** | `SentinelSecKillFlowController` | `GET /sample/cloud/sentinel/seckill/product/{productId}` | `curl -s "http://localhost:8080/sample/cloud/sentinel/seckill/product/1001"` |
| **VIP 绿色通道切面异常逃逸兜底** | `SentinelSecKillFlowController` | `GET /sample/cloud/sentinel/seckill/vip-channel` | `curl -s "http://localhost:8080/sample/cloud/sentinel/seckill/vip-channel"` |
| **受管 Feign 默认降级转译** | `SentinelPaymentDegradeController` | `POST /sample/cloud/sentinel/payment/pay` | `curl -s -X POST "http://localhost:8080/sample/cloud/sentinel/payment/pay" -H "Content-Type: application/json" -d "{\"orderNo\":\"ORD-2026-001\",\"amount\":99.00,\"payType\":\"ALIPAY\",\"fail\":true}"` |
| **柔性 Feign 降级返回 Mock 保底** | `SentinelPaymentDegradeController` | `GET /sample/cloud/sentinel/payment/status/{payNo}` | `curl -s "http://localhost:8080/sample/cloud/sentinel/payment/status/PAY-1001?fail=true"` |
| **异常熔断降级对齐 HTTP 503** | `SentinelPaymentDegradeController` | `GET /sample/cloud/sentinel/payment/circuit-breaker` | `curl -s "http://localhost:8080/sample/cloud/sentinel/payment/circuit-breaker?fail=true"` |
| **RESTful 动态路径收敛归一化** | `SentinelSecurityAuthorityController` | `GET /sample/cloud/sentinel/security/orders/{id}` | `curl -s "http://localhost:8080/sample/cloud/sentinel/security/orders/1001"` |
| **调用源溯源与白名单访问控制** | `SentinelSecurityAuthorityController` | `GET /sample/cloud/sentinel/security/admin/dashboard` | `curl -s "http://localhost:8080/sample/cloud/sentinel/security/admin/dashboard" -H "X-Origin-Service: trusted-gateway"` |
| **Ant 路径白名单免检旁路跳过** | `SentinelSecurityAuthorityController` | `GET /sample/cloud/sentinel/security/public/ping` | `curl -s "http://localhost:8080/sample/cloud/sentinel/security/public/ping"` |
| **Sentinel 全景自省诊断与脱敏快照** | `SentinelPanoramaEndpoint` | `GET /actuator/sentinel-panorama` | `curl -s "http://localhost:8080/actuator/sentinel-panorama"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-sentinel,ateng-cloud-samples/sample-cloud-sentinel -am -T 1C -nsu -B
```

> 详细架构原理、全景 API 矩阵、YAML 配置属性清单及扩展定制说明，请参阅 Starter 核心文档：[ateng-cloud-sentinel README](../../ateng-cloud-sentinel/README.md)。
