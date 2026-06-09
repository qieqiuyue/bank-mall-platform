# Contributing

## 日常开发流程

```bash
# 1. 从 main 切分支
git checkout main && git pull
git checkout -b feat/s0-xxx

# 2. 开发、提交
git add .
git commit -m "[FEAT] xxx: 描述"

# 3. 推送到 GitHub
git push origin feat/s0-xxx

# 4. 打开 GitHub → Pull requests → New pull request
#    选 feat/s0-xxx → main → Create pull request

# 5. 自己 review → Merge → 回到步骤 1
```

> 禁止直接 `git push origin main`。所有改动必须通过 PR 合并。

## 提交约定

本项目使用前缀式提交消息：

| 前缀 | 用途 | 前缀 | 用途 |
|------|------|------|------|
| `[INIT]` | 项目初始化 | `[OBS]` | 可观测性 |
| `[MIGRATE]` | 代码迁移 | `[TRACE]` | 链路追踪 |
| `[VERIFY]` | 前置验证 | `[SEC]` | 安全加固 |
| `[FEAT]` | 新功能 | `[DEPLOY]` | 部署 |
| `[FIX]` | Bug 修复 | `[CI]` | CI/CD |
| `[TEST]` | 测试 | `[CHAOS]` | 故障演练 |
| `[API]` | API 文档 | `[PERF]` | 性能测试 |
| `[DOC]` | 文档 | | |

格式：`[PREFIX] scope: description`

示例：
```
[FEAT] account-service: JPA entities + Flyway migrations
[FIX] auth-service: BCrypt password encoding
[SEC] NetworkPolicy: deny-all + whitelist rules
```

## 分支策略

**GitHub Flow**。分支命名：

```
feat/s0-platform-scaffolding
feat/s1-auth-jwt
feat/s1-account-service
chaos/s4-oom-killed
fix/s2-grafana-dashboard
docs/s5-interview-prep
```

合并策略：**Squash merge** 到 `main`。

## PR 流程

1. 创建 PR 到 `main`
2. 标题写清楚做了什么
3. 自己 Approve（单人项目）
4. Squash merge
5. 删除远程分支

## 代码风格

### Java
- Java 21，Spring Boot 4.0.6
- HTTP 客户端：RestClient（禁止 RestTemplate/WebClient 同步调用）
- 测试：JUnit 5
- 数据库迁移：Flyway
- 响应格式：统一 `ApiResponse<T>`（code/message/data/timestamp）
- **RestClient 必须配置超时**：connect 2s / read 5s，禁止无超时的 `RestClient.builder().build()`
- **DataInitializer 必须加 `@Profile("dev")`**：生产环境不灌入 demo 数据
- **所有 Controller 的 `@RequestBody` 参数必须加 `@Valid`**
- **所有 DTO 必须加 Bean Validation 注解**：`@NotBlank`/`@NotNull`/`@Positive`
- **`ddl-auto` 只能用 `validate` 或 `none`**：禁止 `update`/`create-drop` 在生产 profile 下

### YAML / Kubernetes
- 2 空格缩进
- 按服务分目录：`k8s/base/{service}/`
- **禁止提交明文 Secret**——用 `secret.yaml.example` 占位

### Shell
- 用 `bash`，加 `set -euo pipefail`

## 文档命名规范

- 构建/部署文档：`docs/[数字前缀]-[描述].md`（例：`15-ingress-deployment.md`）
- 故障复盘文档：`docs/s4-postmortem-[序号]-[描述].md`（例：`s4-postmortem-02-networkpolicy.md`）
- 设计文档：`docs/[域]-[描述].md`（例：`redis-idempotency-design.md`、`ha-architecture-design.md`）
- 总结/汇总文档：`docs/[主题]-summary.md`（例：`chaos-postmortem-summary.md`）
- 执行阶段文档：`docs/execution-[内容].md`（例：`execution-plan.md`、`execution-record.md`）
- **禁止**：`my-*`、`test-*`、`tmp-*`、无意义缩写、纯日期命名

## 安全

- 禁止提交 `secret.yaml`、`.env`、`*.pem`、`*.key`
- 提交前跑 Gitleaks
- 发现密钥泄露 → 立即轮换 + 清除 git 历史

## 代码审查红线（审计驱动）

以下规则源自两轮深度审计中发现的真实问题，违反即 CI 阻断或 Review 驳回：

| # | 规则 | 检查方式 |
|---|------|---------|
| 1 | RestClient 必须配置 connect/read 超时 | `grep -rn "RestClient.builder()" apps/*/src/main/java` — 无 `requestFactory` 即为违规 |
| 2 | DataInitializer 必须加 `@Profile("dev")` | `grep -L "@Profile" apps/*/src/main/java/**/DataInitializer.java` |
| 3 | 所有 `@RequestBody` 参数加 `@Valid` | `grep -rn "public.*@RequestBody" apps/*/src/main/java \| grep -v "@Valid"` |
| 4 | `ddl-auto` 只能用 `validate` 或 `none` | `grep -rn "ddl-auto: update\|ddl-auto: create" apps/*/src/main/resources/` |
| 5 | Docker HEALTHCHECK 与 K8s livenessProbe 路径一致 | 对比 Dockerfile 和 deployment.yaml 的 health 路径 |
| 6 | 脚本中 MySQL 密码不走命令行参数 | `grep -rn "\-p\"" scripts/*.sh` — 应使用 `env MYSQL_PWD=` |
| 7 | 日志中不能有 `password` / `secret` 等敏感字段 | Code Review 时人工检查 `log.info` / `log.debug` 参数 |
| 8 | 禁止在构造器中生成业务 ID（UUID） | JPA 反射构造时产生副作用，移至 `@PrePersist` |

### 准入红线自动化

```bash
# 提交前本地自检（约 5 秒）
make lint                                          # Semgrep + Gitleaks
grep -rn "ddl-auto: update" apps/*/src/main/       # 禁止 update
grep -L "@Profile" apps/*/src/main/java/**/DataInitializer.java  # 禁止无 Profile
grep -rn "RestClient.builder()" apps/*/src/main/java/ | grep -v requestFactory  # 必须有超时
```
