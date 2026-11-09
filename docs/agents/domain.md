# 领域模型与多上下文治理规范 (Domain Model & Multi-Context Governance)

`ateng-cloud` 采用 **Multi-Context（多限界上下文）** 架构治理微服务基础设施模块。在探索模块边界、命名领域概念、设计跨服务契约或新增/查阅架构决策记录（ADR）时，必须严格遵守本规约。

## 1. 领域文件拓扑布局

```text
ateng-cloud/
├── CONTEXT-MAP.md                         # 根目录唯一上下文拓扑图与跨模块关系总览（严禁在根目录创建 CONTEXT.md）
├── ateng-cloud-<module>/
│   └── CONTEXT.md                         # 各微服务 Starter 限界上下文的纯领域词汇表（Ubiquitous Language + Avoid Terms）
├── ateng-cloud-samples/
│   └── CONTEXT.md                         # Sample 1:1 验证沙箱矩阵与双模测试领域词汇表
└── docs/
    └── adr/
        ├── README.md                      # 全量 ADR 索引看板与防膨胀治理规范
        └── NNNN-kebab-case-title.md       # 编号递增的架构决策记录
```

## 2. 上下文探索与词汇对齐法则

1. **先读图，再入域**：跨模块研发或架构探查时，首先读取根目录 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md) 识别目标限界上下文与上下游协同关系，再按需读取目标子模块的 `ateng-cloud-<module>/CONTEXT.md`。
2. **严格遵守领域通用语言（Ubiquitous Language）**：
   - 类名、接口、配置属性、测试方法与文档术语必须严格对齐所属模块 `CONTEXT.md` 中定义的标准词汇（如 `ReactiveZeroTrustGateway`、`AdaptiveResponseDecoder`、`FourDimensionContextFilter`、`ThreeTierGrayLoadBalancer`、`TccFenceInterceptor`）。
   - **严禁使用 `Avoid Terms` 列表中的废弃或歧义术语**。
3. **保持 `CONTEXT.md` 纯粹性**：子模块 `CONTEXT.md` 仅维护领域核心概念定义与禁用词表，严禁在其中堆砌实现代码细节、配置长列表或把根模块当成单体上帝上下文。

## 3. ADR 查阅与防膨胀演进铁律

1. **前置对齐既有决策**：修改任何核心微服务机制（如 Gateway 响应式防线、Feign 重试策略、Dubbo Filter 上下文清理、Nacos 优雅上下线、Seata TCC 防悬挂）前，必须通过 [`docs/adr/README.md`](../adr/README.md) 查阅对应 ADR。
2. **冲突显式预警**：若当前需求或实现方案与既有 `Accepted` 状态的 ADR 存在冲突，**必须立即向开发者指出冲突点并征求决策**，严禁静默违背既有架构红线。
3. **防膨胀与增量修订（Update Over Create）**：
   - **新建门槛**：仅当从 0 到 1 孵化全新 Starter 模块、引入全新分布式中间件/协议或推翻既有跨模块架构契约时，方可新增编号递增的 `docs/adr/NNNN-*.md`。
   - **存量演进**：既有模块的常规特性增强、拦截器加固或配置扩展，一律在原归属 ADR 文档中增量修订，并同步更新 [`docs/adr/README.md`](../adr/README.md) 与 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md)。
