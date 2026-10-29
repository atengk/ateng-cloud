# ateng-cloud-sentinel

面向企业级分布式生产环境的 Spring Cloud Alibaba Sentinel 流量防卫、受管熔断降级与规则持久化治理 Starter，单向依赖消费已发布的 `ateng-boot-bom`，专注于打造垂直正交、开箱即用、无侵入的高可用微服务流量管控基础设施。

内置 WebMVC 入站限流、RESTful 动态路径监控树防爆清洗、多级调用源溯源防刷、OpenFeign 受管降级工厂代理、@SentinelResource 切面异常逃逸兜底防御，以及 Nacos 5 大规则全家桶约定持久化与动态热加载能力。

---

## 维度零：特性与公共 API 全景速查矩阵

### 1. 核心类与门面接口

| 类 / 接口 | 类型 | 核心职责 | 默认行为 / 装配条件 |
| :--- | :--- | :--- | :--- |
| `AtengSentinelAutoConfiguration` | 自动装配类 | Sentinel 流量防卫底座自动装配总入口 | 当存在 `BlockException` 且 `ateng.cloud.sentinel.enabled=true` 时激活 |
| `AtengSentinelNacosRuleAutoConfiguration` | 自动装配类 | Nacos 5 大规则全家桶约定动态持久化自动装配 | 当存在 `NacosDataSource` 且 `ateng.cloud.sentinel.nacos-rules.enabled=true` 时激活 |
| `AtengSentinelProperties` | 配置属性类 | 强类型映射配置前缀 `ateng.cloud.sentinel` | 默认开启，收敛阻断处理器、路径清洗、来源解析与切面兜底配置 |
| `AtengSentinelNacosRuleProperties` | 配置属性类 | 强类型映射配置前缀 `ateng.cloud.sentinel.nacos-rules` | 默认关闭（`enabled=false` 保障离线自洽），收敛 Nacos 连接与 5 大规则配置 |
| `AtengBlockExceptionHandler` | 核心处理器 | 统一 Spring MVC 阻断异常处理并物理对齐 HTTP 状态码 | 标配 `@ConditionalOnMissingBean`，实现 `BlockExceptionHandler` |
| `AtengUrlCleaner` | 核心清洗器 | RESTful 动态 URL 归一化清洗，防范资源监控树内存无界膨胀与 OOM | 标配 `@ConditionalOnMissingBean`，实现 `UrlCleaner` |
| `AtengRequestOriginParser` | 核心解析器 | 四级调用来源溯源解析器，支持精细化授权与流控 | 标配 `@ConditionalOnMissingBean`，实现 `RequestOriginParser` |
| `AtengFallbackFactory<T>` | 抽象基类 | 受管 Feign 降级工厂基类，动态代理并转译熔断异常保留因果链 | 开放钩子方法 `createFallbackInstance` 支持业务保底扩展 |
| `SentinelBlockExceptionAdvice` | 全局切面兜底 | 全局捕获 `@SentinelResource` 逃逸的 `BlockException`，杜绝 500 误报 | 标配 `@ConditionalOnMissingBean`，基于 `@RestControllerAdvice` |
| `SentinelNacosRuleSubscriber` | 规则订阅管理器 | 基于约定 DataId 统一订阅监听 Flow、Degrade、ParamFlow、System、Authority 5 大规则 | 实现 `InitializingBean` 与 `DisposableBean`，生命周期确定性释放 |
| `NacosDataSourceFactory` | 扩展点 SPI | Sentinel Nacos 数据源构造工厂函数式接口 | 标配 `@ConditionalOnMissingBean`，支持下游通过 SPI 或测试桩无缝接管 |
| `DefaultNacosDataSourceFactory` | 默认工厂实现 | 生产级原生 `NacosDataSource` 构建实现类 | 默认注册，连接 Nacos 服务端并提供监听机制 |
| `SentinelResultCode` | 强类型错误码 | 统一定义 Sentinel 阻断业务错误码 (区段 4200~4299) | 实现 `IResultCode`，覆盖限流、降级、热点、系统、授权全场景 |
| `SentinelServletUtil` | 工具类 | 客户端真实 IP 解析辅助工具类 | 依次尝试 `X-Forwarded-For`、`X-Real-IP` 与 `RemoteAddr` 回溯解析 |
| `AtengSentinelEnvironmentPostProcessor` | 环境后置处理器 | 探测到 Classpath 存在 OpenFeign 时自动注入激活 `feign.sentinel.enabled=true` | 注册于 `META-INF/spring.factories`，支持业务显式声明 false 覆盖 |

---

### 2. 配置属性速查表 (`ateng.cloud.sentinel.*`)

| 配置项 | 类型 | 默认值 | 详细业务说明 |
| :--- | :--- | :--- | :--- |
| `ateng.cloud.sentinel.enabled` | `boolean` | `true` | 是否激活 ateng-cloud-sentinel 流量防卫增强底座 |
| `ateng.cloud.sentinel.block-handler.enabled` | `boolean` | `true` | 是否启用统一阻断异常处理器 `AtengBlockExceptionHandler` |
| `ateng.cloud.sentinel.block-handler.http-status-align` | `boolean` | `true` | 是否物理对齐 HTTP 状态码 (限流 429 / 降级 503；false 则返回 200 OK) |
| `ateng.cloud.sentinel.url-clean.enabled` | `boolean` | `true` | 是否启用 RESTful URL 路径归一化清洗器 |
| `ateng.cloud.sentinel.url-clean.number-replacement` | `String` | `"{id}"` | 路径中纯数字 ID 替换占位符 |
| `ateng.cloud.sentinel.url-clean.uuid-replacement` | `String` | `"{uuid}"` | 路径中 UUID 字符串替换占位符 |
| `ateng.cloud.sentinel.origin-parser.enabled` | `boolean` | `true` | 是否启用多级请求来源解析器 |
| `ateng.cloud.sentinel.origin-parser.origin-header` | `String` | `"X-Origin-Service"` | 第一优先级调用方服务标识请求头 |
| `ateng.cloud.sentinel.origin-parser.gateway-header` | `String` | `"X-Gateway-Caller"` | 第二优先级网关透传来源请求头 |
| `ateng.cloud.sentinel.origin-parser.default-origin` | `String` | `"default"` | 无来源标识且未启用 IP 保底时的默认回退来源 |
| `ateng.cloud.sentinel.origin-parser.enable-client-ip-fallback` | `boolean` | `true` | 当请求头缺失时是否回退至远端客户端 IP 作为来源标识 |
| `ateng.cloud.sentinel.block-advice.enabled` | `boolean` | `true` | 是否启用 `@SentinelResource` 全局切面逃逸兜底拦截器 |
| `ateng.cloud.sentinel.block-advice.http-status-align` | `boolean` | `true` | 切面兜底拦截时是否物理对齐 HTTP 状态码 |
| `ateng.cloud.sentinel.nacos-rules.enabled` | `boolean` | `false` | 是否启用 Nacos 规则全家桶约定动态持久化 (默认关闭保障离线自洽) |
| `ateng.cloud.sentinel.nacos-rules.server-addr` | `String` | `"127.0.0.1:8848"` | Nacos 服务端连接地址 |
| `ateng.cloud.sentinel.nacos-rules.namespace` | `String` | `""` | Nacos 命名空间 ID (默认为空字符串表示 public) |
| `ateng.cloud.sentinel.nacos-rules.group-id` | `String` | `"SENTINEL_GROUP"` | Nacos 配置分组 GroupId |
| `ateng.cloud.sentinel.nacos-rules.data-id-prefix` | `String` | `null` | Nacos DataId 前缀 (缺省时平滑回退至 `${spring.application.name}`) |
| `ateng.cloud.sentinel.nacos-rules.flow.enabled` | `boolean` | `true` | 是否订阅流控规则 (DataId: `${prefix}-flow.json`) |
| `ateng.cloud.sentinel.nacos-rules.degrade.enabled` | `boolean` | `true` | 是否订阅熔断降级规则 (DataId: `${prefix}-degrade.json`) |
| `ateng.cloud.sentinel.nacos-rules.param-flow.enabled` | `boolean` | `true` | 是否订阅热点参数限流规则 (DataId: `${prefix}-param-flow.json`) |
| `ateng.cloud.sentinel.nacos-rules.system.enabled` | `boolean` | `true` | 是否订阅系统自适应保护规则 (DataId: `${prefix}-system.json`) |
| `ateng.cloud.sentinel.nacos-rules.authority.enabled` | `boolean` | `true` | 是否订阅来源访问控制规则 (DataId: `${prefix}-authority.json`) |

---

### 3. 标准错误码契约对照表 (`SentinelResultCode`)

| 错误码枚举 | 编码值 (`code`) | 物理 HTTP 状态码 | 业务描述与阻断场景 |
| :--- | :--- | :--- | :--- |
| `FLOW_LIMIT` | `4200` | `429 Too Many Requests` | 接口请求流量超过预设阈值，触发流控保护 |
| `DEGRADE` | `4201` | `503 Service Unavailable` | 下游服务异常比例、异常数或慢调用过载，触发熔断降级 |
| `PARAM_FLOW_LIMIT` | `4202` | `429 Too Many Requests` | 热点参数访问频度超限，触发热点参数限流 |
| `SYSTEM_BLOCK` | `4203` | `429 Too Many Requests` | 系统负载过高（CPU/Load/线程数/总体QPS超限），触发系统保护 |
| `AUTHORITY_BLOCK` | `4204` | `429 Too Many Requests` | 请求来源未命中白名单或命中黑名单，拒绝非法访问 |

---

## 维度一：最小起步体验 (Quick Start)

### 1. 引入 Maven 依赖

在微服务业务工程的 `pom.xml` 中引入（版本受管于 `ateng-cloud-bom`）：

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-cloud-sentinel</artifactId>
</dependency>
```

### 2. 最小 YAML 配置

在 `application.yml` 中声明：

```yaml
spring:
  application:
    name: demo-service

ateng:
  cloud:
    sentinel:
      enabled: true
      block-handler:
        http-status-align: true
      # 可选：连接 Nacos 开启 5 大规则自动持久化
      nacos-rules:
        enabled: true
        server-addr: 127.0.0.1:8848
        group-id: SENTINEL_GROUP
```

### 3. 开箱即用的业务控制器与声明式调用

```java
package io.github.atengk.cloud.sample.sentinel.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import io.github.atengk.boot.core.model.Result;
import org.springframework.web.bind.annotation.*;

/**
 * 流量防卫场景实测控制器
 */
@RestController
@RequestMapping("/api/test")
public class SampleSentinelController {

    // 1. 普通 WebMVC 接口：自动由 CommonFilter 拦截，超限自动返回 429 与 FLOW_LIMIT
    @GetMapping("/flow")
    public Result<String> testFlow() {
        return Result.success("flow-pass");
    }

    // 2. RESTful 接口：自动清洗为 /api/test/users/{id}，防止内存无界泄露
    @GetMapping("/users/{id}")
    public Result<String> getUserById(@PathVariable("id") Long id) {
        return Result.success("user-" + id);
    }

    // 3. 来源访问控制：校验 X-Origin-Service 标头白名单
    @GetMapping("/admin/secure")
    public Result<String> adminSecure() {
        return Result.success("admin-ok");
    }

    // 4. 注解切面方法：无需手写 blockHandler，异常逃逸时由切面统一捕获对齐
    @GetMapping("/annotation-resource")
    @SentinelResource(value = "custom-annotation-resource")
    public Result<String> testAnnotationResource() {
        return Result.success("annotation-resource-pass");
    }

    // 5. 热点参数限流：按参数值统计流控
    @GetMapping("/param-flow")
    @SentinelResource(value = "custom-param-resource")
    public Result<String> testParamFlow(@RequestParam("itemId") String itemId) {
        return Result.success("item-" + itemId);
    }
}
```

---

## 维度二：生产扩展接管 (Production Extensibility)

### 1. 自定义受管 Feign 降级工厂 (重写保底值)

框架提供 `AtengFallbackFactory<T>` 基类。默认情况下，所有降级方法均通过动态代理自动抛出语义化 `BusinessException`。若业务需要为特定方法提供柔性保底值（如返回离线默认数据），可通过重写 `createFallbackInstance` 优雅扩展：

```java
package io.github.atengk.cloud.sample.sentinel.client;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.cloud.sentinel.fallback.AtengFallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 柔性保底降级工厂实现示例
 */
@Component
public class DemoCustomFallbackFactory extends AtengFallbackFactory<DemoCustomFeignClient> {

    @Override
    protected DemoCustomFeignClient createFallbackInstance(Throwable cause) {
        return new DemoCustomFeignClient() {
            @Override
            public Result<String> getUserName(Long id) {
                // 业务柔性保底返回，不中断主流程
                return Result.success("fallback-user-" + id);
            }

            @Override
            public Result<Integer> getUserAge(Long id) {
                // 保底返回默认值
                return Result.success(18);
            }
        };
    }
}
```

### 2. 接管 Nacos 数据源构造 (SPI 替换 / 脱网测试桩)

通过实现 `NacosDataSourceFactory` 并在 Spring 容器中声明 Bean，可平滑接管框架的数据源构建逻辑（例如在单元测试中注入内存数据源）：

```java
@Configuration
public class CustomDataSourceConfig {

    @Bean
    public NacosDataSourceFactory nacosDataSourceFactory() {
        return new NacosDataSourceFactory() {
            @Override
            public <T> ReadableDataSource<String, T> createDataSource(
                    Properties properties, String groupId, String dataId, Converter<String, T> converter) {
                // 自定义构造逻辑或返回测试内存数据源
                return new MyCustomReadableDataSource<>(converter);
            }
        };
    }
}
```

### 3. 柔性降级响应契约切换 (关闭 HTTP 状态码物理对齐)

生产中部分遗留前端网关要求所有请求物理状态码均为 `200 OK`，只需在配置中关闭物理对齐开关：

```yaml
ateng:
  cloud:
    sentinel:
      block-handler:
        http-status-align: false  # 物理返回 200 OK，仅 Result 体现 code=4200
      block-advice:
        http-status-align: false
```

---

## 维度三：核心生产边界与非功能语义 (Production Boundaries)

### 1. RESTful 资源监控树内存无界防爆机制 (Resource Tree Explosion Defense)

在 Spring MVC 中，对于诸如 `/api/v1/orders/10001`、`/api/v1/orders/10002` 的 RESTful 接口，若原生 Sentinel 未做路径收敛，会将每个具体数字 URL 视为一个独立资源注册到 `ClusterNode` 监控树中，造成大量内存句柄无法回收，最终引发 JVM 堆内存 OOM 崩溃。

`AtengUrlCleaner` 实施**双层纵深防爆清洗**：
1. **第一层（Spring MVC 模式提取）**：优先从当前请求的 `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` 属性中提取路由模式（如 `/api/v1/orders/{id}`），直接收敛为单一静态资源；
2. **第二层（正则兜底收敛）**：若脱离 Spring MVC 路由上下文，保底通过高性能预编译正则表达式，将 URL 路径片段中的纯数字与标准 36 位 UUID 替换为 `{id}` 与 `{uuid}`。

### 2. 阻断异常契约双重对齐 (Block Dual-Alignment Contract)

开源 Sentinel 默认阻断时仅输出裸文本（如 `"Blocked by Sentinel (flow limiting)"`），未与微服务标准的强类型契约自洽。

`ateng-cloud-sentinel` 遵循**双重对齐契约**：
- **物理传输层**：限流、热点、系统、授权统一对齐 `HTTP 429 Too Many Requests`；熔断降级统一对齐 `HTTP 503 Service Unavailable`；
- **业务载荷层**：统一输出 `application/json;charset=UTF-8` 格式的强类型 `Result<Void>` 结构体：
  ```json
  {
    "code": 4200,
    "msg": "服务流量过载，触发限流保护",
    "data": null,
    "timestamp": 1793258866760,
    "success": false
  }
  ```

### 3. Feign 熔断防呆自动注入 (Foolproof Feign Activation)

原生 Spring Cloud OpenFeign 必须手动配置 `feign.sentinel.enabled=true` 才能激活熔断切面。开发者极易漏配导致熔断静默失效。

`AtengSentinelEnvironmentPostProcessor` 会在 Spring 容器启动前探测 Classpath：若检测到包含 `feign.Feign`，且用户未显式配置 `feign.sentinel.enabled`，则自动将其置为 `true`；若用户显式声明 `false`，则遵循用户意图不覆盖。

### 4. 注解切面逃逸兜底防御 (Aspect Escape Defense)

在业务方法上标注 `@SentinelResource` 时，若未手写对应的 `blockHandler`，Sentinel 切面抛出的 `BlockException` 会逃逸至最外层，被默认全局异常处理器误判为 `500 Internal Server Error`，造成监控误报警。

`SentinelBlockExceptionAdvice` 专职捕获 `BlockException` 与反射包装的 `UndeclaredThrowableException`，在切面边界将其安全拦截并按标准 429/503 及 `SentinelResultCode` 转译输出。

### 5. Nacos 5 大规则全家桶约定持久化与生命周期闭环

原生 Sentinel 规则保存在内存中，重启即失。`SentinelNacosRuleSubscriber` 建立全量 5 种规则数据源桥接：

```mermaid
flowchart LR
    Nacos[("Nacos 配置中心\n(SENTINEL_GROUP)")] -->|${prefix}-flow.json| FlowDS["FlowRule NacosDataSource"]
    Nacos -->|${prefix}-degrade.json| DegradeDS["DegradeRule NacosDataSource"]
    Nacos -->|${prefix}-param-flow.json| ParamDS["ParamFlowRule NacosDataSource"]
    Nacos -->|${prefix}-system.json| SystemDS["SystemRule NacosDataSource"]
    Nacos -->|${prefix}-authority.json| AuthDS["AuthorityRule NacosDataSource"]

    FlowDS --> FlowMgr["FlowRuleManager"]
    DegradeDS --> DegradeMgr["DegradeRuleManager"]
    ParamDS --> ParamMgr["ParamFlowRuleManager"]
    SystemDS --> SystemMgr["SystemRuleManager"]
    AuthDS --> AuthMgr["AuthorityRuleManager"]
```

- **约定优于配置**：统一按照 `${dataIdPrefix}-{type}.json` 建立订阅；
- **健壮反序列化**：通过定制 `ObjectMapper` 忽略未知属性，非法 JSON 优雅告警并返回空列表，杜绝单条规则格式瑕疵导致监听线程挂死；
- **确定性资源清理**：实现 `DisposableBean`，在应用关闭时依次关闭底层 `NacosDataSource` 与后台更新线程池，杜绝句柄泄露。

---

## 维度四：物理排版与代码全真验证

本模块所有 Java 示例代码与 YAML 配置片段均来自场景样例工程 [sample-cloud-sentinel](../ateng-cloud-samples/sample-cloud-sentinel) 的实测用例，并受以下端到端测试 100% 离线自洽守护：

- `SentinelWebFlowDegradeTest`：验证 WebMVC 入站 QPS 限流与异常降级阻断；
- `SentinelUrlCleanAuthorityTest`：验证 RESTful 路径清洗归一化与调用来源白名单防刷；
- `SentinelFeignFallbackTest`：验证 Feign 客户端熔断触发受管降级工厂代理与柔性保底；
- `SentinelAnnotationAdviceTest`：验证 `@SentinelResource` 注解逃逸切面拦截；
- `SentinelNacosRuleDynamicRefreshTest`：验证从脱网 Mock Nacos 动态推送 5 大规则 JSON，强断言规则被热加载并即时生效。
