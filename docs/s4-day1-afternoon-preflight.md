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

## 3. Jaeger CrashLoopBackOff（已修复）

**根因**（两个）：

1. **Liveness probe 路径错误**（`jaeger-deployment.yaml` bug）：
   `QUERY_BASE_PATH=/jaeger` 配置下，Jaeger query HTTP server 在 `:16686/` 返回 404，只有 `/jaeger/` 有内容。probe 打 `/` → 404 → kubelet kill 容器。
   - 修复：`path: /` → `path: /jaeger/`（已提交到 Git）

2. **Badger 数据损坏**：702 次强制重启导致 `/badger/data/` 残留损坏文件，进程在 Badger 初始化后立即退出，16686 端口从未成功绑定。

**完整修复流程**：
```bash
# Step 1: 修 probe（master01 在线 patch）
kubectl patch deployment jaeger -n jaeger --type=json -p='[
  {"op":"replace","path":"/spec/template/spec/containers/0/livenessProbe/httpGet/path","value":"/jaeger/"}
]'

# Step 2: 停 Jaeger
kubectl scale deployment jaeger -n jaeger --replicas=0
kubectl wait --for=delete pod -l app=jaeger -n jaeger --timeout=60s

# Step 3: 清 Badger 磁盘脏数据（worker02）
ssh root@10.0.0.42 "rm -rf /data/jaeger-badger/*"

# Step 4: 重建 PV + PVC + 启动
kubectl delete pvc jaeger-badger-pvc -n jaeger 2>/dev/null
kubectl apply -f infra/kubernetes/base/jaeger/jaeger-pv.yaml
kubectl apply -f infra/kubernetes/base/jaeger/jaeger-storage.yaml
kubectl scale deployment jaeger -n jaeger --replicas=1
kubectl wait --for=condition=ready pod -l app=jaeger -n jaeger --timeout=120s

# Step 5: 验证
kubectl port-forward -n jaeger svc/jaeger-query 16686:16686 --address=0.0.0.0 &
curl -s http://localhost:16686/jaeger/api/services
# 返回: {"data":["account-service","auth-service","notification-service","payment-service"],...}
```

**注意**：PV (`jaeger-pv.yaml`) 和 PVC (`jaeger-storage.yaml`) 是两个独立文件，重建时都要 apply。

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
