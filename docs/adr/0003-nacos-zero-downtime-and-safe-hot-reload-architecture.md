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
   - 拦截底层 `NacosException`，统一转译为携带完整 `cause` 因果链的受管 `BusinessException`。

5. **深水区生产防线与极端场景治理 (Deep-Water Production Defense)**：
   - **网卡智能筛选 (NIC Selection)**：内置虚拟与桥接网卡黑名单（忽略 `docker0`、`cni*` 等），优先绑定内网可路由 IP，非单测模式下绑定 `127.0.0.1` 直接 Fail-Fast 阻断启动；
   - **可用区机房亲和 (Zone Affinity)**：上报 `zone` 元数据，`NacosTemplate` 提供 `getSameZoneInstances` 门面与 `ZoneAffinityInstanceFilter`，遵循“同机房优先，全宕时平滑降级全集群”；
   - **配置敏感加密脱敏 (Config Encryption)**：支持客户端透明前缀解密（`ENC(...)`），并在事件通知、运维端点与 MDC 日志中对敏感密码密钥强制执行掩码脱敏（`sk-***abcd`）；
   - **容灾快照与 gRPC 连接护卫 (Failover Snapshot & gRPC Flapping Defense)**：网络分区时强制降级使用本地快照与内存拓扑，监听 gRPC `ConnectionEvent` 输出连接告警与平滑防抖；
   - **动态权重与保护阈值协同 (Dynamic Weight & Protection Threshold)**：严格遵循 Nacos 动态权重（`0.0` 隔离下线），透传集群保护阈值过载标识（`underProtection = true`）；
   - **命名空间与配置可选规范**：统一推行 `optional:nacos:...` 导入规范，空配置启动输出友好提示；
   - **可观测性核心指标**：自动导出 5 组核心 Micrometer 指标（gRPC 连接状态、配置热更总数/失败数、同名微服务健康节点数、生命周期状态）；
   - **K8s 停机协同**：约束排空超时 `drain-timeout` 严格小于容器 `terminationGracePeriodSeconds`，防范 `SIGKILL` 强杀。

6. **100% 离线自洽单测铁律**：
   - 默认配置 `discovery.enabled: false` 与 `config.enabled: false` 时，所有治理组件自动平滑 No-Op 降级，保障 CI 环境秒级绿灯；
   - 针对公网/私有真实 Nacos 的联调测试，收敛于 `application-local.yml` 本地 profile 触发。

## Consequences

1. **全场景工业级覆盖**：系统性消除了多网卡 IP 漂移、敏感配置明文泄露、跨机房高延迟、网络分区断连雪崩、权重失灵及 K8s 停机强杀等高频生产故障。
2. **零 502 滚动发布保障**：全自动的主动注销与排空窗口配合平滑预热，彻底消除了微服务滚动发布时的瞬间调用报错与冷启动雪崩。
3. **生命周期绝对安全**：彻底杜绝 `@RefreshScope` 滥用引发的深层内存泄漏与锁悬挂问题。
4. **CI/CD 高度稳定**：离线单测与本地真实联调彻底解耦，杜绝因外部网络抖动导致 GitHub CI 构建失败。
