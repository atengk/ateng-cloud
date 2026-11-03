# Aggregate All Context

微服务通用基础设施聚合全家桶上下文，负责将全套微服务骨干构件打包为一站式起步依赖，并守护响应式网关物理隔离与按需裁剪边界。

## Language

**All-in-One Aggregate**:
面向高频微服务业务场景提供的一键聚合起步依赖包，开箱即用集成 Nacos、Feign、Dubbo、Sentinel 与 Traffic 骨干能力。
_Avoid_: 全家桶, 大包, 混合包

**Microservice Backbone**:
微服务拓扑运行所不可或缺的注册发现、RPC 通信、流量防卫与链路染色四大核心治理底座。
_Avoid_: 基础组件, 通用依赖, 骨架

**Optional Isolation**:
针对具有运行时冲突的特殊构件（如响应式网关 Spring Cloud Gateway），在聚合包中采用 `optional=true` 声明以杜绝 Servlet 阻塞栈与 Netty 响应式栈相互污染的物理防线机制。
_Avoid_: 排除网关, 可选引入, 弱引用

**Dependency Tailoring**:
当下游业务属于特定单一技术栈（如纯 Dubbo RPC 或纯 Feign HTTP）时，通过 Maven 排除特定骨干依赖以消除冗余加载的轻量化裁剪实践。
_Avoid_: 依赖瘦身, 删包, 模块剔除

**Smoke Verification**:
通过离线自洽沙箱一键验证聚合全家桶内所有骨干构件装配、容器启动与基础心跳存活的冒烟准出流程。
_Avoid_: 启动测试, 冒烟打标, 自检
