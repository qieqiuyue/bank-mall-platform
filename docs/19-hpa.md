# HPA 自动扩缩容部署文档

> 时间：2026年第4周 | 目标：让服务在高负载时自动扩容，低负载时自动缩容

---

## 一、为什么需要 HPA？

### 面试怎么问

| 面试问题 | 没有 HPA 的回答 | 有 HPA 的回答 |
|---------|---------------|-------------|
| "流量突增怎么办？" | "手动改 replicas" | "HPA 根据 CPU/内存自动扩到 3 副本，流量降下来自动缩回 1" |
| "扩容阈值怎么定？" | "凭经验" | "CPU 70% 触发扩容，内存 60% 触发扩容。HPA 有 10% 默认容差，实际触发线是阈值的 110%" |
| "生产环境 HPA 要注意什么？" | "没想过" | "必须设 resources requests/limits，否则 HPA 算不出利用率；缩容要有 stabilizationWindowSeconds 防抖动；maxReplicas 不能超过节点容量；镜像必须预拉到所有 worker 节点" |

### 技术原理

```
                    ┌─────────────────────────────────┐
                    │         HPA Controller           │
                    │   (kube-controller-manager 内)    │
                    │                                  │
                    │   每 15 秒执行一次：               │
                    │   1. 从 Metrics Server 获取      │
                    │      Pod 的 CPU/内存使用率        │
                    │   2. 计算目标副本数               │
                    │      desired = current ×          │
                    │        (currentMetric / target)   │
                    │   3. 调整 Deployment replicas     │
                    └─────────────────────────────────┘
                           │                 │
                    扩容 ↑                 ↓ 缩容
                           │                 │
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  1 Replica    │ → │  2 Replicas   │ → │  3 Replicas   │
│  CPU: 85%    │   │  CPU: 42%     │   │  CPU: 28%     │
│  超过阈值70% │   │  负载均衡     │   │  稳定运行     │
└──────────────┘   └──────────────┘   └──────────────┘
```

**关键概念：**

| 概念 | 含义 | 本项目配置 |
|------|------|-----------|
| minReplicas | 最少副本数 | 1（始终保留 1 个） |
| maxReplicas | 最多副本数 | 3（学习环境有限，生产可 10+） |
| CPU Target | CPU 使用率阈值 | 70%（超过此值触发扩容） |
| Memory Target | 不使用 | JVM 内存不随负载释放，不适合做 HPA 指标 |
| stabilizationWindowSeconds | 缩容冷却期 | 300 秒（5 分钟内不缩容） |
| HPA 默认容差 | 触发线 = 阈值 × (1 + tolerance) | 默认 tolerance=0.1，所以 60% 实际触发线 66% |

---

## 二、前置条件

### 2.1 必须设置 resources requests

HPA 根据 **利用率百分比**计算扩容：

```
利用率 = (实际使用量 / requests) × 100%
```

**如果没有设 requests，HPA 无法工作。** 本项目 4 个服务都已设置：

```yaml
# auth-service（内存较高，设 384Mi）
resources:
  requests:
    cpu: "100m"
    memory: "384Mi"
  limits:
    cpu: "500m"
    memory: "1Gi"

# 其他 3 个服务（内存 256Mi）
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

> **踩坑教训：** auth-service 空闲就占 ~210Mi 内存，如果 requests 只设 256Mi，空闲利用率就 81%。最初设 memory 阈值 80%，以为会触发扩容，结果 HPA 默认有 10% 容差（tolerance），实际触发线是 80% × 1.1 = 88%，81% 根本不够。最终方案：调高 requests 到 384Mi（空闲 ~55%），降低阈值到 60%（触发线 66%）。

### 2.2 安装 Metrics Server

HPA 需要 Metrics Server 提供 CPU/内存数据。

```bash
# 安装 Metrics Server
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# ⚠️ 关键：kubeadm 自签证书环境必须加 --kubelet-insecure-tls
# 否则 Metrics Server 无法连接 kubelet，报错：
#   "x509: cannot validate certificate for 10.0.0.xx because it doesn't contain any IP SANs"
kubectl patch deployment metrics-server -n kube-system --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

# 验证（等 30 秒后）
kubectl top nodes
kubectl top pods -n bank-mall
```

> **踩坑教训：** 我们忘了加 `--kubelet-insecure-tls`，导致 Metrics Server Pod 虽然 Running 但 `kubectl top` 报 `Metrics API not available`。查日志才看到 `x509` 错误。

### 2.3 去掉 nodeName 固定调度

HPA 扩容时需要 Pod 能调度到任意节点。如果 Deployment 设了 `nodeName: k8s-worker01`，所有副本都会挤在同一台机器上，失去扩容意义。

**哪些服务需要去掉 nodeName：**

| 服务 | 原 nodeName | HPA 需要 | 处理 |
|------|------------|---------|------|
| auth-service | k8s-worker01 | 是 | 去掉 |
| account-service | 无 | 是 | 已是自由调度 |
| payment-service | 无 | 是 | 已是自由调度 |
| notification-service | 无 | 是 | 已是自由调度 |
| mysql | k8s-worker01 | 否（数据库不扩容） | 保留 |
| prometheus | k8s-worker01 | 否 | 保留 |
| grafana | k8s-worker01 | 否 | 保留 |
| ingress-nginx | 无→被调到 worker02 | 否 | **必须钉回 worker01** |

> **踩坑教训：** 去掉 auth-service 的 nodeName 后，滚动更新把新 Pod 调度到 worker02，但 worker02 上没有 auth-service 镜像（Harbor HTTP 模式），导致 ImagePullBackOff。解决：**在所有 worker 节点上预拉业务镜像**。

### 2.4 所有 worker 节点预拉业务镜像

```bash
# 在每台 worker 节点（worker01 + worker02）上执行
for img in auth-service account-service payment-service notification-service; do
  sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> \
    10.0.0.61/bank-mall/${img}:1.0.0
done
```

### 2.5 Ingress 必须钉在 worker01

worker02 的 Pod 网络有偶发性故障（readiness probe 报 `invalid argument`），NodePort 在 worker02 上不可靠。必须把 ingress-nginx-controller 固定调度到 worker01：

```yaml
spec:
  template:
    spec:
      nodeName: k8s-worker01    # 必须钉在这里
```

> **踩坑教训：** ci.sh 的 `rollout restart` 让 Ingress 重新调度到 worker02，导致 NodePort 30080 从外部不可达。花了好久才意识到问题。

---

## 三、文件清单

### 新增文件

| # | 文件 | 说明 |
|---|------|------|
| 1 | `k8s/base/hpa/auth-service-hpa.yaml` | auth-service HPA（1-3 副本，CPU 70%） |
| 2 | `k8s/base/hpa/account-service-hpa.yaml` | account-service HPA（同上） |
| 3 | `k8s/base/hpa/payment-service-hpa.yaml` | payment-service HPA（同上） |
| 4 | `k8s/base/hpa/notification-service-hpa.yaml` | notification-service HPA（同上） |

### 修改文件

| 文件 | 变更 | 原因 |
|------|------|------|
| `k8s/base/auth-service/deployment.yaml` | 去掉 `nodeName: k8s-worker01`，memory requests 256Mi→384Mi | HPA 需要自由调度；384Mi 让空闲利用率降到 ~55% |
| `k8s/base/ingress/controller-deploy.yaml` | 加回 `nodeName: k8s-worker01` | worker02 Pod 网络不稳定 |
| `k8s/base/hpa/*.yaml` | 内存阈值 80%→60% | HPA 默认 10% 容差，80% 实际需 88%；Spring Boot 空闲 ~210Mi 远不够 |
| `scripts/deploy.sh` | 追加 `[10/10]` HPA 部署步骤 | 部署 HPA |
| `scripts/ci.sh` | 修复 rollout restart 顺序：先 apply 再 restart | 避免 kubectl wait 命中已销毁的旧 Pod 名 |

---

## 四、HPA 配置详解

以 auth-service 为例：

```yaml
apiVersion: autoscaling/v2          # v2 支持多指标，v1 只支持 CPU
kind: HorizontalPodAutoscaler
metadata:
  name: auth-service-hpa
  namespace: bank-mall
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: auth-service
  minReplicas: 1                   # 最少 1 个（保证可用性）
  maxReplicas: 3                   # 最多 3 个（学习环境，生产可更多）
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70      # CPU 超过 70% 触发扩容
  # 不使用 memory 指标——JVM 内存不随负载释放，会导致无法缩容
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300  # 缩容冷却 5 分钟（防抖动）
      policies:
      - type: Pods                     # 每分钟至少缩 1 个 Pod
        value: 1
        periodSeconds: 60
      selectPolicy: Min                 # 选最保守的策略
    scaleUp:
      stabilizationWindowSeconds: 0    # 扩容无冷却，立即响应
      policies:
      - type: Percent
        value: 100                     # 每分钟最多扩容 100%（翻倍）
        periodSeconds: 60
      - type: Pods
        value: 1                       # 或每分钟至少加 1 个 Pod
        periodSeconds: 60
      selectPolicy: Max                # 取两者中大的（快速扩容）
```

### ⚠️ HPA 默认容差（tolerance）—— 最容易踩的坑

HPA 有一个 **默认 10% 容差**：

```
实际触发线 = 目标阈值 × (1 + tolerance)

例 1：memory target = 80%
  触发线 = 80% × 1.1 = 88%
  Spring Boot 空闲 ~210Mi / 256Mi requests = 81%  ← 低于 88%，不触发！

例 2：memory target = 60%
  触发线 = 60% × 1.1 = 66%
  Spring Boot 空闲 ~210Mi / 384Mi requests = 55%  ← 空闲不触发，但负载上去能触发 ✅
```

**设计原则：** `requests × 目标利用率 > 空闲内存用量`，否则空闲就超过阈值，一直在扩容。

**最终方案：只用 CPU 指标。** JVM 内存不随负载释放（GC 只回收堆内对象，不还给 OS），空闲时利用率也居高不下，导致 HPA 无法缩容。CPU 是流量敏感的指标，负载来了上升、走了下降，天然适合扩缩容。

| 服务 | 空闲 CPU | CPU 阈值 | 能否正常扩缩 |
|------|---------|---------|------------|
| auth-service | ~3% | 70% | 空闲不触发，负载能触发 ✅ |
| account-service | ~3% | 70% | 空闲不触发，负载能触发 ✅ |
| payment-service | ~3% | 70% | 空闲不触发，负载能触发 ✅ |
| notification-service | ~3% | 70% | 空闲不触发，负载能触发 ✅ |

### 面试知识点

**Q: 为什么用 `autoscaling/v2` 而不是 `v1`？**

A: v2 支持多指标（CPU + 内存 + 自定义指标），v1 只支持 CPU 目标利用率。生产环境通常需要组合多个指标。

**Q: `averageUtilization: 70` 是什么意思？**

A: 所有 Pod 的 CPU 使用率平均值。如果 auth-service 有 2 个 Pod，一个 50%，一个 90%，平均值 70%，刚好在阈值边界，不会扩容。超过 70% 才会触发。

**Q: 缩容为什么需要 `stabilizationWindowSeconds: 300`？**

A: 防止"抖动"——流量瞬间下降又马上回来，如果 HPA 立刻缩容再扩容，Pod 反复创建销毁。5 分钟内保持不缩，确保负载真的下降了。

**Q: `selectPolicy: Max` 是什么意思？**

A: 扩容策略取最激进的选项。比如 Percent:100 说"翻倍"，Pods:1 说"至少加 1 个"，取 max 意味着流量大时快速扩容。

**Q: HPA 的容差（tolerance）是什么？**

A: 默认 10%，即利用率在目标值的 ±10% 范围内 HPA 不会行动。这避免了指标微小波动导致频繁扩缩。但也意味着你设 80% 阈值，实际要到 88% 才扩容。设计时要算 `requests × 触发线 > 空闲值`。

---

## 五、部署步骤

### 5.1 安装 Metrics Server（前置）

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# ⚠️ 关键步骤：kubeadm 自签环境必须加
kubectl patch deployment metrics-server -n kube-system --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

# 等待 30 秒后验证
kubectl top nodes
kubectl top pods -n bank-mall
```

### 5.2 预拉镜像到所有 worker 节点

```bash
# 在每台 worker 节点（worker01 + worker02）上执行
for img in auth-service account-service payment-service notification-service; do
  sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> \
    10.0.0.61/bank-mall/${img}:1.0.0
done
```

### 5.3 确保 Ingress 钉在 worker01

```bash
kubectl get pods -n ingress-nginx -o wide
# 确认 NODE 列是 k8s-worker01
# 如果不是，检查 controller-deploy.yaml 是否有 nodeName: k8s-worker01
```

### 5.4 部署 HPA

```bash
cd ~/bank-mall-cloudnative

# 方式一：单独部署 HPA（推荐，不影响已有服务）
kubectl apply -f k8s/base/hpa/

# 方式二：一键部署（会重启所有服务，谨慎使用）
# bash scripts/deploy.sh

# 验证
kubectl get hpa -n bank-mall
```

> ⚠️ **不要用 `bash scripts/deploy.sh`** 来部署 HPA！deploy.sh 会重新 apply 所有 Deployment，触发不必要的滚动更新。如果 auth-service 的 Deployment 有变更（如 memory requests 从 256Mi 改为 384Mi），滚动更新会创建新 Pod 并调度到 worker02，而 worker02 上可能没有镜像。

### 5.5 验证 HPA

```bash
# 查看 HPA 状态
kubectl get hpa -n bank-mall

# 期望输出：
# 期望输出（CPU-only）：
# NAME                      REFERENCE                 TARGETS   MINPODS   MAXPODS   REPLICAS
# auth-service-hpa          Deployment/auth-service   3%/70%    1         3         1
# account-service-hpa       Deployment/account-service 3%/70%  1         3         1
# payment-service-hpa       Deployment/payment-service 3%/70%   1         3         1
# notification-service-hpa  Deployment/notification-service 3%/70% 1 3      1

# 如果 TARGETS 显示 <unknown>，说明 Metrics Server 还没就绪
# 如果显示具体百分比且 REPLICAS=1，说明 HPA 正常工作
```

### 5.6 压测触发扩容

```bash
# 打开两个终端

# 终端 1：持续观察 HPA 和 Pod 状态
watch -n 5 'kubectl get hpa -n bank-mall; echo "---"; kubectl get pods -n bank-mall -o wide'

# 终端 2：压测（确保 10.0.0.41:30080 可达）
ab -n 20000 -c 100 http://10.0.0.41:30080/auth/actuator/health

# 观察要点：
# 1. 压测期间 CPU% 上升，REPLICAS 从 1 变为 2 或 3
# 2. 新 Pod 会调度到不同的 worker 节点（负载均衡）
# 3. 压测结束后等 5 分钟，REPLICAS 自动缩回 1

# 如果 10.0.0.41:30080 不通，检查 Ingress 是否在 worker01：
kubectl get pods -n ingress-nginx -o wide
```

### 5.7 验证缩容

```bash
# 压测结束后，等 5 分钟（stabilizationWindowSeconds=300）
# 然后观察 REPLICAS 是否自动缩回 1

kubectl get hpa -n bank-mall
# auth-service-hpa 的 REPLICAS 应从 3 逐渐变为 2 → 1
```

---

## 六、踩坑记录

### 6.1 HPA 显示 `<unknown>`

**现象：** `kubectl get hpa` 的 TARGETS 列显示 `<unknown>/70%`

**根因：** Metrics Server 未安装或未就绪。

**解决：**
```bash
kubectl get deployment metrics-server -n kube-system
kubectl logs -n kube-system deploy/metrics-server --tail=10
```

### 6.2 Metrics Server 日志报 x509 错误

**现象：** Metrics Server Pod Running 但 `kubectl top` 报 `Metrics API not available`，日志显示：
```
"Failed to scrape node" err="Get ... x509: cannot validate certificate for 10.0.0.xx because it doesn't contain any IP SANs"
```

**根因：** kubeadm 默认生成的 kubelet 证书不含 IP SANs，Metrics Server 严格验证 TLS 会失败。

**解决：** 加 `--kubelet-insecure-tls` 参数：
```bash
kubectl patch deployment metrics-server -n kube-system --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

### 6.3 HPA 内存超过 80% 阈值但不扩容

**现象：** auth-service 内存 81%，阈值 80%，但 REPLICAS 始终为 1。`kubectl describe hpa` 显示 `DesiredWithinRange`。

**根因：** HPA 默认有 10% 容差（tolerance），实际触发线 = 80% × 1.1 = 88%。81% < 88%，不触发。

**解决：** 两种方案：
1. 降低阈值到 60%（触发线 66%）——本项目采用
2. 调高 memory requests 让空闲利用率降低（auth-service 256Mi→384Mi）——本项目同时采用

### 6.4 HPA 扩容新 Pod 拉不到镜像（ImagePullBackOff）

**现象：** HPA 扩容到 worker02，新 Pod ImagePullBackOff。

**根因：** Harbor HTTP 模式下，kubelet 通过 CRI 拉取时不传 `--plain-http`。worker02 上没有预拉镜像。

**解决：**
```bash
# 在所有 worker 节点预拉镜像
for img in auth-service account-service payment-service notification-service; do
  sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> \
    10.0.0.61/bank-mall/${img}:1.0.0
done
```

### 6.5 Deployment 变更触发滚动更新导致服务中断

**现象：** 修改 auth-service 的 memory requests 后，新 Pod 调度到 worker02 但 ImagePullBackOff，旧 Pod 等待新 Pod 就绪不释放，服务卡在滚动更新中。

**根因：** Deployment 的滚动更新策略默认 `maxUnavailable=25%, maxSurge=25%`，即先建新 Pod 再删旧 Pod。如果新 Pod 拉不到镜像，旧 Pod 永远不会被替换。

**解决：**
1. 所有 worker 节点预拉镜像（根本解决）
2. 修改 HPA 相关 Deployment 时，先预拉镜像再 apply
3. 紧急恢复：`kubectl rollout undo deploy/auth-service -n bank-mall`

### 6.6 Ingress 跑到 worker02 导致 NodePort 不可达

**现象：** `curl http://10.0.0.41:30080/` 超时。

**根因：** Ingress controller 被 `ci.sh` 的 `rollout restart` 重新调度到 worker02，worker02 的 Pod 网络有问题，NodePort 不可达。

**解决：** ingress-nginx-controller 必须设 `nodeName: k8s-worker01`：
```yaml
spec:
  template:
    spec:
      nodeName: k8s-worker01
```

### 6.7 ci.sh rollout restart 竞态条件

**现象：** ci.sh 中 `rollout restart` 创建新 Pod 后，`kubectl wait` 引用旧 Pod 名报 `NotFound`。

**根因：** `rollout restart` 先创建新 Pod 替换旧 Pod，`kubectl wait` 用旧的 label selector 查到的 Pod 名已不存在。

**解决：** 改为先 `apply` 再 `rollout restart`，让 Deployment 只滚动一次：
```bash
kubectl apply -f "${K8S_BASE}/${service}/"
kubectl rollout restart "deployment/${service}" -n bank-mall
```

### 6.8 HPA scaleDown Percent:10 策略导致无法缩容

**现象：** 压测结束后 auth-service 3 副本一直不缩回 1，等了超过 5 分钟冷却期仍然 REPLICAS=3。

**根因：** `scaleDown` 策略设了 `Percent: 10`，3 个副本的 10% = 0.3，向下取整为 0。HPA 计算"每分钟最多缩容 0 个 Pod"，永远缩不回去。

**解决：** 增加 `Pods: 1` 策略 + `selectPolicy: Min`，确保每分钟至少缩 1 个 Pod：
```yaml
scaleDown:
  policies:
  - type: Pods
    value: 1
    periodSeconds: 60
  selectPolicy: Min    # 选最保守的策略
```

### 6.9 JVM 内存不适合做 HPA 指标

**现象：** auth-service 内存 51%（超过 60% 阈值），HPA 算出 `desired = ceil(3 × 51/60) = ceil(2.55) = 3`，始终不缩容。

**根因：** JVM 的内存使用特性——GC 后内存不会释放回操作系统。Spring Boot 空闲时内存 ~210Mi（主要在堆内），GC 只在堆内回收，不会把内存还给 OS。所以即使流量下降，内存利用率也不会明显下降，HPA 按内存公式算出来始终需要多个副本。

```
JVM 内存模型：
  堆内：对象分配 → GC 回收 → 但堆大小不缩小 → 利用率居高不下
  堆外：Metaspace、线程栈等 → 更不会释放
```

**解决：** HPA 只用 CPU 指标，去掉内存指标。CPU 是流量敏感的——请求多了 CPU 上升，请求少了 CPU 下降，天然适合扩缩容。

```yaml
metrics:
- type: Resource
  resource:
    name: cpu           # 只用 CPU
    target:
      type: Utilization
      averageUtilization: 70
  # 不用 memory
```

**面试关键点：** 这不是 K8s 的问题，是 JVM 的内存管理特性。Go、Python、Node.js 的 GC 也有类似问题但程度较轻。C/Rust 程序内存释放更彻底，更适合内存 HPA。

---

## 七、与监控体系的集成

HPA 与 Prometheus/Grafana 的关系：

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│  Spring Boot App  │────→│  Metrics Server   │────→│  HPA Controller   │
│  /actuator/       │     │  (CPU only)        │     │  (扩缩容决策)     │
│  prometheus       │     │                   │     │                   │
└──────────────────┘     └──────────────────┘     └──────────────────┘
        │                                                │
        │                                                │ 扩缩容
        ↓                                                ↓
┌──────────────────┐                           ┌──────────────────┐
│  Prometheus      │                           │  Deployment      │
│  (历史趋势)      │                           │  (副本数变化)     │
│  + Grafana       │                           └──────────────────┘
│  Dashboard       │
└──────────────────┘
```

- **Metrics Server** 提供实时 CPU/内存数据给 HPA（短期决策）
- **Prometheus** 提供历史趋势给 Grafana（长期分析）
- 两者是互补的，不冲突

可以在 Grafana Dashboard 中添加 HPA 副本数面板：
- 指标：`kube_replicas_status`（需要 kube-state-metrics）
- 或者直接用 `kubectl get hpa -w` 观察变化

---

## 八、压测结果

### 8.1 扩容验证

```bash
# 压测命令
ab -n 10000 -c 50 http://10.0.0.41:30080/auth/actuator/health

# 压测前（空闲）
NAME                       REFERENCE                         TARGETS         MINPODS   MAXPODS   REPLICAS
auth-service-hpa           Deployment/auth-service           3%/70%          1         3         1

# 压测中（CPU 飙升）
auth-service-hpa           Deployment/auth-service           105%/70%        1         3         3
#                                                 REPLICAS 从 1 → 3 ✅

# 压测结束 5 分钟后（CPU 下降，自动缩容）
auth-service-hpa           Deployment/auth-service           3%/70%          1         3         1
#                                                 REPLICAS 从 3 → 1 ✅
```

> **注意：** 最初使用内存指标时，JVM 内存不释放导致 3 副本永不缩回 1。改为 CPU-only 后缩容正常工作。

### 8.2 ab 压测数据

```
Concurrency Level:      50
Complete requests:      10000
Failed requests:        0
Requests per second:    334.90 [#/sec]
Time per request:       149.300 [ms] (mean)
p99 latency:            681ms
```

---

## 九、面试要点

| 面试问题 | 回答要点 |
|---------|---------|
| "HPA 怎么工作的？" | HPA Controller 每 15 秒从 Metrics Server 获取 Pod 指标，计算 `desired = current × (currentMetric / target)`，然后调整 Deployment 的 replicas |
| "为什么必须设 resources requests？" | HPA 用利用率百分比 = 实际使用量 / requests。没有 requests，HPA 算不出利用率，会报 `<unknown>` |
| "缩容为什么要冷却期？" | 防抖动。流量波动时如果立刻缩容再扩容，Pod 反复创建销毁影响稳定性。我们设了 5 分钟冷却 |
| "生产环境 HPA 怎么配？" | minReplicas ≥ 2（高可用），maxReplicas 根据集群容量设，CPU 阈值 60-80%，加自定义指标（如 QPS） |
| "HPA 的容差怎么理解？" | 默认 10%，即利用率在目标值的 ±10% 范围内不行动。设 80% 阈值实际要到 88% 才扩容。设计时要算 `requests × 触发线 > 空闲值` |
| "HPA 和 VPA 的区别？" | HPA 调副本数（横向扩缩），VPA 调资源请求/限制（纵向调整）。HPA 更常用，VPA 还在 beta |
| "为什么不用内存做 HPA 指标？" | JVM 内存不随负载释放（GC 只回收堆内对象，不还给 OS），空闲时利用率也居高不下，导致 HPA 无法缩容。CPU 才是流量敏感的指标 |
| "HPA scaleDown 策略要注意什么？" | 不能只设 Percent 策略。3 副本 × 10% = 0.3 取整为 0，永远缩不了。必须同时设 Pods: 1 策略兜底 |
| "HPA 扩容到其他节点但拉不到镜像怎么办？" | 预拉镜像或配置镜像仓库 HTTPS 访问。我们用 Harbor HTTP 模式，需要在所有 worker 节点手动 `ctr pull --plain-http` |
| "Metrics Server 报 x509 错误怎么办？" | kubeadm 自签证书不含 IP SANs，加 `--kubelet-insecure-tls` 参数跳过验证 |

---

> 本文档配合 `docs/14-troubleshooting-handbook.md`（故障排查）、`docs/18-deployment-verification.md`（部署验证）一起阅读。