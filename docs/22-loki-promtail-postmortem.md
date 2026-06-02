# 22 - Loki + Promtail 日志模块故障复盘报告

> 复盘日期：2026-05-25
> 涉及组件：Loki 2.9.12 / Promtail 2.9.8
> 集群：kubeadm v1.36.1 | Calico CNI | containerd | Ubuntu 24.04
> 网络：中国大陆 GFW | VMware NAT 内网

---

## 一、事故时间线

| # | 时间 | 操作 | 现象 | 决策 |
|---|------|------|------|------|
| 1 | T+0h | Loki 3.0 + Promtail 3.0 首次 `kubectl apply` | ImagePullBackOff | Harbor HTTP 镜像需 containerd `ctr import` 手动导入 |
| 2 | T+0.5h | Loki Running, Promtail Running | Promtail 日志持续 `error sending batch...EOF` | 怀疑 Loki 3.0 与 ClusterIP Service 不兼容 |
| 3 | T+1h | Loki 降级到 2.9.12 | Loki CrashLoopBackOff：`mkdir /wal: permission denied` | UID 10001 无权在根目录创建 WAL |
| 4 | T+1.2h | ConfigMap 加 `wal.dir: /loki/wal` → `kubectl apply` | Loki Running | WAL 路径修复，但 Promtail EOF 仍持续 |
| 5 | T+1.5h | Promtail 降级到 2.9.8 | Promtail EOF 仍持续 | 版本匹配不是根因 |
| 6 | T+2h | Promtail Pod 内 `nc -w5 10.0.0.41 30310` | PORT_FAIL | NodePort 不可从 Pod 网络访问 |
| 7 | T+2.5h | `kubectl port-forward` → `curl POST localhost:3101` | 204 OK | Loki 自身正常，怀疑 kube-proxy |
| 8 | T+3h | `kubectl run bypass` 直连 Loki Pod IP `10.244.79.114:3100` | 204 OK | **定位：kube-proxy ClusterIP 代理 POST 请求断开** |
| 9 | T+3.5h | Promtail ConfigMap 改 Pod IP → `kubectl rollout restart` | ConfigMap 仍显示 ClusterIP URL | `kubectl patch` 嵌套引号导致替换失效 |
| 10 | T+4h | `kubectl delete` + `kubectl create --from-file` 重建 ConfigMap | Promtail 无 EOF 错误 | 推送地址修复成功 |
| 11 | T+4.5h | 查询 Loki → `Streams: 0, NO DATA` | Promtail `read_lines=368` 但 `sent_entries=0` | **核心根因：`cri: {}` 管道静默丢弃所有行** |
| 12 | T+5h | 删除 `pipeline_stages: - cri: {}` → restart | `sent_entries_total=393`, 查询 `Streams: 1` | **日志采集正常** |
| 13 | T+5.5h | `kubectl rollout restart daemonset/kube-proxy` | ClusterIP POST 仍 `000` | kube-proxy 重启无效 |
| 14 | T+6h | Grafana → Loki 数据源验证 | Grafana Pod wget POST 成功 | 查询功能验证通过 |

**时间统计：** 总耗时约 6 小时；误判方向（版本/镜像格式）约 2 小时。

---

## 二、根因分析

### 问题 1：CRI 管道静默丢弃所有日志行 【核心根因 | P0 | 配置类】

- **根因**：Promtail 2.9.8 `cri: {}` 默认配置未正确解析 containerd CRI 日志格式。Promtail 读取了 368 行但 `sent_entries_total` 始终为 0。管道静默丢弃不报错，无任何 error log。
- **表象**：Promtail 无 error/warn 日志；Loki 查询永远返回 0 streams；`promtail_read_lines_total` 正常增长但 `promtail_sent_entries_total=0`
- **影响**：全部日志数据丢失。排障过程被反复误导（在版本兼容性、网络路由、kube-proxy 之间徘徊 4+ 小时）
- **修复**：删除 `pipeline_stages: - cri: {}` 配置段。日志保留原始 CRI 格式（含 `stdout F` 前缀，不影响 LogQL 标签过滤）
- **类型**：根本解决（功能正常），临时缓解（日志格式含 CRI 前缀，不够美观）

**证据链：**
```
有 cri: {} 时：read_lines_total=299  sent_entries_total=0
无 cri: {} 时：read_lines_total=299  sent_entries_total=393  ← 393条发送成功
```

### 问题 2：kube-proxy ClusterIP POST 请求断开 【P1 | 架构类 | 未根本解决】

- **根因**：kube-proxy (iptables/IPVS) 对 POST `loki:3100/loki/api/v1/push` 转发失败，返回空响应。直连 Pod IP 正常。GET 请求不受影响。kube-proxy 重启未解决。可能与 conntrack 表、MTU、或 POST body 大小相关。
- **表象**：Promtail `Post ".../push": EOF`；Pod 内 `curl -X POST` 返回 `Empty reply from server` / HTTP `000`
- **影响**：无法通过标准 ClusterIP Service 地址推送日志。须用 Pod IP 直连绕过
- **修复**：Promtail `clients[0].url` 指向 `http://<Loki-Pod-IP>:3100/loki/api/v1/push`
- **类型**：临时缓解。根本解决需：`conntrack -L` 检查连接跟踪表 + `tcpdump -i any port 3100` 抓包分析

### 问题 3：Loki 2.9 WAL 路径权限 【P1 | 配置类 | 已解决】

- **根因**：Loki 2.9.12 默认 WAL 目录 `/wal`（根文件系统），UID 10001 无权创建子目录
- **表象**：Loki CrashLoopBackOff；日志 `mkdir /wal: permission denied`
- **修复**：ConfigMap 显式 `wal.dir: /loki/wal`（PVC 挂载点内）
- **类型**：根本解决

### 问题 4：positions.yaml 在 emptyDir 丢失 【P2 | 运维类 | 已缓解】

- **根因**：Promtail positions 存在 emptyDir 卷，Pod 重启后清空，所有日志从 offset 0 重新读取
- **影响**：重启后大量旧数据涌入 Loki，峰值负载增加但不丢数据
- **修复**：Loki `reject_old_samples: false` 允许旧时间戳数据写入
- **类型**：临时缓解。生产环境应将 positions 迁移到 hostPath

### 问题 5：pipeline_stages 空标签覆盖 【P3 | 配置类 | 已解决】

- **根因**：早期 ConfigMap 含 `pipeline_stages: - labels: {app: "", namespace: "", pod: "", container: ""}`，空值覆盖了 `relabel_configs` 设置的标签
- **影响**：Loki 中所有日志流标签为空字符串，无法按 app/namespace 过滤
- **修复**：删除 pipeline_stages 中的空 labels 段
- **类型**：根本解决

### 问题 6：NodePort 不可从 Pod 网络访问 【P3 | 设计限制 | 无需修复】

- **根因**：NodePort 设计为集群外部流量入口，Pod 内不应通过 NodeIP:NodePort 访问
- **表象**：`nc -w5 10.0.0.41 30310` 从 Pod 内返回 PORT_FAIL
- **修复**：使用 ClusterIP（Pod 间）或 Pod IP 直连；不尝试从 Pod 内访问 NodePort
- **类型**：设计如此

---

## 三、当前生产配置（实际生效版本）

### Loki ConfigMap 关键项

```yaml
ingester:
  wal:
    dir: /loki/wal              # 必须 — UID 10001 无权写 /
schema_config:
  configs:
  - store: boltdb-shipper       # 2.9.x 索引引擎（非 TSDB）
    schema: v11                 # 2.9.x schema（非 v13）
limits_config:
  reject_old_samples: false     # 允许 Promtail 重启后推送旧时间戳
```

### Promtail ConfigMap 关键项

```yaml
clients:
- url: http://<Loki-Pod-IP>:3100/loki/api/v1/push   # POD IP 直连，非 ClusterIP
  batchwait: 1s
  batchsize: 131072
scrape_configs:
  # ... relabel_configs 正常
  # 注意：没有 pipeline_stages！cri: {} 会静默丢弃数据
```

### 部署前检查清单

```bash
# 1. worker01 预创建数据目录
ssh qian@10.0.0.41 "sudo mkdir -p /data/loki && sudo chown -R 10001:10001 /data/loki"

# 2. 镜像导入到 containerd（每节点）
ssh qian@10.0.0.41 "sudo ctr -n k8s.io images import /tmp/loki-2.9.12.tar"
ssh qian@10.0.0.42 "sudo ctr -n k8s.io images import /tmp/promtail-2.9.8.tar"

# 3. 部署后，Promtail ConfigMap 写入 Pod IP（因 ClusterIP 不可靠）
LIP=$(kubectl get pod -n monitoring -l app=loki -o jsonpath='{.items[0].status.podIP}')
kubectl delete configmap promtail-config -n monitoring
kubectl create configmap promtail-config -n monitoring --from-file=promtail.yaml=<正确的yaml>
kubectl rollout restart daemonset/promtail -n monitoring
```

### 推送验证首选方法——先查 Metrics，再查网络

```bash
POD=$(kubectl get pods -n monitoring -l app=promtail -o wide | grep worker02 | awk '{print $1}')
kubectl port-forward -n monitoring $POD 9080:9080 &
curl -s http://localhost:9080/metrics | grep -E "read_lines_total|sent_entries_total"
```

| 场景 | read_lines | sent_entries | 结论 |
|------|-----------|-------------|------|
| >0 数据 | >0 | 0 | **管道丢弃** — 删 `pipeline_stages` |
| >0 数据 | >0 | >0 但查询无 | 时间范围错误 |
| =0 | 0 | 0 | 路径 glob 或权限问题 |

---

## 四、成本

| 项目 | 用量 | 说明 |
|------|------|------|
| 人力成本 | ~6h | 排障+配置修改+版本降级+文档 |
| 误判消耗 | ~2h | 在版本/网络两个错误方向反复尝试 |
| 废弃镜像 | ~2GB | 多版本 Loki/Promtail 镜像残留于 Harbor + 各节点 containerd |

---

## 五、经验教训

1. **CRI 管道是静默杀手** — `cri: {}` 丢弃数据不报告错误。排障第一步必须对比 `read_lines_total` vs `sent_entries_total`
2. **kube-proxy POST 不可假设可靠** — EOF 时立即 port-forward 直连排除 Service 层
3. **ConfigMap 用 delete+create 而非 patch** — 多行 YAML 嵌套引号极易静默失败
4. **positions 生产环境须持久化** — emptyDir 重启丢偏移 → 海量数据重推 → Loki 过载
5. **版本匹配是必要但不充分条件** — 管道配置错误比版本不兼容更隐蔽更难排查
