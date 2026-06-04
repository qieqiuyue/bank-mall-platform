# S4 实施交接清单

> 本机已完成：核心代码变更 + 所有实施文件准备
> 你需要在 VM（harbor01 / master01）上执行的步骤

---

## ✅ 本机已完成的工作

### 1. 核心代码变更（account-service）

| 文件 | 变更 |
|------|------|
| `apps/account-service/src/main/java/.../AccountService.java` | 新增 `@Value("${account.chaos.debit-delay-ms:0}")` + 延迟注入逻辑 |
| `apps/account-service/src/main/resources/application.yml` | 新增 `account.chaos.debit-delay-ms: ${ACCOUNT_CHAOS_DEBIT_DELAY_MS:0}` |

**变更说明**：
- 默认 `debitDelayMs = 0`，无延迟，不影响正常业务
- 通过环境变量 `ACCOUNT_CHAOS_DEBIT_DELAY_MS` 或 ConfigMap 动态注入延迟
- 延迟注入位置：`doDebitWithType()` 方法开头（在余额检查之前）
- 日志标记 `[CHAOS]`，便于 Loki 检索

### 2. 实施文件已创建

| 文件 | 用途 |
|------|------|
| `tests/payment-load.sh` | 零依赖 shell 压测脚本（curl + xargs） |
| `tests/k6/payment-load.js` | k6 压测脚本（ramp-up / threshold） |
| `infra/kubernetes/base/security/allow-services-ingress-minus-payment.yaml` | S4-NetworkPolicy 临时规则 |
| `docs/s4-execution-plan-evaluated.md` | 完整实施计划 v3.0（已修正所有问题） |

---

## 🚀 你在 VM 上的执行步骤

### Step 1：提交代码并构建镜像（提前一天完成）

```bash
# 在 harbor01 上
cd /path/to/bank-mall-platform

# 1. 确认变更
git diff apps/account-service/

# 2. 提交
git add apps/account-service/
git commit -m "[CHAOS] account-service: add configurable debit delay for S4 fault injection

- @Value account.chaos.debit-delay-ms, default 0ms
- inject delay in doDebitWithType() before balance check
- log marker [CHAOS] for Loki traceability

Related: S4 slow-call fault injection scenario"

# 3. 构建（跳过测试，因为无 MySQL）
cd apps/account-service
mvn clean package -DskipTests

# 4. 打镜像（build context 必须是 apps/）
cd ../..
docker build -t 10.0.0.61/bank-mall/account-service:s4-chaos \
  -f apps/account-service/Dockerfile apps/

# 5. 推送到 Harbor
docker push 10.0.0.61/bank-mall/account-service:s4-chaos
```

### Step 2：更新 K8s Deployment 使用新镜像

```bash
# 在 master01 上
kubectl set image deployment/account-service \
  account-service=10.0.0.61/bank-mall/account-service:s4-chaos \
  -n bank-mall

# 等待滚动更新完成
kubectl rollout status deployment/account-service -n bank-mall

# 验证 Pod 运行正常
kubectl get pods -l app=account-service -n bank-mall

# 验证延迟默认关闭（0ms）
kubectl logs -l app=account-service -n bank-mall --tail=20 | grep CHAOS
# 预期：无 CHAOS 日志（因为默认 0ms）
```

### Step 3：Day 1 上午 — 环境检查 + 基线压测

```bash
# === Pre-flight Checklist ===

# 1. 集群健康
kubectl get pods -n bank-mall
kubectl get pods -n jaeger
kubectl get pods -n monitoring

# 2. 可观测性端点
curl http://10.0.0.31:30300/api/health      # Grafana
curl http://10.0.0.31:30090/-/healthy        # Prometheus
curl http://10.0.0.31:31686/jaeger            # Jaeger UI

# 3. DB 基线 + 表名验证
kubectl exec -it deploy/mysql -n bank-mall -- mysql -ubankapp -p<password> bank_account -e \
  "SELECT account_no, balance, version FROM account WHERE account_no LIKE 'USER%';"

kubectl exec -it deploy/mysql -n bank-mall -- mysql -ubankapp -p<password> -e \
  "SHOW TABLES FROM bank_payment; SHOW TABLES FROM bank_account;"

# 4. ResourceQuota 余量
kubectl get resourcequota -n bank-mall

# 5. DB 备份
kubectl exec deploy/mysql -n bank-mall -- mysqldump -ubankapp -p<password> --all-databases \
  > /tmp/bank-mall-baseline-$(date +%Y%m%d-%H%M).sql

# === 基线压测 50 并发 ===
cd /path/to/bank-mall-platform/tests
chmod +x payment-load.sh
./payment-load.sh 50 180 http://10.0.0.31:30080/api/payments

# 记录结果到 tests/baseline-report.md
```

### Step 4：Day 1 下午 — 100/200 并发压测

```bash
# 100 并发
cd tests
./payment-load.sh 100 180 http://10.0.0.31:30080/api/payments

# 200 并发
./payment-load.sh 200 180 http://10.0.0.31:30080/api/payments

# 观察 HPA 扩容
kubectl get hpa -n bank-mall -w

# 记录结果到 tests/baseline-report.md

# === 压测后清理（Mandatory）===
kubectl exec -it deploy/mysql -n bank-mall -- mysql -ubankapp -p<password> -e "
USE bank_payment; TRUNCATE TABLE payment; TRUNCATE TABLE payment_transaction;
USE bank_account; UPDATE account SET balance = 100000, version = 1 WHERE account_no LIKE 'USER%';
"
```

### Step 5：Day 2 上午 — OOMKilled 场景

```bash
# 1. 降低 JVM 堆上限
kubectl patch deployment account-service -n bank-mall --type='merge' -p='{
  "spec": {
    "template": {
      "spec": {
        "containers": [{
          "name": "account-service",
          "env": [{
            "name": "JAVA_TOOL_OPTIONS",
            "value": "-javaagent:/otel/opentelemetry-javaagent.jar -Xmx280m"
          }]
        }]
      }
    }
  }
}'

# 2. 触发压测
cd tests
./payment-load.sh 50 300 http://10.0.0.31:30080/api/payments

# 3. 观察（另开终端）
# - Grafana: jvm_memory_used_bytes{area="heap"}
# - kubectl get pods -n bank-mall（看 RESTARTS）
# - kubectl logs -f deploy/payment-service -n bank-mall

# 4. 数据一致性校验
kubectl exec -it deploy/mysql -n bank-mall -- mysql -ubankapp -p<password> -e "
SELECT p.payment_no, p.status, p.fail_reason, p.amount,
       t.type, t.amount
FROM bank_payment.payments p
LEFT JOIN bank_account.transactions t ON t.idempotency_key = p.idempotency_key
WHERE p.status = 'FAILED' AND t.type = 'DEBIT';
"

# 5. 恢复
kubectl patch deployment account-service -n bank-mall --type='merge' -p='{
  "spec": {
    "template": {
      "spec": {
        "containers": [{
          "name": "account-service",
          "env": [{
            "name": "JAVA_TOOL_OPTIONS",
            "value": "-javaagent:/otel/opentelemetry-javaagent.jar"
          }]
        }]
      }
    }
  }
}'
kubectl rollout status deployment/account-service -n bank-mall

# 6. 恢复验证 + DB 清理（同 Step 3/4）

# 7. 写复盘 docs/s4-postmortem-01-oomkilled.md
```

### Step 6：Day 2 下午 — NetworkPolicy 场景

```bash
# 1. 备份原规则
kubectl get networkpolicy allow-services-ingress -n bank-mall -o yaml \
  > /tmp/allow-services-ingress-backup.yaml

# 2. 应用临时规则（从白名单移除 payment-service）
kubectl apply -f infra/kubernetes/base/security/allow-services-ingress-minus-payment.yaml
sleep 5

# 3. 验证故障生效
kubectl exec -it deploy/payment-service -n bank-mall -- \
  wget -qO- --timeout=5 http://account-service:8082/api/accounts/health || echo "BLOCKED"

# 4. 触发业务故障
curl -X POST http://10.0.0.31:30080/api/payments \
  -H "Content-Type: application/json" \
  -d '{"payerAccount":"USER001","payeeAccount":"MALL-SETTLEMENT","amount":10}'

# 5. 观察
# - kubectl logs -f deploy/payment-service -n bank-mall（ConnectException）
# - Grafana: payment_requests_total{status="FAILED"} 飙升
# - Jaeger: debit span 缺失

# 6. 恢复
kubectl apply -f infra/kubernetes/base/security/allow-services-ingress.yaml
sleep 5

# 7. 验证恢复
kubectl exec -it deploy/payment-service -n bank-mall -- \
  wget -qO- --timeout=5 http://account-service:8082/api/accounts/health

# 8. 恢复验证 + DB 清理
# 9. 写复盘 docs/s4-postmortem-02-networkpolicy-misconfig.md
```

### Step 7：Day 3 上午 — 慢调用场景

```bash
# 1. 关闭 HPA（防止请求分散）
kubectl delete hpa account-service-hpa -n bank-mall

# 2. 通过 ConfigMap 注入 2000ms 延迟
kubectl patch configmap bank-mall-config -n bank-mall --type='merge' -p='{
  "data": {"ACCOUNT_CHAOS_DEBIT_DELAY_MS": "2000"}
}'

# 3. 重启 account-service 加载配置
kubectl rollout restart deployment/account-service -n bank-mall
kubectl rollout status deployment/account-service -n bank-mall
sleep 10

# 4. 验证延迟生效
kubectl exec -it deploy/payment-service -n bank-mall -- \
  wget -qO- --timeout=5 http://account-service:8082/api/accounts/health

# 5. 触发压测（50 并发叠加 2s sleep）
cd tests
./payment-load.sh 50 180 http://10.0.0.31:30080/api/payments

# 6. 观察
# - Grafana P99 飙升至 3s+
# - Jaeger: debit span ≈ 2000ms

# 7. 恢复
kubectl patch configmap bank-mall-config -n bank-mall --type='merge' -p='{
  "data": {"ACCOUNT_CHAOS_DEBIT_DELAY_MS": "0"}
}'
kubectl rollout restart deployment/account-service -n bank-mall
kubectl rollout status deployment/account-service -n bank-mall

# 8. 恢复 HPA
kubectl apply -f infra/kubernetes/base/hpa/account-service-hpa.yaml

# 9. 恢复验证 + DB 清理
# 10. 写复盘 docs/s4-postmortem-03-slow-call.md
```

### Step 8：Day 3 下午 — 复盘整合

```bash
# 1. 整合三份复盘
# docs/s4-postmortem-01-oomkilled.md
# docs/s4-postmortem-02-networkpolicy-misconfig.md
# docs/s4-postmortem-03-slow-call.md

# 2. 写 SLO 对比报告 docs/s4-slo-comparison-report.md

# 3. 提交 Git
git add docs/
git commit -m "[CHAOS] S4: fault injection postmortems + SLO comparison report

Three scenarios:
1. OOMKilled — JVM heap exhaustion under load
2. NetworkPolicy misconfiguration — payment→account traffic blocked
3. Slow call tracing — 2s debit delay + Jaeger root cause analysis

Includes: baseline metrics, MTTR, business impact quantification, SLO deviation"

# 4. Push
git push origin $(git branch --show-current)
```

---

## ⚠️ 关键注意事项

1. **镜像构建**：`docker build` 的 build context 必须是 `apps/`，不是 `apps/account-service/`
2. **DB 清理**：每个场景结束后必须执行 DB 清理，否则基线漂移
3. **HPA**：慢调用场景必须先关闭 HPA，否则请求分散到 3 Pod 排队效应减弱
4. **NetworkPolicy**：用 `allow-services-ingress-minus-payment.yaml` 替换原规则，恢复时 apply 回原文件
5. **ConfigMap**：Spring Boot 无热加载，改 ConfigMap 后必须 `rollout restart`
6. **时间**：排期 24h（3 天），包含 2h 截图/文档缓冲

---

## 📁 文件清单（本机已创建/修改）

```
✅ apps/account-service/src/main/java/.../AccountService.java      (修改)
✅ apps/account-service/src/main/resources/application.yml         (修改)
✅ tests/payment-load.sh                                         (新建)
✅ tests/k6/payment-load.js                                      (新建)
✅ infra/kubernetes/base/security/allow-services-ingress-minus-payment.yaml (新建)
✅ docs/s4-execution-plan-evaluated.md                           (修改，v3.0)
✅ docs/s4-实施交接清单.md                                        (本文件，新建)
```
