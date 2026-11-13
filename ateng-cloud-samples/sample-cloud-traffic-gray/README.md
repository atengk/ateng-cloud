# sample-cloud-traffic-gray 全链路流量染色与金丝雀灰度体验工程

> 专注于演示与自验 `ateng-cloud-traffic` 全链路流量染色、Spring 6 `RestClient`/OpenFeign 东西向透传、跨跳泳道穿透、反向排除谓词、多金丝雀互斥放量、多粒度严格隔离与三级规则消解在真实电商履约场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（默认配置为随机端口 `server.port: 0` 以便集成测试，本地体验可通过 `--server.port=8080` 指定）
- **启动方式**：

```bash
# 方式一：IDE 中运行 SampleCloudTrafficApplication.java 的 main 方法
# 方式二：命令行启动（指定 8080 端口便于本地体验）
mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-traffic-gray -Dspring-boot.run.arguments=--server.port=8080
```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线内存桩与自洽配置（`application.yml` 默认预置本地保底规则 `rule-local-canary-fallback`），无需启动外部 Nacos 注册中心即可启动运行与跑通测试；
- **Local 真实模式**：若需连接真实物理 Nacos 注册中心与配置中心，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串，通过 `-Dspring.profiles.active=local` 启动或执行测试（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令直接发起调用体验各场景：

| 功能场景 | 核心代码锚点 | 请求方式与路由 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **电商订单调度（普通基线）** | `TrafficOrderDispatchController#dispatchOrder` | `GET /sample/cloud/traffic/order/dispatch?orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/order/dispatch?orderNo=ORD-1001"` |
| **静态 Header 染色与跨跳泳道穿透** | `TrafficOrderDispatchController#dispatchOrder` | `GET /sample/cloud/traffic/order/dispatch?orderNo={no}` | `curl -H "X-Traffic-Tag: v2" "http://localhost:8080/sample/cloud/traffic/order/dispatch?orderNo=ORD-1002"` |
| **URL Query 参数染色透传** | `TrafficOrderDispatchController#dispatchOrder` | `GET /sample/cloud/traffic/order/dispatch?traffic_tag=v2&orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/order/dispatch?traffic_tag=v2&orderNo=ORD-1003"` |
| **Spring 6 `RestClient` 灰度透传** | `TrafficOrderDispatchController#restClientDispatch` | `GET /sample/cloud/traffic/order/restclient-dispatch?orderNo={no}` | `curl -H "X-Traffic-Tag: v2" "http://localhost:8080/sample/cloud/traffic/order/restclient-dispatch?orderNo=ORD-RC-2001"` |
| **OpenFeign 跨微服务出站透传** | `TrafficOrderDispatchController#feignDispatch` | `GET /sample/cloud/traffic/order/feign-dispatch?orderNo={no}` | `curl -H "X-Traffic-Tag: v2" "http://localhost:8080/sample/cloud/traffic/order/feign-dispatch?orderNo=ORD-FEIGN-001"` |
| **一致性哈希会话粘性切流** | `TrafficOrderDispatchController#canaryHashDispatch` | `GET /sample/cloud/traffic/order/canary-hash?userId={uid}&orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/order/canary-hash?userId=user_sticky_9999&orderNo=ORD-STICKY-001"` |
| **多金丝雀互斥区间放量** | `TrafficOrderDispatchController#multiCanaryDispatch` | `GET /sample/cloud/traffic/order/multi-canary?userId={uid}` | `curl "http://localhost:8080/sample/cloud/traffic/order/multi-canary?userId=user_range_test_10"` |
| **`NOT_EXACT`/`NOT_IN` 排除谓词分流** | `TrafficOrderDispatchController#exclusionPredicateDispatch` | `GET /sample/cloud/traffic/order/exclusion-predicate?orderNo={no}` | `curl -H "X-Biz-Channel: mobile-app" -H "X-Tenant-Code: tenant-vip-88" "http://localhost:8080/sample/cloud/traffic/order/exclusion-predicate?orderNo=ORD-EXCL-PASS"` |
| **声明式 `@TrafficTag` 切面切流** | `TrafficCanaryPolicyController#declarativeTagDispatch` | `GET /sample/cloud/traffic/policy/declarative-tag?orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/policy/declarative-tag?orderNo=ORD-DECL-1001"` |
| **安全白名单防伪校验** | `TrafficCanaryPolicyController#whitelistCheck` | `GET /sample/cloud/traffic/policy/whitelist-check?orderNo={no}` | `curl -H "X-Traffic-Tag: forged-tag" "http://localhost:8080/sample/cloud/traffic/policy/whitelist-check?orderNo=ORD-WL-001"` |
| **全局严格泳道强隔离阻断** | `TrafficCanaryPolicyController#strictLaneDispatch` | `GET /sample/cloud/traffic/policy/strict-lane?orderNo={no}` | `curl -H "X-Traffic-Tag: v3-not-exist" "http://localhost:8080/sample/cloud/traffic/policy/strict-lane?orderNo=ORD-STRICT-001"` |
| **按服务名细粒度严格隔离** | `TrafficCanaryPolicyController#strictLaneByServiceDispatch` | `GET /sample/cloud/traffic/policy/strict-lane-service?targetService={svc}` | `curl -H "X-Traffic-Tag: v3-not-exist" "http://localhost:8080/sample/cloud/traffic/policy/strict-lane-service?targetService=order-service"` |
| **按标签细粒度严格隔离** | `TrafficCanaryPolicyController#strictLaneByTagDispatch` | `GET /sample/cloud/traffic/policy/strict-lane-tag?orderNo={no}` | `curl -H "X-Traffic-Tag: vip-v3" "http://localhost:8080/sample/cloud/traffic/policy/strict-lane-tag?orderNo=ORD-STRICT-TAG"` |
| **三级规则消解与本地保底演练** | `TrafficCanaryPolicyController#hierarchicalRulesDispatch` | `GET /sample/cloud/traffic/policy/hierarchical-rules?orderNo={no}` | `curl -H "X-Local-Canary: enabled" "http://localhost:8080/sample/cloud/traffic/policy/hierarchical-rules?orderNo=ORD-HIER-LOCAL"` |
| **灰度全下线弹性平滑降级** | `TrafficCanaryPolicyController#resilientFallbackDispatch` | `GET /sample/cloud/traffic/policy/resilient-fallback?orderNo={no}` | `curl -H "X-Traffic-Tag: v2" "http://localhost:8080/sample/cloud/traffic/policy/resilient-fallback?orderNo=ORD-FALLBACK-001"` |
| **电商拓扑节点（订单/库存/物流）** | `TrafficTopologyMockProviderController` | `GET /sample/cloud/traffic/topology/{service}/process?orderNo={no}` | `curl "http://localhost:8080/sample/cloud/traffic/topology/order-service/process?orderNo=ORD-TOP-001"` |
| **运维监控与沙箱模拟演练** | `TrafficEndpoint` | `GET /actuator/traffic` & `POST /actuator/traffic/simulate` | `curl "http://localhost:8080/actuator/traffic"` |

### 典型真实 JSON 响应快照 (`OrderFulfillmentVO`)

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "orderNo": "ORD-RC-2001",
    "trafficTag": "v2",
    "userId": null,
    "tenantId": null,
    "deliveryStatus": "FULFILLED",
    "status": "SUCCESS",
    "visitedNodes": [
      "RestClientGateway[tag=v2]",
      "OrderService[inst=inst-order-gray, tag=v2]",
      "InventoryService[inst=inst-inv-base, tag=default]",
      "LogisticsService[inst=inst-logistics-gray, tag=v2]"
    ]
  }
}
```

> 当细粒度严格隔离命中保护服务/标签或白名单 `REJECT` 模式阻断时，系统抛出 `4210`（`STRICT_LANE_BLOCK`）或 `4212`（`UNAUTHORIZED_TRAFFIC_TAG`）标准业务异常 JSON，严禁脏写基线节点。

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-traffic,ateng-cloud-samples/sample-cloud-traffic-gray,ateng-cloud-samples/sample-cloud-smoke-test -am -T 1C -nsu -B
```

> 详细原理机制、完整属性字典与扩展定制，请参阅核心 Starter 文档：[ateng-cloud-traffic README](../../ateng-cloud-traffic/README.md)。
