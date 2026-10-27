# ateng-cloud-nacos

Nacos 服务注册发现与动态配置中心基础设施构件，全面支持 Spring Boot 3 原生 `spring.config.import` 规范，内置容器多网卡精准绑定、多维元数据增强、60s 平滑预热、无损上下线治理与零破坏安全配置热更。

---

## 维度零：特性与公共 API 全景速查矩阵

### 1. 核心类与门面接口

| 类 / 接口 | 类型 | 核心职责 | 默认行为 / 装配条件 |
| :--- | :--- | :--- | :--- |
| `AtengNacosAutoConfiguration` | 自动装配类 | Nacos 基础设施自动装配总入口 | 当 `ateng.cloud.nacos.enabled=true` 时激活 |
| `AtengNacosProperties` | 配置属性类 | 强类型映射配置前缀 `ateng.cloud.nacos` | 默认开启，排空 `10s`，预热 `60s` |
| `NacosMetadataProvider` | 核心门面接口 | 收集全量元数据快照并支持填充至注册目标 | 默认实现为 `DefaultNacosMetadataProvider` |
| `NacosMetadataCustomizer` | 扩展点 SPI | 业务按需增量改写或注入自定义实例元数据 | `@FunctionalInterface`，支持 `Ordered` 排序 |
| `NacosNetworkAddressDetector` | 网络智能探测器 | 自动清洗虚拟网卡并绑定首选私网 IP | 默认忽略 `docker0`、`cni*` 等桥接网卡 |
| `NacosDiscoveryPropertiesPostProcessor` | 属性后置处理器 | 注册前将检测 IP、集群与元数据注入 NacosDiscoveryProperties | 条件装配于 `NacosDiscoveryProperties` |
| `NacosLifecycleStateMachine` | 状态机管理器 | 原子驱动双阶段优雅下线排空与上线恢复 | 状态流转 `UP` -> `DRAINING` -> `OUT_OF_SERVICE` |
| `NacosGracefulShutdownLifecycle` | 生命周期钩子 | `SmartLifecycle` 最高优先级（`PHASE_MAX`）排空钩子 | 容器停机最先触发注销与排空 |
| `NacosServiceEndpoint` | Actuator 端点 | `/actuator/nacosService` 专属运维与状态端点 | 支持读写分离与外部流水线主动摘牌 |
| `ZoneAffinityInstanceFilter` | 路由过滤器 | 机房 (Zone) 就近亲和与跨区平滑容灾降级过滤器 | 标配 `@ConditionalOnMissingBean` |
| `NacosTemplate` | 核心门面类 | Nacos 注册发现、机房亲和筛选与对称运维门面 | 标配 `@ConditionalOnMissingBean`，空安全 |
| `NacosResultCode` | 错误码枚举 | Nacos 统一业务错误码 (`4101~4104`，归属 `NETWORK_RPC`) | 底层异常因果转译映射 |

### 2. NacosTemplate 核心门面 API 速查表

| 方法签名 | 返回类型 | 功能说明与生产语义 | 离线自洽保底行为 |
| :--- | :--- | :--- | :--- |
| `getAllInstances(serviceName [, groupName])` | `List<Instance>` | 查询指定服务全量实例列表（含未通过健康检查实例） | 保底返回空集合 `[]` |
| `getHealthyInstances(serviceName [, groupName])` | `List<Instance>` | 查询指定服务通过健康检查的实例列表 | 保底返回空集合 `[]` |
| `getInstances(serviceName [, groupName])` | `List<Instance>` | 查询指定服务健康且可用实例列表（自动过滤 `weight <= 0.0` 与禁用节点） | 保底返回空集合 `[]` |
| `selectOneHealthyInstance(serviceName [, groupName])` | `Optional<Instance>` | 基于 Nacos 随机权重算法选举单个健康且可用实例 | 保底返回 `Optional.empty()` |
| `getMyInstance()` | `Optional<Instance>` | 获取当前服务节点在 Nacos 注册的自身实例信息快照 | 保底返回 `Optional.empty()` |
| `getSameZoneInstances(serviceName [, groupName])` | `List<Instance>` | 查询机房（Zone）就近亲和优先的可用实例，本区全宕平滑降级全集群 | 保底返回空集合 `[]` |
| `getServices(pageNo, pageSize [, groupName])` | `List<String>` | 分页拉取 Nacos 注册表中的服务名称列表 | 保底返回空集合 `[]` |
| `isUnderProtection(serviceName [, groupName])` | `boolean` | 检测目标服务集群是否触发 Nacos 实例保护阈值（阈值内输出 WARN） | 保底返回 `false` |
| `setInstanceEnabled(serviceName, [groupName,] ip, port, enabled)` | `void` | 动态启用或禁用目标服务实例（跨实例通过 MaintainService 治理） | 平滑 No-Op |
| `updateInstanceWeight(serviceName, [groupName,] ip, port, weight)` | `void` | 动态调节目标服务实例权重（设为 `0.0` 流量隔离，负数抛出异常） | 平滑 No-Op |
| `registerMetadata(key, value)` | `void` | 动态为当前自身实例追加或覆盖指定单项元数据并立即向 Nacos 推送 | 平滑 No-Op |
| `removeMetadata(key)` | `void` | 动态从当前自身实例移除指定键名的元数据并立即向 Nacos 推送 | 平滑 No-Op |
| `updateInstanceMetadata(serviceName, [groupName,] ip, port, metadata)` | `void` | 覆盖更新指定实例全量元数据字典（原子推送） | 平滑 No-Op |
| `markOnline()` | `void` | 主动将当前服务节点挂牌注册至 Nacos 并恢复 Spring 就绪探针 `UP` | 平滑 No-Op |
| `markOffline()` | `void` | 主动将当前服务节点从 Nacos 注销并执行 `drain-timeout` 优雅停机排空 | 平滑 No-Op |
| `isCurrentInstance(ip, port)` | `boolean` | 精准判断目标 IP 与端口是否匹配当前本地服务节点 | 依据配置与探测比对 |

### 3. 配置属性速查表 (`ateng.cloud.nacos.*`)

| 配置项 | 类型 | 默认值 | 详细业务说明 |
| :--- | :--- | :--- | :--- |
| `ateng.cloud.nacos.enabled` | `boolean` | `true` | 是否激活 ateng-cloud Nacos 治理增强 |
| `ateng.cloud.nacos.drain-timeout` | `Duration` | `10s` | 实例优雅下线在途请求排空安全窗口时限 |
| `ateng.cloud.nacos.zone` | `String` | `""` | 默认物理机房 / 可用区标识（如 `beijing-az1`） |
| `ateng.cloud.nacos.cluster` | `String` | `""` | 默认集群标识（如 `default-cluster`） |
| `ateng.cloud.nacos.metadata.startup-time` | `boolean` | `true` | 是否自动注入 JVM 启动毫秒时间戳 |
| `ateng.cloud.nacos.metadata.warmup-duration` | `Duration` | `60s` | 平滑启动预热窗口时长（供负载均衡动态升权） |
| `ateng.cloud.nacos.metadata.version` | `String` | `""` | 应用版本号（为空时自动从 Environment 读取） |
| `ateng.cloud.nacos.metadata.env` | `String` | `""` | 运行时环境标识（为空时自动对齐 Spring Profile） |
| `ateng.cloud.nacos.metadata.traffic-tag` | `String` | `""` | 全链路流量染色标记（如 `gray`） |
| `ateng.cloud.nacos.metadata.custom.*` | `Map<String, String>` | `{}` | 业务自定义元数据键值字典 |
| `ateng.cloud.nacos.network.network-interface` | `String` | `""` | 显式指定绑定的物理网卡名（如 `eth0`） |
| `ateng.cloud.nacos.network.preferred-networks` | `List<String>` | `[]` | 首选网段前缀列表（如 `["10.", "172.16."]`) |
| `ateng.cloud.nacos.network.ignored-interfaces` | `List<String>` | `["docker0", ...]` | 忽略的虚拟或桥接网卡正则黑名单 |
| `ateng.cloud.nacos.network.fail-on-loopback` | `boolean` | `true` | 仅侦测到 127.0.0.1 时是否 Fail-Fast 阻断启动 |
| `ateng.cloud.nacos.endpoint.enabled` | `boolean` | `true` | 是否暴露 `/actuator/nacosService` 运维端点 |

---

## 维度一：最小起步体验

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.atengk</groupId>
    <artifactId>ateng-cloud-nacos</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 2. 最小化配置 (`application.yml`)

```yaml
spring:
  application:
    name: demo-service
  cloud:
    nacos:
      server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
      discovery:
        server-addr: ${spring.cloud.nacos.server-addr}

management:
  endpoints:
    web:
      exposure:
        include: health,info,nacosService
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true

ateng:
  cloud:
    nacos:
      enabled: true
      drain-timeout: 10s
      zone: beijing-az1
      cluster: default-cluster
      metadata:
        startup-time: true
        warmup-duration: 60s
        version: 1.0.0
        custom:
          team: core-platform
```

### 3. 获取实例元数据快照

注入 `NacosMetadataProvider` 即可获取经由框架清洗与 SPI 定制后的不可变元数据字典：

```java
@RestController
@RequestMapping("/api/nacos")
public class NacosMetadataDemoController {

    private final NacosMetadataProvider metadataProvider;

    public NacosMetadataDemoController(NacosMetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    @GetMapping("/metadata")
    public Result<Map<String, String>> getMetadata() {
        return Result.success(metadataProvider.getMetadata());
    }
}
```

### 4. 使用 NacosTemplate 统一门面进行健康发现与运维

注入 `NacosTemplate` 门面即可享受空安全保障（查询绝不返回 `null`，单实例返回 `Optional`）、机房亲和路由与对称运维能力：

```java
@RestController
@RequestMapping("/api/nacos/ops")
public class NacosOpsDemoController {

    private final NacosTemplate nacosTemplate;

    public NacosOpsDemoController(NacosTemplate nacosTemplate) {
        this.nacosTemplate = nacosTemplate;
    }

    /**
     * 查询指定服务的健康可用实例列表 (已过滤 weight <= 0.0 与禁用节点)
     */
    @GetMapping("/instances")
    public Result<List<InstanceVO>> getInstances(@RequestParam(defaultValue = "sample-cloud-nacos") String serviceName) {
        List<InstanceVO> list = nacosTemplate.getInstances(serviceName).stream()
                .map(InstanceVO::fromInstance)
                .collect(Collectors.toList());
        return Result.success(list != null ? list : Collections.emptyList());
    }

    /**
     * 查询同机房亲和优先的健康实例列表 (本区全宕自动平滑降级全集群)
     */
    @GetMapping("/same-zone")
    public Result<List<InstanceVO>> getSameZoneInstances(@RequestParam(defaultValue = "sample-cloud-nacos") String serviceName) {
        List<InstanceVO> list = nacosTemplate.getSameZoneInstances(serviceName).stream()
                .map(InstanceVO::fromInstance)
                .collect(Collectors.toList());
        return Result.success(list != null ? list : Collections.emptyList());
    }

    /**
     * 获取当前服务节点在 Nacos 注册的自身实例信息
     */
    @GetMapping("/me")
    public Result<InstanceVO> getMyInstance() {
        return nacosTemplate.getMyInstance()
                .map(InstanceVO::fromInstance)
                .map(Result::success)
                .orElseGet(() -> Result.success(null));
    }

    /**
     * 检测目标服务是否触发过载保护阈值
     */
    @GetMapping("/protection")
    public Result<Boolean> isUnderProtection(@RequestParam(defaultValue = "sample-cloud-nacos") String serviceName) {
        return Result.success(nacosTemplate.isUnderProtection(serviceName));
    }

    /**
     * 动态设置指定服务实例启用/禁用状态
     */
    @PostMapping("/instance/status")
    public Result<Void> setInstanceEnabled(@RequestParam String serviceName,
                                          @RequestParam String ip,
                                          @RequestParam int port,
                                          @RequestParam boolean enabled) {
        nacosTemplate.setInstanceEnabled(serviceName, ip, port, enabled);
        return Result.success();
    }

    /**
     * 动态调节指定服务实例权重 (设为 0.0 实现流量隔离)
     */
    @PostMapping("/instance/weight")
    public Result<Void> updateInstanceWeight(@RequestParam String serviceName,
                                            @RequestParam String ip,
                                            @RequestParam int port,
                                            @RequestParam double weight) {
        nacosTemplate.updateInstanceWeight(serviceName, ip, port, weight);
        return Result.success();
    }

    /**
     * 动态注册当前实例元数据
     */
    @PostMapping("/metadata")
    public Result<Void> registerMetadata(@RequestParam String key, @RequestParam String value) {
        nacosTemplate.registerMetadata(key, value);
        return Result.success();
    }

    /**
     * 动态移除当前实例元数据
     */
    @DeleteMapping("/metadata")
    public Result<Void> removeMetadata(@RequestParam String key) {
        nacosTemplate.removeMetadata(key);
        return Result.success();
    }

    /**
     * 主动将当前服务挂牌上线
     */
    @PostMapping("/online")
    public Result<Void> markOnline() {
        nacosTemplate.markOnline();
        return Result.success();
    }

    /**
     * 主动触发当前服务优雅下线排空
     */
    @PostMapping("/offline")
    public Result<Void> markOffline() {
        nacosTemplate.markOffline();
        return Result.success();
    }
}
```

---

## 维度二：生产扩展接管

### 1. 外部发布流水线 / K8s PreStop Hook 主动摘牌

发布平台或 K8s 可以在向 Pod 发送 `SIGTERM` 之前，通过 HTTP 调用主动下线端点，实现提前安全摘牌与在途排空：

```bash
# 1. 查询当前节点运行状态与元数据
curl http://localhost:8080/actuator/nacosService

# 2. 主动发起下线与排空（同步阻塞等待 drain-timeout 完成后返回）
curl -X POST http://localhost:8080/actuator/nacosService \
     -H "Content-Type: application/json" \
     -d '{"action": "offline"}'

# 3. 重新挂牌上线恢复接收流量
curl -X POST http://localhost:8080/actuator/nacosService \
     -H "Content-Type: application/json" \
     -d '{"action": "online"}'
```

### 2. 实现 `NacosMetadataCustomizer` SPI 增量定制元数据

当下游微服务需要动态追加业务标签（如业务线、运维联系人、自定义特征码）时，声明 Spring Bean 实现 `NacosMetadataCustomizer` 接口即可：

```java
@Component
public class SampleMetadataCustomizer implements NacosMetadataCustomizer {

    @Override
    public void customize(Map<String, String> metadata) {
        metadata.put("demo-module", "sample-cloud-nacos");
        metadata.put("maintainer", "Ateng");
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
```

### 3. 自定义机房亲和过滤器与扩展接管

`ZoneAffinityInstanceFilter` 与 `NacosTemplate` 均标配 `@ConditionalOnMissingBean`。若需定制多级机房亲和策略（如跨同城双活 AZ 加权或多地域就近），直接声明同类型 Bean 即可无缝接管：

```java
@Configuration(proxyBeanMethods = false)
public class CustomNacosConfiguration {

    @Bean
    public ZoneAffinityInstanceFilter customZoneAffinityInstanceFilter() {
        return new ZoneAffinityInstanceFilter() {
            @Override
            public List<Instance> filter(List<Instance> instances, String targetZone) {
                // 自定义机房亲和与权重算法，无缝接管框架默认行为
                return super.filter(instances, targetZone);
            }
        };
    }
}
```

---

## 维度三：核心生产边界与非功能语义

### 1. 零 502 无损下线双阶段状态机时序

```mermaid
sequenceDiagram
    autonumber
    actor DevOps as K8s / 发布流水线
    participant Hook as SmartLifecycle / Endpoint
    participant SM as NacosLifecycleStateMachine
    participant Probe as Spring ReadinessState
    participant Nacos as Nacos 注册中心
    participant Client as 上游网关 / 消费端 Feign
    participant App as 本地在途请求

    DevOps->>Hook: 停机信号 SIGTERM / POST offline
    Hook->>SM: markOffline()
    Note over SM: CAS 原子切换: UP -> DRAINING (幂等防重)
    par 阶段一: 流量阻断与注销
        SM->>Probe: 切换 REFUSING_TRAFFIC (OUT_OF_SERVICE)
        SM->>Nacos: deregisterInstance() 主动注销节点
    end
    Note over Nacos,Client: 注册表推空缓存，停止打入新请求
    Note over SM: 阶段二: 安全在途排空 (drain-timeout: 10s)
    App-->>Client: 在途长连接与业务请求平稳处理完毕
    Note over SM: 状态置为 OUT_OF_SERVICE，排空结束
    SM-->>Hook: 放行容器销毁
    Hook->>DevOps: 容器正常平稳退出 (Exit 0)
```

### 2. 生产关键边界与治理不变量

1. **同机房就近亲和与跨区容灾降级 (Zone Affinity & Fallback)**：
   - 筛选处于相同机房区域（`zone`）的可用实例；
   - 严格响应 Nacos 动态权重治理，权重 `<= 0.0` 或已禁用的节点自动被剔除；
   - 当本机房无任何可用健康实例时，平滑降级（Cross-Zone Fallback）至全集群健康实例，防御机房级雪崩。
2. **动态权重响应与集群过载保护阈值 (Dynamic Weight & Overload Defense)**：
   - 当通过控制台或运维 API 将某节点权重调整为 `0.0` 时，调用端门面自动将其排除于路由列表外，实现安全灰度与流量隔离；
   - 当检测到集群健康比跌破 Nacos 保护阈值（`isReachProtectionThreshold` 或 healthy ratio `<= protectThreshold`），自动输出 WARN 级告警并透传状态。
3. **底层驱动异常因果转译体系 (Causal Exception Translation)**：
   - 所有调用 Nacos 底层驱动可能抛出的 `NacosException`，100% 被捕获并转译为框架受管的 `BusinessException`；
   - 错误码规划遵循 `ateng-boot-core` `ResultCodeSegment.NETWORK_RPC` 规范（`NacosResultCode.NACOS_CLIENT_ERROR` 等，4101~4104），且 100% 保留原始 `cause` 异常链，严禁异常吞咽与信息丢失。
4. **容器多网卡智能清洗防漂移**：
   - 框架默认自动清洗常见容器网络虚拟网卡（`docker0`、`flannel.*`、`cni.*`、`veth.*`、`vmnet.*`、`virbr.*`、`br-.*`）；
   - 优先绑定可路由的内网网段（`10.*`、`172.16~31.*`、`192.168.*`），从源头杜绝容器注册为宿主机虚拟网卡或 `127.0.0.1` 导致集群不可达。
5. **Kebab-Case 标准化元数据与平滑预热**：
   - 元数据键名严格遵循小写中划线格式：`startup-time`、`warmup-duration`、`version`、`env`、`zone`、`cluster`、`traffic-tag`；
   - 启动时自动注入 `startup-time` 启动时间戳与 `warmup-duration: 60s`，供调用端负载均衡器执行动态平滑爬升，彻底消除冷启动雪崩。
6. **K8s 停机协同与防强杀约束**：
   - 排空时间 `drain-timeout`（生产默认 10s）必须严格小于 Kubernetes Pod 配置的 `terminationGracePeriodSeconds`（建议设为 30s），预留充足缓冲防止被 K8s `SIGKILL` 强杀。
7. **100% 离线自洽单测铁律与平滑空转**：
   - 在单测环境（`spring.cloud.nacos.discovery.enabled: false`）下，`NacosTemplate` 安全空转（查询返回空集合或 `Optional.empty()`），优雅下线、探针联动与端点平滑降级（No-Op），保障 CI 流水线零外部物理依赖秒级全绿。
8. **双模自适应集成与本地真实连接规范 (Dual-Mode Integration)**：
   - 基准样例工程通过 `optional:classpath:application-local.yml` 弹性探测本地真实配置；本地联调时复制 `application-local.example.yml` 为 `application-local.yml` 并激活 `local` Profile 即可直连真实 Nacos 实例，测试套件依托 `@Profile("!local")` 自动让位原生自动装配，并在 `@AfterEach` 中执行确定性状态清理与元数据排空；未激活 `local` 时默认走 100% 离线自洽构建，保障 CI 零外部依赖秒级全绿。

---

## 维度四：物理排版与代码全真验证

- **样例沙箱锚定**：上述代码与配置片段 100% 逆向提取自 [`sample-cloud-nacos`](../ateng-cloud-samples/sample-cloud-nacos) 真实运行工程。
- **自动化测试保证**：端到端覆盖于 `SampleCloudNacosApplicationTest`：
  - `testGetMetadataEndpoint`：断言校验 `startup-time`、`warmup-duration`、`zone` 及 SPI 定制标签均端到端正确生效；
  - `testGetActuatorNacosServiceEndpoint`：验证 `/actuator/nacosService` 状态与元数据正确读取；
  - `testActuatorNacosServiceOfflineAndOnlineActions`：验证执行 offline 摘牌排空时，就绪探针立即切换为 `OUT_OF_SERVICE`，执行 online 时平稳恢复 `UP`；
  - `testNacosTemplateOpsEndpoints`：验证 `NacosTemplate` 健康实例查询、同机房亲和筛选、自身实例感知、保护阈值探测、实例启用/禁用状态与权重调节、动态元数据维护及主动上下线全链路离线平滑通过。
