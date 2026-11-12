# sample-cloud-feign 声明式 HTTP 客户端微服务场景体验工程

> 专注于演示与自验 `ateng-cloud-feign` 核心微服务治理特性在真实分布式场景下的开箱使用（包含双模自适应解包、双向四维上下文与灰度传播、防雪崩重试、载荷边界防护与慢调用告警）。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `src/main/resources/application.yml` 中修改）
- **核心依赖**：`ateng-cloud-feign`、`ateng-boot-web`、`spring-boot-starter-actuator`、`spring-boot-starter-test`
- **启动方式**：

  ```bash
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-feign
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线模拟对端（`RemoteProductProviderController`、`RemoteToleranceProviderController`）与自洽配置（默认关闭 Nacos 注册与配置强制连接），无需启动外部中间件即可直接运行并跑通全部端到端测试；
- **Local 真实模式**：复制 `src/main/resources/application-local.example.yml` 为同目录下的 `application-local.yml`（已被 `.gitignore` 忽略，严禁提交真实凭据），将 `YOUR_NACOS_SERVER`、`YOUR_NACOS_USERNAME`、`YOUR_NACOS_PASSWORD`、`YOUR_NAMESPACE` 替换为实际环境参数后，通过 `-Dspring.profiles.active=local`（测试或可执行 JAR）或 `-Dspring-boot.run.profiles=local`（`spring-boot:run`）启动激活：

  ```bash
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-feign -Dspring-boot.run.profiles=local
  ```

> [!NOTE]
> 本体验工程的 `application.yml` 为缩短测试阻塞等待时间，将 `ateng.cloud.feign.retry.period` 与 `max-period` 覆盖为 `50ms` / `200ms`；`ateng-cloud-feign` Starter 生产默认基线为 `100ms` / `1s`。

---

## 3. 核心 Controller 接口与一键体验

启动服务后，可通过以下两大领域 Controller 路由直接发起体验（二进制流下载除外，所有接口均返回规范 `Result<T>` 包装）：

| 功能场景 | Controller / 路由端点 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **自适应契约解包 (`AdaptiveUnwrap`)** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/unwrap/1"` |
| **原生 `Result<T>` 契约透传** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/raw/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/raw/2"` |
| **全局白名单 `exclude-clients` 豁免解包** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/excluded-unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/excluded-unwrap/1"` |
| **类级注解 `@AdaptiveUnwrap(false)` 豁免** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/class-excluded/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/class-excluded/1"` |
| **方法级注解 `@AdaptiveUnwrap(false)` 豁免** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/method-excluded/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/method-excluded/1"` |
| **注解 `@AdaptiveUnwrap(true)` 覆盖白名单解包** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/forced-unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/forced-unwrap/1"` |
| **GET 多参数 POJO 自动绑定 (`@SpringQueryMap`)** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/query` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/query?keyword=手机&category=数码&minPrice=1000&pageNum=1&pageSize=10"` |
| **二进制流无损下载 (`StreamPassthrough`)** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/invoice/download` | `curl -s -o invoice.pdf "http://localhost:8080/sample/cloud/feign/order/product/invoice/download"` |
| **Multipart 表单文件与参数混合上传** | `FeignOrderConsumerController` | `POST /sample/cloud/feign/order/product/attachment/upload` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/order/product/attachment/upload" -F "file=@pom.xml" -F "description=发票凭证附件"` |
| **POST JSON 提交与四维上下文对称还原** | `FeignOrderConsumerController` | `POST /sample/cloud/feign/order/create` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/order/create" -H "Content-Type: application/json" -H "x-trace-id: trace-demo-100" -H "x-user-id: user-buyer-007" -H "x-tenant-id: tenant-demo-999" -H "X-Traffic-Tag: canary-v2" -d "{\"productId\":100,\"quantity\":2,\"deliveryAddress\":\"北京市海淀区中关村南大街1号\",\"remark\":\"工作日派送\"}"` |
| **原生基本类型 Null Fail-Fast 防卫** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/primitive-null-guard` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/primitive-null-guard"` |
| **包装类型 `Long` Null 安全接收** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/wrapper-null` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/wrapper-null"` |
| **`void` 返回体契约状态码校验** | `FeignOrderConsumerController` | `POST /sample/cloud/feign/order/product/void-success` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/order/product/void-success"` |
| **非幂等写接口读超时禁重试防雪崩** | `FeignToleranceController` | `POST /sample/cloud/feign/tolerance/write-timeout` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/tolerance/write-timeout"` |
| **只读 GET 读超时零重试保护慢服务** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/get-timeout` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/get-timeout"` |
| **TCP 建连超时 Full Jitter 弹性重试 1 次** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/connect-timeout` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/connect-timeout"` |
| **单 Client 级独立重试参数覆盖 (`custom-retry-client`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/custom-retry` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/custom-retry"` |
| **单 Client 级超时参数物理隔离 (`isolated-timeout-client`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/isolated-timeout` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/isolated-timeout"` |
| **10MB 响应载荷有界读取熔断 (`Payload Guard`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/payload-overflow` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/payload-overflow"` |
| **下游业务错误码因果解码 (`code=6001`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/business-error` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/business-error"` |
| **下游 HTTP 500 崩溃因果链保留 (`FeignException`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/http-500` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/http-500"` |
| **慢调用主动 WARN 告警探测 (`slow-call-client`)** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/slow-call` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/slow-call"` |
| **全链路上下文出站传播回显探查** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/headers` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/headers" -H "x-trace-id: trace-demo-888" -H "X-Traffic-Tag: canary-v1"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-feign,ateng-cloud-samples/sample-cloud-feign,ateng-cloud-samples/sample-cloud-smoke-test -am -T 1C -nsu -B
```

如需单独快速运行体验工程测试：

```bash
mvn test -pl ateng-cloud-samples/sample-cloud-feign -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-cloud-feign README](../../ateng-cloud-feign/README.md)。
