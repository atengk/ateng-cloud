# sample-cloud-feign 声明式 HTTP 客户端微服务场景体验工程

> 本体验工程是 `ateng-cloud-feign` Starter 的 1:1 全特性真机实战沙箱，依托 Spring Boot 3 与内置模拟对端服务端提供开箱即用的跨进程 HTTP 通信、双模自适应契约剥壳、四维上下文双向对称还原、防雪崩弹性重试、载荷硬防爆与慢调用探测体验。

---

## 1. 模块定位与依赖清单

- **对应 Starter**：`ateng-cloud-feign`（声明式微服务 HTTP 通信治理）
- **核心依赖清单**：
  - `io.github.atengk:ateng-cloud-feign`：OpenFeign 自动装配、HC5 连接池、双模解包、防雪崩重试、双向上下文透传与安全日志
  - `io.github.atengk:ateng-boot-web`：标准 Servlet Web 接入层、`Result<T>` 契约封装与全局异常处理
  - `org.springframework.boot:spring-boot-starter-actuator`：运行健康与端点可观测探针
  - `org.springframework.boot:spring-boot-starter-test`：端到端容器自验与 HTTP 集成断言底座

---

## 2. 本地启动与环境配置说明

1. **离线纯本地自洽模式（默认）**

   默认配置已关闭 Nacos 注册与配置中心强制连接，服务端口锁定 `8080`，内置商品与容灾模拟提供者（`RemoteProductProviderController`、`RemoteToleranceProviderController`）用于本地闭环演示：

   ```bash
   mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-feign
   ```

2. **本地真实集群联调模式（`local` Profile）**

   如需对接真实 Nacos 与微服务集群，请勿修改默认主配置，按以下步骤启用本地覆盖模板：

   - 将 `src/main/resources/application-local.example.yml` 复制为同目录下的 `application-local.yml`（已被 `.gitignore` 忽略，严禁提交真实凭据）。
   - 在 `application-local.yml` 中将 `YOUR_NACOS_SERVER`、`YOUR_NACOS_USERNAME`、`YOUR_NACOS_PASSWORD`、`YOUR_NAMESPACE` 替换为实际环境参数。
   - 追加激活参数启动：

   ```bash
   mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-feign -Dspring-boot.run.profiles=local
   ```

> [!NOTE]
> 默认 `application.yml` 为加快本地测试与故障重试演示节奏，将 `ateng.cloud.feign.retry.period` 与 `max-period` 覆盖为 `50ms` / `200ms`；`ateng-cloud-feign` 生产默认基线为 `100ms` / `1s`。

---

## 3. 功能演示场景与一键体验 cURL 清单

启动 `sample-cloud-feign` 后（默认监听 `http://localhost:8080`），所有体验端点均返回规范的 `Result<T>` 包装（二进制流下载除外）：

### 3.1 声明式调用、契约解包与上下文对称穿透 (`FeignOrderConsumerController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **自适应契约剥壳解包** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/unwrap/1"` |
| **原生 `Result<T>` 契约透传** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/raw/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/raw/2"` |
| **全局白名单 `exclude-clients` 豁免解包** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/excluded-unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/excluded-unwrap/1"` |
| **类级注解 `@AdaptiveUnwrap(false)` 豁免** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/class-excluded/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/class-excluded/1"` |
| **方法级注解 `@AdaptiveUnwrap(false)` 豁免** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/method-excluded/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/method-excluded/1"` |
| **注解 `@AdaptiveUnwrap(true)` 覆盖白名单强解包** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/forced-unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/forced-unwrap/1"` |
| **GET 多参数 POJO 自动平铺 (`@SpringQueryMap`)** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/query` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/query?keyword=手机&category=数码&minPrice=1000&pageNum=1&pageSize=10"` |
| **二进制流无损下载 (`StreamPassthrough`)** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/invoice/download` | `curl -s -o invoice.pdf "http://localhost:8080/sample/cloud/feign/order/product/invoice/download"` |
| **Multipart 表单文件与参数混合上传** | `FeignOrderConsumerController` | `POST /sample/cloud/feign/order/product/attachment/upload` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/order/product/attachment/upload" -F "file=@pom.xml" -F "description=发票凭证附件"` |
| **POST JSON 提交与四维上下文对称还原透传** | `FeignOrderConsumerController` | `POST /sample/cloud/feign/order/create` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/order/create" -H "Content-Type: application/json" -H "x-trace-id: trace-demo-100" -H "x-user-id: user-buyer-007" -H "x-tenant-id: tenant-demo-999" -H "X-Traffic-Tag: canary-v2" -d "{\"productId\":100,\"quantity\":2,\"deliveryAddress\":\"北京市海淀区中关村南大街1号\",\"remark\":\"工作日派送\"}"` |
| **原生基本类型 Null Fail-Fast 防卫** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/primitive-null-guard` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/primitive-null-guard"` |
| **包装类型 `Long` Null 安全接收** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/wrapper-null` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/wrapper-null"` |
| **`void` 返回体契约状态码校验** | `FeignOrderConsumerController` | `POST /sample/cloud/feign/order/product/void-success` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/order/product/void-success"` |

### 3.2 防雪崩重试、隔离超时、载荷防爆与慢调用告警 (`FeignToleranceController`)

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **非幂等写接口读超时禁重试防雪崩** | `FeignToleranceController` | `POST /sample/cloud/feign/tolerance/write-timeout` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/tolerance/write-timeout"` |
| **只读 GET 读超时零重试保护慢服务** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/get-timeout` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/get-timeout"` |
| **TCP 建连超时 Full Jitter 弹性重试 1 次** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/connect-timeout` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/connect-timeout"` |
| **单 Client 级独立重试参数覆盖 (`custom-retry-client`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/custom-retry` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/custom-retry"` |
| **单 Client 级超时参数物理隔离 (`isolated-timeout-client`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/isolated-timeout` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/isolated-timeout"` |
| **10MB 响应载荷有界读取硬防爆熔断** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/payload-overflow` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/payload-overflow"` |
| **下游业务错误码因果解码 (`code=6001`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/business-error` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/business-error"` |
| **下游 HTTP 500 崩溃因果链保留 (`FeignException`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/http-500` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/http-500"` |
| **慢调用主动 WARN 告警探测 (`slow-call-client`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/slow-call` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/slow-call"` |
| **全链路标头出站透传回显探查** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/headers` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/headers" -H "x-trace-id: trace-demo-888" -H "X-Traffic-Tag: canary-v1"` |

---

## 4. 自动化测试运行指南

执行本体验工程及配套 Starter 的端到端集成测试（包含 HTTP 协议 1:1 路由可达性、双向上下文还原清理与防雪崩断言）：

```bash
mvn test -pl ateng-cloud-feign,ateng-cloud-samples/sample-cloud-feign,ateng-cloud-samples/sample-cloud-smoke-test -am -T 1C -nsu -B
```

如需单独快速运行体验工程测试：

```bash
mvn test -pl ateng-cloud-samples/sample-cloud-feign -am -T 1C -nsu -B
```

> 完整的配置矩阵、底层防御架构与最佳实践请参阅：[ateng-cloud-feign README](../../ateng-cloud-feign/README.md)。
