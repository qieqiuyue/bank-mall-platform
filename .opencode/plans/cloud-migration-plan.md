# bank-mall 云迁移方案（整合修订版 v2）

> **专业承诺**：
> - **诚实**：不夸大云迁移对当前项目的影响，HA 10-12h 估值已含踩坑
> - **节俭**：能本地验证的不上云，ACK 体验 8 元 ROI 最高
> - **可清理**：所有 ECS/SLB/VSwitch 必须能干净销毁，避免持续计费
> - **可回退**：云上验证失败不影响本地集群继续运行
> 
> **创建日期**：2026-06-04
> **修订日期**：2026-06-04（整合 Claude Code + Deepseek V4 Pro 审计）
> **基于**：
> - 云迁移 v1 计划（之前讨论）
> - 独立审计报告
> - Claude+Deepseek 综合审计（5 个 P0 + 3 个 P1 + 3 个 P2）
> - AGENTS.md 环境约束（GFW、Harbor HTTP、containerd v2 限制）
> - 面试回报分析（ACK 高 ROI / HA 推迟到面试后）

---

## 一、核心结论

**面试回报 vs 时间投入的优先级**：
1. ✅ **ACK 体验**（4h，~8元）→ Week 1 立刻做，ROI 最高
2. ✅ **S3 CI/CD 核心层**（~18h）→ Week 1-2
3. ✅ **S4 故障演练精简版**（~12h）→ Week 2
4. ✅ **S5 润色 + 面试材料**（~8h）→ Week 3
5. ⏸️ **自建 HA kubeadm**（10-12h）→ **面试后**（ROI 不如刷 LeetCode）
6. ⏸️ **项目 B 云上实验** → 面试后单独排期

---

## 二、资源全景

```
┌─────────────────────────────────────────────────────────┐
│                    阿里云（150元+可追加）                  │
│  ┌─────────────────────────────────────────────────┐    │
│  │ ACK 托管版体验（4h，~8元）                       │    │
│  │ → 立刻做，ROI 最高                               │    │
│  └─────────────────────────────────────────────────┘    │
│                                                         │
│  项目B相关：SLB/OSS/CDN/WAF（已有企业项目经验）        │
│  → 面试后单独排期                                       │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│              腾讯云轻量 2C2G40G（可重置）                  │
│  ┌─────────────────────────────────────────────────┐    │
│  │ 利用率优化：配置为 Squid 正向代理                │    │
│  │ → 加速本地 VM 拉取 GitHub/ACR                    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│              本地 VMware（当前主力）                       │
│  k8s-master01 + 2worker + harbor01                     │
│  → S2/S3/S4/S5 剩余工作在这里完成                      │
│  → HA 验证在这里做更经济                                │
└─────────────────────────────────────────────────────────┘
```

---

## 三、前置验证清单（实施前必须完成）

> **职业道德**：以下每一项必须在动手前确认。**未验证先实施 = 浪费钱**。

### 🔴 P0 — 阻塞项，必须确认

| # | 问题 | 验证方法 | 通过标准 | 状态 |
|---|------|---------|---------|:----:|
| **Q1** | ACK CNI 与 NetworkPolicy 兼容 | 创建集群时**选 Flannel 模式**（不选 Terway）| 文档确认 Flannel 支持 NetworkPolicy | ⬜ |
| **Q2** | ACR Image Pull 权限 | 创建集群时绑定 `AliyunCSManagedNetworkRole` | 验证 pod 能拉取 ACR 镜像 | ⬜ |
| **Q3** | ECS 配额（10 vCPU） | 阿里云控制台 → 配额中心 | vCPU 配额 ≥ 10 | ⬜ |

### 🟡 P1 — 实施中需要留意

| # | 问题 | 验证方法 | 通过标准 | 状态 |
|---|------|---------|---------|:----:|
| **Q4** | SLB TCP 监听与 kubeadm init 兼容性 | 先启动 master apiserver → 注册 SLB → 再 join | HA 第二步验证 | ⬜ |
| **Q5** | ECS 公网带宽 | 创建 ECS 时选固定带宽 ≥ 5 Mbps | `curl -o` 测试拉镜像 < 30s | ⬜ |
| **Q6** | Maven Central 内网可达 | `curl -I https://maven.aliyun.com/repository/public/ -m 5` | HTTP 200 | ⬜ |
| **Q7** | Harbor HTTP 镜像拉取 | `ctr -n k8s.io images pull --plain-http` | 拉取成功 | ⬜ |
| **Q8** | 资源清理确认 | 部署后立即标记销毁日期 | 用完立即释放 | ⬜ |

### 🟢 P2 — 可选优化

| # | 问题 | 建议 |
|---|------|------|
| **Q9** | 腾讯云 2C2G 利用率 | 立刻配置为 Squid 正向代理，加速本地 VM 拉取 GitHub/ACR |
| **Q10** | 多架构构建 | 删除 S3 中的"项目 A"引用，amd64 only，多架构留到 S5 |

---

## 四、ACK 托管版体验（4h，~8元）✅ Week 1 立即执行

### 4.1 创建集群

**前置确认**：Q1、Q2、Q3 必须已验证

**步骤**：

```bash
# 1. ACK 托管版集群（控制台操作）
# - 地域：华东1（杭州）
# - Worker 节点：2 × ECS c6.large（2C4G）
# - CNI：Flannel（Q1 要求，避免 Terway NetworkPolicy 兼容问题）
# - Kubernetes 版本：v1.36.1（与本地一致）
# - 网络插件：Flannel
# - RAM Role：AliyunCSManagedNetworkRole（Q2 要求）

# 2. 配置 kubectl 上下文
aliyun cs GET /k8s/<cluster-id>/user_config > ~/.kube/config-ack
export KUBECONFIG=~/.kube/config-ack
kubectl get nodes
```

### 4.2 推送镜像到 ACR

**专业说明**：本地项目用 `10.0.0.61/bank-mall/*` 镜像，云上需要重新 tag 到 ACR。

```bash
# 4 个服务镜像
for svc in auth-service account-service payment-service notification-service; do
  docker tag 10.0.0.61/bank-mall/${svc}:2.0.0 \
    registry.cn-hangzhou.aliyuncs.com/qieqiuyue/bank-mall-${svc}:2.0.0
  docker push registry.cn-hangzhou.aliyuncs.com/qieqiuyue/bank-mall-${svc}:2.0.0
done

# 第三方镜像
docker pull mysql:8.0
docker tag mysql:8.0 registry.cn-hangzhou.aliyuncs.com/qieqiuyue/mysql:8.0
docker push registry.cn-hangzhou.aliyuncs.com/qieqiuyue/mysql:8.0
# ... 类似的 Grafana / Prometheus / Loki / Jaeger
```

**专业注意**：根据 AGENTS.md，**multi-stage builds 在 harbor01 有 DNS 问题**。云上同样需要避免在 ECS 上 multi-stage build（除非使用 `maven:3.9-eclipse-temurin-21-alpine` 镜像内自带的 Maven）。

### 4.3 部署 K8s 清单

**专业说明**：使用 Kustomize overlay 模式，base 清单零修改，云上差异由 patch 管理。

```bash
# 创建 overlay 目录
mkdir -p infra/kubernetes/cloud/patches

# 关键 patches：
# 1. 镜像地址：10.0.0.61/bank-mall/* → registry.cn-hangzhou.aliyuncs.com/qieqiuyue/bank-mall-*
# 2. 存储：hostPath → csi + StorageClass: alicloud-disk
# 3. 移除 nodeName: k8s-worker01
# 4. Service type: NodePort → LoadBalancer（让 ACK 自动创建 SLB）
```

**kustomization.yaml**：
```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../base
patches:
  - path: patches/deployment-images.yaml
    target:
      kind: Deployment
  - path: patches/remove-nodename.yaml
    target:
      kind: Deployment
  - path: patches/storage-class.yaml
    target:
      kind: PersistentVolume
  - path: patches/ingress-lb.yaml
    target:
      kind: Service
```

### 4.4 验证

```bash
# SLB 自动分配 External-IP（可能需要几分钟）
kubectl get svc -n ingress-nginx  # Ingress Service 应有 EXTERNAL-IP

# 从外网访问
curl http://<EXTERNAL-IP>/auth/api/auth/health
```

### 4.5 学习点（可写入面试材料）

- ACK 控制台操作（集群创建、节点池、组件管理）
- ACR 推送镜像 + imagePullSecrets
- SLB 自动创建 + Ingress 使用
- CSI 云盘 StorageClass（`alicloud-disk` / `alicloud-disk-ssd`）
- 云上 NetworkPolicy（Flannel CNI，兼容现有 Calico 规则）

---

## 五、腾讯云 2C2G 优化（1h）✅ 立即执行

### 5.1 改造为 Squid 正向代理

**目的**：加速本地 VM 拉取 GitHub/ACR，缓解 GFW 问题。

```bash
# 在腾讯云轻量上安装 Squid
sudo apt install -y squid

# 配置 /etc/squid/squid.conf
http_port 3128
acl allowed_ips src 10.0.0.0/24
http_access allow allowed_ips
http_access deny all

# 重启
sudo systemctl restart squid
```

**本地 VM 使用代理**：
```bash
# /etc/profile.d/proxy.sh
export http_proxy=http://<tencent-cloud-ip>:3128
export https_proxy=http://<tencent-cloud-ip>:3128
```

---

## 六、自建 HA kubeadm（10-12h，~40元）⏸️ 面试后执行

### 6.1 为什么推迟到面试后？

| 因素 | 评估 |
|------|------|
| 时间投入 | 10-12h（含踩坑） |
| 面试回报 | 高，但可"设计文档"代替实操 |
| 替代时间价值 | 30-50 道 LeetCode > HA 演练 |
| 风险 | 阿里云 SLB 6443 + kubeadm init 时序问题，踩坑可能拖到 15h |

**决策**：面试后做。**面试前可用设计文档 + 架构图代替实操**。

### 6.2 实施步骤（面试后）

**前置确认**：Q1-Q8 全部已验证

```bash
# 1. 开 5 台 ECS（3 master × 2C4G + 2 worker × 2C4G）
# 2. 在前面一台 ECS 上装 HAProxy + Keepalived，做 API Server VIP
#    或直接用阿里云 SLB 做 6443 负载均衡（Q4 验证）

# 3. kubeadm init --control-plane-endpoint "vip:6443" \
#     --upload-certs \
#     --image-repository registry.aliyuncs.com/google_containers

# 4. 2 个 master join --control-plane
# 5. 2 个 worker join
# 6. 验证 etcd 集群：ETCDCTL_API=3 etcdctl member list
# 7. 模拟故障：关一台 master → 验证集群仍可用
# 8. etcd 备份恢复实验
# 9. 证书轮换：kubeadm certs renew all

# 10. 销毁所有 ECS（Q8 要求立即清理）
```

### 6.3 学习点（可写入面试材料）

- stacked etcd HA 架构原理
- API Server VIP（SLB vs HAProxy+Keepalived）
- kubeadm HA 初始化流程
- etcd 备份恢复（`etcdctl snapshot save/restore`）
- 证书轮换（`kubeadm certs renew`）
- 节点故障演练（关 master → 验证集群可用性）

---

## 七、需要改的文件清单

| # | 文件 | 改动 | 难度 |
|---|------|------|------|
| 1 | 所有 Deployment `nodeName` | 删除 `nodeName: k8s-worker01`，让调度器自动选择 | 低 |
| 2 | MySQL PV (`mysql/storage.yaml`) | `hostPath` → `csi` + `StorageClass: alicloud-disk` | 中 |
| 3 | Loki PV (`monitoring/loki-storage.yaml`) | 同上 | 中 |
| 4 | Jaeger PV (`jaeger/jaeger-pv.yaml`) | 同上 | 中 |
| 5 | 所有 Deployment `image` | `10.0.0.61/bank-mall/*` → `registry.cn-hangzhou.aliyuncs.com/qieqiuyue/bank-mall-*` | 低 |
| 6 | Ingress Service (`ingress/controller-service.yaml`) | `NodePort` → `type: LoadBalancer`（让 ACK 自动创建 SLB） | 低 |
| 7 | Ingress Controller 本身 | ACK 托管版自带，不需要手动部署 | **不需要改** |
| 8 | Prometheus PV | `emptyDir` → `alicloud-disk` PVC | 中 |
| 9 | Grafana PV | `emptyDir` → `alicloud-disk` PVC（可选，生产建议） | 低 |
| 10 | `deploy.sh` | 去掉 `ssh root@10.0.0.41 mkdir -p /data/loki`，改用 StorageClass | 低 |
| 11 | NetworkPolicy | Flannel CNI 兼容现有规则 | 验证 |
| 12 | ArgoCD Application CR | `repoURL` 不变，但 `targetRevision` 可能改分支 | 低 |
| 13 | Sealed Secrets Controller | 重新部署到 ACK 集群，密钥对重新生成 | 低 |

### 不需要改的文件

- 所有 Java 源码（服务无感知，零改动）
- Dockerfile（只改镜像推送目标）
- ConfigMap（环境变量不变）
- HPA（autoscaling/v2 ACK 支持）
- PDB / ResourceQuota / LimitRange（K8s 原生资源，ACK 兼容）

---

## 八、Kustomize Overlay 方案

```
infra/kubernetes/cloud/
├── kustomization.yaml          # 指向 base + cloud overlay
├── patches/
│   ├── deployment-images.yaml   # 镜像地址替换：Harbor → ACR
│   ├── storage-class.yaml       # hostPath → alicloud-disk
│   ├── remove-nodename.yaml    # 删除 nodeName 固定调度
│   └── ingress-lb.yaml         # NodePort → LoadBalancer
└── README.md                    # 云上部署说明
```

**专业说明**：用 Kustomize overlay 是最优雅的方案——base 清单一个字不改，云上差异全部走 patch。这本身就是面试亮点（声明式配置 + DRY 原则）。

---

## 九、成本预估（更新版）

| 步骤 | 资源 | 时长 | 成本 | 排期 |
|------|------|------|------|------|
| ACK 托管版体验 | 2×ECS 2C4G（按量）| 4h | ~8元 | **Week 1（立刻）** |
| 腾讯云 Squid 代理 | 轻量服务器 | 1h | 0元（已有） | **Week 1（立刻）** |
| 自建 HA 实验 | 5×ECS 2C4G（按量）| 10-12h | ~40元 | **面试后** |
| 全栈迁移验证（可选） | ACK + 5×ECS | 24h | ~120元 | **不推荐** |
| **总计（推荐路径）** | — | — | **~48元** | — |

---

## 十、面试话术对照（更新版）

| 面试问题 | 只有本地经验 | 加上云上实验后 |
|---------|------------|--------------|
| "你们 K8s 怎么部署的？" | "kubeadm 单 master" | "本地单 master 实验环境 + 阿里云 ACK 托管版已验证（HA 设计文档中，面试后实操）" |
| "生产怎么做高可用？" | "文档看了，没实操" | "已做过 3 master HA 设计（stacked etcd + API Server VIP + 证书轮换），阿里云实操安排在面试后" |
| "存储怎么管理的？" | "hostPath" | "本地 hostPath + 云上 CSI 云盘，理解 StorageClass 动态供给" |
| "Ingress 怎么做的？" | "NodePort 30080" | "NodePort 实验环境 + 云上 SLB LoadBalancer，理解两层负载均衡" |
| "镜像怎么管理的？" | "自建 Harbor" | "Harbor 私有仓库 + ACR 云上镜像服务" |
| "GFW 怎么解决？" | "文档说用阿里云" | "**实际用过**：腾讯云轻量搭 Squid 代理加速本地 VM 拉取 GitHub/ACR" |

---

## 十一、执行节奏（修正版）

```
以 2026-06-04 为 Day 0，假设面试在 3-4 周后：

Week 1（6/4-6/10）：
  ✅ S3 CI/CD 核心层（Gitleaks + Semgrep + ci.sh 重写，~10h）
  ✅ 腾讯云 Squid 代理（1h）
  ✅ ACK 体验（4h，~8元）→ 立刻更新简历话术

Week 2（6/11-6/17）：
  ✅ S3 收尾（ci.yml 重写 + 阻断案例 + 设计文档，~8h）
  ✅ S4 故障演练精简版（2 个演练 + 1 次压测，~12h）

Week 3（6/18-6/24）：
  ✅ S5 润色（Swagger + Helm + 面试材料含云上话术，~8h）
  ✅ 面试准备（算法 + 系统设计，每天 2-3h）

面试后：
  ⏸️ 自建 HA kubeadm（10-12h）
  ⏸️ 项目 B 云上实验
```

---

## 十二、风险与注意事项

| 风险 | 缓解 | 优先级 |
|------|------|:---:|
| ACK 镜像拉取（quay.io 被墙） | 阿里云 ACR 海外同步功能 | P1 |
| 成本超支 | 按量付费，用完立即释放；设置费用告警 | **P0** |
| 资源清理遗漏（持续计费） | 部署后立即标记销毁日期；用完立即释放 | **P0** |
| 云上 NetworkPolicy 不兼容 | 选 Flannel CNI（Q1） | **P0** |
| SLB 6443 端口 + kubeadm init 时序 | 先启动 apiserver → 注册 SLB → 再 join（Q4） | P1 |
| Sealed Secrets 跨集群 | 用 `kubeseal --re-encrypt` 重新加密 | P1 |
| multi-stage builds 在 ECS 失败 | 避免在 ECS 上 multi-stage，用预编译 JAR | P1 |
| 腾讯云 2C2G 不够跑 HA | 仅做单节点验证或辅助实验，HA 在阿里云做 | P1 |
| 项目 B 云上实验时间冲突 | 项目 B 文档未整理完，先专注 bank-mall | P2 |

---

## 十三、面试后清理清单（Q8 要求）

**专业承诺**：云上资源用完立即清理，避免持续计费。

```bash
# 1. 删除 ACK 集群（自动清理 SLB、VSwitch、Worker 节点）
aliyun cs DELETE /k8s/<cluster-id>

# 2. 手动检查残留（ACK 删除不彻底的情况）
# - 阿里云控制台 → SLB → 检查是否有遗留 SLB
# - 阿里云控制台 → VPC → VSwitch → 检查是否有遗留
# - 阿里云控制台 → 安全组 → 检查是否有遗留规则
# - 阿里云控制台 → 快照 → 检查是否有遗留快照

# 3. 删除 ACR 仓库
aliyun cr DELETE /repositories/qieqiuyue/bank-mall-auth

# 4. 验证费用
aliyun bss QueryAccountBalance
```

---

## 十四、与项目 B 的关系

| 项目 | 核心卖点 | 面试话术 | 排期 |
|------|---------|---------|------|
| bank-mall | **云原生全链路**：微服务+K8s+GitOps+链路追踪+监控告警 | "银行商城从零上云，JPA+补偿+OTEL+ArgoCD+ACK" | 优先 |
| 项目 B | **企业级安全防护**：CDN+SLB+WAF+OSS+多机房 | "企业Web应用的纵深防御架构" | 面试后 |

两者独立，面试时根据岗位侧重点选讲。项目 B 的云上实验（CDN/SLB/WAF/OSS）在 bank-mall 云迁移完成后单独排期。

---

**创建时间**：2026-06-04  
**修订时间**：2026-06-04  
**下次更新**：Q1-Q8 验证后  
**状态**：Week 1 立即执行 ACK 体验，HA 面试后做
