# 主干发布与流水线运维规约 (Release Workflow & CI Decoupling)

本指南记录 `ateng-cloud` 主干发布铁律、发布脚本调度机制与上游 BOM 依赖锁定策略，供发布者与自动化流水线协同遵守。

## 1. 主干发布铁律与分支防呆 (Trunk-Based Release)

- **分支唯一性**：版本发布必须且只能在 `main` 主干分支发起，严禁从任何未经合入的特性分支或孤立提交发版；
- **状态纯净度**：发版前置要求本地工作区 100% 干净（无未暂存或未提交变更），且本地 `main` 必须与远端 `origin/main` 保持完全同步；
- **上游 BOM 依赖锁定**：`ateng-cloud` 仅消费上游 `ateng-boot-bom` 的正式 Release 版本（当前基线 `1.0.0`），发版前必须确保工程中不存在任何 SNAPSHOT 依赖；
- **CI 祖先谱系校验**：GitHub Actions 流水线针对网页手动调度（`workflow_dispatch`）与 Tag 推送事件实施双重祖先谱系校验（`git merge-base --is-ancestor`），确保发布产物 100% 源自主干分支。

---

## 2. 自动化发布门面 (Release Facade)

发版统一通过根目录门面脚本调度：

```bash
bash scripts/release.sh
```

- **单一版本流与原地更新**：文档接入依赖坚持展示纯净具体版本，严禁在 XML 中插入机器占位注释。脚本内置原生 `sed -i` 直接流式修改同步更新所有模块 POM 与用户接入文档；
- **消除 SNAPSHOT 冗余提交**：主干始终保持在最新稳定正式版，彻底消除 `-SNAPSHOT` 升级与回退的双重提交与双重 CI 干扰；
- **CI 智能网关秒级放行**：发布提交使用 `chore(release): ...` 规范命名，由 CI 工作流内置的路径过滤智能网关识别后秒级短路跳过冗余单元测试，交由专用 Release 工作流闭环处理。

---

## 3. Maven Central 部署解耦 (Publishing Decoupling)

- **非分发模块隔离**：非分发模块（如 `ateng-cloud-samples` 及其场景子模块）自身标配声明 `<maven.deploy.skip>true</maven.deploy.skip>`，且根发布插件协同配置 `<excludeArtifacts>` 排除，杜绝多模块反应堆封包缺 POM 导致 Central 拒收；
- **极速异步验证**：发布插件使用 `<waitUntil>validated</waitUntil>` 进行异步解耦验证，避免长轮询等待 Central 同步导致的流水线挂起与超时。
