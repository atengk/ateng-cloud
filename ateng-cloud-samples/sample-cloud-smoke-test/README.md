# sample-cloud-smoke-test 微服务全反应堆离线冒烟体验工程

> 专注于演示与自验 `ateng-cloud-all` 聚合全家桶在全套核心微服务构件（Nacos、Feign、Dubbo、Sentinel、Traffic）协同装配下的离线平滑起步与端到端健康自检。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleSmokeTestApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-cloud-samples/sample-cloud-smoke-test
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线自洽配置（Nacos 注册与配置设为 `enabled: false`，Dubbo 注册中心设为 `N/A`，Sentinel 与 Traffic 处于离线待命），无需启动任何外部物理中间件即可秒级极速启动并 100% 跑通测试；
- **Local 真实模式**：若需连接真实物理 Nacos 注册中心与配置中心，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串与凭据，启动自动生效（该文件已被 Git 全局忽略，CI 零泄露风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **冒烟心跳存活探测** | `SmokeTestController` | `GET /sample/cloud/smoke/ping` | `curl "http://localhost:8080/sample/cloud/smoke/ping"` |
| **全家桶组件装配状态** | `SmokeTestController` | `GET /sample/cloud/smoke/status` | `curl "http://localhost:8080/sample/cloud/smoke/status"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-cloud-all,ateng-cloud-samples/sample-cloud-smoke-test -am -T 1C -nsu -B
```

> 详细架构原理、排包裁剪指南与依赖清单，请参阅核心聚合构件文档：[ateng-cloud-all README](../../ateng-cloud-all/README.md)。
