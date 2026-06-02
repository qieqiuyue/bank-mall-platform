# 25 - Grafana 统一告警 (Unified Alerting)

> 部署日期：2026-05-29 | Grafana 10.4.0

---

## 架构决策

### 为什么用 Grafana Alerting 而不是 AlertManager？

| 维度 | Grafana Unified Alerting | Prometheus AlertManager |
|------|--------------------------|------------------------|
| 额外组件 | 无（Grafana 已部署） | 需单独部署 AlertManager |
| UI | Dashboard + Alert 同一界面 | 独立 UI |
| 查询语言 | PromQL（与 Dashboard 一致） | PromQL |
| 通知渠道 | Webhook/Email/Slack/PagerDuty | Webhook/Email/Slack/PagerDuty |
| 多实例去重 | 不支持 | HA gossip 协议 |
| 配置方式 | Provisioning as Code (GitOps) | YAML ConfigMap |
| 适用场景 | 单集群，运维成本低 | 多集群/多 Prometheus，企业级 |

**结论：** V1 单集群用 Grafana Alerting，V2 多集群场景加 AlertManager。

---

## 配置方式：Provisioning as Code

告警配置通过 ConfigMap 以文件方式 provisioned，挂载到 `/etc/grafana/provisioning/alerting/`：

```yaml
# k8s/base/monitoring/grafana-configmap.yaml (alerting 部分)
data:
  contact-points.yaml:       # 通知接收器
  notification-policies.yaml: # 路由策略
  alert-rules.yaml:          # 告警规则（PromQL）
```

```yaml
# k8s/base/monitoring/grafana-deployment.yaml (挂载部分)
volumeMounts:
- name: alerting
  mountPath: /etc/grafana/provisioning/alerting
volumes:
- name: alerting
  configMap:
    name: grafana-config
    items:
    - key: contact-points.yaml
      path: contact-points.yaml
    - key: notification-policies.yaml
      path: notification-policies.yaml
    - key: alert-rules.yaml
      path: alert-rules.yaml
```

**GitOps 优势：** 修改规则 → 改 ConfigMap → `kubectl apply` → `kubectl rollout restart`，全程无需登录 Grafana UI。

---

## 3 条告警规则

### 规则 1：Service Down (critical)

```promql
up{job=~"bank-mall/.*"} == 0
```

| 参数 | 值 |
|------|-----|
| 评估间隔 | 1m |
| 持续时间 | 1m |
| 严重度 | critical |

**触发条件：** 任意 bank-mall 微服务 Down 超过 1 分钟。

**面试话术：** "`up` 是 Prometheus 自动抓取的指标，值为 1 表示服务可达，0 表示不可达。我加了 1 分钟持续时间（`for: 1m`），避免网络抖动导致的误报。"

### 规则 2：High CPU Usage (warning)

```promql
rate(process_cpu_seconds_total{job=~"bank-mall/.*"}[5m]) > 0.8
```

| 参数 | 值 |
|------|-----|
| 评估间隔 | 1m |
| 持续时间 | 5m |
| 严重度 | warning |

**触发条件：** 任意服务 5 分钟内 CPU 使用率持续超过 0.8 核/秒（相当于 80%）。

**面试话术：** "`process_cpu_seconds_total` 是 JVM 暴露的累计 CPU 时间，`rate` 计算瞬时速率。阈值 0.8 接近我们 500m limits，触发后考虑 HPA 扩容或优化代码。"

### 规则 3：High JVM Heap Usage (warning)

```promql
jvm_memory_used_bytes{job=~"bank-mall/.*", area="heap"} 
  / jvm_memory_max_bytes{job=~"bank-mall/.*", area="heap"} 
  > 0.85
```

| 参数 | 值 |
|------|-----|
| 评估间隔 | 1m |
| 持续时间 | 5m |
| 严重度 | warning |

**触发条件：** 任意服务 JVM 堆内存使用率持续超过 85%。

**面试话术：** "这直接对应我们的 memory limits (512Mi)。85% 是预警线，如果持续 5 分钟说明要么内存泄漏，要么需要扩大 limits。"

---

## 通知链路

```
Prometheus 指标 → Grafana 评估 → 触发告警 → Contact Point (webhook) → 生产: PagerDuty/Slack
```

**当前通知配置：**

```yaml
# contact-points.yaml
receivers:
- uid: webhook-1
  type: webhook
  settings:
    url: http://localhost:9999/alerts   # 学习环境占位
    httpMethod: POST
```

**路由策略：**

```yaml
# notification-policies.yaml
receiver: bank-mall-alerts
group_by: [grafana_folder, alertname]
group_wait: 30s        # 同一组告警收集 30s 后统一发送
group_interval: 5m     # 同一组新告警至少隔 5m 再发送
repeat_interval: 4h    # 同一告警至少隔 4h 再重复
```

**面试话术：** "学习环境 webhook 指向本地占位 URL，验证告警触发记入 Grafana 告警列表即可。生产环境把 URL 换成 PagerDuty 或 Slack Incoming Webhook，一条配置改动即可。"

---

## 部署与验证

### 部署

```bash
kubectl apply -f k8s/base/monitoring/grafana-configmap.yaml
kubectl apply -f k8s/base/monitoring/grafana-deployment.yaml
kubectl rollout restart deployment/grafana -n monitoring
```

### 验证规则已加载

```bash
# 方法 1：API 查询（需 admin 凭据）
kubectl exec -n monitoring deploy/grafana -- \
  curl -s -u admin:<GRAFANA_PASSWORD> \
  http://localhost:3000/api/ruler/grafana/api/v1/rules | python3 -m json.tool

# 方法 2：浏览器
# 登录 http://<worker-ip>:30300/alerting/list → Bank-Mall 文件夹
```

**成功标志：** 3 条规则显示 `provenance: "file"`，状态 `Normal`。

### 验证 provisioning 文件挂载

```bash
kubectl exec -n monitoring deploy/grafana -- ls /etc/grafana/provisioning/alerting/
# 输出: alert-rules.yaml  contact-points.yaml  notification-policies.yaml
```

---

## 踩坑记录

### 坑 1：legacy 和 unified alerting 冲突

**症状：** Grafana CrashLoopBackOff，日志 `legacy and unified alerting cannot both be enabled`

**根因：** 同时设置了 `GF_ALERTING_ENABLED=true`（旧版）和 `GF_UNIFIED_ALERTING_ENABLED=true`（新版）。

**修复：** 删除 `GF_ALERTING_ENABLED`，只保留 unified。

### 坑 2：数据源 UID 不匹配

**症状：** 告警规则加载后显示 "No Data"，PromQL 查询不到数据。

**根因：** ConfigMap 中 datasource 未设 `uid`，Grafana 自动生成随机 UID（如 `P8E80F9AEF21F6940`），与 alert rules 中写死的 `datasourceUid: prometheus` 不匹配。

**修复：** 在 datasource 定义中显式指定 `uid: prometheus` 和 `uid: loki`。

### 坑 3：Calico 授权丢失

**症状：** VM 挂起/恢复后新 Pod ContainerCreating，Events 显示 `error getting ClusterInformation: connection is unauthorized`。

**根因：** Calico node Pod 的 kubeconfig token 过期或网络状态不一致。

**修复：** 删除 Calico node Pod → 自动重建并重新注册：
```bash
kubectl delete pod -n kube-system -l k8s-app=calico-node --field-selector spec.nodeName=k8s-worker01
```

---

## 面试要点

| 面试官可能问 | 准备好回答 |
|-------------|-----------|
| "告警怎么配的？" | Provisioning as Code，ConfigMap 挂载，GitOps |
| "为什么不用 AlertManager？" | 单集群场景下 Grafana Alerting 覆盖同需求，组件更少 |
| "告警通知发到哪？" | 学习环境 webhook 占位，生产 PagerDuty/Slack |
| "为什么是这 3 条规则？" | 覆盖可用性 (up)、性能 (CPU)、资源 (内存) 三个维度 |
| "for: 5m 是什么意思？" | 告警触发后需持续 5 分钟才真正发送通知，避免抖动误报 |

---

## 相关文件

| 文件 | 内容 |
|------|------|
| `k8s/base/monitoring/grafana-configmap.yaml` | contact-points + policies + rules |
| `k8s/base/monitoring/grafana-deployment.yaml` | 挂载 + env vars |
| `docs/interview/interview-qa.md` | Q17 告警话术 |
| `.opencode/skills/interview-guide.md` | Alerting Q&A |
