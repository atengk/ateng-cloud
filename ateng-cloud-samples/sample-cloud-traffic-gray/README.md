# sample-cloud-traffic-gray 全链路流量染色与金丝雀灰度体验工程

> 专注于演示与自验 `ateng-cloud-traffic` 全链路流量染色、跨跳泳道穿透、多金丝雀互斥放量与金融级严格隔离在真实电商履约场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleCloudTrafficApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-traffic-gray
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线内存桩与自洽配置，无需启动外部 Nacos 注册中心即可启动运行与跑通测试；
- **Local 真实模式**：若需连接真实物理 Nacos 注册中心与配置中心，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串，启动自动生效（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **电商订单调度 (普通基线)** | `TrafficOrderDispatchController` | `GET /sample/cloud/traffic/order/dispatch?orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/order/dispatch?orderNo=ORD-1001"` |
| **静态 Header 染色透传** | `TrafficOrderDispatchController` | `GET /sample/cloud/traffic/order/dispatch?orderNo={no}` | `curl -H "X-Traffic-Tag: v2" "http://localhost:8080/sample/cloud/traffic/order/dispatch?orderNo=ORD-1002"` |
| **URL Query 参数染色透传** | `TrafficOrderDispatchController` | `GET /sample/cloud/traffic/order/dispatch?traffic_tag=v2&orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/order/dispatch?traffic_tag=v2&orderNo=ORD-1003"` |
| **OpenFeign 跨微服务出站透传** | `TrafficOrderDispatchController` | `GET /sample/cloud/traffic/order/feign-dispatch?orderNo={no}` | `curl -H "X-Traffic-Tag: v2" "http://localhost:8080/sample/cloud/traffic/order/feign-dispatch?orderNo=ORD-FEIGN-001"` |
| **一致性哈希会话粘性切流** | `TrafficOrderDispatchController` | `GET /sample/cloud/traffic/order/canary-hash?userId={uid}&orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/order/canary-hash?userId=user_sticky_9999&orderNo=ORD-STICKY-001"` |
| **多金丝雀互斥百分比区间放量** | `TrafficOrderDispatchController` | `GET /sample/cloud/traffic/order/multi-canary?userId={uid}` | `curl "http://localhost:8080/sample/cloud/traffic/order/multi-canary?userId=user_range_test_10"` |
| **声明式 @TrafficTag 切面切流** | `TrafficCanaryPolicyController` | `GET /sample/cloud/traffic/policy/declarative-tag?orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/policy/declarative-tag?orderNo=ORD-DECL-1001"` |
| **安全白名单防伪校验 (STRIP/REJECT)** | `TrafficCanaryPolicyController` | `GET /sample/cloud/traffic/policy/whitelist-check?orderNo={no}` | `curl -H "X-Traffic-Tag: forged-tag" "http://localhost:8080/sample/cloud/traffic/policy/whitelist-check?orderNo=ORD-WL-001"` |
| **金融级严格泳道强隔离阻断** | `TrafficCanaryPolicyController` | `GET /sample/cloud/traffic/policy/strict-lane?orderNo={no}` | `curl -H "X-Traffic-Tag: v3-not-exist" "http://localhost:8080/sample/cloud/traffic/policy/strict-lane?orderNo=ORD-STRICT-001"` |
| **灰度全下线弹性平滑降级** | `TrafficCanaryPolicyController` | `GET /sample/cloud/traffic/policy/resilient-fallback?orderNo={no}` | `curl -H "X-Traffic-Tag: v2" "http://localhost:8080/sample/cloud/traffic/policy/resilient-fallback?orderNo=ORD-FALLBACK-001"` |
| **电商拓扑节点 (订单处理)** | `TrafficTopologyMockProviderController` | `GET /sample/cloud/traffic/topology/order-service/process?orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/topology/order-service/process?orderNo=ORD-TOP-001"` |
| **电商拓扑节点 (库存锁定)** | `TrafficTopologyMockProviderController` | `GET /sample/cloud/traffic/topology/inventory-service/process?orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/topology/inventory-service/process?orderNo=ORD-TOP-001"` |
| **电商拓扑节点 (物流履约)** | `TrafficTopologyMockProviderController` | `GET /sample/cloud/traffic/topology/logistics-service/process?orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/topology/logistics-service/process?orderNo=ORD-TOP-001"` |
| **运维监控与沙箱模拟演练** | Spring Boot Actuator | `GET /actuator/traffic` & `POST /actuator/traffic/simulate` | `curl "http://localhost:8080/actuator/traffic"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-traffic,ateng-cloud-samples/sample-cloud-traffic-gray -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-cloud-traffic README](../../ateng-cloud-traffic/README.md)。
