# S4 Day 1 下午 — 跑前检查清单

> 在启动 100/200 扩展压测 + HPA 观察之前，补 4 个纰漏。

## 1. 补充测试账户 + 更新脚本

**背景**：基线压测 3 轮 ~9500 次支付，A1001 余额只剩 ~3253。下一轮会耗尽。

```bash
# Step A：插入 9 个测试账户（幂等，可重复执行）
bash scripts/db-seed-accounts.sh

# Step B：验证账户列表
MYSQL_PASS=$(kubectl get secret mysql-secret -n bank-mall -o jsonpath='{.data.MYSQL_ROOT_PASSWORD}' | base64 -d)
kubectl exec deploy/mysql -n bank-mall -- mysql -uroot -p${MYSQL_PASS} bank_account \
  -e "SELECT account_no, balance, status FROM accounts ORDER BY account_no;"
```

`tests/payment-load.sh` 的 ACCOUNTS 数组已更新为 10 个账户。

## 2. DB 基线备份

**背景**：场景 1-3（OOMKilled / NetworkPolicy / Jaeger 慢调用）之前必须有恢复点。

```bash
bash scripts/db-backup.sh
# 输出示例：
# Backup created: /tmp/bank-mall-baseline-20260605-1430.sql
# Size: 245760 bytes (244K)
```

恢复命令：
```bash
MYSQL_PASS=$(kubectl get secret mysql-secret -n bank-mall -o jsonpath='{.data.MYSQL_ROOT_PASSWORD}' | base64 -d)
kubectl exec -i deploy/mysql -n bank-mall -- mysql -uroot -p${MYSQL_PASS} < /tmp/bank-mall-baseline-YYYYMMDD-HHMM.sql
```

## 3. Jaeger NodePort 31686 跨节点不通

**根因**：Jaeger Pod 在 worker02（10.244.69.204）。kube-proxy NodePort 在 master01 / worker01 上转发失败。Calico overlay 或 iptables NAT 层面问题，非 Jaeger 代码问题。

**方案 A（推荐）— port-forward**：
```bash
kubectl port-forward -n jaeger svc/jaeger-query 16686:16686 --address=0.0.0.0 &
# 浏览器：http://10.0.0.31:16686/jaeger
```

**方案 B — 直连 worker02 NodePort**：
```bash
curl http://10.0.0.42:31686/jaeger/api/services
# 浏览器：http://10.0.0.42:31686/jaeger
```

**方案 C — 迁 Jaeger 到 master01**（不推荐，改 Git manifest 更好）：
```bash
kubectl patch deployment jaeger -n jaeger -p \
  '{"spec":{"template":{"spec":{"nodeSelector":{"kubernetes.io/hostname":"k8s-master01"}}}}}'
```

## 4. Grafana SLO 面板验证

**背景**：Pre-flight 2.1 跳过，`bank-mall-sli-slo` dashboard 的 PromQL 可能写错导致面板空白。

**验证步骤**：
1. 浏览器打开 `http://10.0.0.31:30300`
2. 登录（admin / admin，S0 设的）
3. 左侧 Dashboards → **Bank Mall - SLI/SLO**
4. 确认 4 个面板都有数据线，无 "No data"

**如果面板空白，常见原因及修复**：

| 症状 | 可能原因 | 处理 |
|------|----------|------|
| QPS 面板无数据 | `payment_requests_total` 缺少 status tag | 点 Panel Edit → 确认 PromQL 匹配实际指标名 |
| P99 面板无数据 | Prometheus scrape interval 过大 | Grafana 时间窗口选 Last 5 minutes |
| 所有面板空 | Dashboard JSON 未 provision | 检查 `infra/kubernetes/base/monitoring/grafana-configmap.yaml` |
