# ateng-cloud-samples 微服务场景样例工程矩阵

> 面向开发者的“1:1 专属微服务场景沙箱与最小可运行开箱体验工程”。

---

## 1. 样例工程矩阵全景

各样例工程与微服务 Starter 严格保持 1:1 映射，提供自包含的最小可用代码、Controller 体验接口与离线自洽测试：

| 样例工程目录 | 映射 Starter | 场景说明 | 详细使用体验卡 |
| :--- | :--- | :--- | :---: |
| **`sample-cloud-nacos`** | `ateng-cloud-nacos` | 服务注册发现、冷启动平滑预热、机房就近亲和与安全配置热更 | [查看体验卡](./sample-cloud-nacos/README.md) |
| **`sample-cloud-feign`** | `ateng-cloud-feign` | 声明式 HTTP 客户端、四维上下文透传、双模自适应剥壳与防重试雪崩 | [查看体验卡](./sample-cloud-feign/README.md) |
| **`sample-cloud-dubbo`** | `ateng-cloud-dubbo` | Apache Dubbo 3.3 高性能 RPC、Triple/TCP 双核流式调用与上下文透传 | [查看体验卡](./sample-cloud-dubbo/README.md) |
| **`sample-cloud-gateway`** | `ateng-cloud-gateway` | WebFlux 纯响应式网关、2MB 载荷硬防爆、Sa-Token 鉴权与动态路由 | [查看体验卡](./sample-cloud-gateway/README.md) |
| **`sample-cloud-sentinel`** | `ateng-cloud-sentinel` | 流量防卫、QPS 限流、受管降级工厂与 Nacos 5 大规则持久化 | [查看体验卡](./sample-cloud-sentinel/README.md) |
| **`sample-cloud-traffic-gray`** | `ateng-cloud-traffic` | 全链路流量染色、三层复合灰度责任链调度与金丝雀平滑降级 | [查看体验卡](./sample-cloud-traffic-gray/README.md) |
| **`sample-cloud-seata`** | `ateng-cloud-seata` | 分布式事务协调、AT/TCC 双核、TCC 防悬挂业务屏障与假切源守卫协同 | [查看体验卡](./sample-cloud-seata/README.md) |
| **`sample-cloud-smoke-test`** | `ateng-cloud-all` | **CI 准出门禁**：全家桶全套骨干组件协同装配离线自检与心跳探测 | [查看体验卡](./sample-cloud-smoke-test/README.md) |

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线自洽配置（零外部网络与物理中间件依赖），直接在 IDE 中启动或执行 `mvn test` 即可跑通；
- **Local 真实模式**：若需连接真实物理 Nacos/Sentinel/网关，复制对应 Sample 目录下的 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填写真实连接串即可激活。
