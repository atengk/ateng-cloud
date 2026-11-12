# Feign Context

声明式 HTTP/RPC 客户端通信与治理上下文，负责跨微服务声明式接口调用、全链路四维上下文透传、双模自适应契约解包、防雪崩弹性重试与全场景企业级生产通信治理。

## Language

**Adaptive Unwrap**:
当调用端 Feign 接口方法显式声明返回具体业务对象 `T` 时，解码引擎自动校验底层物理契约 `Result<T>` 的业务成功状态（`code == 200`）并剥离壳对象返回纯业务数据；若方法声明返回 `Result<T>` 则原样透传。若方法声明返回原生基本类型（如 `long`、`boolean`）但无论源自 `Result.data == null`、HTTP 204 还是 0 字节空响应体导致数据为 `null`，严格前置 Fail-Fast 抛出受管 `BusinessException` 杜绝裸 NPE。
_Avoid_: 自动脱壳, 智能拆包, 响应拦截剥离

**Retry Storm Defense**:
针对非幂等写接口（POST/PUT 等）在发生读取超时时彻底禁止重试，仅对只读接口（GET）在握手建连失败时受控重试 1 次，并采用带保底时间窗的 Full Jitter 随机抖动算法打散重试脉冲的高可用防雪崩纪律。
_Avoid_: 容错重试, 弹性重试机制, 失败轮询

**4D Context Propagation**:
在微服务跨进程 HTTP 通信时，出站请求（`FeignContextPropagationInterceptor`）以最高优先级自动抽取当前线程的 MDC traceId、userId、tenantId、ignoreTenant 快照及可选的 Seata 全局事务 XID（`TX_XID`）挂载至 HTTP 标头；入站请求（`FeignContextWebFilter`）对称提取 HTTP 标头还原至对应上下文容器，并在出口 `finally` 块中 100% 确定性释放的全链路双向治理机制。
_Avoid_: 标头透传, 请求头复制, 线程变量传递

**Canary Traffic Tag Propagation**:
在微服务全链路跨进程通信中，出站与入站通过零强依赖反射桥梁（`FeignTrafficPropagationSupport`）双向同步 `TrafficContextHolder` 中的流量染色标签（`X-Traffic-Tag`）、所属泳道（`X-Traffic-Lane`）与命中规则（`X-Traffic-Rule`），并在未引入流量模块时平滑回退 MDC 的金丝雀灰度发布路由机制。
_Avoid_: 灰度标头透传, 标签传递, 染色转发

**Same-Token Fail-Fast Guard**:
依托 `SameTokenProvider` SPI 向出站 Feign 请求动态挂载内部同源受信凭据标头（`Same-Token`），并在服务端入站过滤器中支持按需开启 `Same-Token` 强校验与白名单路径放行，对绕过网关直连内网暴露端口的非受信任流量实施快速 401 阻断的内网零信任防穿透机制。
_Avoid_: 内网Token拦截, 鉴权请求头, 安全签名透传

**Causal Error Decoding**:
当远端服务返回业务错误码、HTTP 4xx/5xx 错误或非标准报文时，优先按对端标准 `Result` 报文的业务错误状态转译为受管的强类型 `BusinessException`，且 100% 完整保留原始因果链（底层 `FeignException` 作为 `cause`）；对于网络建连/读取超时（`RetryableException`），默认保留原生异常以兼容熔断器统计，并支持按需开启网络异常强转译的防御性异常解码体系。
_Avoid_: 统一异常拦截, 错误包装器, Feign异常处理

**Connection Pool Warm Transport**:
摒弃原生单连接阻塞模式，默认以 Apache HttpClient 5 线程池作为高并发通信底座，正交解耦长连接物理生命周期（TTL）与空闲淘汰时限并在后台主动定时驱逐失效连接，保障微服务间长连接复用与优雅路由管理的传输底座。
_Avoid_: HTTP客户端, 底层驱动, 网络通信组件

**Stream Passthrough**:
对声明返回 `byte[]`、`Resource`、`InputStream`、`File` 或原生 `Response` 的二进制与文件流接口执行无条件豁免，绝对不碰流、不解析 JSON 报文并原样透传的流式传输机制。
_Avoid_: 二进制解包, 文件流拦截, 数据流解码

**QueryMap Auto-Binding**:
针对 GET 请求携带 POJO 复杂查询对象的入参，自动解析并展开为 HTTP URL Query 参数，彻底消除误转 POST 或参数丢失的参数绑定规范。
_Avoid_: 参数展平器, GET对象映射, 查询参数转换

**Per-Client Isolation**:
支持“全局默认基线 + 单 Client 精确覆盖（`contextId` 优先、`name` 回退）”的三级配置优先级模型，实现不同下游服务在连接/读取超时、重试策略、日志级别、自适应解包及响应载荷防爆上限（`maxResponseSize`）上的物理隔离配置机制。
_Avoid_: 客户端独立配置, 差异化设置, 局部属性覆盖

**Eager Preheating**:
在容器启动完毕后通过后台探针对关键 Feign 客户端及其连接池执行静默饥饿预热，彻底消灭滚动发布期间首次请求冷启动高耗时与读取超时故障。
_Avoid_: 客户端提前加载, 预热初始化, 首次请求加速

**Observation Metrics**:
无缝接入 Micrometer Observation 标准门面，自动采集微服务 RPC 调用的耗时直方图、吞吐量、状态码分布及分布式 Span 编织的统一可观测度量体系。
_Avoid_: 客户端埋点, Feign监控, 调用链采集

**Slow Call Detection**:
在 `AtengFeignLogger` 响应拦截切面中基于强类型 `Duration` 阈值（支持全局与单 Client 覆盖）实时监测 RPC 调用耗时，一旦超越阈值即突破 `DEBUG` 限制主动输出结构化 `WARN` 慢调用告警日志的生产排障机制。
_Avoid_: 慢请求日志, 耗时打印, 慢接口监控

**Payload Guard**:
配合真实的请求体字节流 GZIP 压缩与响应 GZIP 解压流透明适配，对超过阈值（支持全局与单 Client 覆盖）的超大响应报文实施流式有界读取与快速失败熔断，守住客户端 JVM 堆内存与防范 GZIP 解压炸弹的内存防波堤机制。
_Avoid_: 报文大小限制, 内存防爆, 响应拦截器
