# S3 CI/CD + ACK 云迁移 — 实施完成报告

> **用途**：供 OpenCode 多模型独立审计。逐项核验交付物、验证结果、踩坑记录。
> **日期**：2026-06-04 | **分支**：`feature/ci-pipeline`（待 PR → main）

---

## 一、S3 CI/CD 交付清单

### Layer 1-2：Gitleaks + Semgrep

| 文件 | 类型 | 验证 |
|------|:---:|:---:|
| `.gitleaks.toml` | NEW | GitHub Actions job 通过 ✅ |
| `.pre-commit-config.yaml` | NEW | gitleaks v8.30.1 hook |
| `.semgrep.yml` | NEW | GitHub Actions job 通过 ✅ |

### Layer 3：GitHub Actions ci.yml 重写

| 改动 | 旧值 | 新值 | 验证 |
|------|------|------|:---:|
| JDK 版本 | 17 | 21 | test job 4/4 通过 |
| 源码路径 | `bank-digital-platform/` | `apps/` | test + build 通过 |
| K8s 路径 | `k8s/` | `infra/` | build context 正确 |
| Trivy | 无 | hard gate HIGH/CRITICAL | 6 CVE 检出 → Tomcat 11.0.22 fix → 0 CVE |
| Feishu | 无 | graceful skip | SECRET 未配置时优雅退出 |
| Gitleaks job | 无 | 第一步 gate | 通过 |
| Semgrep job | 无 | 第二步 gate | 通过 |

**最终运行**：10 jobs 全绿（gitleaks / semgrep / test×4 / build-and-scan×4 / notify）

### Layer 4-6：ci.sh + smoke-test + 阻断案例

| 文件 | 改动 | 验证 |
|------|------|:---:|
| `scripts/ci.sh` | 6-stage 重写（路径 apps/ + plain-http fix + ArgoCD GitOps + Trivy soft gate） | harbor01 全流程 ✅ |
| `scripts/smoke-test.sh` | idempotencyKey 必填字段 | harbor01 4/4 pass ✅ |
| `scripts/build-images.sh` | 路径 apps/ + version from git tag | 已同步 |
| `docs/29-gitleaks-blocking-case.md` | 阻断案例文档 | 已提交 |

### Q1-Q4 决策

| 决策 | 选择 | 实施 |
|------|:---:|------|
| Q1 Maven 父 POM | A | 4 服务 pom.xml → bank-mall-parent ✅ |
| Q2 ArgoCD vs ci.sh | A | ci.sh Git commit+push 触发 ArgoCD ✅ |
| Q3 状态机单测 | C | PaymentServiceTest 6→10 tests ✅ |
| Q4 项目 A 引用 | B | 删除，amd64 only ✅ |

---

## 二、ACK 云迁移体验

### 部署结果

```
ACK 托管版（华南3 广州）
├── 2 × ECS c6e.large (2C4G)
├── Flannel CNI
├── K8s 1.36.1-aliyun.1
├── 5 Pods Running ✅
│   ├── auth-service 1/1 ✅
│   ├── account-service 1/1 ✅（ddl-auto=update 建表后）
│   ├── payment-service 1/1 ✅
│   ├── notification-service 1/1 ✅
│   └── mysql 1/1 ✅（ACR 镜像）
└── 验证：port-forward curl auth health → SUCCESS ✅
```

### 交付物

| 文件 | 说明 |
|------|------|
| `infra/kubernetes/base/kustomization.yaml` | 基础资源清单 |
| `infra/kubernetes/cloud/kustomization.yaml` | ACR 镜像映射 + patches |
| `infra/kubernetes/cloud/patches/remove-nodename.yaml` | 删 MySQL nodeName |
| `infra/kubernetes/cloud/patches/remove-otel.yaml` | 删 OTEL initContainer |
| `infra/kubernetes/cloud/patches/ingress-lb.yaml` | NodePort→LoadBalancer |
| `docs/execution-record.md` | ACK 踩坑记录 |

### 7 个踩坑

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 1 | Kustomize base 目录无 kustomization.yaml | overlay 引用的 base 需要一份 | 新建 base/kustomization.yaml |
| 2 | ACR 镜像 not found | 推送叫 `*-service`，Kustomize 映射没有 | 加 `-service` 后缀 |
| 3 | ACK 无 ACR 拉取凭证 | 节点没有 imagePullSecret | `docker-registry` secret + patch serviceaccount |
| 4 | mysql:8.0 Docker Hub 被墙 | GFW 阻断 registry-1.docker.io | harbor01 拉 + tag + push ACR |
| 5 | JAVA_TOOL_OPTIONS 残留 | cloud overlay 删了 initContainer 但 env 还在 | cloud overlay inline patch 设空值；base 保持正确 javaagent 路径 |
| 6 | MySQL hostPath PVC 不兼容 | ACK 无 VMware hostPath | hotfix：PVC→emptyDir |
| 7 | ingress controller registry.k8s.io 被墙 | GFW 阻断 `registry.k8s.io` | **待解决**：port-forward 验证了 5 服务健康，但公网 SLB 不通。ACK 应使用托管版 Ingress Controller（自带 SLB），而非自部署 nginx-ingress |

### ACK 临时改动的 GitOps 处理

| 改动 | 方式 | 位置 |
|------|------|------|
| JAVA_TOOL_OPTIONS 云上空值 | Kustomize inline strategic merge patch | `cloud/kustomization.yaml` |
| MySQL hostPath→emptyDir | Kustomize JSON patch | `cloud/patches/mysql-emptydir.yaml` |
| OTEL initContainer 移除 | Kustomize JSON patch | `cloud/patches/remove-otel.yaml` |

> 所有云上差异均在 `infra/kubernetes/cloud/` overlay 中管理，base 清单保持纯净。

---

## 三、审计检查清单

### 文件完整性

```
新增文件（36+）：
├── .gitleaks.toml
├── .pre-commit-config.yaml
├── .semgrep.yml
├── apps/pom.xml（Maven 父 POM）
├── apps/*/metrics/*.java（4 个 Metrics 类）
├── docs/29-gitleaks-blocking-case.md
├── docs/polish-list.md
├── docs/S2-independent-audit-report.md
├── docs/execution-record.md（追加 S2 + ACK）
├── infra/dashboards/bank-mall-business.json
├── infra/dashboards/bank-mall-sli-slo.json
├── infra/kubernetes/base/argocd/
├── infra/kubernetes/base/jaeger/
├── infra/kubernetes/base/sealed-bank-mall.yaml
├── infra/kubernetes/base/security/{pdb,limit-range,resource-quota,allow-jaeger-*,allow-ingress-to-jaeger}.yaml
├── infra/kubernetes/base/kustomization.yaml
├── infra/kubernetes/cloud/
├── .opencode/plans/{ALL-QUESTIONS,S3-cicd-implementation-plan,cloud-migration-plan}.md
└── scripts/（ci.sh, smoke-test.sh, build-images.sh 重写）

删除文件：
├── infra/kubernetes/base/secret.yaml（→ sealed-bank-mall.yaml）
├── 4 个旧 feat/* 分支（s0-cluster-verification, s0-platform-scaffolding, notification-service, s2-platform-matrix）
└── %TEMP%s2-delta.tar.gz（误提交，已清理）
```

### 验证状态

| 验证项 | 结果 |
|--------|:---:|
| JDK 21 编译 | ✅ harbor01 |
| Maven 父 POM 继承 | ✅ Docker 容器内解析 |
| GitHub Actions ci.yml（10 jobs） | ✅ 全绿 |
| ci.sh 6-stage（harbor01） | ✅ 全流程 |
| smoke-test（harbor01） | ✅ 4/4 pass |
| PaymentServiceTest（10 tests） | ✅ GitHub + harbor01 |
| ACK 5 服务 Running | ✅ port-forward curl health SUCCESS |
| Ingress 公网访问 | ❌ 待解决：nginx-ingress 镜像被 GFW 阻断，ACK 托管版 Ingress 替代 |
| cloud overlay 不污染 base | ✅ JAVA_TOOL_OPTIONS 通过 inline patch、MySQL PVC 通过 mysql-emptydir patch |
| Q5 JDK 21 编译 | ✅ harbor01 `mvn clean compile test` |
| Q6 ghcr.nju.edu.cn 可达性 | ✅ HTTP 200, registry/2.0 API（Trivy DB 下载成功即验证） |
| Q7 Maven Central 内网可达 | ✅ Q5 编译通过间接验证（aliyun 镜像可下载依赖） |
| Q8 Gitleaks pre-commit 兼容 | ✅ harbor01 + master01 均 8.30.1 |
| Q9 飞书 webhook 内网可达 | ⚠️ `open.feishu.cn` 域名可达（Tengine 响应），实际 webhook 未配置 SECRET |

### 待 S4/S5 处理

| # | 项 | 文件 | 阶段 |
|---|------|------|:---:|
| 1 | HEALTHCHECK `A1001/health` → `/actuator/health` | account-service Dockerfile | S5 |
| 2 | egress port 16686 精简 | allow-services-egress.yaml | S5 |
| 3 | Mockito JDK 21 `-XX:+EnableDynamicAgentLoading` | 4 个服务 pom.xml | S5 |
| 4 | CI/CD 面试 Q&A | docs/interview/ | S5 |
| 5 | 岗位话术区分 | docs/interview/ | S5 |
| 6 | docs/26 更新 | 文档 | S5 |
| 7 | docs/30-ci-cd-pipeline.md | 设计文档 | S5 |
| 8 | Trivy 漏洞数据库内网不可达 | — | S5 |

---

## 四、分支状态

```
main ←（待 PR）feature/ci-pipeline
```

- `feature/ci-pipeline` 有 S3+ACK 全部提交
- main 受 GitHub 保护，需 PR 合并
- 4 个旧 feat/* 分支已删除（本地+远程）

---

**创建时间**：2026-06-04 20:45 CST
**状态**：S3+ACK 实施完成
