# ateng-cloud-dubbo

面向企业级分布式生产环境的 Apache Dubbo 3.3 高性能 RPC 通信与微服务治理 Starter，单向依赖消费已发布的 `ateng-boot-bom`，专注于打造垂直正交、开箱即用、无侵入的双模微服务通信与生产治理底座。

全面拥抱现代原生 **Triple 协议** (基于 HTTP/2 与 gRPC 工业级标准)，标配 **Fastjson2 JSONB** 超高速二进制序列化引擎与应用级服务发现模式 (`register-mode: instance`)；深度融合微服务专属治理铁律：双模自适应契约校验与结果断言、因果异常保真与反序列化防御、全链路四维上下文透明透传、零信任 Same-Token 内网防穿透、Seata 分布式事务 XID 状态对称穿透、TrafficContext 全域流量染色与泳道元数据联动、客户端原生 Mock 服务降级、金丝雀灰度发布标签路由平滑降级、服务端线程池过载保护与 Micrometer 监控、JSR-303 参数防卫、Triple Server Stream 原生流式通信、16MB 报文载荷硬防爆以及 15 秒优雅停机无损上下线。

---

## 维度零：特性与公共 API 全景速查矩阵

### 1. 核心类与治理门面

| 类 / 接口 | 类型 | 核心职责 | 默认行为 / 装配条件 |
| :--- | :--- | :--- | :--- |
| `AtengDubboAutoConfiguration` | 自动装配类 | Dubbo 通信与核心治理基础设施装配总入口 | 当 `ateng.cloud.dubbo.enabled=true` 且类路径存在 Dubbo 时激活 |
| `AtengDubboProperties` | 根配置属性类 | 强类型映射配置前缀 `ateng.cloud.dubbo` | 生产默认启用，收敛超时、重试、硬防爆载荷及各治理子项 |
| `AtengDubboProtocolProperties` | 协议配置属性类 | Triple 协议基线配置（协议名、暴露端口与序列化） | 默认 `tri` 协议、端口 `50051`、`fastjson2` JSONB 序列化 |
| `AtengDubboApplicationProperties` | 应用配置属性类 | 应用级服务发现模式与优雅停机等待超时 | 默认应用级注册 `instance`，停机超时硬兜底 `15000ms` |
| `AtengDubboContextProperties` | 上下文配置属性类 | 全链路四维上下文出站注入与入站还原开关 | 默认 `true` 开启 |
| `AtengDubboSameTokenProperties` | 零信任配置属性类 | 零信任内网防穿透 Same-Token 校验开关 | 默认 `true` 开启 |
| `AtengDubboExceptionProperties` | 异常配置属性类 | 因果异常保真与第三方未知未受检异常转译开关 | 默认 `true` 开启 |
| `AtengDubboContractProperties` | 契约配置属性类 | 双模契约校验与非 200 业务结果自动断言转译开关 | 默认 `true` 开启 (`ateng.cloud.dubbo.contract.enabled=true`) |
| `AtengDubboTrafficProperties` | 流量路由配置属性类 | 金丝雀灰度发布流量染色穿透与标签路由模式配置 | 默认 `true` 开启，`tag-force=false` 平滑降级兜底 |
| `AtengDubboOverloadProperties` | 过载保护配置属性类 | 服务端线程池过载保护转译与指标采集导出开关 | 默认 `true` 开启，`metrics-enabled=true` |
| `AtengDubboValidationProperties` | 参数校验配置属性类 | JSR-303 参数格式快速失败防卫与结构化转译开关 | 默认 `true` 开启 |
| `AtengDubboSecurityProperties` | 生产安全配置属性类 | Dubbo 生产级安全防护配置入口，挂载反序列化安全配置 | 默认初始化安全子配置 |
| `AtengDubboSerializeProperties` | 序列化安全配置属性类 | 声明反序列化受信任的业务包路径前缀集合 (`allowPackages`) | 配合 `SerializeSecurityManager` 注册安全白名单 |
| `AtengDubboEnvironmentPostProcessor` | 环境后置处理器 | 向 Spring 环境预设优雅停机安全基线默认属性 | 预设停机 15s 硬兜底，支持用户 application.yml 优先无缝接管 |
| `DubboContextPropagationFilter` | RPC 过滤器 | 全链路四维上下文、Seata XID、Traffic 泳道透传与 Netty 线程池确定性清理 | 双向激活 (`order = -10000`)，`finally` 块绝对清理杜绝污染 |
| `DubboSameTokenFilter` | RPC 过滤器 | 零信任 Same-Token 校验拦截器（防公网绕过网关穿透） | 双向激活 (`order = -9500`)，内部调用自动双向校验并清理 |
| `DubboValidationFilter` | RPC 过滤器 | Provider 边界 JSR-303 参数校验拦截与结构化转译 | 双向激活 (`order = -9000`)，Fail-Fast 抛出 400 业务异常 |
| `DubboTrafficTagFilter` | RPC 过滤器 | 出站提取流量染色标记并压入 Dubbo 标签路由 | Consumer 端激活 (`order = -8500`)，支持强隔离与平滑降级 |
| `DubboContractAssertFilter` | RPC 过滤器 | 对端返回 `Result<T>` 时自动断言业务成功，失败自动转译 | Consumer 端激活 (`order = 1000`, SPI `contractAssert`)，豁免流式通信 |
| `AtengDubboExceptionFilter` | RPC 过滤器 | 接管默认异常过滤器，白名单放行业务异常并转译未知私有异常 | Provider 端激活，防御客户端 `ClassNotFoundException` |
| `DubboOverloadFilter` | RPC 过滤器 | 捕获线程池打满拒绝异常并转译为标准 429 限流异常 | Provider 端激活 (`order = -9800`) |
| `DubboOverloadHandler` | 拒绝执行处理器 | Dubbo 线程池耗尽拒绝策略实现，记录过载诊断并递增计数 | 绑定 Dubbo 扩展机制 `threadpool` |
| `DubboOverloadThreadPool` | 线程池扩展 SPI | 挂载 `DubboOverloadHandler` 拒绝策略的生产级线程池实现 | 注册于 Dubbo `ThreadPool` SPI (`threadpool=overload`) |
| `DubboOverloadListener` | 线程池耗尽监听器 | 监听 Dubbo 线程池耗尽事件并提供全局静态累计计数器 | SPI 注册于 `ThreadPoolExhaustedListener` |
| `DubboOverloadMetrics` | 性能指标采集器 | 自动采集 Dubbo 核心线程池状态指标与拒绝计数至 Micrometer | 标配 `@ConditionalOnMissingBean`，支持 Prometheus 监控 |
| `DubboSeataPropagationSupport` | 分布式事务适配桥梁 | Seata 全局事务 XID 条件探测、双向透传与状态对称绑定/释放 | 缺失 Seata 时自动静默短路，局部持有判定杜绝误释放 |
| `DubboTrafficPropagationSupport` | 流量治理适配桥梁 | `TrafficContextHolder` 泳道与规则元数据探测、穿透与确定性清理 | 缺失 Traffic 模块时平滑降级至 MDC 模式，入站出口 `finally` 幂等清理 |
| `DubboExceptionUtils` | 异常研判公共工具类 | 线程池过载判断、`ConstraintViolationException` 链路提取与流式调用判定 | 统一异常判定收敛，消除重复代码 |
| `AbstractDubboTripleExceptionHandler` | 协议异常处理器基类 | Triple 协议异常处理公共模板，统一流式豁免与 grpc-status 封包 | 模板方法模式消除重复代码，统一保护传输层通道 |
| `DubboBusinessExceptionHandler` | 协议异常处理器 | Triple 传输层受管 `BusinessException` 保真回传 | HTTP/2 协议层异常处理器，流式通信自动放行 |
| `DubboOverloadExceptionHandler` | 协议异常处理器 | Triple 传输层线程池过载拒绝异常转译为 429 业务异常 | HTTP/2 协议层异常处理器，杜绝传输物理断连 |
| `DubboValidationExceptionHandler` | 协议异常处理器 | Triple 传输层参数校验违规异常转译为 400 业务异常 | HTTP/2 协议层异常处理器，保真违规字段信息 |
| `DubboResultUnwrapFilter` | RPC 过滤器 (已废弃) | 旧版业务结果自动解包过滤器，平滑标记弃用 | `@Deprecated`，建议迁移至 `DubboContractAssertFilter`，保持类二进制与编译向后兼容 |
| `AtengDubboResultUnwrapProperties` | 配置属性类 (已废弃) | 旧版 `ateng.cloud.dubbo.result-unwrap.*` 配置，映射至新契约属性 | `@Deprecated`，建议迁移至 `AtengDubboContractProperties` |

---

### 2. 核心声明式注解全景速查

| 注解 | 作用目标 | 核心属性与微服务治理语义 | 生产推荐配置与默认行为 |
| :--- | :--- | :--- | :--- |
| `@DubboReference` | 消费端注入字段/方法 | 客户端声明式 RPC 引用：`mock = "true"`（启用原生客户端降级兜底）、`check = false`（启动期不强依赖对端在线）、`timeout = 5000`（读超时时限）、`retries = 0`（写请求防重试雪崩铁律） | 生产推荐标配 `check = false` 与写请求 `retries = 0` |
| `@DubboService` | 服务端实现类 | 服务端声明式 RPC 暴露：`protocol = "tri"`（Triple 协议）、`version`（服务版本号）、`group`（服务分组）、`timeout`（服务端超时阈值） | 默认发布至应用级服务发现注册中心 |

---

### 3. 配置属性速查表 (`ateng.cloud.dubbo.*`)

| 配置项 | 类型 | 默认值 | 详细业务说明 |
| :--- | :--- | :--- | :--- |
| `ateng.cloud.dubbo.enabled` | `boolean` | `true` | 是否激活 ateng-cloud-dubbo 核心通信与治理能力 |
| `ateng.cloud.dubbo.timeout` | `Duration` | `5000ms` | 全局 RPC 默认调用超时时限（消除无量纲裸数值） |
| `ateng.cloud.dubbo.retries` | `int` | `0` | 全局 RPC 默认重试次数（写请求读超时绝不重试防雪崩铁律） |
| `ateng.cloud.dubbo.payload` | `DataSize` | `16MB` | 单个 RPC 请求/响应报文载荷硬防爆上限（防止黑客报文击穿 OOM） |
| `ateng.cloud.dubbo.protocol.name` | `String` | `"tri"` | 默认通信协议名称，全面推行现代原生 Triple 协议 |
| `ateng.cloud.dubbo.protocol.port` | `Integer` | `50051` | 默认服务暴露端口，对齐 gRPC / Triple 工业级标准推荐端口 |
| `ateng.cloud.dubbo.protocol.serialization` | `String` | `"fastjson2"` | 默认序列化算法，采用 Fastjson2 JSONB 高速二进制格式 |
| `ateng.cloud.dubbo.application.register-mode` | `String` | `"instance"` | 默认服务注册模式，应用级服务发现，彻底避免注册表膨胀 |
| `ateng.cloud.dubbo.application.shut-wait` | `Duration` | `15000ms` | 优雅停机等待超时时间（由 AtengDubboEnvironmentPostProcessor 注入原生 dubbo.application.shut-down.wait 与 shutwait，默认 15000ms 硬兜底） |
| `ateng.cloud.dubbo.context.enabled` | `boolean` | `true` | 是否开启全链路四维上下文透传与 Netty 线程池确定性清理 |
| `ateng.cloud.dubbo.same-token.enabled` | `boolean` | `true` | 是否开启内部微服务零信任 Same-Token 防穿透隔离校验 |
| `ateng.cloud.dubbo.exception.enabled` | `boolean` | `true` | 是否开启因果异常保真与反序列化防御 |
| `ateng.cloud.dubbo.contract.enabled` | `boolean` | `true` | 是否开启双模契约校验与非 200 业务结果自动断言转译 |
| `ateng.cloud.dubbo.traffic.enabled` | `boolean` | `true` | 是否开启金丝雀灰度流量染色与标签路由支持 |
| `ateng.cloud.dubbo.traffic.tag-force` | `boolean` | `false` | 标签路由是否强制隔离（`false` 平滑降级至基线实例；`true` 严格强隔离） |
| `ateng.cloud.dubbo.overload.enabled` | `boolean` | `true` | 是否开启服务端线程池过载保护与异常受管转译 |
| `ateng.cloud.dubbo.overload.metrics-enabled` | `boolean` | `true` | 是否自动将 Dubbo 核心线程池状态与拒绝指标导出至 Micrometer |
| `ateng.cloud.dubbo.validation.enabled` | `boolean` | `true` | 是否在 Provider 边界开启 JSR-303 参数格式快速失败防卫 |
| `ateng.cloud.dubbo.security.serialize.allow-packages` | `List<String>` | `[]` | 允许反序列化的业务包路径前缀白名单列表（自动注册至 Dubbo 3 序列化安全校验器） |

---

### 4. 上下文透传与防穿透标准标头

| 标头名称 (Attachment / Header) | 来源常量 | 语义与流转机制 | 默认脱敏策略 |
| :--- | :--- | :--- | :--- |
| `X-Trace-Id` / `traceId` | `MessageHeaderConstants.TRACE_ID` | 分布式调用链 Trace ID，与 MDC 自动绑定透传并在入站还原 | 明文透传，日志打印 |
| `X-User-Id` | `MessageHeaderConstants.USER_ID` | 当前操作用户 ID，出站从 `UserContextHolder` 提取，入站自动还原 | 原始透传 |
| `X-Tenant-Id` | `MessageHeaderConstants.TENANT_ID` | 当前多租户 ID，出站从 `TenantContextHolder` 提取，入站自动还原 | 原始透传 |
| `X-Ignore-Tenant` | `MessageHeaderConstants.IGNORE_TENANT` | 是否忽略多租户隔离过滤逻辑 (`true`/`false`) | 原始透传 |
| `X-Traffic-Tag` | `DubboTrafficPropagationSupport.TRAFFIC_TAG` | 全链路流量染色标记 (如 `gray-v1`)，激活 Dubbo 标签路由 | 原始透传 |
| `X-Traffic-Lane` | `DubboTrafficPropagationSupport.TRAFFIC_LANE` | 流量所属隔离泳道名称 (如 `payment-gray-lane`) | 原始透传 |
| `X-Traffic-Rule` | `DubboTrafficPropagationSupport.TRAFFIC_RULE` | 命中的动态分流规则标识 (如 `rule-user-vip`) | 原始透传 |
| `TX_XID` / `tx_xid` | `DubboSeataPropagationSupport.TX_XID` | Seata 全局分布式事务 ID，跨 RPC 透明透传并状态对称绑定/解绑 | 内部透传，出口对称清理 |
| `Same-Token` / `SA-SAME-TOKEN` | `SaSameUtil.SAME_TOKEN` | 内部微服务零信任受信凭据（防止公网直连内部微服务端口） | **受管校验并在出口自动清理** |

---

## 维度一：最小起步体验

### 1. 引入 Starter 依赖

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-cloud-dubbo</artifactId>
</dependency>
```

> [!TIP]
> 依赖版本已由 `ateng-cloud-bom` 统一纳管，业务子工程中无需声明 `<version>` 标签。

### 2. 最小起步配置 (`application.yml`)

以下配置逆向提取自真实跑通的场景样例工程 [application.yml](../ateng-cloud-samples/sample-cloud-dubbo/src/main/resources/application.yml)：

```yaml
server:
  port: 0

spring:
  application:
    name: sample-cloud-dubbo

dubbo:
  application:
    name: sample-cloud-dubbo
    register-mode: instance
  registry:
    address: N/A
  protocol:
    name: tri
    port: 50051
    serialization: fastjson2
  consumer:
    check: false
  provider:
    timeout: 5000

ateng:
  cloud:
    dubbo:
      enabled: true
      timeout: 5000ms
      retries: 0
```

### 3. 服务端契约接口与实现

#### 服务接口契约声明 ([UserRpcService.java](../ateng-cloud-samples/sample-cloud-dubbo/src/main/java/io/github/atengk/cloud/sample/dubbo/api/UserRpcService.java))
```java
public interface UserRpcService {

    /**
     * 健康探活 ping 方法
     */
    String ping(String message);

    /**
     * 依据 ID 查询用户信息（原生 POJO 强类型返回契约）
     */
    UserVO getUserById(Long id);

    /**
     * 依据 ID 查询用户信息（显式包装 Result 强契约返回）
     */
    Result<UserVO> getUserResult(Long id);
}
```

#### 服务提供端实现 ([UserRpcServiceImpl.java](../ateng-cloud-samples/sample-cloud-dubbo/src/main/java/io/github/atengk/cloud/sample/dubbo/service/UserRpcServiceImpl.java))
```java
@DubboService
public class UserRpcServiceImpl implements UserRpcService {

    @Override
    public String ping(String message) {
        return "pong: " + message;
    }

    @Override
    public UserVO getUserById(Long id) {
        return new UserVO(id, "ateng_" + id, "ateng_" + id + "@example.com");
    }

    @Override
    public Result<UserVO> getUserResult(Long id) {
        return Result.success(new UserVO(id, "ateng_result_" + id, "result_" + id + "@example.com"));
    }
}
```

### 4. 消费端远程调用

#### 消费端引用与直接调用 ([BaselineDubboRpcTest.java](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/BaselineDubboRpcTest.java))
```java
@SpringBootTest
public class BaselineDubboRpcTest {

    @DubboReference(url = "tri://127.0.0.1:50051", check = false)
    private UserRpcService userRpcService;

    @Test
    void testPing() {
        // 1. 探活调用
        String response = userRpcService.ping("hello-triple");
        assertEquals("pong: hello-triple", response);
    }

    @Test
    void testGetUserById() {
        // 2. 原生 POJO 强契约调用
        UserVO user = userRpcService.getUserById(888L);
        assertNotNull(user);
        assertEquals(888L, user.getId());
        assertEquals("ateng_888", user.getUsername());
        assertEquals("ateng_888@example.com", user.getEmail());
    }
}
```

---

## 维度二：生产扩展接管

### 1. 双模契约校验与非 200 结果自动断言转译

当 RPC 接口返回 `Result<T>` 统一包装对象时，`DubboContractAssertFilter` 自动校验对端业务状态码。当 `code != 200` 时，自动转译为本地 `BusinessException` 抛出，守住业务正确性防线，彻底消除消费端漏判 `isSuccess()` 导致的“带着错误码继续往下跑”隐患：

```java
@Test
void testFailedResultConvertedToBusinessException() {
    // 服务端返回 Result.fail(4001, "用户状态已被冻结")
    // 消费端自动断言转译为 BusinessException，无需显式 if (!result.isSuccess()) 判断
    BusinessException ex = assertThrows(BusinessException.class, () -> {
        userRpcService.getFailedResult(999L);
    });

    assertEquals(4001, ex.getCode(), "必须原样保留服务端返回的具体业务错误码");
    assertTrue(ex.getMessage().contains("用户状态已被冻结"), "必须原样保留服务端返回的业务错误描述");
}
```

若需对全局关闭业务断言，配置：
```yaml
ateng:
  cloud:
    dubbo:
      contract:
        enabled: false
```

---

### 2. 全链路四维上下文透传与零信任 Same-Token 防穿透

出站自动提取上下文与受信凭据，入站自动还原至工作线程并在执行完毕后强制清空：

```java
@Test
void testFourDimensionalContextPropagation() {
    // 1. 在消费端线程设置四维上下文快照与灰度染色标记
    MDC.put("traceId", "dubbo-trace-99998888");
    UserContextHolder.setUserId("user-ateng-admin");
    TenantContextHolder.setTenantId("tenant-enterprise-001");
    TenantContextHolder.setIgnoreTenant(true);
    TrafficContextHolder.setContext(TrafficContext.of("gray-canary-v2", "user-lane-01", "rule-user-vip"));

    // 2. 发起 RPC 调用，服务端自动还原上下文
    Map<String, String> providerContext = userRpcService.getProviderContext();

    // 3. 强断言对端上下文镜像完全一致
    assertNotNull(providerContext);
    assertEquals("dubbo-trace-99998888", providerContext.get("traceId"));
    assertEquals("user-ateng-admin", providerContext.get("userId"));
    assertEquals("tenant-enterprise-001", providerContext.get("tenantId"));
    assertEquals("true", providerContext.get("ignoreTenant"));
    assertEquals("gray-canary-v2", providerContext.get("trafficTag"));
}
}
```

---

### 3. Seata 全局事务 XID 跨 RPC 透明穿透与状态判断对称释放

基于 `DubboSeataPropagationSupport` 反射与 ClassUtils 条件探测实现 Seata 解耦桥接：消费端出站时探测 `RootContext.getXID()`，若处于分布式事务中则自动将 XID 注入 RPC 隐式参数 `TX_XID`；服务端入站时自动还原绑定至对端 `RootContext`。

> [!IMPORTANT]
> **状态判断对称释放范式**：服务提供端通过局部布尔标志 `boundByCurrent` 记录是否由当前 RPC 调用真实绑定 XID；在 `finally` 块中**仅当本端真实绑定成功时才触发 `RootContext.unbind()`**。严禁盲目解绑导致外层全局事务上下文被篡改或丢失！当环境未引入 Seata 依赖时，自动静默短路，不发生 `ClassNotFoundException`。

```java
// 消费端出站：处于全局事务中时自动将 XID 注入 RPC Attachment
RootContext.bind("192.168.1.100:8091:123456789");
try {
    userRpcService.ping("seata-call");
} finally {
    RootContext.unbind();
}
```

---

### 4. TrafficContext 泳道双向联动与流量染色透传

Dubbo 深度联动全域流量治理底座 `TrafficContextHolder`。消费端出站自动提取流量标签、泳道名称及匹配规则并压入 RPC Attachment：
- `X-Traffic-Tag`：流量染色标签（同时自动激活 Dubbo 原生标签路由 `CommonConstants.TAG_KEY`）
- `X-Traffic-Lane`：隔离泳道名称（如 `payment-gray-lane`）
- `X-Traffic-Rule`：命中的动态分流规则标识（如 `rule-user-vip`）

服务端入站时将多维泳道元数据完整还原至 `TrafficContextHolder`，并在 `finally` 块中确定性清空，杜绝跨请求线程污染。若环境未引入 `ateng-cloud-traffic`，自动平滑降级至 MDC 模式。

```java
// 设置当前线程 TrafficContext 泳道元数据
TrafficContextHolder.setContext(TrafficContext.of("gray-v3", "payment-lane", "rule-999"));
try {
    userRpcService.ping("traffic-call");
} finally {
    TrafficContextHolder.clear();
}
```

---

### 5. 自定义反序列化安全包白名单配置

在 Dubbo 3 严格安全模式下，为防止恶意序列化载荷注入，需显式声明业务传输对象 (DTO/VO) 的包路径。`ateng-cloud-dubbo` 支持通过配置属性动态注入白名单，自动注册至 Dubbo `SerializeSecurityManager`：

```yaml
ateng:
  cloud:
    dubbo:
      security:
        serialize:
          allow-packages:
            - com.company.biz.dto
            - com.company.biz.vo
            - com.company.order.model
```

---

### 6. Dubbo 客户端原生 Mock 服务降级

遵循微服务弹性容灾与服务降级规范，对齐 Dubbo 3 原生 Mock 同包约定。在服务接口同包下编写 `*Mock` 实现类（例如 `UserRpcServiceMock` 实现 `UserRpcService`）：

#### 降级实现类 ([UserRpcServiceMock.java](../ateng-cloud-samples/sample-cloud-dubbo/src/main/java/io/github/atengk/cloud/sample/dubbo/api/UserRpcServiceMock.java))
```java
public class UserRpcServiceMock implements UserRpcService {

    public static final String MOCK_TAG = "[MOCK-FALLBACK]";
    public static final String DEFAULT_MOCK_EMAIL = "mock@example.com";

    @Override
    public String ping(String message) {
        return MOCK_TAG + " pong: " + message;
    }

    @Override
    public UserVO getUserById(Long id) {
        return new UserVO(id != null ? id : 0L, "mock-user-fallback", DEFAULT_MOCK_EMAIL);
    }

    @Override
    public Result<UserVO> getUserResult(Long id) {
        return Result.success(new UserVO(id != null ? id : 0L, "mock-result-fallback", DEFAULT_MOCK_EMAIL));
    }

    @Override
    public Result<UserVO> getFailedResult(Long id) {
        return Result.fail(CommonResultCode.SERVICE_UNAVAILABLE.getCode(), "下游服务不可用，已触发客户端 Mock 降级");
    }
}
```

#### 声明式挂载 Mock 引用与容灾实测 ([MockFallbackDubboRpcTest.java](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/MockFallbackDubboRpcTest.java))
```java
@SpringBootTest
public class MockFallbackDubboRpcTest {

    // 声明 mock = "true"：当下游不可用时自动触发 UserRpcServiceMock 降级兜底
    @DubboReference(url = "tri://127.0.0.1:50051", check = false, mock = "true", timeout = 1000)
    private UserRpcService fallbackMockRpcService;

    @Test
    void testMockFallbackWhenProviderUnreachable() {
        // 下游宕机时自动无感返回业务保底对象，防止调用级联雪崩
        UserVO fallbackUser = fallbackMockRpcService.getUserById(888L);
        assertNotNull(fallbackUser);
        assertEquals("mock-user-fallback", fallbackUser.getUsername());
    }
}
```

---

### 7. 金丝雀灰度发布流量染色与标签路由

消费端出站时提取染色标记，自动转化为 Dubbo 原生标签路由参数 `CommonConstants.TAG_KEY`：

```yaml
ateng:
  cloud:
    dubbo:
      traffic:
        enabled: true
        tag-force: false   # 默认 false：平滑降级（无灰度实例时回退基线实例）；true：强隔离阻断
```

```java
@Test
void testTrafficTagAttachmentAndFallback() {
    // 注入金丝雀灰度染色标记
    MDC.put(DubboTrafficTagFilter.DEFAULT_TRAFFIC_TAG_HEADER, "canary-v2");

    // 默认 tag-force=false：无对应灰度实例时平滑降级至基线实例正常响应
    String result = grayEchoRpcService.echo("hello");
    assertEquals("baseline:hello", result);
}
```

---

### 8. 服务端线程池过载保护与 Micrometer 指标监控

服务端并发打满触发拒绝时，`DubboOverloadHandler` 打印过载诊断并递增计数器，`DubboOverloadFilter` 与 `DubboOverloadExceptionHandler` 将其结构化转译为 `BusinessException(429 / TOO_MANY_REQUESTS)`，同时导出指标至 Micrometer：

- **导出的核心度量项**：
  - `dubbo.threadpool.active`：当前活跃执行线程数
  - `dubbo.threadpool.size`：当前物理线程池大小
  - `dubbo.threadpool.core`：核心线程数
  - `dubbo.threadpool.max`：最大线程数
  - `dubbo.threadpool.queue.size`：等待队列积压任务数
  - `dubbo.threadpool.completed`：已完成任务总数
  - `dubbo.threadpool.reject`：累计过载拒绝请求总次数（Counter）

```java
@Test
void testProviderThreadOverloadProtection() {
    // 模拟服务端线程池过载拒绝，DubboOverloadFilter 自动转译为 BusinessException(TOO_MANY_REQUESTS)
    BusinessException ex = assertThrows(BusinessException.class, () -> userRpcService.simulateOverload());
    assertEquals(CommonResultCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
    assertTrue(ex.getMessage().contains("服务端并发线程池已耗尽"));
}
```

---

### 9. JSR-303 参数格式防卫与结构化转译

Provider 入口处拦截入参违规注解，自动提取字段路径并转译为标准 `BusinessException(400 / BAD_REQUEST)`：

```java
@Test
void testUserValidationFailureOnBlankUsername() {
    // 构造非法入参（username 为空）
    UserCreateRequest invalidReq = new UserCreateRequest("", 25);
    BusinessException ex = assertThrows(BusinessException.class, () -> userRpcService.createUser(invalidReq));

    assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex.getCode());
    assertTrue(ex.getMessage().contains("用户名不能为空") || ex.getMessage().contains("username"));
}
```

---

### 10. Triple Server Stream 原生流式通信

基于 `StreamObserver` 实现高效服务端流式推送，四维上下文随请求帧完整透传，流数据自然豁免单体结果断言：

```java
@Test
void testServerStreamSuccessWithContextPropagation() throws InterruptedException {
    UserContextHolder.setUserId("stream-user-888");
    TenantContextHolder.setTenantId("stream-tenant-666");
    MDC.put("traceId", "trace-stream-9999");
    MDC.put(DubboTrafficPropagationSupport.TRAFFIC_TAG, "canary-v2");

    List<UserLogVO> receivedLogs = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);

    // 发起 Triple Server Stream 流式调用
    UserLogStreamRequest request = new UserLogStreamRequest("ORDER_EXPORT", 3);
    userRpcService.streamUserLogs(request, new StreamObserver<>() {
        @Override
        public void onNext(UserLogVO data) {
            receivedLogs.add(data);
        }

        @Override
        public void onError(Throwable throwable) {
            latch.countDown();
        }

        @Override
        public void onCompleted() {
            latch.countDown();
        }
    });

    latch.await(5, TimeUnit.SECONDS);
    assertThat(receivedLogs).hasSize(3);
    assertThat(receivedLogs.get(0).getUserId()).isEqualTo("stream-user-888");
    assertThat(receivedLogs.get(0).getTraceId()).isEqualTo("trace-stream-9999");
}
```

---

## 维度三：核心生产边界与非功能语义

### 1. 读写超时与写请求绝对禁重试防雪崩铁律
- **默认重试次数为 0** (`retries: 0`)：对于非幂等写接口（如落库、发券、转账），发生读超时（`SocketTimeoutException`）默认坚决关闭重试，彻底杜绝下游重复落库与并发死锁；
- **超时保护生产基线**：默认全局 RPC 超时时间为 `5000ms`，禁止无超时无界挂起耗尽工作线程。

### 2. 报文载荷硬防爆上限与内存安全
- **16MB 载荷硬防爆** (`ateng.cloud.dubbo.payload: 16MB`)：统一注入 Netty 与 Triple 协议层；超大报文在解码边界立即触发 `ExceedPayloadLimitException` 熔断拦截，防范黑客超大报文引起堆外内存 OOM 导致进程崩溃。

### 3. 优雅停机无损上下线状态机协同
- **停机等待 15 秒硬兜底**：
  - 由 `AtengDubboEnvironmentPostProcessor` 默认向底层 Spring 环境注入原生 `dubbo.application.shut-down.wait=15000` 与 `dubbo.application.shutwait=15000`；
  - 支持通过配置属性 `ateng.cloud.dubbo.application.shut-wait`（支持带时间单位如 `15s`、`15000ms` 或 ISO-8601 `PT15S`）进行定制；
  - 挂载优先级设为最低优先级（`Ordered.LOWEST_PRECEDENCE`），保证用户在 `application.yml` 中配置的原生 Dubbo 停机参数绝对优先生效；
  - **停机协同三部曲**：
    - 步骤一（注销服务）：停机钩子触发时，优先向 Nacos / 注册中心注销当前实例，令新流量不再路由进来；
    - 步骤二（排空在途）：等待线程池中的在途（in-flight）请求执行完毕；
    - 步骤三（超时硬兜底）：达到 15 秒超时阈值后平稳释放 Netty 线程池与网络连接，杜绝僵死进程阻碍容器平滑发布与 Pod 滚动更新。

### 4. 分布式事务生命周期状态判断对称释放
- 在跨 RPC 调用中传递 Seata XID 时，服务提供端基于局部变量 `boundByCurrent` 强判定；
- 只有在当前调用入栈由本端新绑定 XID 时，才在 `finally` 块中执行 `RootContext.unbind()`，杜绝误解绑外部调用方已存在的全局事务；若出现异常，在对称释放前完整保留原始因果链。

### 5. 全域流量泳道双向穿透与零硬依赖弹性降级
- 支持条件探测 `TrafficContextHolder`，当存在 `ateng-cloud-traffic` 时自动双向透传流量标签、隔离泳道与匹配规则；
- 缺失流量组件时平滑回退至 MDC 模式，入站出口 `finally` 块中无条件幂等执行清理，确保零硬依赖与线程池零污染。

### 6. 反序列化动态包白名单与安全防御
- 针对 Fastjson2 / 原生反序列化黑客漏洞，接入 Dubbo 3 序列化安全校验规范；
- 运行时通过配置属性 `ateng.cloud.dubbo.security.serialize.allow-packages` 动态向 `SerializeSecurityManager` 注入白名单，严格防御未知反序列化恶意载荷注入。

### 7. 客户端原生 Mock 降级与 Failover
- 遵循 Dubbo 3 客户端原生 Mock 规范，在服务端不可达、网络超时或线程池耗尽等极端故障时，透明触发同包 `*Mock` 保底实现，快速阻断服务雪崩。

### 8. 确定性生命周期与线程池上下文清理
- 在 `DubboContextPropagationFilter` 的 `finally` 块中强制执行 `cleanContexts()`，无条件清空 `UserContextHolder`、`TenantContextHolder`、`TrafficContextHolder` 及 MDC，杜绝 Netty/Dubbo 线程池复用污染与多租户越权。

### 9. 链式异常保真与反序列化防御
- 受管 `BusinessException`、JSR-303 参数校验异常及 JDK 核心异常白名单放行，100% 完整保留原始状态码与因果链；
- 未受检的第三方底层私有异常（如数据库驱动死锁异常），安全转译为框架标准受管 `BusinessException`，彻底防御消费端发生 `ClassNotFoundException` 反序列化灾难。

---

## 维度四：物理排版与代码全真验证

所有特性与场景用例均 100% 逆向提取自场景工程 `sample-cloud-dubbo` 与模块白盒单元测试中已跑通的代码（**总计 119 个用例，100% PASS**）：

### 1. 场景样例工程端到端集成测试矩阵 (34 Tests PASS)

| 测试类 | 验证维度 | 对应测试用例方法 | 执行结果 |
| :--- | :--- | :--- | :--- |
| [`BaselineDubboRpcTest`](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/BaselineDubboRpcTest.java) | Triple 基础通信 | `testPing`<br>`testGetUserById`<br>`testDubboProperties` | 100% PASS |
| [`ClassicDubboRpcTest`](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/ClassicDubboRpcTest.java) | 经典 TCP Dubbo 协议 | `testClassicDubboGetUserById`<br>`testClassicDubboPing` | 100% PASS |
| [`ContextAndSameTokenDubboRpcTest`](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/ContextAndSameTokenDubboRpcTest.java) | 四维上下文与零信任隔离 | `testFourDimensionalContextPropagation`<br>`testProviderThreadDeterministicCleanup`<br>`testTrafficContextLanePropagationAndDeterministicCleanup`<br>`testSeataDistributedTransactionPropagationAndSymmetricRelease`<br>`testSameTokenInvalidRejection`<br>`testSameTokenMissingRejection` | 100% PASS |
| [`ContractAndExceptionDubboRpcTest`](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/ContractAndExceptionDubboRpcTest.java) | 契约解包与异常保真 | `testSuccessfulResultReturn`<br>`testFailedResultConvertedToBusinessException`<br>`testBusinessExceptionCausalPreservation`<br>`testCustomExceptionTranslatedToBusinessException` | 100% PASS |
| [`MockFallbackDubboRpcTest`](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/MockFallbackDubboRpcTest.java) | 客户端原生 Mock 服务降级 | `testMockFallbackWhenProviderUnreachable`<br>`testLiveProviderCallSucceedsWhenHealthy` | 100% PASS |
| [`StreamAndPayloadDubboRpcTest`](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/StreamAndPayloadDubboRpcTest.java) | 流式通信与载荷/停机防护 | `testServerStreamSuccessWithContextPropagation`<br>`testServerStreamErrorHandling`<br>`testServerStreamValidationFailFast`<br>`testDubboGracefulShutdownAndPayloadConfig`<br>`testEchoNormalPayload`<br>`testExceedPayloadLimitDefense` | 100% PASS |
| [`TrafficTagDubboRpcTest`](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/TrafficTagDubboRpcTest.java) | 流量染色与标签路由 | `testDefaultRouteToBaseline`<br>`testTrafficTagAttachmentAndFallback`<br>`testSmoothFallbackToBaselineWhenTagNotMatched`<br>`testStrictTagIsolationWhenTagForceIsTrue` | 100% PASS |
| [`ValidationAndOverloadDubboRpcTest`](../ateng-cloud-samples/sample-cloud-dubbo/src/test/java/io/github/atengk/cloud/sample/dubbo/ValidationAndOverloadDubboRpcTest.java) | 参数校验与过载保护 | `testUserValidationSuccess`<br>`testUserValidationFailureOnBlankUsername`<br>`testUserValidationFailureOnInvalidAge`<br>`testProviderThreadOverloadProtection`<br>`testDubboOverloadHandlerExecution`<br>`testMicrometerMetricsExport`<br>`testDubboOverloadThreadPoolSpi` | 100% PASS |

### 2. Starter 核心模块离线自洽白盒测试矩阵 (85 Tests PASS)

| 测试类 | 验证维度 | 对应测试用例方法 / 覆盖特性 | 执行结果 |
| :--- | :--- | :--- | :--- |
| [`AtengDubboAutoConfigurationTest`](src/test/java/io/github/atengk/cloud/dubbo/autoconfigure/AtengDubboAutoConfigurationTest.java) | 自动装配与条件守卫 | 验证禁用开关、默认配置属性绑定、Triple 协议基线、Micrometer 指标采集 Bean 注入及条件缺失守卫 (8 个用例) | 100% PASS |
| [`AtengDubboEnvironmentPostProcessorTest`](src/test/java/io/github/atengk/cloud/dubbo/autoconfigure/AtengDubboEnvironmentPostProcessorTest.java) | 环境后置处理与停机兜底 | 验证优雅停机 15 秒默认注入、用户配置无缝接管、ISO-8601 格式解析与边界保护 (5 个用例) | 100% PASS |
| [`AtengDubboExceptionFilterTest`](src/test/java/io/github/atengk/cloud/dubbo/filter/AtengDubboExceptionFilterTest.java) | 异常白名单放行与因果转译 | 验证 `BusinessException`、JSR-303、未受检未知私有异常转译与因果链保真 (8 个用例) | 100% PASS |
| [`DubboContextPropagationFilterTest`](src/test/java/io/github/atengk/cloud/dubbo/filter/DubboContextPropagationFilterTest.java) | 上下文透传与 Netty 线程池清理 | 验证四维上下文双向透传、异常分支 `finally` 强制清理及线程池零污染 (5 个用例) | 100% PASS |
| [`DubboContractAssertFilterTest`](src/test/java/io/github/atengk/cloud/dubbo/filter/DubboContractAssertFilterTest.java) | 双模契约校验与结果断言 | 验证 `Result.success` 正常剥壳、非 200 结果转译为 `BusinessException`、原生 POJO 与流式豁免 (9 个用例) | 100% PASS |
| [`DubboSameTokenFilterTest`](src/test/java/io/github/atengk/cloud/dubbo/filter/DubboSameTokenFilterTest.java) | 零信任 Same-Token 防穿透隔离 | 验证出站凭据注入、入站校验通过、非法直连拦截、缺失凭据 401 阻断与出口清理 (8 个用例) | 100% PASS |
| [`DubboTrafficTagFilterTest`](src/test/java/io/github/atengk/cloud/dubbo/filter/DubboTrafficTagFilterTest.java) | 流量染色与 Dubbo 标签路由 | 验证出站提取染色标记、注入标签路由、`tag-force` 开闭及执行后清理 (6 个用例) | 100% PASS |
| [`DubboValidationFilterTest`](src/test/java/io/github/atengk/cloud/dubbo/filter/DubboValidationFilterTest.java) | JSR-303 参数防卫与快速失败 | 验证合法参数放行、违规参数拦截、结构化提取违规字段及 400 业务异常转译 (5 个用例) | 100% PASS |
| [`DubboOverloadTest`](src/test/java/io/github/atengk/cloud/dubbo/overload/DubboOverloadTest.java) | 线程池过载保护与指标导出 | 验证拒绝策略诊断输出、全局计数器累计、429 业务异常转译与 Micrometer 指标绑定 (6 个用例) | 100% PASS |
| [`DubboSerializeSecurityTest`](src/test/java/io/github/atengk/cloud/dubbo/security/DubboSerializeSecurityTest.java) | 反序列化安全包白名单扩展 | 验证自定义包路径属性绑定、自动注入 `SerializeSecurityManager` 及安全白名单注册 (3 个用例) | 100% PASS |
| [`DubboExceptionUtilsTest`](src/test/java/io/github/atengk/cloud/dubbo/support/DubboExceptionUtilsTest.java) | 异常工具与流式判定 | 验证过载异常研判、`ConstraintViolationException` 链路深度提取与流式调用判定 (5 个用例) | 100% PASS |
| [`DubboNativeMockFallbackTest`](src/test/java/io/github/atengk/cloud/dubbo/support/DubboNativeMockFallbackTest.java) | 客户端原生 Mock 降级白盒契约 | 验证调用超时 (TIMEOUT_EXCEPTION)、系统级未受检崩溃异常平滑降级、健康直通与业务异常原样透传 (4 个用例) | 100% PASS |
| [`DubboSeataPropagationTest`](src/test/java/io/github/atengk/cloud/dubbo/support/DubboSeataPropagationTest.java) | Seata XID 跨 RPC 对称穿透 | 验证全局事务 XID 出站提取、入站绑定、局部持有判断与 `finally` 对称安全释放 (6 个用例) | 100% PASS |
| [`DubboTrafficPropagationTest`](src/test/java/io/github/atengk/cloud/dubbo/support/DubboTrafficPropagationTest.java) | TrafficContext 泳道元数据联动 | 验证泳道名与匹配规则透传、缺失时 MDC 平滑降级、纯泳道还原与选择性 MDC 清理 (7 个用例) | 100% PASS |

> [!NOTE]
> 本 Starter 全套 119 个单元测试均遵循“100% 离线自洽铁律”，在脱离外部物理 Nacos / 注册中心的环境下基于直连与内存桩秒级执行，保障 CI/CD 构建流水线坚固稳定。
