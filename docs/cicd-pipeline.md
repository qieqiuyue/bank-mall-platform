# CI/CD Pipeline

> **双通路**：公网 GitHub Actions（代码质量门） + 内网 `scripts/ci.sh`（Harbor 构建交付）

## 公网通路：GitHub Actions

`.github/workflows/ci.yml` — 触发条件：push/PR to `main`, paths: `apps/**`, `infra/**`, `scripts/**`

```
Job 1: gitleaks (强制阻断)
  └── git checkout (fetch-depth: 0)
  └── gitleaks-action@v2

Job 2: semgrep (并行)
  └── semgrep-action@v1
  └── config: p/java-lang-security, p/generic-secrets, p/java-spring-security

Job 3: test (并行, matrix × 4 服务)
  └── setup-java@v4 (temurin 21)
  └── mvn test -q

Job 4: build-and-scan (main only, hard gate)
  └── mvn package -DskipTests
  └── docker build (context: apps/)
  └── trivy-action@master (HIGH/CRITICAL → exit-code 1)

Job 5: notify (main only)
  └── Feishu webhook
```

## 内网通路：scripts/ci.sh

harbor01 上执行 `make ci` 或 `bash scripts/ci.sh`

```
Stage 1: Maven Test (可选, RUN_TESTS=true)
Stage 2: Maven Package (4 服务编译)
Stage 3: Docker Build + Push Harbor (4 镜像, 10.0.0.61)
Stage 4: Trivy Scan (soft gate)
  └── DB: NJU mirror (ghcr.nju.edu.cn) — 12s 拉取, 无 GFW 阻塞
  └── 扫描: docker save → trivy --input (snap sandbox 兼容)
Stage 5: Deploy (kubectl 检测 → 无连接则跳过)
Stage 6: Verify (smoke-test + Feishu)
```

**端到端时间**：211 秒（2026-06-07 实测）

## Trivy 硬门 vs 软门

| 通路 | 门禁类型 | 行为 |
|------|---------|------|
| GitHub Actions | 硬门（Hard Gate） | HIGH/CRITICAL → `exit-code 1` → Job 失败 → PR 不可合并 |
| `scripts/ci.sh` | 软门（Soft Gate） | HIGH/CRITICAL → `exit-code 0` → 记录结果 → 不阻塞部署 |

## GFW 对策

- GitHub Actions：走 GitHub-hosted runner（在公网，无 GFW 限制）
- `scripts/ci.sh` Trivy DB：NJU 镜像 `ghcr.nju.edu.cn/aquasecurity/trivy-db`（12 秒拉取）
- 飞书通知：内网 `scripts/ci.sh` 有飞书 webhook，GitHub Actions 有 `FEISHU_WEBHOOK` secret
