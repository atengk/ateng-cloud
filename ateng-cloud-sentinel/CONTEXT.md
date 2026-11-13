# Sentinel Context

流量防卫、熔断降级与高可用容灾治理限界上下文，负责微服务入站 Web 流量限流、RESTful 资源归一化清洗、请求来源防刷管控、Feign 出站客户端弹性熔断、@SentinelResource 异常受控转译，以及 Nacos 5 大规则全家桶的动态持久化桥接。

## Language

**Managed Fallback Factory**:
基于 `AtengFallbackFactory<T>` 实现的受管 Feign 降级工厂。自动识别并原样透传下游已解码的业务异常，将客户端流控或熔断异常精准映射为对应阻断错误码并联动阻断计数器，其余底层网络异常转译为保留因果链的降级异常；同时对集合、字典与可选容器提供空安全兜底并开放柔性保底钩子。
_Avoid_: 降级实现类, Feign回调桩, 熔断兜底对象

**Exception Trace Bridge**:
在全局异常处理器消化业务未受检异常前，自动将非豁免的系统故障异常桥接注入当前 Sentinel 资源上下文的异常追踪机制，解决因全局异常切面吞噬异常导致 Web 接口异常比例与异常数熔断静默失效的机制。
_Avoid_: 异常埋点器, 手动Tracer调用, 报错拦截器

**Resource Tree Explosion Defense**:
优先在当前请求路径匹配时提取非通配的 Spring MVC 路由模板、归一化尾部斜杠，并按配置白名单与内置/自定义正则收敛动态 ID（数字、UUID、Hex 哈希与业务单号），防止带可变参数的 RESTful URL 导致 Sentinel 内部资源监控树发生无界内存膨胀与 OOM 隐患。
_Avoid_: 路径格式化, URL替换, 资源过滤

**Multi-Dimensional Origin Resolution**:
按调用方服务标头、网关来源标头、可选多租户上下文及客户端真实 IP 优先级依次回溯解析请求来源的多维调用溯源机制，支撑服务级、网关级与租户级精细化黑白名单授权及流控。
_Avoid_: 来源提取器, Header解析器, IP获取工具

**Block Dual-Alignment Contract**:
当入站 Web、切面注解或出站 Feign 请求触发流量防卫阻断时，同时返回物理 HTTP 状态码（HTTP 429 Too Many Requests / HTTP 503 Service Unavailable，含可选退避响应头）与强类型统一响应体 `Result<Void>` 的双重对齐响应契约。
_Avoid_: 拦截报错, 限流提示, 降级错误输出

**Aspect Escape Defense**:
通过全局异常增强切面捕获标注 `@SentinelResource` 的方法在未指定 `blockHandler` 时逃逸的 `BlockException`（含动态代理与反射包装异常）以及携带阻断错误码的受管降级异常，杜绝未捕获阻断转化为系统 500 报警的防御机制。
_Avoid_: 注解异常处理, AOP报错捕获, 切面补丁

**Full-Spectrum Nacos Rule Bridge**:
通过统一命名契约 `${prefix}-{type}.json` 与四级连接参数自适应继承，按需自动注册与动态监听 Flow、Degrade、ParamFlow、System、Authority 5 种规则数据源的开箱即用持久化治理机制。
_Avoid_: 规则配置同步, Nacos规则推送, 动态规则监听

**Rule Fail-Safe Invariant**:
当 Nacos 推送的规则报文发生 JSON 语法错误或违反核心规则语义（如空资源名、负数阈值、空元素）时，拒绝更新规则属性、记录容错拦截快照并严格保留最后已知有效规则（Last Known Good Rules），杜绝脏配置将运行中有效规则清空或覆盖引发流量击穿的容错防线。
_Avoid_: 规则清空, 空列表容错, 格式报错降级

**Sentinel Panorama Diagnostics**:
通过暴露受管 Actuator 监控端点 `/actuator/sentinel-panorama`，实时输出微服务内存中 5 大规则快照、精确的已启用 Nacos 数据源订阅状态与容错拦截计数、路径清洗与溯源配置，以及包含 `traceId` 与 `origin` 的原子阻断事件快照的运维自省可观测机制。
_Avoid_: 规则查看器, 控制台诊断, 状态打印接口
