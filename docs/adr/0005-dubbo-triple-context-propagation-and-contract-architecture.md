# Dubbo 3.3 高性能 RPC、四维上下文透传与双模契约架构设计

## Context

在分布式微服务架构中，对于高并发、低延迟、大吞吐以及流式通信的核心计算链路，微服务间需要具备比 HTTP/REST 更轻量、高效的 RPC 传输底座。基于 Apache Dubbo 3.3 的微服务通信体系在生产落地面临以下典型架构挑战：

1. **统一契约与 RPC 接口共享矛盾**：框架推行 `Result<T>` 显式统一返回契约，但 Dubbo 倡导共享 Java 接口（在 `*-api` 中声明）。若强制要求接口方法返回 `Result<T>`，客户端可能遗漏 `isSuccess()` 检查导致脏数据逻辑穿透；若直接返回业务对象 `T`，又需要与 HTTP Feign 的显式统一契约规范保持架构自洽。
2. **读超时重试风暴与资损风险**：Dubbo 默认配置对所有方法开启 `retries: 2`（总共调用 3 次），默认 `timeout: 1000ms`。在非幂等写接口（下单、支付、扣库）发生网络慢调用时，默认重试会导致下游重复落库，引发数据库锁死与全链路雪崩，直接触犯“写请求绝对禁止重试不变量”。
3. **四维上下文断链与内网穿透隐患**：RPC 跨进程通信若未能穿透 MDC traceId、userId、tenantId、ignoreTenant 及流量染色标记（`X-Traffic-Tag`），会导致调用链断裂与多租户数据泄露；绕过网关直连内部 Dubbo 暴露端口时若缺乏零信任校验，会带来内网穿透安全漏洞。
4. **异常因果链抹平与反序列化崩溃**：Dubbo 原生 `ExceptionFilter` 会将未在接口 `throws` 中声明的自定义业务异常强制包装为裸 `RuntimeException`，导致客户端丢失错误码；而直接放行所有底层未知异常又容易因客户端类路径缺失引发 `ClassNotFoundException` 反序列化灾难。
5. **深水区生产治理缺失**：包含线程池打满过载保护、应用级服务发现（避免 Nacos 注册表膨胀）、金丝雀灰度平滑降级（Tag Router）、流式传输报文防爆以及无损上下线状态机协同。

## Decision

确立 `ateng-cloud-dubbo` 核心架构机制与生产防线：

1. **双核通信协议基线与二进制序列化 (Triple & Fastjson2 Invariant)**：
   - 默认主推云原生 **Triple 协议**（`tri`，默认端口 `50051`），基于 HTTP/2 协议栈与 gRPC 规范，原生支持 POJO 与流式通信；
   - 序列化默认采用高性能且类型安全的 **`fastjson2`**（JSONB 二进制模式），天然支持 JDK 17 Records、多态对象与异常堆栈，杜绝反序列化 RCE 风险；同时支持经典 `hessian2` 备选；
   - 保持对经典高性能 TCP `dubbo` 协议（端口 `20880`）的无缝兼容与多协议共存支持。

2. **双模契约与结果守卫机制 (Dual-Mode RPC Contract)**：
   - **模式 1（原生强类型实体返回）**：RPC 接口直接声明返回强类型业务对象 `T`。业务正常执行直接返回数据；业务错误时 Provider 抛出受管 `BusinessException(code, message)`，通过定制 Filter 原生透传；
   - **模式 2（显式 Result<T> 强契约防呆）**：当 RPC 接口声明返回 `Result<T>` 时，Consumer 端 Filter 自动后置拦截出参：若 `!result.isSuccess()`，自动剥壳转译为 `BusinessException(code, message)` 抛出；确保调用端只要正常接收到返回值，业务就必定成功，彻底消除漏判隐患。

3. **全链路四维上下文透传与零信任同源防穿透 (4D Context & Same-Token Guard)**：
   - 实现 `DubboContextPropagationFilter`（双向出入栈拦截）：
     - **Consumer 出站**：从当前线程提取 `X-Trace-Id`、`X-User-Id`、`X-Tenant-Id`、`X-Ignore-Tenant` 及 `X-Traffic-Tag`，压入 `RpcContext.getClientAttachment()`；
     - **Provider 入站**：从 `RpcContext.getServerAttachment()` 还原至 MDC、`UserContextHolder`、`TenantContextHolder`；在 `finally` 块中强制执行 `clear()`，杜绝 Netty 线程池脏读；
   - **零信任内网防穿透**：平滑条件探测 Sa-Token。当微服务开启 `sa-token.same-token.check=true` 时，Consumer 自动附加 `Same-Token`，Provider 强制执行合法性校验，缺失或无效凭据直接 Fail-Fast 401 阻断。

4. **因果异常保真与反序列化防御 (Causal Exception Defense)**：
   - 实现定制 `AtengDubboExceptionFilter`（接管 Dubbo 原生 ExceptionFilter）：
     - 对 `BusinessException`、JSR-303 参数校验异常及 JDK 标准异常实施白名单放行，100% 完整保留原始错误码、错误信息及因果链；
     - 对客户端类路径可能缺失的底层未知系统崩溃异常，在 Provider 端安全包装为 `BusinessException(CommonResultCode.RPC_ERROR, ...)`，彻底防御 Consumer 端 `ClassNotFoundException`。

5. **防重试雪崩安全生产基线 (Retry Storm Defense)**：
   - 遵循“写请求绝对禁止重试不变量”，全局配置基线默认强制收敛为 `dubbo.consumer.retries=0`、`dubbo.provider.retries=0`；
   - 默认超时放宽至高并发生产合理的 `dubbo.consumer.timeout=5000`（5秒）；
   - 仅对纯读幂等接口，允许开发者在方法级显式声明 `@DubboReference(retries = 1)`。

6. **全链路流量染色与金丝雀灰度平滑降级 (Tag Router Gray Fallback)**：
   - Consumer Filter 自动将上下文中的 `X-Traffic-Tag` 压入 `CommonConstants.TAG_KEY`，激活 Dubbo 3 原生 `TagRouter`；
   - 默认配置 `dubbo.tagforce=false`，若灰度实例全部下线，自动弹性平滑降级至基线（无标签）实例，杜绝全网 503 故障。

7. **服务端线程池过载保护与参数校验统一转译 (Overload Protection & Validation Guard)**：
   - 默认装配 `fixed` 200 线程池，定制拒绝执行处理器，线程池打满时记录关键过载日志并优雅抛出 `BusinessException(CommonResultCode.FLOW_LIMIT)`；联动 Micrometer 采集线程池指标；
   - 整合 Dubbo `ValidationFilter`，将 JSR-303 参数格式校验失败异常统一转译为 `BusinessException(CommonResultCode.PARAM_INVALID, ...)`。

8. **应用级服务发现与优雅停机联动 (Application Discovery & Zero-Downtime)**：
   - 默认推行纯应用级服务发现（`dubbo.application.register-mode=instance`），与 Nacos 1:1 对齐并彻底消灭注册表膨胀；
   - 停机时协同 `ateng-cloud-nacos` 无损下线状态机，先注销注册表 -> 排空在途请求（`dubbo.application.shut-down.wait=15000`）-> 释放物理资源。

9. **100% 离线自洽 1:1 场景工程**：
   - 在 `ateng-cloud-samples/sample-cloud-dubbo` 配套专属沙箱，声明 `dubbo.registry.address=N/A` 点对点直连，全真覆盖 Triple 协议、双模契约、四维上下文透传、Same-Token 拦截、异常保真与流式通信。

## Consequences

1. **高性能与双模兼备**：企业级高并发链路获得 HTTP/2 Triple 与 Fastjson2 二进制 RPC 极致吞吐，同时完美兼容 `Result<T>` 统一契约与原生 POJO 返回。
2. **资损重试与雪崩免疫**：彻底根除 Dubbo 默认重试导致的非幂等接口多次落库资损隐患。
3. **零信任与链路治理闭环**：实现 Dubbo RPC 与 HTTP Feign 完全对称的四维上下文穿透、灰度染色路由与内网防穿透保护。
4. **CI 门禁离线自洽全绿**：脱网单测与 Sample 均可在无物理 Nacos 下秒级自验通过。
