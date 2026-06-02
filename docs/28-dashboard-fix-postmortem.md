# 28 - Grafana Dashboard 修复复盘

> 日期：2026-05-29 | Grafana 10.4.0 | Prometheus 从零数据到全 Panel 有数据

## 问题

部署 Prometheus + Grafana 后，Dashboard（Bank Mall - Service Overview）8 个面板中仅 Pod Count 有数据，其余 7 个面板显示 "No data" 或 Angular 弃用警告。

## 修复过程（4 轮）

### 第 1 轮：面板类型弃用

**症状：** 面板显示 "This panel requires Angular (deprecated)"。

**根因：** Grafana 10.x 弃用了 Angular 面板插件，Dashboard JSON 使用旧版 `"type": "graph"`。

**修复：** 5 个 panel 类型从 `"graph"` → `"timeseries"`（Grafana 10.x 原生）。`stat` 类型不变。

### 第 2 轮：Prometheus 抓取失败

**症状：** Prometheus Targets 页显示 4 个 `spring-boot` job 的地址为 `http://8081:8081`（缺 Pod IP），全部 `DOWN`。

**根因：** `prometheus-configmap.yaml` 中的 port relabel 配置有误：

```yaml
# 错误配置 — 覆盖了整个 __address__
- source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_port]
  action: replace
  target_label: __address__
  regex: '(\d+)'
  replacement: '${1}:${1}'   # 变为 "8081:8081"，Pod IP 丢失
```

**修复：** 删除该 relabel。Kubernetes SD 自动提供正确的 `Pod_IP:container_port`。

### 第 3 轮：PromQL 标签不匹配

**症状：** spring-boot targets 变绿（UP），但面板仍显示 "No data"。

**根因：** Dashboard 查询使用 `{namespace="bank-mall"}`，但 Prometheus k8s_sd relabel 只创建了 `job`（`bank-mall/auth-service`）、`pod`、`node` 标签，没有 `namespace` 标签。

```yaml
# k8s_sd relabel 创建的标签
- job: bank-mall/auth-service
- pod: auth-service-6dbd64d947-kch6m
- node: k8s-worker02
# 没有 namespace 标签
```

**修复：** 所有 PromQL 查询中 `{namespace="bank-mall"}` → `{job=~"bank-mall/.*"}`。

### 第 4 轮：指标名不对

**症状：** CPU 和 HTTP Response Time 面板仍 "No data"。

**根因：**

| 面板 | 原查询指标 | 实际可用指标 | 说明 |
|------|-----------|------------|------|
| Pod CPU Usage | `process_cpu_seconds_total` | `process_cpu_usage` | `_seconds_total` 仅 Prometheus 自身有，Spring Boot 暴露的是 `_usage`（0-1 gauge） |
| HTTP Response Time | `http_server_requests_seconds_bucket` | 无 `_bucket` | Spring Boot 默认不暴露 histogram bucket，无法算 p99 |

**修复：**

```promql
# CPU — 直接用 gauge
process_cpu_usage{job=~"bank-mall/.*"}

# HTTP — 用 sum/count 算平均值来代替 p99
http_server_requests_seconds_sum{job=~"bank-mall/.*"} 
  / 
http_server_requests_seconds_count{job=~"bank-mall/.*"}
```

## 最终 Dashboard 状态

| Panel | 指标 | 类型 | 状态 |
|-------|------|------|------|
| Pod CPU Usage | `process_cpu_usage` (gauge) | timeseries | ✅ |
| Pod Memory Usage | `jvm_memory_used_bytes` | timeseries | ✅ |
| JVM GC Pause Time | `jvm_gc_pause_seconds_sum` | timeseries | ✅ |
| HTTP Request Rate | `http_server_requests_seconds_count` | timeseries | ✅ |
| Service Up/Down | `up` | stat | ✅ |
| JVM Thread Count | `jvm_threads_live_threads` | timeseries | ✅ |
| HTTP Response Time (avg) | `sum / count` | timeseries | ✅ |
| Pod Count | `count(up)` | stat | ✅ |

## 涉及文件

| 文件 | 改动 |
|------|------|
| `k8s/base/monitoring/grafana-configmap.yaml` | panel `graph→timeseries`、PromQL 标签修正、指标名修正 |
| `k8s/base/monitoring/prometheus-configmap.yaml` | 删除 broken port relabel |

## 教训

1. **Prometheus relabel 覆盖 `__address__` 需谨慎** — `source_labels` 的值直接覆盖目标标签，必须保留 Pod IP
2. **Dashboard 查询标签必须与实际指标标签一致** — k8s_sd 标签 ≠ 手动配置的 static 标签
3. **Spring Boot Micrometer 指标名 ≠ Prometheus 指标名** — `process_cpu_usage` vs `process_cpu_seconds_total`
4. **Grafana 10.x 弃用 Angular** — 所有 `graph` 面板必须迁移到 `timeseries`
5. **histogram bucket 需要主动配置** — Spring Boot 默认不暴露 `_bucket`，算分位数需额外配置
