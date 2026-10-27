# Nacos Context

Nacos 服务注册发现与动态配置中心治理上下文，负责微服务集群节点动态寻址、元数据拓扑及基于 Spring Boot 3 标准规范的配置热更。

## Language

**Registration**:
微服务实例向 Nacos 注册中心主动上报自身网络地址、端口及元数据的生命周期行为。
_Avoid_: 发布, 挂载, 登记

**Discovery**:
服务调用方从 Nacos 服务端拉取可用健康节点拓扑并建立本地缓存路由表的过程。
_Avoid_: 寻址, 探测, 查表

**Namespace**:
Nacos 租户层级的最高物理隔离单元，用于开发、测试与生产等不同运行环境的物理隔离。
_Avoid_: 环境名, 租户空间

**Group**:
同一命名空间下用于隔离不同业务线、项目群或应用集合的次级配置聚合分组。
_Avoid_: 分类, 分组名

**DataId**:
Nacos 配置中心中特定配置集文件的唯一资源标识符（通常对齐应用名与扩展名，如 `order-service.yaml`）。
_Avoid_: 配置文件名, 配置键

**Ephemeral Instance**:
基于心跳探针保活的临时服务节点，当心跳丢失时由注册中心自动摘除（微服务应用默认模式）。
_Avoid_: 动态节点, 瞬态实例

**Persistent Instance**:
基于主动健康检查由服务端主动探测的持久化服务节点，即使健康状态异常也不会自动从注册表注销。
_Avoid_: 静态节点, 固定实例

**Graceful Deregistration**:
微服务在停机前主动向 Nacos 撤销实例注册并阻断新流量接入的生命周期行为。
_Avoid_: 停机, 实例杀死, 强制注销

**In-flight Draining**:
实例注销后、Spring 容器物理销毁前，安全等待全网客户端刷新注册表缓存并处理完毕已有在途请求的时间窗口。
_Avoid_: 睡眠等待, 延迟停机, 悬挂

**Startup Preheating**:
节点注册时注入启动时间戳元数据，协同调用端负载均衡器在预热期内按平滑权重曲线分配流量的冷启动防御机制。
_Avoid_: 热身, 慢启动, 启动延迟

**Safe Hot-Reload**:
恪守单例 Bean 不可变性铁律（严禁 `@RefreshScope` 物理重建），仅对纯属性 POJO 实施增量绑定并受控发布刷新事件的配置热更模型。
_Avoid_: 动态刷新, 重构Bean, 动态重载

**Instance Metadata**:
附加于微服务注册节点上的多维描述键值字典（涵盖版本、环境、启动时间戳、流量染色与机房拓扑）。
_Avoid_: 节点标签, 实例属性, 自定义标头

**Zone Affinity**:
优先将调用流量路由至同机房可用区健康节点、并在本区全宕时平滑跨区容灾降级的亲和调度机制。
_Avoid_: 区域绑定, 机房路由, 局部调用

**Failover Snapshot**:
当 Nacos 服务端不可达或长连接网络分区时，客户端自动读取的本地磁盘配置快照与内存服务拓扑熔断备份。
_Avoid_: 本地兜底, 离线缓存, 静态备份

**Protection Threshold**:
微服务健康实例占比低于设定安全水位时触发的过载保护状态，客户端感知该状态以防盲目剔除幸存节点导致雪崩。
_Avoid_: 限流阈值, 熔断水位, 警戒线

**Dual-Mode Adaptive Test**:
基于 `@Profile("!local")` 在同一测试套件中无缝切换离线自洽 Mock 与本地真实网络通信的测试集成架构。
_Avoid_: 本地双跑测试, 离线测试分离, 双套测试

**Resilient Config Import**:
基于 Spring Boot 3 `optional:classpath:` 探测规范在外部配置缺失时静默降级而不阻断容器启动的弹性加载机制。
_Avoid_: 静默加载, 隐式配置, 容错导入

