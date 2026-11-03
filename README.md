# ateng-cloud

> 面向生产就绪的 Spring Cloud 工业级微服务基础设施 Starter 套件。

[![Java CI](https://github.com/atengk/ateng-cloud/actions/workflows/ci.yml/badge.svg)](https://github.com/atengk/ateng-cloud/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.atengk/ateng-cloud-bom.svg?style=flat-square&color=blue)](https://central.sonatype.com/artifact/io.github.atengk/ateng-cloud-bom)
[![JDK](https://img.shields.io/badge/JDK-17%20~%2021+-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.13-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.6-blue.svg)](https://spring.io/projects/spring-cloud)
[![Apache Dubbo](https://img.shields.io/badge/Apache%20Dubbo-3.3.6-red.svg)](https://dubbo.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 1. 架构全景与生态定位

`ateng-cloud` 是面向企业级分布式生产环境打造的微服务通用基础设施套件。项目单向消费并依赖已发布的 `ateng-boot-bom`（纯净单体基础设施底座），专注于提供垂直正交、开箱即用、无侵入的高可用微服务治理能力。

```mermaid
flowchart TD
    Client["外部公网客户端 / Web / App"] --> Gateway["ateng-cloud-gateway<br>(WebFlux 纯响应式网关 / Sa-Token 鉴权 / Same-Token 签发 / 2MB 载荷硬防爆)"]

    Gateway -->|"受信内网路由 (注入四维上下文 + 内部 Same-Token)"| InternalCluster

    subgraph InternalCluster ["企业内部业务微服务集群 (基于 ateng-boot-web Servlet 栈)"]
        direction TB
        
        subgraph RPCLink ["双模微服务通信契约"]
            Feign["ateng-cloud-feign<br>(声明式 HTTP 客户端 / 智能剥壳 / 写接口禁重试防雪崩)"]
            Dubbo["ateng-cloud-dubbo<br>(Apache Dubbo 3.3 / Triple & TCP 双核 / 契约校验)"]
        end

        subgraph Governance ["全域微服务治理底座"]
            Nacos["ateng-cloud-nacos<br>(注册发现 / 60s平滑预热 / 机房亲和 / 配置安全热更)"]
            Sentinel["ateng-cloud-sentinel<br>(流量防卫 / 熔断降级 / 路径防爆 / 受管降级工厂)"]
            Traffic["ateng-cloud-traffic<br>(全链路流量染色 X-Traffic-Tag / 泳道隔离 / 金丝雀灰度)"]
            Seata["ateng-cloud-seata<br>(分布式事务 / AT & TCC 双核 / 防悬挂业务屏障)"]
        end

        RPCLink <--> Governance
    end

    UpstreamBoot["ateng-boot-bom: 1.1.0<br>(单体通用底座 / Web / 持久层 / 安全 / 观测门面)"] -.->|"单向依赖消费"| InternalCluster
```

---

## 2. 核心特性亮点 (Features)

- ⚡ **开箱即用全能聚合**：通过 `ateng-cloud-bom` 统一锁定全域版本，`ateng-cloud-all` 一键装配微服务骨干构件，消除版本地狱与繁琐依赖配置；
- 🔄 **双模微服务通信**：支持 OpenFeign 声明式 HTTP 与 Apache Dubbo 3.3 现代流式 Triple/TCP RPC，根据业务吞吐自由选型与正交共存；
- 🌐 **全链路四维上下文穿透**：跨进程透明透传四维快照 $\langle\text{MDC traceId}, \text{userId}, \text{tenantId}, \text{ignoreTenant}\rangle$ 与金丝雀染色标（`X-Traffic-Tag`），多跳泳道隔离不丢标；
- 🛡️ **微服务防雪崩与弹性容灾**：智能剥壳并因果转译对端业务异常；非幂等写接口（POST/PUT）发生读超时坚决禁止重试，杜绝重复落库与重试风暴；
- 🔒 **零信任内网防穿透与响应式隔离**：网关集中鉴权并向下游安全注入 `Same-Token`，直连服务端口直接 401 熔断；网关坚持 WebFlux 纯响应式，与 Servlet 阻塞栈严格物理隔离；
- ⚙️ **单例不可变配置安全热更**：全面遵循 Spring Boot 3 `spring.config.import` 规范，严禁在业务单例上标注 `@RefreshScope`，增量热更严格收敛于 `@ConfigurationProperties`。

---

## 3. 30 秒极速起步 (Quick Start)

### 第 1 步：引入物料清单 (BOM)

在业务工程根 `pom.xml` 中导入 `ateng-cloud-bom`（单导入即可自动继承微服务与单体底座全域版本，子模块免写 `<version>`）：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.atengk</groupId>
            <artifactId>ateng-cloud-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 第 2 步：引入微服务起步包

在业务微服务（如 `order-service/pom.xml`）中声明聚合全家桶：

```xml
<dependencies>
    <!-- 一键引入微服务通用聚合全家桶 (Nacos + Feign + Dubbo + Sentinel + Traffic) -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-cloud-all</artifactId>
    </dependency>

    <!-- 引入 Web 运行底座 (基于 ateng-boot-web 提供 Spring MVC 容器与统一 Result<T> 契约) -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-web</artifactId>
    </dependency>
</dependencies>
```

> 💡 **按需精细化装配**：若无需全家桶，可将 `ateng-cloud-all` 替换为独立单 Starter（如仅引入 `ateng-cloud-feign` 或 `ateng-cloud-dubbo`），自由组合。

### 第 3 步：配置基础连接 (application.yml)

```yaml
server:
  port: 8080

spring:
  application:
    name: order-service
  cloud:
    nacos:
      server-addr: 127.0.0.1:8848
      discovery:
        enabled: true
      config:
        enabled: true
        file-extension: yaml
    sentinel:
      transport:
        dashboard: 127.0.0.1:8080
      eager: true

dubbo:
  application:
    name: order-service
  registry:
    address: nacos://127.0.0.1:8848
  protocol:
    name: tri
    port: 50051

ateng:
  cloud:
    feign:
      enabled: true
    traffic:
      enabled: true
```

### 第 4 步：编写业务代码

声明式定义跨服务 HTTP 客户端，开箱即享四维上下文自动透传与智能解包：

```java
@FeignClient(name = "user-service")
public interface UserServiceClient {

    @GetMapping("/api/user/{id}")
    Result<UserVO> getUserById(@PathVariable("id") Long id);
}

@RestController
@RequestMapping("/api/order")
public class OrderController {

    @Autowired
    private UserServiceClient userServiceClient;

    @GetMapping("/{orderId}")
    public Result<OrderDetailVO> getOrderDetail(@PathVariable("orderId") String orderId) {
        // 自动完成 Result<T> 状态码校验与解包，若下游报错自动因果转译为本地业务异常
        UserVO user = userServiceClient.getUserById(1001L).getData();
        return Result.success(new OrderDetailVO(orderId, user));
    }
}
```

---

## 4. 核心模块生态导航 (Module Ecosystem)

| 模块名称 | 坐标 (ArtifactId) | 一句话核心职责 | 详细文档 |
| :--- | :--- | :--- | :---: |
| **物料清单 (BOM)** | `ateng-cloud-bom` | 全域依赖版本物料清单，统管 Spring Cloud、Dubbo 3.3 与全套 Starter 版本 | [查看文档](./ateng-cloud-bom/README.md) |
| **Nacos 服务治理** | `ateng-cloud-nacos` | Nacos 2.x 注册中心、冷启动平滑预热、机房就近亲和与配置安全增量热更 | [查看文档](./ateng-cloud-nacos/README.md) |
| **Feign 声明式客户端** | `ateng-cloud-feign` | 声明式 HTTP 通信、四维上下文透明透传、智能剥壳与非幂等写接口禁重试防雪崩 | [查看文档](./ateng-cloud-feign/README.md) |
| **Dubbo 高性能 RPC** | `ateng-cloud-dubbo` | Apache Dubbo 3.3 高性能 RPC、Triple/TCP 双核、双模契约校验与四维上下文透传 | [查看文档](./ateng-cloud-dubbo/README.md) |
| **响应式微服务网关** | `ateng-cloud-gateway` | WebFlux 纯响应式网关、2MB 载荷硬防爆、Sa-Token 鉴权、Same-Token 签发与动态路由 | [查看文档](./ateng-cloud-gateway/README.md) |
| **流量防卫与降级** | `ateng-cloud-sentinel` | Sentinel 流量防卫、熔断降级、路径防爆清洗、受管降级工厂与 Nacos 5 大规则持久化 | [查看文档](./ateng-cloud-sentinel/README.md) |
| **全链路流量染色** | `ateng-cloud-traffic` | 全链路流量染色、泳道隔离、三层复合灰度责任链与金丝雀平滑降级 | [查看文档](./ateng-cloud-traffic/README.md) |
| **分布式事务增强** | `ateng-cloud-seata` | 分布式事务协调、AT/TCC 双核、TCC 防悬挂业务屏障与假切源守卫协同 | [查看文档](./ateng-cloud-seata/README.md) |
| **全家桶一键聚合** | `ateng-cloud-all` | 通用微服务骨干构件全家桶聚合起步包（网关保持可选隔离） | [查看文档](./ateng-cloud-all/README.md) |
| **场景样例与冒烟工程** | `ateng-cloud-samples` | 1:1 场景自验沙箱矩阵与 CI 全家桶离线冒烟门禁工程 | [查看文档](./ateng-cloud-samples/README.md) |

---

## 5. 微服务核心开发契约速查 (AI & Developer Best Practices)

为保障分布式体系的高可用与一致性，在基于 `ateng-cloud` 进行业务开发或使用 AI 工具生成代码时，严格遵循以下约定：

1. **命名与配置坐标**：
   - 基础包名：`io.github.atengk.cloud.*`；
   - 配置属性前缀：统一为 `ateng.cloud.<module>.*`；
2. **显式强类型响应契约**：
   - Controller 接口必须显式声明返回强类型 `Result<T>`，严禁返回裸对象或依靠切面全局隐式包装；
3. **网关与业务服务物理隔离**：
   - 网关工程（`ateng-cloud-gateway`）基于 Spring WebFlux 纯响应式实现，**严禁引入** `ateng-boot-web`（Servlet 阻塞栈）；
   - 普通微服务业务工程统一基于 `ateng-boot-web` 运行，严禁混用；
4. **单例不可变性与配置热更**：
   - **严禁在 `@Service`、`@Component` 等承载业务逻辑与事务的单例 Bean 上标注 `@RefreshScope`**；
   - 配置安全热更统一收敛于 `@ConfigurationProperties` 纯属性类增量绑定；
5. **持久层与传输层绝对解耦**：
   - 跨服务 RPC（Feign/Dubbo）与 Controller 接口 **100% 严禁接收或返回数据库 ORM 实体（Entity）**，统一使用 DTO/VO 传输模型。

---

## 6. 延伸阅读与生态资产

- [系统限界上下文拓扑图谱 (CONTEXT-MAP.md)](./CONTEXT-MAP.md) — 梳理微服务 Starter 限界上下文边界与系统协作拓扑；
- [微服务配置中心治理与配置速查 (docs/guides/nacos-configuration-guide.md)](./docs/guides/nacos-configuration-guide.md) — 详述微服务配置分层哲学与 Nacos 4 层 Data ID 配置字典；
- [标准 Nacos 配置模板资产库 (deploy/nacos/)](./deploy/nacos/) — 收录全网共享基线、Redis/MyBatis/RocketMQ 共享配置与标准微服务模板；
- [架构决策记录库 (docs/adr/)](./docs/adr/) — 沉淀不可逆重大架构选型与技术演进；
- [研发行为准则 (AGENTS.md)](./AGENTS.md) — 详述微服务架构铁律、成熟度防线与代码审查红线；
- [贡献指南 (CONTRIBUTING.md)](./CONTRIBUTING.md) 与 [安全策略 (SECURITY.md)](./SECURITY.md) — 规范开源协作与安全漏洞披露。
