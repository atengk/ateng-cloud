# Feign Context

声明式 HTTP/RPC 客户端通信与治理上下文，负责跨微服务声明式接口调用、全链路四维上下文透传、双模自适应契约解包、防雪崩弹性重试与全场景企业级生产通信治理。

## Language

**Adaptive Unwrap**:
当调用端 Feign 接口方法显式声明返回具体业务对象 `T` 时，解码引擎自动校验底层物理契约 `Result<T>` 的业务成功状态（`code == 200`）并剥离壳对象返回纯业务数据；若方法声明返回 `Result<T>` 则原样透传。
_Avoid_: 自动脱壳, 智能拆包, 响应拦截剥离

**Retry Storm Defense**:
针对非幂等写接口（POST/PUT 等）在发生读取超时时彻底禁止重试，仅对只读接口（GET）在握手建连失败时受控重试 1 次的高可用防雪崩纪律。
_Avoid_: 容错重试, 弹性重试机制, 失败轮询

**4D Context Propagation**:
在微服务跨进程 HTTP 通信时，出站请求自动抽取当前线程的 MDC traceId、userId、tenantId 及 ignoreTenant 快照并标准化挂载至 HTTP 标头的全链路治理机制。
_Avoid_: 标头透传, 请求头复制, 线程变量传递

**Zero-Trust Header Guard**:
协同 Sa-Token 在 Feign 请求出站时动态附加内部同源受信签名凭证（Same-Token），未携带凭证或绕过网关直连内部微服务暴露端口的流量直接 Fail-Fast 401 阻断。
_Avoid_: 内网认证头, 内部Token, 安全签名

**Causal Error Decoding**:
当远端服务返回业务错误码、HTTP 4xx/5xx 错误或非标准报文时，精准解析并转译为框架受管的强类型 `BusinessException`，且 100% 完整保留原始因果链（`cause`）的防御性异常解码器。
_Avoid_: 统一异常拦截, 错误包装器, Feign异常处理

**Connection Pool Warm Transport**:
摒弃原生单连接阻塞模式，默认以 Apache HttpClient 5 线程池作为高并发通信底座，保障微服务间长连接复用与优雅路由管理的传输底座。
_Avoid_: HTTP客户端, 底层驱动, 网络通信组件

**Stream Passthrough**:
对声明返回 `byte[]`、`Resource`、`InputStream`、`File` 或原生 `Response` 的二进制与文件流接口执行无条件豁免，绝对不碰流、不解析 JSON 报文并原样透传的流式传输机制。
_Avoid_: 二进制解包, 文件流拦截, 数据流解码

**QueryMap Auto-Binding**:
针对 GET 请求携带 POJO 复杂查询对象的入参，自动解析并展开为 HTTP URL Query 参数，彻底消除误转 POST 或参数丢失的参数绑定规范。
_Avoid_: 参数展平器, GET对象映射, 查询参数转换

**Per-Client Isolation**:
支持“全局默认基线 + 单 Client 精确覆盖”的三级配置优先级模型，实现不同下游服务在超时、重试、日志级别及解包规则上的物理隔离配置机制。
_Avoid_: 客户端独立配置, 差异化设置, 局部属性覆盖

**Eager Preheating**:
在容器启动完毕后通过后台探针对关键 Feign 客户端及其连接池执行静默饥饿预热，彻底消灭滚动发布期间首次请求冷启动高耗时与读取超时故障。
_Avoid_: 客户端提前加载, 预热初始化, 首次请求加速

**Observation Metrics**:
无缝接入 Micrometer Observation 标准门面，自动采集微服务 RPC 调用的耗时直方图、吞吐量、状态码分布及分布式 Span 编织的统一可观测度量体系。
_Avoid_: 客户端埋点, Feign监控, 调用链采集

**Payload Guard**:
配合弹性 GZIP 传输压缩机制，对超过阈值的超大响应报文实施快速失败熔断，守住客户端 JVM 堆内存与防范解压炸弹的内存防波堤机制。
_Avoid_: 报文大小限制, 内存防爆, 响应拦截器
