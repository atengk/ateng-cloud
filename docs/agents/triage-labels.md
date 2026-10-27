# 分诊标签 (Triage Labels)

工程技能遵循 5 个标准分诊角色。本文件将这些抽象角色映射为当前仓库 Issue 跟踪器中实际使用的标签字符串：

| 技能标准标签 | 本仓库实际标签 | 业务含义 |
| :--- | :--- | :--- |
| `needs-triage` | `needs-triage` | 待维护者评估与分诊 |
| `needs-info` | `needs-info` | 等待提报者补充更多上下文或复现信息 |
| `ready-for-agent` | `ready-for-agent` | 规格完全明确，可直接由自主 AI Agent 离线执行 |
| `ready-for-human` | `ready-for-human` | 涉及敏感授权或主观体验决策，必须由人类工程师实现 |
| `wontfix` | `wontfix` | 经评估不予处理/超出范围/不采纳 |

当技能指明特定角色（例如“打上 ready-for-agent 标签”）时，使用上表对应的标签字符串。
