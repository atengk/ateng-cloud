# Nacos 流量无损上下线与配置安全热更架构设计

## Context

在分布式微服务高并发生产环境中，服务节点的滚动发布、水平伸缩及配置变更面临两大典型故障隐患：
1. **发布期 502 / 调用超时风暴**：当节点关机时，若直接关闭 Spring 容器并销毁连接池与 Web 容器，上游网关与调用端（Feign / Dubbo）由于服务注册表缓存未刷新，仍会将流量路由至下线节点，造成请求失败；新节点启动时，因 JVM JIT 尚未完成和连接池冷启动，突发全量请求易致使节点瞬时打崩。
2. **`@RefreshScope` 状态断裂隐患**：滥用 Spring Cloud 原生 `@RefreshScope` 对 `@Service`、`@Component` 等单例进行物理重建，在高并发下会导致未完成事务断裂、并发锁悬挂及内部线程池脏读。

此外，微服务单元测试与 GitHub Actions CI 构建必须保持 100% 离线自洽，严禁强依赖外部物理 Nacos 集群。

## Decision

确立 `ateng-cloud-nacos` 核心治理架构契约：

1. **无损上下线双阶段状态机 (Graceful Deregistration & In-flight Draining)**：
   - 实现最高优先级 `SmartLifecycle`（`phase = Integer.MAX_VALUE`）停机生命周期编排：
     - **第一阶段（主动注销与流量阻断）**：主动向 Nacos 撤销实例注册，并联动切换 Actuator 探针为 `ReadinessState.REFUSING_TRAFFIC`（返回 `OUT_OF_SERVICE`），通知外部 SLB / K8s 切断新流量；
     - **第二阶段（安全排空窗口）**：受控阻塞排空 `drain-timeout`（生产默认 10s，强类型 `Duration`），等待全网注册表缓存刷新并在途请求处理完毕；
     - **第三阶段（放行销毁）**：放行后续容器销毁与资源释放。
   - **双模与并发防重**：提供 `/actuator/nacos-service` 运维端点支持显式提前摘牌，底层采用 `AtomicBoolean` 原子状态机保障并发防重与幂等，避免重复睡眠。

2. **平滑启动预热 (Startup Preheating) 与多维元数据增强**：
   - 节点注册时统一使用 Kebab-Case 自动注入：`startup-time`（启动时间戳）、`warmup-duration`（默认 60s）、`version`、`env`、`zone`、`cluster` 与 `traffic-tag`；
   - 对外暴露 `NacosMetadataCustomizer` SPI，支持业务按需扩展元数据字典。

3. **单例 Bean 不可变性与配置安全热更 (Safe Hot-Reload)**：
   - 严禁在承载业务与事务的单例 Bean 上标注 `@RefreshScope`；
   - 动态配置热更严格收敛于 `@ConfigurationProperties` 纯属性 POJO 增量热绑；
   - 对外提供 `NacosConfigChangeListener` SPI 接口与强类型 `SafeConfigRefreshEvent` 事件双模回调，附带 MDC 日志隔离与异常捕获屏障。

4. **对称运维抽象门面 (`NacosTemplate`)**：
   - 提供完备对称的实例上下线（`markOnline` / `markOffline`）、节点探测（`getInstances` 返回空集合保底，`getMyInstance` 返回 `Optional<Instance>`）、元数据动态增删及配置读写 API；
   - 拦截底层 `NacosException`，统一转译为携带完整 `cause` 因果链的受管 `BusinessException`；
   - **多格式强类型解析与发布预检**：支持泛型读取 `getConfig(dataId, group, Class<T>)`（依据 DataId 扩展名自动推断 YAML/JSON/Properties），写接口发布前执行语法 Fail-Fast 校验；
   - **编程式强类型订阅门面 (`subscribeConfig`)**：提供强类型配置变更监听门面，自动捕获解析异常并注入 MDC 链路追踪，返回 `AutoCloseable` 并在容器停机时受管自动注销。

5. **客户端负载均衡与平滑预热加权调度 (Zone & Warmup Load Balancing)**：
   - 基于 `@ConditionalOnClass(ServiceInstanceListSupplier.class)` 条件自动装配定制 `ZonePreferenceAndWarmupServiceInstanceListSupplier` 与 `NacosWarmupLoadBalancer`；
   - **同机房亲和优先**：优先向同可用区（Zone）健康实例路由流量，本区无可用节点时平滑跨区容灾降级；
   - **平滑加权预热算法**：消费 `startup-time` 与 `warmup-duration`，采用“线性升权（$\text{uptime} / \text{warmupDuration}$）+ 10% 底噪保底权重”算法动态调度，预热期后平滑收敛为 100% 原始权重。

6. **长连接通信事件总线 (Connection Event Bus)**：
   - 注册底层 Nacos 原生 gRPC `ConnectionEventListener`，在断开或重连时向 Spring 上下文广播强类型 `NacosConnectionEvent`（封装 `NAMING`/`CONFIG` 模块标识、`CONNECTED`/`DISCONNECTED` 状态与远端地址）；
   - 联动 `NacosMetricsManager` 实时统计连接抖动标尺并输出结构化告警日志。

7. **全景健康诊断器与深水区安全预检 (`NacosHealthChecker`)**：
   - 统一收敛启动健康自检（实现 `SmartInitializingSingleton`），集中输出配置导入规范、长连接状态与元数据拓扑全景报告；
   - **K8s 停机排空安全时限对比预警**：排查 `drain-timeout`，超过 25s 安全阈值时输出明确 WARN 引导，防范容器被 `SIGKILL` 强杀；
   - **容灾快照 (Failover Snapshot) 磁盘可写性预检**：启动期主动探测本地快照缓存目录写入权限，防御容器只读根文件系统导致容灾失效；
   - **预热可观测性透出**：在 `/actuator/nacosService` 端点与 Micrometer 标尺中透出预热剩余倒计时（`warmupRemainingSeconds`）与完成态标识。

8. **100% 离线自洽单测铁律**：
   - 默认配置 `discovery.enabled: false` 与 `config.enabled: false` 时，所有治理组件自动平滑 No-Op 降级，保障 CI 环境秒级绿灯；
   - 针对公网/私有真实 Nacos 的联调测试，收敛于 `application-local.yml` 本地 profile 触发。

## Consequences

1. **全场景工业级覆盖**：系统性消除了多网卡 IP 漂移、敏感配置明文泄露、跨机房高延迟、网络分区断连雪崩、权重失灵及 K8s 停机强杀等高频生产故障。
2. **零 502 滚动发布保障**：全自动的主动注销与排空窗口配合平滑预热，彻底消除了微服务滚动发布时的瞬间调用报错与冷启动雪崩。
3. **生命周期绝对安全**：彻底杜绝 `@RefreshScope` 滥用引发的深层内存泄漏与锁悬挂问题。
4. **CI/CD 高度稳定**：离线单测与本地真实联调彻底解耦，杜绝因外部网络抖动导致 GitHub CI 构建失败。
