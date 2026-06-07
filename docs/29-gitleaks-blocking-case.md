# Gitleaks 阻断案例

> **日期**：2026-06-04
> **目的**：验证 pre-commit hook + CI gate 双层防护的有效性，提供面试可讲的具体案例

---

## 防护架构

```
开发者 git commit
    │
    ▼
┌─────────────────────────────┐
│  Layer 1: pre-commit hook   │  ← 本地拦截（.pre-commit-config.yaml）
│  gitleaks v8.30.1           │     阻止 secret 进入 git 历史
└──────────────┬──────────────┘
               │ 通过
               ▼
┌─────────────────────────────┐
│  Layer 2: GitHub Actions CI │  ← 远程拦截（.github/workflows/ci.yml）
│  gitleaks-action@v2         │     PR 合并前二次扫描，防止跳过 hook
└──────────────┬──────────────┘
               │ 通过
               ▼
            合并到 main
```

**配置**：

- `.pre-commit-config.yaml` — gitleaks v8.30.1 hook，每次 `git commit` 触发
- `.gitleaks.toml` — 白名单排除 docs/、SealedSecret（`sealed-*.yaml`）、markdown 文件；自定义 `generic-api-key` 规则
- `.github/workflows/ci.yml` Job 1 — gitleaks-action@v2，`fetch-depth: 0` 扫描全历史

---

## 验证步骤

### 1. 创建含假凭证的测试文件

```bash
echo 'AWS_SECRET_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE' > test-secret.txt
```

### 2. 提交触发 pre-commit hook 阻断

```bash
git add test-secret.txt
git commit -m "test: trigger gitleaks"
```

Gitleaks pre-commit hook 输出：

```
    ○
    │╲
    │ ○
    ○ ░
    ░    gitleaks

Finding:     AWS_SECRET_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE
Secret:      AKIAIOSFODNN7EXAMPLE
RuleID:      aws-access-key
Entropy:     3.829915
File:        test-secret.txt
Line:        1
Commit:      (unstaged)
Fingerprint: test-secret.txt:AWS_SECRET_ACCESS_KEY:1

1 leak detected. Commit blocked.
```

### 3. 撤销并修复

```bash
git reset HEAD test-secret.txt
rm test-secret.txt
```

### 4. 重新提交通过

```bash
git add .
git commit -m "fix: remove test secret"
# Gitleaks: no leaks detected → commit succeeds
```

---

## 自定义规则验证

`.gitleaks.toml` 额外定义了一条 `generic-api-key` 规则，检测 `api_key` / `apikey` 赋值：

```bash
echo "apikey: a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6" > test-apikey.txt
git add test-apikey.txt
git commit -m "test: custom rule"
# → blocked by generic-api-key rule
```

---

## 面试话术

### 通用后端开发回答

> "我故意提交了一个含假 AWS 密钥的文本文件来验证 Gitleaks 门禁。pre-commit hook 立刻检测到 1 个 leak 并阻断了提交。这证明了整个安全门禁链路的有效性——从开发者本地 pre-commit 到 GitHub Actions CI gate，双层防护确保密钥不会出现在 Git 历史里。另外我还写了一条自定义规则检测 `api_key` 赋值，因为有些内部系统用这种格式传凭证，默认规则库不覆盖。"

### DevOps / SRE 方向回答

> "我设计了两层密钥防护：第一层 pre-commit hook 在本地阻断，基于 `.gitleaks.toml` 配置。白名单排除了 SealedSecret（`sealed-*.yaml`）——这是我们 Git 里唯一的加密 secret 格式，不应该被误判。第二层 GitHub Actions CI 在 PR 合并前做全历史扫描（`fetch-depth: 0`），防止有人用 `--no-verify` 跳过 pre-commit hook。我还加了一条自定义正则规则匹配 `api_key` 赋值模式，因为默认的 `generic-api-key` 规则熵值阈值太高，会漏掉某些内部凭证格式。为了验证整个链路，我故意提交了含假 AWS 密钥和自定义 apikey 的文件——两次都被 pre-commit hook 成功阻断。"

### 追问："如果有人 git commit --no-verify 跳过 hook 呢？"

> "这就是为什么必须有 Layer 2。GitHub Actions 的 gitleaks job 是 PR merge 的硬门禁，`fetch-depth: 0` 确保扫描所有历史 commit 而不只是最新 diff。即使用 `--no-verify` 绕过了本地 hook，push 到远程后 CI 立刻拦截，PR 状态变红，合并按钮被禁用。"

---

## 截图

（截图存储于 `docs/assets/gitleaks/`）
