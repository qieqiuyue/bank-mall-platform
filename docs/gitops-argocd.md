# GitOps with ArgoCD

> **原则**：**一切变更走 Git，kubectl apply 只在 disaster recovery 场景使用。**
> ArgoCD 自动 sync + prune + self-heal，手动 apply 会被回滚。

## ArgoCD Application CRs

`infra/kubernetes/argocd/` 中定义 3 个 Application：

| Application | Source Path | Namespace | 组件 |
|-------------|------------|-----------|------|
| `bank-mall` | `infra/kubernetes/base/` | bank-mall | 4 服务 + MySQL + Ingress + HPA + Security |
| `monitoring` | `infra/kubernetes/base/monitoring/` | monitoring | Prometheus + Grafana + Loki + Promtail |
| `ingress-nginx` | `infra/kubernetes/base/ingress/` | ingress-nginx | Ingress Controller |

## 同步策略

```yaml
syncPolicy:
  automated:
    prune: true       # 自动删除 Git 中移除的资源
    selfHeal: true    # 自动修复漂移（手动 apply 的变更会被回滚到 Git 状态）
  syncOptions:
    - CreateNamespace=true
```

## 日常操作

```bash
# 部署新版本（推荐）
git push → ArgoCD auto-sync（2-3 分钟）

# 手动触发 sync（加速）
argocd app sync bank-mall

# 查看 sync 状态
argocd app list

# Rollback（回退 Git commit + sync）
git revert <commit> && git push
```

## ArgoCD vs scripts/ci.sh

| | `scripts/ci.sh` | ArgoCD |
|---|:---:|:---:|
| 适用环境 | harbor01（内网构建节点） | master01（控制面） |
| 触发方式 | 手动 `make ci` | `git push` → 自动 sync |
| 部署方式 | `kubectl apply` （ArgoCD 后会冲突） | Application CR sync |
| V1 角色 | 构建 + 镜像推送 | 部署 + GitOps |

## 已知局限

- ArgoCD 需要公网访问 GitHub。GFW 阻断时需用 SSH 方式（`git@github.com`），已配通（S0 前置验证）。
- `selfHeal: true` 会导致 kubectl 手动 patch（如 `kubectl set env`）在 3 分钟内被回滚。**所有配置变更必须在 Git 中修改后再 push。**
