# Gitleaks 阻断案例

> **日期**：2026-06-04
> **目的**：验证 pre-commit hook + CI gate 的有效性，提供面试可讲的具体案例

---

## 步骤

### 1. 创建含假密码的测试文件

```bash
echo 'AWS_SECRET_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE' > test-secret.txt
```

### 2. 提交触发阻断

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

## 面试话术

> "我故意提交了一个含假 AWS 密钥的文本文件来验证 Gitleaks 门禁。pre-commit hook 立刻检测到 1 个 leak 并阻断了提交。这证明了整个安全门禁链路的有效性——从开发者本地 pre-commit 到 GitHub Actions CI gate，双层防护确保密钥不会出现在 Git 历史里。如果 CI 里也配了 Gitleaks，PR 合并前会再做一次扫描，防止有人跳过 pre-commit hook。"

---

## 截图

（截图存储于 `docs/assets/gitleaks/`）
