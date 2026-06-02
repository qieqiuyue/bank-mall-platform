# 21 - 日志聚合：Loki + Promtail

## 架构概览

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  worker01     │    │  worker02     │    │  master01    │
│  Promtail Pod │    │  Promtail Pod │    │  (no Promtail │
│  (DaemonSet)  │    │  (DaemonSet)  │    │   by default) │
└──────┬───────┘    └──────┬───────┘    └──────────────┘
       │                    │
       │  push /loki/api/v1/push
       ▼                    ▼
┌──────────────────────────────────┐
│  Loki (monitoring ns, worker01)  │
│  存储: /data/loki (hostPath PV) │
└──────────────┬───────────────────┘
               │
               │  数据源: http://loki:3100
               ▼
┌──────────────────────────────────┐
│  Grafana (monitoring ns)        │
│  Explore → LogQL 查询           │
└──────────────────────────────────┘
```

## 设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| Loki 版本 | **2.9.12**（从 3.0.0 降级） | 3.0 TSDB + Promtail 3.0 通过 ClusterIP 推送不兼容；2.9.x boltdb-shipper 更稳定 |
| Loki 存储 | hostPath PV (5Gi) | 学习环境持久化，和 MySQL 一致 |
| Promtail 部署 | DaemonSet | 每个节点一个，自动收集所有容器日志 |
| Promtail 日志发现 | K8s SD，无 CRI pipeline | `cri: {}` 管道静默丢弃数据（见复盘报告） |
| Promtail 推送地址 | **Pod IP 直连**，非 ClusterIP | kube-proxy 对 POST 请求转发不可靠 |
| 镜像 | Docker Hub 原始名 | 避免 Harbor HTTP 兼容性问题 |
| 命名空间 | monitoring | 复用现有命名空间，复用 NetworkPolicy |
| Grafana 集成 | 添加 Loki 数据源 | 在现有 Grafana 中直接查询日志 |

## 组件清单

### Loki（日志存储）
- **镜像**: `grafana/loki:2.9.12`（降级自 3.0.0）
- **副本**: 1（钉 worker01）
- **端口**: HTTP 3100, gRPC 9096
- **存储**: hostPath PV `/data/loki` → 5Gi
- **安全**: UID 10001, nonRoot, drop ALL caps
- **Schema**: **v11 + boltdb-shipper**（非 v13/TSDB）
- **NodePort**: 30310
- **关键配置**: `wal.dir: /loki/wal`, `reject_old_samples: false`

### Promtail（日志采集）
- **镜像**: `grafana/promtail:2.9.8`（降级自 3.0.0）
- **部署**: DaemonSet（每个节点一个 Pod）
- **日志源**: `/var/log/pods` (containerd CRI 格式)
- **推送目标**: `http://<Loki-Pod-IP>:3100/loki/api/v1/push`（**Pod IP 直连**，非 ClusterIP）
- **采集命名空间**: bank-mall, monitoring, ingress-nginx
- **管道**: **无 pipeline_stages**（`cri: {}` 会静默丢弃数据）
- **RBAC**: ClusterRole（get/list/watch pods, nodes, services, endpoints）
- **安全**: Pod 级 runAsNonRoot: false（需读 host 日志），容器级 drop ALL caps

## 文件结构

```
k8s/base/monitoring/
├── loki-configmap.yaml      # Loki 配置（schema v13, TSDB, filesystem）
├── loki-deployment.yaml     # Loki Deployment (1 replica, worker01)
├── loki-service.yaml        # NodePort 30310
├── loki-storage.yaml        # PV/PVC (hostPath 5Gi)
├── promtail-configmap.yaml  # Promtail 配置（K8s SD, CRI pipeline）
├── promtail-daemonset.yaml  # Promtail DaemonSet
├── promtail-rbac.yaml       # SA + ClusterRole + Binding
└── (existing files...)
```

## 部署步骤

### 1. 预拉镜像（GFW 环境需手动处理）

```bash
# 在能访问 Docker Hub 的机器上
docker pull grafana/loki:3.0.0
docker pull grafana/promtail:3.0.0

# 导出
docker save grafana/loki:3.0.0 -o loki.tar
docker save grafana/promtail:3.0.0 -o promtail.tar

# 传到 worker 节点
scp loki.tar promtail.tar root@10.0.0.41:/tmp/
scp loki.tar promtail.tar root@10.0.0.42:/tmp/

# 在每个 worker 上导入到 containerd
ctr -n k8s.io images import /tmp/loki.tar
ctr -n k8s.io images import /tmp/promtail.tar
```

### 2. 创建 Loki 数据目录

```bash
ssh root@10.0.0.41 "mkdir -p /data/loki && chown 10001:10001 /data/loki"
```

### 3. 部署

```bash
# deploy.sh 已包含此步骤
kubectl apply -f k8s/base/monitoring/
```

### 4. 验证

```bash
# 检查 Pod 状态
kubectl get pods -n monitoring -l app=loki
kubectl get pods -n monitoring -l app=promtail

# 检查 Loki 健康状态
curl http://10.0.0.41:30310/ready

# 检查 Promtail 推送
curl -s http://10.0.0.41:30310/loki/api/v1/label | jq .

# 在 Grafana Explore 中查询
# 数据源: Loki
# 查询: {namespace="bank-mall"}
```

## LogQL 常用查询

```logql
# 查看所有 bank-mall 日志
{namespace="bank-mall"}

# 按 app 过滤
{namespace="bank-mall", app="auth-service"}

# 搜索错误日志
{namespace="bank-mall"} |= "ERROR"

# 搜索异常堆栈
{namespace="bank-mall"} |~ "Exception.*at"

# 按 pod 名过滤
{namespace="bank-mall", pod=~"auth-service-.*"} | json | line_format "{{.level}} {{.msg}}"

# 统计每分钟错误数
sum(count_over_time({namespace="bank-mall"} |= "ERROR" [1m])) by (app)
```

## Loki 配置要点（实际生效版本）

### Schema v11 + boltdb-shipper

```yaml
schema_config:
  configs:
  - from: 2024-01-01
    store: boltdb-shipper      # 2.9.x 索引引擎
    object_store: filesystem   # 本地文件系统存储
    schema: v11                # 2.9.x schema
    index:
      prefix: index_
      period: 24h
```

- `store: boltdb-shipper` — Loki 2.9.x 索引引擎，稳定可靠
- `object_store: filesystem` — 学习环境用本地文件系统
- 3.x 的 `allow_structured_metadata: false` 与 Promtail 3.0 不兼容，已整体降级到 2.9

### 无 CRI 管道解析

Promtail 当前 **不配置 `pipeline_stages`**。`cri: {}` 默认解析器在 2.9.8 中静默丢弃所有 containerd CRI 日志行（不报错）。日志保留原始 CRI 格式：

```
2016-10-06T00:17:09.669794202Z stdout F The log line
```

日志行中 `stdout F` 前缀在 LogQL 查询中不影响标签过滤，但日志消息体含 CRI 时间戳前缀。详见 `docs/22-loki-promtail-postmortem.md`。

### Pod IP 直连（非 ClusterIP）

Promtail `clients[0].url` 使用 `http://<Loki-Pod-IP>:3100/loki/api/v1/push`，而非 `http://loki.monitoring.svc.cluster.local:3100`。原因为 kube-proxy 对 POST 请求的转发不稳定（EOF/Empty reply）。Pod IP 会随 Loki Pod 重启变化，需同步更新 ConfigMap。

## 踩坑记录

> **完整复现报告见：** `docs/22-loki-promtail-postmortem.md`

### 1. CRI 管道静默丢弃数据（最隐蔽，耗时最长）

`pipeline_stages: - cri: {}` 在 Promtail 2.9.8 中静默丢弃所有 containerd CRI 日志行。读取 368 行但发送 0 条，Promtail 日志无任何 error。删除 `pipeline_stages` 后立即恢复正常。

### 2. kube-proxy ClusterIP POST 请求断开

Promtail 通过 `http://loki.monitoring.svc.cluster.local:3100` 推送日志返回 EOF/Empty reply。直连 Loki Pod IP 正常。临时方案用 Pod IP 直连。

### 3. Promtail 必须以 root 运行

需要读取 `/var/log/pods/` hostPath 卷。Pod 安全上下文 `runAsNonRoot: false`。满足 baseline PSA（drop ALL caps, seccomp RuntimeDefault）。

### 4. Loki WAL 路径权限

Loki 2.9.12 默认 WAL 目录 `/wal`，UID 10001 无权写。ConfigMap 必须显式 `wal.dir: /loki/wal`。

### 5. positions.yaml 在 emptyDir 丢失

Pod 重启后 positions 清空，从 offset 0 重读所有日志。配置 `reject_old_samples: false` 允许旧时间戳写入。生产环境 migrate 到 hostPath。

### 6. pipeline_stages 空标签覆盖 relabel_configs

`pipeline_stages: - labels: {app: "", namespace: "", ...}` 覆盖正确的标签值，导致所有流标签为空。

### 7. ConfigMap kubectl patch 嵌套引号陷阱

多行 YAML 用 `kubectl patch` 易因引号转义静默失败。推荐 `kubectl delete + create --from-file`。

### 8. NodePort 不能从 Pod 内访问

设计限制，非 bug。Pod 间通信必须用 ClusterIP 或 Pod IP。

### 9. DaemonSet 只在 worker 节点运行

control-plane 节点有 NoSchedule 污点，不接受 Promtail Pod。

## 面试要点

### Q: 为什么用 Loki 而不是 ELK？
A: Loki **不索引日志内容**，只索引标签（label）。存储成本比 ELK 低 80%+，适合 K8s 环境。Grafana 原生集成，查询用 LogQL。ELK 适合全文搜索场景，Loki 适合标签过滤 + grep 模式。

### Q: Promtail 和 Fluentd/Fluent Bit 怎么选？
A: Promtail 是 Grafana Labs 出品，与 Loki/Grafana 原生集成最好，K8s SD 自动发现零配置。Fluentd 功能更全但更重（Ruby），Fluent Bit 更轻量但与 Loki 配置稍复杂。学习环境 Promtail 最简单。

### Q: Loki 2.9.x 和 3.x 有什么区别？
A: 3.x 默认用 TSDB 索引引擎（替代 boltdb-shipper），schema v13，查询性能提升。但 Promtail 3.0 使用 Protobuf+Snappy 编码推送，与 Loki 3.0 通过 K8s ClusterIP Service 通信时存在兼容问题（EOF 错误）。本项目降级到 2.9.12 + 2.9.8 确保稳定运行。实际踩坑经验说明：生产环境选择版本必须端到端验证推送链路，不能只看功能列表。

### Q: DaemonSet 和 Sidecar 哪种方式更好？
A: **DaemonSet 是生产推荐方式** — 每个节点一个 Pod，资源开销低，不侵入业务容器。Sidecar 方式每个 Pod 多一个容器，资源浪费严重。只有在严格多租户隔离需求时才考虑 Sidecar。

### Q: 日志持久化怎么保证？
A: Loki 数据目录使用 hostPath PV（生产用 NFS/S3），Promtail positions.yaml 用 emptyDir（Pod 重启会重新从头读，但 Loki 会去重）。生产环境 positions 文件也应持久化。

### Q: 如何排查日志采集问题？
1. `kubectl logs -n monitoring <promtail-pod>` — 查看 Promtail 错误
2. `curl http://loki:3100/ready` — Loki 是否就绪
3. `curl http://loki:3100/loki/api/v1/label` — 查看标签是否被索引
4. Grafana Explore → LogQL 查询验证
5. 检查 Promtail positions 文件是否更新：`kubectl exec <promtail> -- cat /run/promtail/positions.yaml`