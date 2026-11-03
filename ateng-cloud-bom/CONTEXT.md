# Bill of Materials (BOM) Context

微服务全域依赖版本物料清单治理上下文，负责跨模块依赖版本统管、上游技术基线对齐与传递依赖仲裁收敛。

## Language

**Bill of Materials**:
集中管理微服务框架及上游生态全部依赖坐标与版本号的清单式 POM 构件，供下游业务项目通过 `import` 模式引入。
_Avoid_: 版本文件, 依赖字典, 根POM

**Dependency Convergence**:
全微服务体系内将同一依赖的不同传递版本收敛到唯一确定版本的版本仲裁治理过程。
_Avoid_: 版本统一, 冲突合并, 依赖消除

**Upstream Baseline**:
由上游正式发布的依赖版本集合（如 Spring Boot、Spring Cloud、Spring Cloud Alibaba 与 ateng-boot-bom），作为框架依赖演进的绝对基准。
_Avoid_: 基础环境, 底层包, 外部版本

**Import Scope**:
Maven `<dependencyManagement>` 专用导入域，用于将远端物料清单的依赖版本约束扁平化合并至当前项目。
_Avoid_: 依赖注入, scope依赖, 引入模式

**Version Overriding**:
下游微服务通过在 BOM 导入前显式声明特定构件坐标，依循 Maven “先声明者优先” 机制局部接管特定组件版本的受控操作。
_Avoid_: 强制覆写, 版本覆盖, 篡改版本

**Single-Source of Truth**:
全项目所有对外构件及其依赖版本的唯一权威定义中心，各微服务子模块声明依赖时严禁配置 `<version>`。
_Avoid_: 统一版本中心, 版本硬编码, 依赖基线表

**Transitive Exclusion**:
通过 Maven `<exclusions>` 主动剔除特定多余或冲突传递依赖的防御性依赖管理策略。
_Avoid_: 排包, 过滤依赖, 依赖屏蔽
