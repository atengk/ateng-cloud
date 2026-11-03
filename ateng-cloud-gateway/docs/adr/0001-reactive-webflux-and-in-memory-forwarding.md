# 0001: 纯响应式 WebFlux 物理隔离与离线内部转发架构

Spring Cloud Gateway 严格运行于 Spring WebFlux 与 Netty 事件循环之上，物理隔离 Servlet 阻塞依赖栈（严禁引入 `ateng-boot-web`），以杜绝 Netty EventLoop 线程被阻塞引发全网假死。

在样例工程 `sample-cloud-gateway` 中，统一基于 Spring Cloud Gateway 原生 `forward:/` 协议断言将微服务路由分发至本地 WebFlux 响应式控制器，替代启动额外外部微服务进程或 Mock HTTP 端口的方案。此决策在确保完整覆盖网关全局过滤器链与断言逻辑的同时，消除了多端口并发冲突与外部进程管理开销，保障了持续集成环境（CI）下的 100% 离线自洽与毫秒级执行。
