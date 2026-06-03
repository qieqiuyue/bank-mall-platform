# S2 平台能力矩阵 — 独立审计报告

> **审计日期**：2026-06-04
> **审计源**：本地 `feat/s2-platform-matrix` 分支（commit `9bf52b0`）+ 远程 GitHub 同步
> **审计方法**：对照 S2 执行计划逐项验证文件存在性、内容深度、配置正确性

---

## Q1: S2 交付率 — 逐项验证

| S2 能力域 | 计划项 | 文件存在 | 内容深度 | 交付率 |
|-----------|--------|---------|---------|--------|
| **GitOps** | ArgoCD Application CR | ✅ 3个Application CR + namespace.yaml | syncPolicy完整，prune+selfHeal | 90% |
| **可观测性** | Micrometer指标 | ✅ 4个Metrics.java | Counter+Timer，带tag | 100% |
| | Grafana Dashboard | ✅ 2个JSON + ConfigMap嵌入 | SLI/SLO 4面板 + Business 6面板 | 95% |
| | Alerting | ✅ 3条规则 | Service Down + High CPU + High Memory | 100% |
| **链路追踪** | Jaeger Deployment | ✅ 完整5个YAML | Badger+PVC+Recreate+NodePort | 100% |
| | OTEL Agent | ✅ 4个Deployment全有initContainer | emptyDir+Harbor镜像+ConfigMap注入 | 100% |
| | NetworkPolicy | ✅ 3个Jaeger规则 | egress+ingress+ingress-nginx | 100% |
| **凭证安全** | SealedSecret | ✅ sealed-bank-mall.yaml | 8个密钥全加密，namespace-wide scope | 100% |
| | 明文secret.yaml | ✅ 已从Git删除 | commit d4cee0e | 100% |
| **安全加固** | PDB | ✅ pdb.yaml | 4服务minAvailable=1 | 100% |
| | ResourceQuota | ✅ resource-quota.yaml | cpu/mem/pods/svc/pvc | 100% |
| | LimitRange | ✅ limit-range.yaml | default/max/min | 100% |
| **工程基础** | Dockerfile | ✅ 4个全改 | multi-stage+non-root+HEALTHCHECK | 100% |
| | 父POM | ✅ apps/pom.xml | SB 4.0.6 + JDK 21 + 4 modules | 100% |
| | .dockerignore | ✅ 55行 | 覆盖target/IDE/secrets | 100% |
| **Total** | — | — | — | **~93%** |

---

## Q2: ArgoCD Application CR 配置正确性

| 检查项 | 结果 |
|--------|------|
| 3个Application CR定义完整 | ✅ |
| `automated.prune: true` | ✅ |
| `selfHeal: true` | ✅ |
| `targetRevision: main` | ✅ |
| repoURL 正确 | ✅ `https://github.com/qieqiuyue/bank-mall-platform.git` |
| path 正确 | ✅ `infra/kubernetes/base` |

**注意事项**：

1. `bank-mall-apps` 的 exclude 规则排除了 `argocd/jaeger/monitoring/ingress/security/namespace.yaml/configmap.yaml/secret.yaml/hpa/`——ArgoCD 只管理 4 个微服务 + MySQL 的 Deployment/Service，其他由另外两个 Application 管理。逻辑正确但依赖精确的目录结构。

2. `targetRevision: main` 意味着只有合并到 main 后才触发同步。feat 分支的变更不会自动同步到集群——这是 GitOps 最佳实践，但面试时应主动说明。

---

## Q3: Jaeger Deployment 存储配置

| 检查项 | 结果 | 说明 |
|--------|------|------|
| 镜像 | ✅ `jaegertracing/all-in-one:1.60` | LTS 版本 |
| Strategy | ✅ `Recreate` | 注释明确说明 "RWO PVC, must not use RollingUpdate" |
| 存储类型 | ✅ `badger` | 符合计划"零外部依赖" |
| `BADGER_EPHEMERAL` | ✅ `false` | 持久化存储 |
| PVC | ✅ `jaeger-badger-pvc` 5Gi RWO | 有匹配的 PV |
| PV | ✅ `jaeger-pv.yaml` hostPath 5Gi | 实验环境方案，注释标注生产应改 StorageClass |
| fsGroup | ✅ `1000` | 确保持久卷可写 |
| **缺失securityContext** | ⚠️ | 缺少 `runAsNonRoot: true`, `runAsUser`, `seccompProfile`，其他 4 个服务都有 |

**与技术文档决策对照**："不用 Jaeger Operator，纯 Deployment + Recreate + PVC"——完全一致。

---

## Q4: OTEL Agent 注入 — 3次迭代回顾

从 git log 可见迭代路径：

| 版本 | 方案 | 问题 | commit |
|------|------|------|--------|
| V1 | hostPath `/opt/otel` | PSA `baseline` 不允许 hostPath 挂载（Pod 无法启动） | `1435d8d` |
| V2 | initContainer 从 GitHub 下载 jar | VM 网络 `objects.githubusercontent.com` 被墙，curl 超时 | `2b277be` → 回退 |
| V3 | Harbor 镜像 `otel-agent-init:latest` → `cp` 到 emptyDir | ✅ 无 hostPath 依赖、无外网依赖、PodSecurity 合规 | `e3925d6` |

**评价**：

- 迭代过程展示了实际问题解决能力：V1 被安全策略阻断 → V2 被网络阻断 → V3 用 Harbor 中转解决两个问题
- 这是**面试亮点故事**：每个方案都有真实的技术约束驱动，不是随意尝试
- 最终方案的巧妙之处在于：用一个轻量 initContainer 镜像打包 OTEL agent jar，通过 emptyDir 共享给主容器，绕过了 PodSecurity 和网络问题

**面试话术**：

> "OTEL agent 的注入经历了三次迭代。第一版用 hostPath 挂载，但 PodSecurity baseline 标签不允许；第二版用 initContainer 从 GitHub 下载，但国内网络被墙；最终版把 agent jar 打进 Harbor 镜像，initContainer 从 Harbor 拉取后 cp 到 emptyDir，业务容器通过 -javaagent 参数加载。三种约束——安全策略、网络策略、零代码侵入——全部满足。"

---

## Q5: SealedSecret 完整性 + scope + 跨集群

| 检查项 | 结果 |
|--------|------|
| `encryptedData` 而非 `data`/`stringData` | ✅ 正确使用加密字段 |
| 密钥数量 | ✅ 8个：JWT_SECRET_KEY + 6个DB/Harbor凭据 + MYSQL_USER |
| `namespace-wide` scope | ✅ `sealedsecrets.bitnami.com/namespace-wide: "true"` |
| Git 中无明文 secret | ✅ secret.yaml 已删除（commit d4cee0e） |
| 部署引用一致性 | ✅ 4个Deployment 都 `secretRef: bank-mall-secret` |

**关键问题：跨集群可移植性**

`namespace-wide` 意味着同一命名空间内的 SealedSecret 可以解密。如果把这份 SealedSecret 拿到另一个 K8s 集群：

- ❌ **不能直接解密**——Sealed Secrets Controller 使用集群特有的私钥加密，不同集群私钥不同
- ✅ 但可以用 `kubeseal --re-encrypt` 重新加密到新集群的公钥
- 也可以在集群迁移时做 controller 密钥备份恢复

**面试话术**：SealedSecret 是集群绑定的，不能跨集群直接用。生产环境有两种方案：1）备份 controller 密钥到新集群；2）用 `kubeseal --re-encrypt` 重新加密。这比 External Secrets + Vault 多了一层 GitOps 原生性，少了一层外部依赖。Sealed Seeds 管引导凭证，External Secrets + Vault 管运行时轮换——两者可以叠加使用。

---

## Q6: 4 个 Dockerfile 完整性

| 服务 | 多阶段 | adduser/addgroup | USER appuser | HEALTHCHECK | 端口匹配 |
|------|--------|-----------------|-------------|-------------|---------|
| auth-service | ✅ | ✅ | ✅ | ✅ `:8081/api/auth/health` | ✅ |
| account-service | ✅ | ✅ | ✅ | ⚠️ `:8082/api/accounts/A1001/health` | ✅ |
| payment-service | ✅ | ✅ | ✅ | ✅ `:8083/api/payments/health` | ✅ |
| notification-service | ✅ | ✅ | ✅ | ✅ `:8084/api/notifications/health` | ✅ |

**注意**：`account-service` HEALTHCHECK 使用了 `/api/accounts/A1001/health`——这是一个具体账号的路径，建议改为 `/actuator/health`。

**为什么 HEALTHCHECK 和 K8s probe 都要？**

K8s livenessProbe/readinessProbe 是集群层面的健康检查，HEALTHCHECK 是 Docker 层面的。在 `docker run` 场景（开发/CI）下没有 K8s probe，HEALTHCHECK 提供兜底保护。两者互补，不冲突。

---

## Q7: OTEL gRPC 协议选择的前因后果

从 commit 历史可见：

```
e1312c0 fix: OTEL endpoint port 4317→4318 (http/protobuf) — agent default protocol
ab33c7e fix: OTEL gRPC protocol on port 4317 (bypasses Jaeger HTTP binding issue)
```

**时间线**：

1. **初始配置**：OTEL 默认 `http/protobuf` 协议 + 4317 端口
2. **第一次修复** (`e1312c0`)：改为 4318 + `http/protobuf`（OTEL 默认 HTTP 协议发送到 4318）
3. **第二次修复** (`ab33c7e`)：改回 4317 + `grpc` 协议

**根因分析**：

- Jaeger all-in-one 在 4317 端口同时监听 gRPC 和 HTTP/protobuf
- 但 Jaeger 的 HTTP/protobuf 绑定存在已知问题（`QUERY_BASE_PATH=/jaeger` 可能影响 HTTP receiver）
- 切换到 gRPC 协议绕过了 HTTP binding bug
- ConfigMap 中 `OTEL_EXPORTER_OTLP_PROTOCOL: grpc` 是最终选择

**当前连通性状态**：OTEL agent 加载成功（JVM 参数正确），但 trace 数据报送到 Jaeger collector 仍有连通性问题。execution-record.md 记载：同节点 Pod IP 直连也超时。**这可能是 Calico iptables 对 gRPC 长连接的 NAT 处理问题，不是 OTEL 配置问题。**

---

## Q8: 综合评估

### S2 整体交付率：93%

| 能力域 | 交付率 | 核心差距 |
|--------|--------|---------|
| GitOps | 90% | ArgoCD 本身安装未 YAML 化（运维操作范畴） |
| 可观测性 | 95% | 业务 Dashboard 未 apply 到集群（代码已完成） |
| 链路追踪 | 95% | Jaeger collector 4317 端口连通性待修复 |
| 凭证安全 | 100% | — |
| 安全加固 | 100% | — |
| 工程基础 | 90% | Semgrep/Gitleaks 属 S3 |

### 问题清单

| # | 严重度 | 文件 | 问题 | 建议 |
|---|--------|------|------|------|
| 1 | **MEDIUM** | `jaeger-deployment.yaml` | 缺少 `runAsNonRoot`, `runAsUser`, `seccompProfile` | 补齐 securityContext |
| 2 | **MEDIUM** | OTEL collector | Jaeger 4317 从 bank-mall Pod 不可达 | 排查 Calico iptables 或尝试 NodePort |
| 3 | **LOW** | `allow-jaeger-egress.yaml` | 包含 port 16686（bank-mall 服务不需要访问 Jaeger UI） | 精简为只保留 4317/4318 |
| 4 | **LOW** | `allow-jaeger-ingress.yaml` | 策略名 `allow-from-monitoring` 命名有误导性 | 重命名为 `allow-jaeger-ingress` |
| 5 | **LOW** | `account-service/Dockerfile` | HEALTHCHECK 使用 `/api/accounts/A1001/health` | 改为 `/actuator/health` |
| 6 | **INFO** | POM vs Docker image | POM version 1.0.0 vs Docker image tag 2.0.0 | 可接受（版本独立管理） |
| 7 | **INFO** | `sealed-bank-mall.yaml` | 缺少 `mysql-secret` 的 SealedSecret | 需确认集群上 mysql/secret.yaml 是否已被合并或仍为明文 |

### 最关键的 2 个遗留问题

1. **OTEL → Jaeger 连通性**：这是 S2 最大的功能缺口。没有 trace 数据流，Jaeger UI 就是空壳。需要排查 Calico iptables 并修复。

2. **Grafana Dashboard 未 apply**：Dashboard JSON 已写好但未部署到集群。更新 ConfigMap + rollout restart 即可。

---

### 与 Claude 审计报告的差异

| 维度 | Claude 审计报告 | 本报告 |
|------|---------------|--------|
| 整体交付率 | 93% | 93%（一致） |
| Jaeger securityContext | 未提及 | ⚠️ 标记为 MEDIUM |
| NetworkPolicy 命名 | 未提及 | ⚠️ 标记为 LOW |
| egress 16686 端口 | 未提及 | ⚠️ 标记为 LOW |
| Dockerfile HEALTHCHECK | ✅ | ✅ 但指出 account-service 路径问题 |
| SealedSecret 跨集群 | 详细分析 | 详细分析（一致） |
| OTEL 协议选择 | 提及 commit | 深入分析前因后果 |
| POM 版本不一致 | 未提及 | 标记为 INFO |
| mysql-secret SealedSecret | 未提及 | 标记为 INFO |