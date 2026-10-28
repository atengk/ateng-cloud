# 贡献指南 (Contributing Guide)

感谢你关注并愿意为 `ateng-cloud` 贡献代码！作为面向现代化微服务架构（Spring Cloud 2023、Spring Cloud Alibaba 与 Apache Dubbo 3.3 双模体系）的基础设施 Starter 套件，我们欢迎并鼓励社区的各种形式贡献——无论是报告缺陷、完善文档还是提交新特性。

为保障代码库高质量交付与团队协同顺畅，请在提交前查阅本指南。

---

## 1. 行为准则 (Code of Conduct)

参与本项目的所有贡献者均须遵守友善、包容与互相尊重的基本原则。严禁任何形式的骚扰、人身攻击或歧视性言行。详见 [行为准则 (CODE_OF_CONDUCT.md)](./CODE_OF_CONDUCT.md)。

---

## 2. 研发准备与环境基线

- **开发工具**：IntelliJ IDEA 或 VS Code
- **运行基线**：JDK 17 LTS（字节码 17，兼容 JDK 21+ 运行）
- **核心基线**：Spring Boot `3.3.13`、Spring Cloud `2023.0.x`、Spring Cloud Alibaba `2023.0.x`、Apache Dubbo `3.3.x`、ateng-boot-bom `1.1.0`
- **工程构建**：Apache Maven 3.6.3+

在本地克隆代码后，执行以下命令验证构建环境并激活本地提交守门钩子：

```bash
git clone https://github.com/atengk/ateng-cloud.git
cd ateng-cloud

# 激活本地 Git 提交守门钩子 (毫秒级拦截非标提交)
git config core.hooksPath .githooks

# 执行全工程测试验证构建环境
mvn clean test
```

---

## 3. 分支与工作流管理

本项目严格遵循 `main`（只读发布）与 `dev`（活跃开发）双分支演进模型：

- **主干分支 (`main`)**：受保护分支，**唯一合规发版来源**，仅接受通过 CI 门禁检查的 Pull Request 合并；
- **开发分支 (`dev`)**：日常特性合并与集成测试分支，所有日常编码工作均在此分支开展；
- **特性分支 (`feat/*`)**：基于 `dev` 拉出，用于新功能研发；
- **修复分支 (`fix/*`)**：基于 `dev` 拉出，用于 Bug 修复；
- **主干发布铁律 (Trunk-Based Release)**：严格禁止在任何特性分支或临时分支发版！CI 流水线针对网页调度与 Tag 推送实施双重祖先谱系校验（`git merge-base --is-ancestor`）确保发布产物 100% 源自主干；
- **精准路径暂存 (Safe Path Staging)**：暂存时必须显式指定具体修改文件的相对路径（如 `git add pom.xml scripts/commit.sh`），严禁使用 `git add .` 或 `git add -A` 通配，从源头杜绝未跟踪的临时文件或敏感配置误入暂存区。

```bash
# 从最新 dev 创建特性分支
git checkout dev
git pull origin dev
git checkout -b feat/your-feature-name
```

---

## 4. 提交信息规范 (Conventional Commits)

本项目强制遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/) 规范，CI 流水线将对 PR 标题与提交进行自动化拦截校验：

```text
<type>(<scope>): <清晰中文描述>
```

- **推荐提交方式**：直接运行项目门面脚本 `bash scripts/commit.sh`，享受自动化格式校验与 Issue 关联。

---

## 5. 质量门禁与审查红线

提交代码前，请确保通过以下门禁：
1. **7 大微服务专属架构铁律**（网关物理隔离 Servlet、全链路四维上下文透明穿透、Feign 读超时禁重试、Same-Token 零信任拦截、Nacos 现代加载及 `@RefreshScope` 禁令、S3 预签名直传旁路、100% 离线自洽单测）；
2. **九道工业级成熟度防线**（详见 [AGENTS.md](./AGENTS.md)）；
3. **Sample-First 研发闭环**：新功能必须在 `ateng-cloud-samples` 中提供端到端真实可运行用例，子模块 `README.md` 代码 100% 从 Sample 逆向提取。
