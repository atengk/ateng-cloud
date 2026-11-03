# sample-cloud-feign 声明式 HTTP 客户端微服务场景体验工程

> 专注于演示与自验 `ateng-cloud-feign` 核心微服务治理能力在真实分布式拓扑场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中自定义修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中直接运行 SampleFeignApplication.java 的 main 方法
  # 方式二：Maven 命令行启动
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-feign
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
| **自适应契约剥壳解包** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/unwrap/1"` |
| **全局配置排除剥壳** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/excluded-unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/excluded-unwrap/1"` |
| **类注解 `@ExcludeAdaptiveUnwrap`** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/class-excluded/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/class-excluded/1"` |
| **方法注解 `@AdaptiveUnwrap` 强制解包** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/forced-unwrap/{id}` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/forced-unwrap/1"` |
| **原生未包装 String 透传** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/raw-string` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/raw-string"` |
| **GET 多参数平铺查询** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/search` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/search?keyword=手机&category=数码&pageNum=1&pageSize=10"` |
| **POST JSON 提交创建商品** | `FeignOrderConsumerController` | `POST /sample/cloud/feign/order/product/create` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/order/product/create" -H "Content-Type: application/json" -d "{\"name\":\"新商品\",\"price\":199.00,\"stock\":50,\"category\":\"数码\"}"` |
| **响应式二进制流导出下载** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/stream/export` | `curl -s -O "http://localhost:8080/sample/cloud/feign/order/product/stream/export"` |
| **全链路四维上下文透传探查** | `FeignOrderConsumerController` | `GET /sample/cloud/feign/order/product/headers` | `curl -s "http://localhost:8080/sample/cloud/feign/order/product/headers" -H "X-Traffic-Tag: canary-v1"` |
| **非幂等写接口读超时禁重试防雪崩** | `FeignToleranceController` | `POST /sample/cloud/feign/tolerance/write-timeout` | `curl -s -X POST "http://localhost:8080/sample/cloud/feign/tolerance/write-timeout"` |
| **建连握手超时弹性重试 1 次** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/connect-timeout` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/connect-timeout"` |
| **单 Client 级独立重试参数覆盖** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/custom-retry` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/custom-retry"` |
| **单 Client 级超时参数物理隔离** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/isolated-timeout` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/isolated-timeout"` |
| **10MB 响应载荷有界硬防爆熔断** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/payload-overflow` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/payload-overflow"` |
| **下游业务错误因果解码转译** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/business-error` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/business-error"` |
| **下游 HTTP 500 崩溃因果链保留** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/http500` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/http500"` |
| **出站标头与 Same-Token 零信任防穿透** | `FeignToleranceController` | `GET /sample/cloud/feign/tolerance/headers` | `curl -s "http://localhost:8080/sample/cloud/feign/tolerance/headers"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-feign,ateng-cloud-samples/sample-cloud-feign -am -T 1C -nsu -B
```

> 详细架构原理、全景 API 矩阵、YAML 配置属性清单及扩展定制说明，请参阅 Starter 核心文档：[ateng-cloud-feign README](../../ateng-cloud-feign/README.md)。
