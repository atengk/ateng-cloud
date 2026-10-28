# Feign 声明式 HTTP 通信、自适应解包与防雪崩重试架构设计

## Context

在分布式微服务高并发生产环境中，基于 Spring Cloud OpenFeign 的服务间通信面临以下典型挑战与架构痛点：
1. **统一契约与业务代码冗余矛盾**：框架推行显式统一返回契约 `Result<T>`，微服务间通信在物理层面上始终返回包装对象。若由业务开发人员在每个 Feign 调用处手动判空与剥壳，会导致代码充斥样板代码；若无脑使用全局切面，又会破坏对第三方非标接口的兼容性。
2. **读超时重试引发全链路雪崩**：对于非幂等写接口（POST/PUT，如下单、扣减库存、账户转账），发生网络读超时（`SocketTimeoutException`）往往意味着对端服务端正处于高负载并在执行数据库写操作。此时客户端若无脑发起重试，不仅会造成下游重复落库，更会瞬间引发数据库锁冲突与全网雪崩。
3. **四维上下文与内网零信任防穿透断链**：微服务跨进程调用时，若未能透明透传 MDC traceId、租户 ID、用户 ID 及 Sa-Token 内部受信凭据（Same-Token），会导致多租户隔离失效、链路追踪断裂及内网未授权穿透攻击。
4. **冷启动首调超时与生产深水区缺陷**：Feign 默认懒加载机制导致滚动发布瞬间首调延迟飙升；对于二进制流、文件导出、复杂 GET POJO 传参以及大报文内存防爆缺乏工业级防御支撑。

## Decision

确立 `ateng-cloud-feign` 核心通信与治理架构契约：

1. **双模自适应契约解包引擎 (Adaptive Unwrap Engine)**：
   - 当调用端 Feign 接口声明返回具体业务对象 `T`（如 `UserVO`，非 `Result<T>`）时，定制解码器自动校验物理契约 `Result<T>` 的 `code == 200`，校验通过自动剥壳返回业务对象 `T`；若 `code != 200`，自动抛出携带原样状态码与错误描述的受管 `BusinessException`；
   - 当方法声明返回原生 `Result<T>` 时，解码器自动关闭解包，原样透传供调用方自行模式匹配；
   - 当方法声明返回 `void` / `Void` 时，校验 `code == 200` 正常返回 `null`，非 200 时同样抛出 `BusinessException`；
   - **精细化规避与第三方豁免**：提供 `@AdaptiveUnwrap(false)` 注解（支持类与方法级）与 `ateng.cloud.feign.adaptive-unwrap.exclude-clients` 配置白名单，对非标准 JSON 响应自动降级为原生 Jackson 反序列化，彻底消除误杀。

2. **因果异常解码与因果链保真 (Causal Error Decoding)**：
   - 定制 `AtengFeignErrorDecoder`，当遭遇非 2xx HTTP 状态码时，优先尝试按 `Result<?>` 格式解析并转译为业务异常；
   - 无法解析或属于网关/网络层故障时，统一转译为 `BusinessException(CommonResultCode.RPC_ERROR, ...)`，同时将底层原始 HTTP 状态码、请求 URL 与原始根因因果链（`cause`）100% 完整保留，杜绝吞咽异常或 Log-and-Throw 污染。

3. **防雪崩弹性重试纪律 (Retry Storm Defense)**：
   - 定制 `AtengFeignRetryer` 彻底践行**“写请求绝对禁止重试不变量”**：
     - 对非幂等写接口（`POST`、`PUT`、`PATCH`、`DELETE`），无论发生任何异常，**默认彻底关闭重试**，直接抛出阻断，杜绝下游重复落库与资损；
     - 仅对纯读接口（`GET`、`HEAD`），且引发异常的根因精确为握手建连失败（`ConnectTimeoutException`），才允许弹性重试 1 次；对读取超时（`SocketTimeoutException` / `ReadTimeoutException`）一律禁止盲目重试，防范对端满载时演变为重试风暴；
   - 强类型配置项 `ateng.cloud.feign.retry.*` 统一采用 `Duration` 规范管理重试间隔与上限。

4. **全链路四维上下文与零信任同源防穿透透传 (4D Context & Same-Token)**：
   - 实现 `FeignContextPropagationInterceptor`，在请求出站前自动从当前线程提取并标准化挂载：`X-Trace-Id`、`X-User-Id`、`X-Tenant-Id`、`X-Ignore-Tenant` 及 `X-Traffic-Tag`；
   - **平滑条件探测零信任凭据**：基于 `@ConditionalOnClass(SaSameUtil.class)` 条件探测，仅当类路径存在 Sa-Token 且开启校验时自动附加 `Same-Token`，未引入时平滑跳过；
   - **空值防呆清洗**：上下文项为空时直接跳过标头注入，严禁向 HTTP Headers 写入 `"null"` 脏数据；
   - **日志脱敏防线**：定制 `AtengFeignLogger`，在输出请求详情时对 `Same-Token`、`Authorization` 等敏感标头执行自动掩码脱敏。

5. **连接池高并发通信底座 (Connection Pool Warm Transport)**：
   - 标配引入 `io.github.openfeign:feign-hc5`，默认自动装配 Apache HttpClient 5 线程池通信底座；
   - 提供生产合理的默认连接池参数（最大连接数 200，单路由并发 50，长连接存活 900s，连接超时 2s，读取超时 5s），彻底告别原生单连接阻塞模式。

6. **全场景二进制流与文件传输支持 (Stream Passthrough & Multipart)**：
   - 在解码器前置设置白名单卫语句，对声明返回 `Response`、`byte[]`、`Resource`、`InputStream`、`File` 的方法无条件豁免，绝对不碰流、不解析 JSON，实现纯净流式透传；
   - 整合 `feign-form` / `feign-form-spring` 编码器，开箱即用支持 `MultipartFile` 单/多文件及表单混合上传。

7. **复杂 GET 参数自动绑定与客户端隔离配置 (QueryMap & Per-Client Isolation)**：
   - 自动装配 `SpringQueryMap` 解析适配器，彻底防范 GET 请求传递 POJO 时被误转为 POST 或参数丢失；
   - 建立“全局默认基线 + 单 Client 精确覆盖”的三级配置体系（`ateng.cloud.feign.clients.<name>.*`），实现不同下游服务在超时、重试与解包规则上的物理隔离配置。

8. **饥饿预热、统一度量与内存防爆 (Preheating, Observation & Payload Guard)**：
   - 提供客户端饥饿加载机制（`eager-load`），在容器启动完成时静默预热指定 FeignClient，消灭滚动发布首调超时；
   - 统一接入 Micrometer Observation 标准门面，自动采集微服务 RPC 调用的耗时直方图与状态码分布，联动 `ateng-boot-observability` 完成跨进程 Span 编织；
   - 支持弹性 GZIP 请求/响应压缩，设置响应体硬防爆阈值（默认 10MB），防范客户端内存 OOM。

9. **100% 离线自洽 1:1 场景工程**：
   - 在 `ateng-cloud-samples/sample-cloud-feign` 配套专属沙箱，内置 Mock Provider 控制器，基于 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 全真覆盖自适应解包、原生透传、错误转译、标头透传、重试防雪崩及流式传输。

## Consequences

1. **极简且安全的研发体验**：业务开发人员无需编写冗余的判空与解包逻辑，直接接收强类型实体，同时对非标接口保持零侵入兼容。
2. **彻底消灭非幂等资损与雪崩**：严密的防重试雪崩算法从底层截断了网络抖动时对非幂等接口的重复提交，保障资金与核心订单安全。
3. **零信任内网防穿透闭环**：协同 Sa-Token Same-Token 与四维标头透传，确保未经网关鉴权的外部恶意探测直接 Fail-Fast 阻断。
4. **全场景工业级覆盖**：系统性攻克了文件流式传输、GET 复杂参数绑定、客户端配置隔离、冷启动首调超时及超大报文 OOM 等生产深水区难题。
5. **CI 门禁毫秒级全绿**：100% 离线自洽测试架构保障在任何脱网环境下持续交付与构建全绿。
