# S3：双平台 CI/CD 实施计划（修订版）

> **重要原则**：如有疑惑，先要询问你详细的答复/选择，再确认后才实施。不会假设你的意图。
> 
> **计划时间**：29h（预估实际 ~20h）
> **创建日期**：2026-06-04

---

  ┌──────────────────────────────────────┬────────┬───────────┬───────────────────────────────────────┐
  │              polish 项               │ 严重度 │ 阻塞 S2？ │                 理由                  │
  ├──────────────────────────────────────┼────────┼───────────┼───────────────────────────────────────┤
  │ docs/26 更新                         │  INFO  │    否     │ 文档更新，不影响功能                  │
  ├──────────────────────────────────────┼────────┼───────────┼───────────────────────────────────────┤
  │ HEALTHCHECK A1001 → /actuator/health │  LOW   │    否     │ 当前也能用，只是不优雅                │
  ├──────────────────────────────────────┼────────┼───────────┼───────────────────────────────────────┤
  │ egress port 16686                    │  LOW   │    否     │ 多放一个端口不是安全漏洞，S2 功能全通 │
  └──────────────────────────────────────┴────────┴───────────┴───────────────────────────────────────┘

  预留 polish 清单

  - docs/26-final-verification-checklist.md 更新 Jaeger/ArgoCD/Sealed Secrets 从「未落地」→「已落地」
  - account-service/Dockerfile HEALTHCHECK 路径 A1001/health → /actuator/health
  - NetworkPolicy allow-jaeger-egress.yaml（bank-mall 侧）port 16686 可精简（服务不需要连 Jaeger UI）

  S2 的核心标准是“必须完美”——指面试能被追问的部分：ArgoCD、Jaeger+OTEL、Grafana+Alerting、Sealed Secrets。这 3 个细节面试官挖不到，S3 顺手改就行。

## 前置验证清单（执行前必须确认）

### 🔴 必须确认的问题

**Q1: Maven 父 POM 策略**

- 现状：`apps/pom.xml` 存在，但 4 个服务的 `pom.xml` 仍以 `spring-boot-starter-parent` 为 parent
- 选项 A：修改 4 个服务 pom.xml，改用 `bank-mall-parent`（统一依赖管理）
- 选项 B：保持现状，父 POM 仅用于版本声明，不用于构建
- **你的选择？**

**Q2: ArgoCD 与 ci.sh 冲突**
- 现状：ArgoCD 已部署，会监听 Git 并自动同步。ci.sh 用 `kubectl set image` 修改集群状态会被 ArgoCD selfHeal 覆盖
- 选项 A：ci.sh 改为 Git commit + push，触发 ArgoCD 同步（纯 GitOps）
- 选项 B：ci.sh 部署时临时禁用 ArgoCD selfHeal（`argocd app set --sync-policy none`）
- 选项 C：ci.sh 仅用于本地验证，生产部署走 ArgoCD
- **你的选择？**

**Q3: "状态机单测 10+ 用例"定义**
- 现状：计划提到"JUnit 5 状态机单测 10+ 用例"，但未明确定义
- 选项 A：Payment 状态转换测试（PENDING → COMPLETED / FAILED / ERROR_MANUAL_REVIEW）
- 选项 B：引入 Spring Statemachine 依赖，实现完整状态机
- 选项 C：仅补充现有 PaymentServiceTest 的边界用例（凑够 10+）
- **你的选择？**

**Q4: "项目 A"参考**
- 现状：计划提到"多架构构建只在 GitHub Actions 做（项目 A 已有配置可迁移）"
- 选项 A：项目 A 是另一个项目，提供链接/路径
- 选项 B：删除这句话，S3 仅做 amd64 构建
- **你的选择？**

---

## 第 1 层：Gitleaks 密钥检测（3h）

### 1.1 配置文件

**`.gitleaks.toml`**（新建）：
```toml
title = "Bank Mall Gitleaks Config"

[extend]
useDefault = true

[allowlist]
description = "Global allowlist"
paths = [
  '''docs/.*''',
  '''\.gitleaks\.toml''',
  '''sealed-.*\.yaml''',
]

[[rules]]
id = "generic-api-key"
description = "Generic API Key"
regex = '''(?i)(api[_-]?key|apikey)\s*[:=]\s*['"]?([a-z0-9]{32,})['"]?'''
tags = ["key", "API"]
```

**`.pre-commit-config.yaml`**（新建）：
```yaml
repos:
  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.18.0
    hooks:
      - id: gitleaks
```

### 1.2 GitHub Actions 集成

在 `.github/workflows/ci.yml` 中添加 Gitleaks job（第一步）：

```yaml
jobs:
  gitleaks:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
      with:
        fetch-depth: 0
    - name: Gitleaks
      uses: gitleaks/gitleaks-action@v2
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        # 社区版免费，无需 GITLEAKS_LICENSE
```

**注意**：Gitleaks v8+ 社区版免费，使用默认规则集。如需企业规则，需购买许可证。

### 1.3 阻断案例文档

**`docs/29-gitleaks-blocking-case.md`**（新建）：
- 故意提交含假密码文件（如 `AWS_SECRET_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE`）
- Gitleaks pre-commit hook 报 1 leak → 阻断
- `git reset` 撤销
- 修复后重新提交 → CI 通过
- 截图存储位置：**`docs/assets/gitleaks/`**（需创建目录，且 `.gitignore` 不排除此目录）

---

## 第 2 层：Semgrep SAST（2h）

### 2.1 配置文件

**`.semgrep.yml`**（新建）：
```yaml
rules:
  - p/java-lang-security
  - p/generic-secrets
  - p/java-spring-security
```

### 2.2 GitHub Actions 集成

在 ci.yml 中添加 Semgrep job（Gitleaks 后、test 前）：

```yaml
  semgrep:
    needs: gitleaks
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - uses: semgrep/semgrep-action@v1  # ← 修正：使用 semgrep/ 而非 returntocorp/
      with:
        config: >-
          p/java-lang-security
          p/generic-secrets
          p/java-spring-security
```

**注意**：Returntocorp 已更名为 Semgrep Inc.，action 名称为 `semgrep/semgrep-action@v1`。

---

## 第 3 层：GitHub Actions 完整流水线（5h）

### 3.1 修正后的 ci.yml

**关键改动**：

| 项目 | 旧值 | 新值 |
|------|------|------|
| `paths` 触发器 | `bank-digital-platform/**`, `k8s/**` | `apps/**`, `infra/**`, `scripts/**` |
| JDK 版本 | `java-version: '17'` | `java-version: '21'` |
| Maven 路径 | `cd bank-digital-platform/${{ matrix.service }}` | `cd apps/${{ matrix.service }}` |
| Docker build context | `bank-digital-platform/${{ matrix.service }}` | `apps/${{ matrix.service }}` |
| 镜像 registry | `10.0.0.61`（内网 Harbor） | `ghcr.nju.edu.cn/${{ github.repository }}`（南大镜像） |

**完整 ci.yml 结构**：

```yaml
name: Bank Mall CI/CD

on:
  push:
    branches: [main]
    paths:
      - 'apps/**'      # ← 修正
      - 'infra/**'     # ← 修正
      - 'scripts/**'
  pull_request:
    branches: [main]

env:
  REGISTRY: ghcr.nju.edu.cn  # ← 修正：南大镜像（ghcr.io 被墙）
  IMAGE_NAME: ${{ github.repository }}
  VERSION: ${{ github.sha }}

jobs:
  # Job 1: Secret detection
  gitleaks:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
      with: { fetch-depth: 0 }
    - uses: gitleaks/gitleaks-action@v2
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

  # Job 2: SAST
  semgrep:
    needs: gitleaks
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - uses: semgrep/semgrep-action@v1
      with:
        config: >-
          p/java-lang-security
          p/generic-secrets
          p/java-spring-security

  # Job 3: Test (parallel matrix)
  test:
    needs: gitleaks
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [auth-service, account-service, payment-service, notification-service]
    steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: '21'  # ← 修正
        distribution: 'temurin'
        cache: 'maven'
    - run: cd apps/${{ matrix.service }} && mvn test -q  # ← 修正

  # Job 4: Build + Trivy scan (main branch only)
  build-and-scan:
    needs: [test, semgrep]
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    strategy:
      matrix:
        service: [auth-service, account-service, payment-service, notification-service]
    steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: '21'
        distribution: 'temurin'
        cache: 'maven'
    - run: cd apps/${{ matrix.service }} && mvn package -DskipTests -q
    - uses: docker/setup-buildx-action@v3
    - uses: docker/build-push-action@v5
      with:
        context: apps/${{ matrix.service }}  # ← 修正
        push: false
        tags: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/${{ matrix.service }}:${{ env.VERSION }}
        load: true
    - uses: aquasecurity/trivy-action@master
      with:
        image-ref: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}/${{ matrix.service }}:${{ env.VERSION }}
        severity: 'HIGH,CRITICAL'
        exit-code: 1  # hard gate
        format: 'table'

  # Job 5: Feishu notification
  notify:
    needs: [build-and-scan]
    if: always()
    runs-on: ubuntu-latest
    steps:
    - name: Feishu webhook
      run: |
        STATUS="${{ needs.build-and-scan.result }}"
        curl -X POST "${{ secrets.FEISHU_WEBHOOK }}" \
          -H "Content-Type: application/json" \
          -d "{\"msg_type\":\"text\",\"content\":{\"text\":\"Bank Mall CI/CD: ${STATUS}\"}}"
```

---

## 第 4 层：内网 ci.sh 重写（4h）

### 4.1 路径映射表

| 旧路径 | 新路径 | 说明 |
|--------|--------|------|
| `bank-digital-platform/` | `apps/` | 源码目录 |
| `k8s/base/` | `infra/kubernetes/base/` | K8s 清单目录 |
| `k8s/base/secret.yaml` | `infra/kubernetes/base/sealed-bank-mall.yaml` | SealedSecret 替代明文 Secret |
| `k8s/base/monitoring/` | `infra/kubernetes/base/monitoring/` | 监控清单 |

### 4.2 新 ci.sh 流程

```bash
#!/usr/bin/env bash
set -euo pipefail

# ========== 配置 ==========
REGISTRY="${REGISTRY:-10.0.0.61}"
NAMESPACE="${NAMESPACE:-bank-mall}"
VERSION="${VERSION:-$(git describe --tags --always 2>/dev/null || echo '1.0.0')}"  # ← 版本优先级：git tag > 默认

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPS_DIR="${ROOT_DIR}/apps"                    # ← 修正
K8S_BASE="${ROOT_DIR}/infra/kubernetes/base"   # ← 修正

SERVICES=(auth-service account-service payment-service notification-service)

# ========== Stage 1/6: Maven Test (可选) ==========
if [[ "${RUN_TESTS:-false}" == "true" ]]; then
  for service in "${SERVICES[@]}"; do
    cd "${APPS_DIR}/${service}"
    mvn test -q
  done
fi

# ========== Stage 2/6: Maven Package ==========
for service in "${SERVICES[@]}"; do
  cd "${APPS_DIR}/${service}"
  mvn clean package -DskipTests -q
done

# ========== Stage 3/6: Docker Build + Push Harbor ==========
for service in "${SERVICES[@]}"; do
  image="${REGISTRY}/${NAMESPACE}/${service}:${VERSION}"
  docker build -t "${image}" "${APPS_DIR}/${service}"
  docker push "${image}"
done

# ========== Stage 4/6: Trivy Scan (soft gate) ==========
for service in "${SERVICES[@]}"; do
  image="${REGISTRY}/${NAMESPACE}/${service}:${VERSION}"
  trivy image --severity HIGH,CRITICAL --exit-code 0 "${image}"  # ← soft gate：记录不阻断
done

# ========== Stage 5/6: K8s Deploy ==========
# 方式取决于 Q2 的回答（ArgoCD 冲突解决策略）
# 选项 A：Git commit + push，触发 ArgoCD
# 选项 B：临时禁用 ArgoCD selfHeal，然后 kubectl set image
# 选项 C：仅本地验证，不部署

# ========== Stage 6/6: Verify + Feishu ==========
bash "${ROOT_DIR}/scripts/smoke-test.sh"

# 飞书通知（需验证内网可达性）
if curl -s --max-time 5 https://open.feishu.cn >/dev/null 2>&1; then
  curl -X POST "${FEISHU_WEBHOOK}" \
    -H "Content-Type: application/json" \
    -d "{\"msg_type\":\"text\",\"content\":{\"text\":\"Bank Mall 内网 CI/CD: SUCCESS\"}}"
else
  echo "[WARN] Feishu webhook unreachable from internal network"
fi
```

---

## 第 5 层：smoke-test.sh 修正（0.5h）

### 5.1 Payment payload 更新

**旧 payload**：
```json
{"orderId":"ORDER-SMOKE-001","payerAccount":"A1001","amount":299.00,"currency":"CNY"}
```

**新 payload**（加 idempotencyKey）：
```json
{"orderId":"ORDER-SMOKE-001","payerAccount":"A1001","amount":299.00,"currency":"CNY","idempotencyKey":"SMOKE-$(date +%s)"}
```

---

## 第 6 层：Gitleaks 阻断案例 + 文档（6h）

### 6.1 执行步骤

1. 创建测试文件 `test-secret.txt`：
   ```
   AWS_SECRET_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE
   ```
2. `git add test-secret.txt` → Gitleaks pre-commit hook 报 1 leak → 阻断
3. `git reset` 撤销
4. 删除 `test-secret.txt`
5. 截图存储到 `docs/assets/gitleaks/`
6. 写入 `docs/29-gitleaks-blocking-case.md`

### 6.2 `.gitignore` 更新

```gitignore
# 排除截图目录（但保留 gitleaks 案例截图）
assets/screenshots/
!docs/assets/gitleaks/
```

---

## 第 7 层：CI 集成验证（5h）

### 7.1 前置验证

```bash
# 验证飞书内网可达性
curl -s --max-time 5 https://open.feishu.cn && echo "OK" || echo "FAIL"

# 验证 settings.xml 存在
ls -la apps/*/settings.xml
```

### 7.2 端到端验证

```bash
# 1. 代码变更触发
cd apps/payment-service
echo "// version bump" >> src/main/java/com/bank/payment/PaymentApplication.java
git commit -am "feat: bump version to 2.1.0"
git push origin feat/s3-cicd

# 2. GitHub Actions 自动跑 Gitleaks → Semgrep → mvn test → Trivy

# 3. 内网 VM 上拉取最新代码
git checkout main && git pull

# 4. 运行 ci.sh
VERSION=2.1.0 bash scripts/ci.sh

# 5. 验证
kubectl get pods -n bank-mall
bash scripts/smoke-test.sh
```

---

## 文件清单

| # | 文件 | 类型 | 说明 |
|---|------|------|------|
| 1 | `.gitleaks.toml` | NEW | Gitleaks 配置 |
| 2 | `.pre-commit-config.yaml` | NEW | pre-commit hook |
| 3 | `.semgrep.yml` | NEW | Semgrep 规则 |
| 4 | `.github/workflows/ci.yml` | UPDATE | JDK21 + Gitleaks + Semgrep + Trivy + 飞书 |
| 5 | `scripts/ci.sh` | UPDATE | 全面重写（路径修正 + Trivy + 飞书） |
| 6 | `scripts/build-images.sh` | UPDATE | 适配 apps/ 目录 |
| 7 | `scripts/smoke-test.sh` | UPDATE | payment payload 加 idempotencyKey |
| 8 | `docs/29-gitleaks-blocking-case.md` | NEW | 阻断案例文档 |
| 9 | `docs/30-ci-cd-pipeline.md` | NEW | CI/CD 流水线文档 |
| 10 | `docs/assets/gitleaks/` | NEW | 截图目录 |
| 11 | `.gitignore` | UPDATE | 排除截图目录（但保留 gitleaks 案例） |

---

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| Gitleaks 社区版规则较少 | 使用默认规则集，如需企业规则需购买许可证 |
| Semgrep 免费规则有限 | 使用 `p/java-lang-security` 等免费规则集 |
| Trivy 扫描基础镜像慢 | 首次慢，之后有缓存；或使用 `--skip-db-update` |
| 飞书 webhook 内网不可达 | 前置验证，如不可达则仅在 GitHub Actions 层做通知 |
| `ghcr.io` 被墙 | 使用南大镜像 `ghcr.nju.edu.cn` |
| ArgoCD 与 ci.sh 冲突 | 取决于 Q2 的回答 |

---

## 待确认问题汇总

**请在实施前回答以下问题**：

1. **Q1: Maven 父 POM 策略** — 选项 A（修改 4 个服务 pom.xml）还是选项 B（保持现状）？
2. **Q2: ArgoCD 与 ci.sh 冲突** — 选项 A（纯 GitOps）、选项 B（临时禁用 selfHeal）、还是选项 C（仅本地验证）？
3. **Q3: "状态机单测 10+ 用例"定义** — 选项 A（Payment 状态转换）、选项 B（Spring Statemachine）、还是选项 C（补充边界用例）？
4. **Q4: "项目 A"参考** — 选项 A（提供链接）还是选项 B（删除这句话）？

**如有其他疑惑，也请先询问我详细的答复/选择，再确认后才实施。**

---

## 面试话术（完整版）

> "CI/CD 做了分层设计。公网 GitHub Actions 管代码质量门禁——Gitleaks 密钥检测、Semgrep SAST、JUnit 单元测试、Trivy 镜像漏洞扫描。这些在代码合并前必须通过，HIGH 和 CRITICAL 漏洞是硬门禁。内网 Harbor 节点跑 scripts/ci.sh 做实际交付——Maven 打包、Docker 构建、推 Harbor、Trivy 软门禁（记录不阻断）、kubectl 滚动更新、飞书通知。两层各有分工，和很多企业的 SaaS CI + 内网 Runner 架构一致。"

---

**创建时间**：2026-06-04  
**最后更新**：2026-06-04  
**状态**：待确认 Q1-Q4 后实施
