# Issue 跟踪器：GitHub

本仓库的所有 Issue、任务工单与特性规范均直接托管在 GitHub Issues 中。所有操作统一使用 `gh` CLI 执行。

## 操作规约

- **创建 Issue**：`gh issue create --title "..." --body "..."`（多行内容建议使用 `-F <file>` 或 Here-doc）。
- **查看 Issue**：`gh issue view <number> --comments`。
- **查询列表**：`gh issue list --state open --json number,title,body,labels,comments` 并辅以 `--label` 过滤。
- **添加评论**：`gh issue comment <number> -F <file>` 或单行 `gh issue comment <number> --body "..."`。
- **打标/移除标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`。
- **认领任务**：`gh issue edit <number> --add-assignee "@me"`。
- **关闭 Issue**：`gh issue close <number> --comment "..."`。

## 跨平台参数避坑防线 (Windows PowerShell)

- **特殊字符与标识符加引号**：在 PowerShell 下执行 `gh` 命令时，含特殊字符（如 `@me`）的参数务必显式使用双引号包裹（如 `--add-assignee "@me"`），避免 PowerShell 将 `@` 解析为数组表达式或未识别 cmdlet 导致报错。
- **长文本强制文件回写 (`-F <file>`)**：回写验收报告、长篇测试清单或多行内容时，强制将内容写入临时文件并使用 `-F <file>` 传入（如 `gh issue comment <n> -F report.md`），严禁在命令行直接拼接复杂多行文本，杜绝 shell 转义截断与字符编码损坏。

## 标准微服务工单生命周期命令参考

1. **认领**：`gh issue view <n> --comments` 研读上下文，执行 `gh issue edit <n> --add-assignee "@me"` 指派认领。
2. **测试与编码**：依据需求执行 TDD，在 `ateng-cloud-samples/sample-cloud-<scenario>` 补齐端到端场景并实测全绿。
3. **准出审查**：通过阶梯二准出测试并运行 `/code-review`，确保符合 7 大微服务铁律与九道成熟度防线。
4. **受控提交**：通过项目门面 `bash scripts/commit.sh` 提交并推送。
5. **流水线守护**：通过 `gh run list -L 1` 获取 Run ID，执行 `gh run watch <run-id>` 跟踪 CI 至全绿。
6. **回写与闭环**：通过 `gh issue comment <n> -F <file>` 回写验收清单与提交记录，执行 `gh issue close <n>` 关闭工单。

## PR 作为外部需求分诊渠道

**PR 作为需求分诊入口：关闭 (no)。**（若后续希望将外部 PR 纳入分诊队列，可将此项修改为 `yes`）。
