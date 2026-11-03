# sample-cloud-nacos Nacos 服务注册与配置中心场景体验工程

> 专注于演示与自验 `ateng-cloud-nacos` 核心微服务治理能力在真实分布式拓扑场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中自定义修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中直接运行 SampleCloudNacosApplication.java 的 main 方法
  # 方式二：Maven 命令行启动
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-nacos
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式 (离线自洽)**：内置内存桩与自洽环境配置，无需物理启动外部 Nacos 服务端即可秒级启动工程并跑通全部端到端测试；
- **Local 真实模式 (直连物理集群)**：
  - 若需连接真实物理 Nacos 注册配置中心，复制 `src/main/resources/application-local.example.yml` 为 `src/main/resources/application-local.yml` 并填写真实连接凭据；
  - 启动时指定激活参数 `-Dspring.profiles.active=local` 即可自动生效（该本地配置文件已被 `.gitignore` 物理忽略，CI 零泄露风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验三大领域控制器的功能：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **电商订单配置读取 (展示脱敏)** | `NacosConfigController` | `GET /sample/cloud/nacos/config/current` | `curl -s "http://localhost:8080/sample/cloud/nacos/config/current"` |
| **支付通道密钥明文 (内存真明文)** | `NacosConfigController` | `GET /sample/cloud/nacos/config/secret` | `curl -s "http://localhost:8080/sample/cloud/nacos/config/secret"` |
| **订单配置增量安全热更** | `NacosConfigController` | `POST /sample/cloud/nacos/config/simulate-refresh` | `curl -s -X POST "http://localhost:8080/sample/cloud/nacos/config/simulate-refresh" -H "Content-Type: application/json" -d "{\"keys\":[\"sample.order.config.freight-rate\",\"sample.order.config.pay-channel-secret-key\"]}"` |
| **反欺诈风控规则 (泛型强类型订阅)** | `NacosConfigController` | `GET /sample/cloud/nacos/config/trade-rule` | `curl -s "http://localhost:8080/sample/cloud/nacos/config/trade-rule"` |
| **服务节点多维元数据快照获取** | `NacosDiscoveryController` | `GET /sample/cloud/nacos/discovery/metadata` | `curl -s "http://localhost:8080/sample/cloud/nacos/discovery/metadata"` |
| **健康微服务实例列表查询** | `NacosDiscoveryController` | `GET /sample/cloud/nacos/discovery/instances` | `curl -s "http://localhost:8080/sample/cloud/nacos/discovery/instances?serviceName=sample-cloud-nacos"` |
| **同机房 (Zone Affinity) 亲和实例** | `NacosDiscoveryController` | `GET /sample/cloud/nacos/discovery/same-zone` | `curl -s "http://localhost:8080/sample/cloud/nacos/discovery/same-zone?serviceName=sample-cloud-nacos"` |
| **微服务主动优雅下线排空** | `NacosDiscoveryController` | `POST /sample/cloud/nacos/discovery/offline` | `curl -s -X POST "http://localhost:8080/sample/cloud/nacos/discovery/offline"` |
| **微服务主动恢复挂牌上线** | `NacosDiscoveryController` | `POST /sample/cloud/nacos/discovery/online` | `curl -s -X POST "http://localhost:8080/sample/cloud/nacos/discovery/online"` |
| **Nacos gRPC 长连接事件透出** | `NacosDiscoveryController` | `GET /sample/cloud/nacos/discovery/connection-event` | `curl -s "http://localhost:8080/sample/cloud/nacos/discovery/connection-event"` |
| **机房亲和与加权预热实例选举** | `NacosLoadBalancerController` | `GET /sample/cloud/nacos/loadbalancer/choose` | `curl -s "http://localhost:8080/sample/cloud/nacos/loadbalancer/choose?serviceName=sample-cloud-nacos"` |
| **节点负载指标自适应动态加权** | `NacosLoadBalancerController` | `GET /sample/cloud/nacos/loadbalancer/calculate-weight` | `curl -s "http://localhost:8080/sample/cloud/nacos/loadbalancer/calculate-weight?currentWeight=1.0&cpuUsage=0.90&port=8080"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-nacos,ateng-cloud-samples/sample-cloud-nacos -am -T 1C -nsu -B
```

> 详细架构原理、全景 API 矩阵、YAML 配置属性清单及扩展定制说明，请参阅 Starter 核心文档：[ateng-cloud-nacos README](../../ateng-cloud-nacos/README.md)。
