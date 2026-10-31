# Sentinel Context

流量防卫、熔断降级与高可用容灾治理限界上下文，负责微服务入站 Web 流量限流、RESTful 资源归一化清洗、请求来源防刷管控、Feign 出站客户端弹性熔断、@SentinelResource 异常受控转译，以及 Nacos 5 大规则全家桶的动态持久化桥接。

## Language

**Managed Fallback Factory**:
基于 `AtengFallbackFactory<T>` 实现的受管 Feign 降级工厂。默认将 Sentinel 阻断与网络因果链转译为语义化 `BusinessException`，彻底阻断脏数据扩散；同时开放反射钩子支持柔性业务保底。
_Avoid_: 降级实现类, Feign回调桩, 熔断兜底对象

**Resource Tree Explosion Defense**:
通过 `AtengUrlCleaner` 优先提取 Spring MVC 路由模板、保底执行路径通配化清洗，防止带可变参数的 RESTful URL 导致 Sentinel 内部资源监控树发生无界内存膨胀与 OOM 隐患。
_Avoid_: 路径格式化, URL替换, 资源过滤

**Block Dual-Alignment Contract**:
当请求触发流量限制时，同时返回物理 HTTP 状态码（HTTP 429 Too Many Requests / HTTP 503 Service Unavailable）与强类型统一响应体 `Result<Void>` 的双重对齐响应契约。
_Avoid_: 拦截报错, 限流提示, 降级错误输出

**Aspect Escape Defense**:
通过 `@RestControllerAdvice` 全局捕获标注 `@SentinelResource` 的 Controller/Service 内部方法在未指定 blockHandler 时逃逸的 `BlockException`，杜绝未捕获异常转化为系统 500 报警的防御机制。
_Avoid_: 注解异常处理, AOP报错捕获, 切面补丁

**Full-Spectrum Nacos Rule Bridge**:
通过统一命名契约 `${prefix}-{type}.json`，一次性自动注册与动态监听 Flow、Degrade、ParamFlow、System、Authority 全量 5 种规则数据源的开箱即用持久化治理机制。
_Avoid_: 规则配置同步, Nacos规则推送, 动态规则监听

**Rule Fail-Safe Invariant**:
当 Nacos 推送的规则报文解析异常（语法错误或结构非法）时，拒绝更新规则属性并严格保留最后已知有效规则（Last Known Good Rules），杜绝误语法配置将运行中全量规则清空引发流量暴击的容错防线。
_Avoid_: 规则清空, 空列表容错, 格式报错降级

**Sentinel Panorama Diagnostics**:
通过暴露受管 Actuator 监控端点 `/actuator/sentinel-panorama`，实时输出微服务内存中 5 大规则全家桶快照、Nacos 动态数据源订阅状态、路径清洗器配置及阻断事件统计的运维自省可观测机制。
_Avoid_: 规则查看器, 控制台诊断, 状态打印接口
