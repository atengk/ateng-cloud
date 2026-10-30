# Dubbo Context

高性能 RPC 通信与微服务治理上下文，负责微服务间 Triple / TCP 协议通信、全链路四维上下文透传、双模契约校验、防重试雪崩与过载保护治理。

## Language

**Triple Protocol**:
基于 HTTP/2 协议栈与 gRPC 规范的 Dubbo 3 现代高性能通信协议。
_Avoid_: gRPC协议, HTTP2 RPC, Dubbo3新协议

**4D Context Filter**:
在 Dubbo RPC 调用出入栈时自动完成四维上下文快照与流量染色标记双向穿透与可靠清理的 SPI 过滤器。
_Avoid_: 上下文拦截器, 隐式传参过滤器, RpcContext透传器

**Same-Token RPC Guard**:
在 Dubbo 传输边界协同 Sa-Token 校验内部受信凭证并拦截非法直连流量的零信任防线。
_Avoid_: 内网Token拦截器, RPC鉴权过滤器, Dubbo安全拦截

**Causal Exception Filter**:
阻断 Dubbo 原生对未受检异常的抹平行为，白名单透传受管业务异常并对底层未检异常实施因果转译的守护过滤器。
_Avoid_: Dubbo异常包装器, 异常转换器, 错误捕获过滤器

**Dual-Mode RPC Contract**:
同时兼容返回原生业务 POJO 与返回显式 `Result<T>` 并实施业务结果自动断言防呆的双模契约机制。
_Avoid_: 强契约封装, 结果自动解包, RPC统一返回

**Retry Storm Defense**:
在微服务 RPC 基线中默认彻底关闭写操作重试（retries=0），严禁读超时引发全链路雪崩的高可用生产防护纪律。
_Avoid_: 容错重试, 失败重试机制, 弹性调用轮询

**Overload Protection**:
在 Dubbo Provider 线程池打满时，实施关键现场记录并将底层拒绝异常转译为语义化限流业务异常的防护机制。
_Avoid_: 线程池限流, 降级拦截, 拒绝策略包装

**Application-Level Discovery**:
以微服务应用名为唯一注册发现单元，彻底消除 Nacos 实例与配置推送膨胀的现代服务注册机制。
_Avoid_: 接口级注册, Dubbo2服务发现, 细粒度注册

**Tag Router Gray Fallback**:
自动将 `X-Traffic-Tag` 转化为 Dubbo 标签路由，并在灰度实例不可用时自动降级至基线实例的弹性灰度治理。
_Avoid_: 标签路由过滤器, 灰度路由拦截器, 金丝雀降级机制

**RPC Validation Guard**:
在 RPC 接口方法入参边界执行 JSR-303 Fail-Fast 格式校验，并自动转译为框架标准受管参数异常的防卫组件。
_Avoid_: 参数校验切面, Dubbo参数拦截器, JSR校验器

**Stream Passthrough**:
对 Triple 原生流式调用（`StreamObserver`）执行上下文预注入且不触碰单体业务解包的流式传输机制。
_Avoid_: 流式解包, Stream拦截, 数据流转译

**Seata XID RPC Propagation**:
在 Dubbo RPC 跨进程通信中通过条件探测透明穿透并清理 Seata 全局事务 ID (TX_XID) 的防线。
_Avoid_: Dubbo事务拦截器, SeataFilter, XID透传器

**Dynamic Serialization Allowlist**:
支持通过配置属性动态声明并合并业务数据传输对象包路径的 Dubbo 3 反序列化安全检查机制。
_Avoid_: 序列化黑白名单, 自动信任包, Fastjson2类白名单

**Hermetic Module Test Suite**:
在脱离物理注册中心与外部网络环境下，使用纯内存桩对 Starter 自动装配、过滤器链与异常转译进行全分支防御性验证的单测矩阵。
_Avoid_: 集成测试套件, Dubbo单测, 本地测试用例
