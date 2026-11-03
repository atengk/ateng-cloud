# sample-cloud-dubbo 高性能 RPC 微服务场景体验工程

> 专注于演示与自验 `ateng-cloud-dubbo` 核心微服务治理能力在真实分布式拓扑场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中自定义修改)
- **Triple RPC 端口**：`50051`
- **启动方式**：
  ```bash
  # 方式一：IDE 中直接运行 SampleDubboApplication.java 的 main 方法
  # 方式二：Maven 命令行启动
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-dubbo
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式 (离线自洽)**：内置提供者与自洽环境配置，无需物理启动外部 Nacos 注册中心即可秒级启动工程并跑通全部端到端测试；
- **Local 真实模式 (直连物理集群)**：
  - 若需连接真实物理 Nacos 注册配置中心，复制 `src/main/resources/application-local.example.yml` 为 `src/main/resources/application-local.yml` 并填写真实连接凭据；
  - 启动时指定激活参数 `-Dspring.profiles.active=local` 即可自动生效（该本地配置文件已被 `.gitignore` 物理忽略，CI 零泄露风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验两大领域控制器的功能：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **单笔原生 POJO 强契约结算** | `DubboTradeConsumerController` | `POST /sample/cloud/dubbo/trade/settle/pojo` | `curl -s -X POST "http://localhost:8080/sample/cloud/dubbo/trade/settle/pojo" -H "Content-Type: application/json" -d "{\"orderNo\":\"ORD_20261103_001\",\"accountId\":999001,\"amount\":258.50,\"tradeType\":\"PURCHASE\"}"` |
| **单笔统一包装 Result 契约结算** | `DubboTradeConsumerController` | `POST /sample/cloud/dubbo/trade/settle/result` | `curl -s -X POST "http://localhost:8080/sample/cloud/dubbo/trade/settle/result" -H "Content-Type: application/json" -d "{\"orderNo\":\"ORD_20261103_002\",\"accountId\":999002,\"amount\":88.00,\"tradeType\":\"PURCHASE\"}"` |
| **业务失败断言转译** | `DubboTradeConsumerController` | `POST /sample/cloud/dubbo/trade/settle/failed` | `curl -s -X POST "http://localhost:8080/sample/cloud/dubbo/trade/settle/failed" -H "Content-Type: application/json" -d "{\"orderNo\":\"ORD_FAIL_001\",\"accountId\":999003,\"amount\":999999.00,\"tradeType\":\"PURCHASE\"}"` |
| **Triple 原生 Server Stream 流式对账** | `DubboTradeConsumerController` | `GET /sample/cloud/dubbo/trade/stream/bills` | `curl -s "http://localhost:8080/sample/cloud/dubbo/trade/stream/bills?accountId=10001&limit=5"` |
| **全链路四维上下文与灰度穿透** | `DubboTradeConsumerController` | `GET /sample/cloud/dubbo/trade/context/probe` | `curl -s "http://localhost:8080/sample/cloud/dubbo/trade/context/probe" -H "X-User-Id: user-vip-01" -H "X-Tenant-Id: tenant-trade" -H "X-Traffic-Tag: canary-v2"` |
| **Web 边界参数格式防卫拦截** | `DubboGovernanceConsumerController` | `POST /sample/cloud/dubbo/governance/validation` | `curl -s -X POST "http://localhost:8080/sample/cloud/dubbo/governance/validation" -H "Content-Type: application/json" -d "{\"username\":\"ateng-admin\",\"age\":25}"` |
| **RPC 协议边界参数防卫拦截** | `DubboGovernanceConsumerController` | `POST /sample/cloud/dubbo/governance/validation/rpc` | `curl -s -X POST "http://localhost:8080/sample/cloud/dubbo/governance/validation/rpc" -H "Content-Type: application/json" -d "{\"username\":\"\",\"age\":0}"` |
| **受管业务异常因果保真 (404)** | `DubboGovernanceConsumerController` | `GET /sample/cloud/dubbo/governance/business-exception` | `curl -s "http://localhost:8080/sample/cloud/dubbo/governance/business-exception"` |
| **底层未受检异常因果转译 (500)** | `DubboGovernanceConsumerController` | `GET /sample/cloud/dubbo/governance/custom-exception` | `curl -s "http://localhost:8080/sample/cloud/dubbo/governance/custom-exception"` |
| **核心线程池过载 429 限流保护** | `DubboGovernanceConsumerController` | `GET /sample/cloud/dubbo/governance/overload` | `curl -s "http://localhost:8080/sample/cloud/dubbo/governance/overload"` |
| **Dubbo 3 原生客户端 Mock 降级** | `DubboGovernanceConsumerController` | `GET /sample/cloud/dubbo/governance/mock/fallback` | `curl -s "http://localhost:8080/sample/cloud/dubbo/governance/mock/fallback?id=888"` |
| **正常在线健康直连调用** | `DubboGovernanceConsumerController` | `GET /sample/cloud/dubbo/governance/mock/live` | `curl -s "http://localhost:8080/sample/cloud/dubbo/governance/mock/live?id=123"` |
| **零信任 Same-Token 内部受信凭据校验** | `DubboGovernanceConsumerController` | `GET /sample/cloud/dubbo/governance/same-token` | `curl -s "http://localhost:8080/sample/cloud/dubbo/governance/same-token"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-dubbo,ateng-cloud-samples/sample-cloud-dubbo -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-cloud-dubbo README](../../ateng-cloud-dubbo/README.md)。
