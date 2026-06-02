# 26 - 最终验收清单

> 用途：作为 `bank-mall-cloudnative` 当前事实入口，确认 V1 已落地能力、验收命令和 V2 规划边界。
> 约束：本文只描述验证方法，不要求执行部署；Redis、OpenTelemetry/Jaeger、AlertManager HA、多 master 和 V2 业务服务未落地，不能在面试中说成已实现。

## 验收口径

| 分类 | 当前状态 | 说明 |
|------|----------|------|
| V1 已落地 | 4 微服务、MySQL、Ingress、HPA、NetworkPolicy/PSA、Prometheus/Grafana、Loki/Promtail、Grafana Alerting | 面试中可以作为已实现能力展开 |
| 实验简化 | Secret 占位值、固定 `nodeName`、hostPath、Grafana webhook 占位 | 需要主动说明生产替代方案 |
| V2 规划 | Redis、OpenTelemetry/Jaeger、AlertManager HA、product/order/inventory、多 master HA | 只能说“规划/设计/下一阶段” |

## 基础环境

| 验收目标 | 对应文件 | 静态检查 | 集群验证 | 通过标准 |
|----------|----------|----------|----------|----------|
| 命名空间与 PSA 存在 | `k8s/base/security/namespace-psa.yaml` | `rg -n "pod-security.kubernetes.io/enforce|bank-mall|monitoring" k8s/base/security/namespace-psa.yaml` | `kubectl get ns bank-mall monitoring --show-labels` | `bank-mall` 与 `monitoring` 存在，且 PSA 标签符合文档 |
| Secret 使用占位模板 | `k8s/base/secret.yaml`、`k8s/base/secret.yaml.example` | `rg -n "PLACEHOLDER|Secret" k8s/base/secret.yaml k8s/base/secret.yaml.example` | `kubectl get secret -n bank-mall bank-mall-secret` | 仓库不保存真实密钥；实际集群部署前已替换占位值 |
| K8s 清单可被客户端解析 | `k8s/base/` | `kubectl apply --dry-run=client -f k8s/base` | 不适用 | dry-run 无 YAML 解析错误 |

## 应用服务

| 验收目标 | 对应文件 | 静态检查 | 集群验证 | 通过标准 |
|----------|----------|----------|----------|----------|
| 4 个服务 Deployment/Service 完整 | `k8s/base/*-service/` | `rg -n "name: (auth|account|payment|notification)-service|containerPort: 808" k8s/base` | `kubectl get pods,svc -n bank-mall` | 4 个服务 Pod Ready，Service 均为 ClusterIP |
| auth-service 接入 MySQL | `bank-digital-platform/auth-service`、`k8s/base/mysql/` | `rg -n "spring.datasource|JpaRepository|DataInitializer" bank-digital-platform/auth-service` | `kubectl logs -n bank-mall deploy/auth-service --tail=100` | auth-service 启动无数据库连接错误，用户数据初始化成功 |
| 基础 API 可访问 | `k8s/base/ingress/ingress-rules.yaml` | `rg -n "/auth|/account|/payment|/notification" k8s/base/ingress/ingress-rules.yaml` | 见下方 API 验证命令 | 健康检查、余额、支付、通知接口返回成功响应 |

API 验证命令：

```bash
curl http://<node-ip>:30080/auth/api/auth/health
curl http://<node-ip>:30080/account/api/accounts/A1001/balance
curl -X POST http://<node-ip>:30080/payment/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORDER-DEMO-001","amount":299}'
curl -X POST http://<node-ip>:30080/notification/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"receiver":"13800000000","template":"PAYMENT_SUCCESS"}'
```

## 流量入口

| 验收目标 | 对应文件 | 静态检查 | 集群验证 | 通过标准 |
|----------|----------|----------|----------|----------|
| Ingress Controller 暴露入口 | `k8s/base/ingress/controller-service.yaml` | `rg -n "nodePort: 30080|nodePort: 30443" k8s/base/ingress/controller-service.yaml` | `kubectl get svc -n ingress-nginx ingress-nginx-controller` | NodePort 为 `30080/30443` |
| 业务路由规则存在 | `k8s/base/ingress/ingress-rules.yaml` | `rg -n "bank-mall-ingress|rewrite-target|auth-service|account-service|payment-service|notification-service" k8s/base/ingress/ingress-rules.yaml` | `kubectl get ingress -n bank-mall` | `bank-mall-ingress` 存在，4 条路径指向对应服务 |

## 弹性伸缩

| 验收目标 | 对应文件 | 静态检查 | 集群验证 | 通过标准 |
|----------|----------|----------|----------|----------|
| 4 个服务 HPA 已配置 | `k8s/base/hpa/` | `rg -n "minReplicas: 1|maxReplicas: 3|averageUtilization: 70" k8s/base/hpa` | `kubectl get hpa -n bank-mall` | 4 个 HPA 均存在，目标 CPU 70%，范围 `1-3` |
| Metrics Server 可用 | 集群组件 | 不适用 | `kubectl top nodes` | 能返回节点指标，HPA 可读取 CPU 指标 |

## 安全加固

| 验收目标 | 对应文件 | 静态检查 | 集群验证 | 通过标准 |
|----------|----------|----------|----------|----------|
| 默认拒绝策略存在 | `k8s/base/security/deny-all.yaml` | `rg -n "name: deny-all|policyTypes" k8s/base/security/deny-all.yaml` | `kubectl get networkpolicy -n bank-mall` | `deny-all` 与白名单策略均存在 |
| DNS/Ingress/Monitoring/MySQL 白名单存在 | `k8s/base/security/` | `rg -n "allow-dns|allow-ingress|allow-monitoring|allow-services-to-mysql" k8s/base/security` | `kubectl describe networkpolicy -n bank-mall` | 规则能解释业务访问路径，不出现“默认全通” |
| Pod 安全上下文配置 | `k8s/base/*-service/deployment.yaml` | `rg -n "runAsNonRoot|allowPrivilegeEscalation|seccompProfile|drop:" k8s/base/*-service/deployment.yaml` | `kubectl get pods -n bank-mall -o yaml | grep -E "runAsNonRoot|allowPrivilegeEscalation"` | 业务容器非 root、禁用提权、使用 RuntimeDefault seccomp |

## 监控告警

| 验收目标 | 对应文件 | 静态检查 | 集群验证 | 通过标准 |
|----------|----------|----------|----------|----------|
| Prometheus 暴露 NodePort | `k8s/base/monitoring/prometheus-service.yaml` | `rg -n "nodePort: 30090" k8s/base/monitoring/prometheus-service.yaml` | `kubectl get svc -n monitoring prometheus` | Prometheus 可通过 `http://<node-ip>:30090` 访问 |
| Grafana 暴露 NodePort | `k8s/base/monitoring/grafana-service.yaml` | `rg -n "nodePort: 30300" k8s/base/monitoring/grafana-service.yaml` | `kubectl get svc -n monitoring grafana` | Grafana 可通过 `http://<node-ip>:30300` 访问 |
| Grafana 数据源与 Dashboard 已 provision | `k8s/base/monitoring/grafana-configmap.yaml` | `rg -n "uid: prometheus|uid: loki|Bank Mall - Service Overview" k8s/base/monitoring/grafana-configmap.yaml` | 在 Grafana UI 查看 datasource 和 dashboard | Prometheus/Loki 数据源存在，Dashboard 标题为 `Bank Mall - Service Overview` |
| Grafana Alerting 规则存在 | `k8s/base/monitoring/grafana-configmap.yaml` | `rg -n "alert-service-down|alert-high-cpu|alert-high-memory" k8s/base/monitoring/grafana-configmap.yaml` | `kubectl get configmap -n monitoring grafana-config -o yaml | grep -E "alert-service-down|alert-high-cpu|alert-high-memory"` | 3 条告警规则存在；webhook 是学习占位，不宣称生产通知已打通 |

## 日志采集

| 验收目标 | 对应文件 | 静态检查 | 集群验证 | 通过标准 |
|----------|----------|----------|----------|----------|
| Loki 暴露 NodePort | `k8s/base/monitoring/loki-service.yaml` | `rg -n "nodePort: 30310" k8s/base/monitoring/loki-service.yaml` | `kubectl get svc -n monitoring loki` | Loki 可通过 `http://<node-ip>:30310` 访问 |
| Loki 存储存在 | `k8s/base/monitoring/loki-storage.yaml` | `rg -n "PersistentVolume|PersistentVolumeClaim|/data/loki" k8s/base/monitoring/loki-storage.yaml` | `kubectl get pv,pvc -n monitoring` | Loki PVC Bound，实验环境使用 hostPath |
| Promtail DaemonSet 采集日志 | `k8s/base/monitoring/promtail-daemonset.yaml`、`promtail-configmap.yaml` | `rg -n "kind: DaemonSet|bank-mall|monitoring|ingress-nginx" k8s/base/monitoring/promtail-*` | `kubectl get ds,pods -n monitoring -l app=promtail` | 每个节点有 Promtail Pod，日志能在 Loki/Grafana Explore 查询 |

## 未落地规划

| 能力 | 当前状态 | 验收方式 | 面试口径 |
|------|----------|----------|----------|
| Redis | 未落地 | `rg -n "kind:.*Redis|redis" k8s/base bank-digital-platform` 不应发现实际部署/依赖 | V2 缓存规划，当前不宣称已部署 |
| OpenTelemetry / Jaeger | 未落地 | `rg -n "JAEGER_ENDPOINT|jaeger-collector" k8s/base` 不应命中 | V2 链路追踪规划，当前不宣称已接入 |
| AlertManager HA | 未落地 | `rg -n "kind:.*Alertmanager|alertmanager" k8s/base` 不应命中 | V1 用 Grafana Alerting；生产/多集群再接 AlertManager |
| product/order/inventory | 未落地 | `Test-Path bank-digital-platform/product-service` 等目录应为 false | V2 业务增强规划 |
| 多 master HA | 未落地 | README 拓扑仍为 `k8s-master01` 单控制面 | V1 实验集群，V2 设计 3 master + LB + etcd 备份 |

## 一次性静态核查

```bash
rg -n "Redis|Jaeger|OpenTelemetry|AlertManager|JAEGER_ENDPOINT" README.md docs k8s/base
kubectl apply --dry-run=client -f k8s/base
rg -n "nodePort: 30080|nodePort: 30090|nodePort: 30300|nodePort: 30310" k8s/base
```

通过标准：

- 关键词出现时必须带有“V2/规划/未部署/生产场景”等边界说明。
- `k8s/base` 中不应存在 Redis、Jaeger、AlertManager 的实际部署清单。
- NodePort 与 README 保持一致：Ingress `30080`，Prometheus `30090`，Grafana `30300`，Loki `30310`。
