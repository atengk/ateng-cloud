# Issue 跟踪器与工单状态机规范 (GitHub Issue Tracker)

本仓库的所有 Issue、任务工单与架构演进规范均托管在 GitHub Issues 中，统一通过 `gh` CLI 操作（标签状态映射详见 [`triage-labels.md`](./triage-labels.md)）。

## 1. `gh` CLI 标准操作速查

- **查看详情与评论**：`gh issue view <number> --comments`
- **按标签查询队列**：`gh issue list --state open --label "ready-for-agent" --json number,title,labels,assignees`
- **认领指派**：`gh issue edit <number> --add-assignee "@me"`
- **流转标签**：`gh issue edit <number> --add-label "<add>" --remove-label "<remove>"`
- **创建工单**：`gh issue create --title "<title>" -F <body-file>`
- **回写报告**：`gh issue comment <number> -F <report-file>`
- **关闭工单**：`gh issue close <number> --comment "<summary>"`

## 2. Windows PowerShell 跨平台避坑铁律

1. **`@me` 等特殊符号强制双引号包裹**：在 PowerShell 下执行 `gh` 命令时，凡包含 `@`、`,` 等特殊字符的参数必须使用双引号包裹（如 `--add-assignee "@me"`），防止 PowerShell 将 `@me` 解析为 Splatting 变量报错。
2. **多行长文本强制 `-F <file>` 文件传入**：创建 Issue 或回写验收报告时，**严禁在命令行直接拼接多行 Markdown 字符串**；必须先将内容写入 Scratch 临时文件，再通过 `-F <file>` 传入，杜绝转义截断与编码乱码。

## 3. 五阶段工单执行状态机与显式 Git 授权边界

处理 GitHub Issue 工单时，严格划分为 **研发验证阶段（阶段 1~3）** 与 **受控交付阶段（阶段 4~5）**：

### 阶段 1~3：默认工作区闭环（未获提交指令前在此停止）
1. **阶段 1（认领与上下文对齐）**：执行 `gh issue view <n> --comments` 研读诉求，对照 [`CONTEXT-MAP.md`](../../CONTEXT-MAP.md) 与 [`docs/adr/README.md`](../adr/README.md) 确认架构边界；若获指令认领则执行 `gh issue edit <n> --add-assignee "@me"`。
2. **阶段 2（TDD 与 Sample-First 实现）**：在目标 Starter 与对应 `ateng-cloud-samples/sample-cloud-<module>` 中实施编码与双模测试。
3. **阶段 3（阶梯二验证与 13 项准出自检）**：运行阶梯二 Maven 测试全绿，完成 [`documentation-specification.md`](./documentation-specification.md) 的 13 项准出自检与 `/code-review`。
   > **🛑 停止判据（Explicit Two-Tier Git Gate）**：若用户未在会话中明确下达“提交/commit”或“推送/push”指令，完成阶段 3 后**必须停留在本地工作区向用户汇报验证结果**，严禁擅自提交、推送或关闭 Issue。

### 阶段 4~5：显式授权交付闭环（仅在收到明确提交/推送指令后触发）
4. **阶段 4（规范提交与 CI 守护）**：使用 `bash scripts/commit.sh -t <type> -s <scope> -m "<主说明>" [-l "<细项>"] -c <n> [-p] -y` 提交（若获批推送则附加 `-p`），随后立即通过 `gh run list -L 1` 与 `gh run watch <run-id>` 持续监控远端 CI 矩阵直至 100% 全绿。
5. **阶段 5（验收报告回写与关单）**：使用 `gh issue comment <n> -F <report-file>` 回写测试结果与 Commit SHA，最后执行 `gh issue close <n>` 闭环。

## 4. 外部 PR 分诊开关

- **PR 作为需求分诊入口**：`no`（当前仅通过 GitHub Issues 进行需求分诊）。
