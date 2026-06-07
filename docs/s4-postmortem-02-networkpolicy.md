# S4 故障复盘 02 — NetworkPolicy 误配

> **日期**：2026-06-07 深夜
> **场景**：payment → account 跨服务调用被 NetworkPolicy 阻断
> **内核**：Calico CNI（IPIP 模式），NetworkPolicy deny-all + 白名单规则

---

## 故障注入前 — 基线

```
bank-mall namespace
  ├── bank-mall-deny-all         → 拒绝所有 Ingress + Egress
  ├── allow-dns-egress           → DNS 出站（kube-dns）
  ├── allow-services-egress      → 服务间出站（8081-8084）
  ├── allow-http-to-services     → 服务间入站（所有 app=*）
  ├── allow-services-to-mysql    → MySQL 入站（3306）
  └── allow-monitoring-to-services → Prometheus 入站

  payment ──── debit/credit ────→ account   ✅ 通
  payment ──── log ────────────→ notification ✅ 通
  auth ────── validate ────────→ account   ✅ 通
```

---

## 故障注入

**方法**：用 `allow-services-ingress-minus-payment.yaml` 替换入站规则，白名单**故意遗漏 `app: payment-service`**。

```bash
kubectl apply -f allow-services-ingress-minus-payment.yaml
```

**攻击机制**：

```
allow-services-ingress（minus 版）:
  入站白名单:
    ├── app: auth-service        ✅
    ├── app: notification-service ✅
    └── app: payment-service     ❌ 故意删除！

  payment → account:8082 → Calico iptables 规则拒绝 → TCP SYN 无响应 → Connect timed out
```

**关键设计**：
- 使用**同名覆盖**（`allow-services-ingress`），模拟运维手误编辑 YAML 漏删某行 → `kubectl apply`
- **精准攻击**：只断 payment→account，auth↔account、payment→notification、monitoring 全部正常
- 恢复时 apply 原版 `allow-services-ingress.yaml` 即可

---

## 排障流程

### Step 1：发现异常

```bash
bash tests/payment-load.sh 5 30
# 全 FAILED
```

### Step 2：看 payment 日志

```bash
kubectl logs -n bank-mall -l app=payment-service --since=5m | grep -v otel
```

```
ERROR Account service unreachable: USER003/debit →
  I/O error on POST request for
  "http://account-service:8082/api/accounts/USER003/debit":
  Connect timed out
```

**分析**：`Connect timed out` 而非 `Connection refused` —
说明 account-service **Pod 在运行但不可达**（不是 Pod 挂了）。指向网络层问题。

### Step 3：确认 account-service 活着

```bash
kubectl get pods -n bank-mall -l app=account-service
# 1/1 Running ✅
kubectl logs -n bank-mall -l app=account-service --since=5m | grep DEBIT
# 没有任何来自 payment 的请求 ← 流量根本没到！
```

**分析**：account 活着但没收到请求 → 请求被中间网络层拦截。下一步查 NetworkPolicy。

### Step 4：查 NetworkPolicy

```bash
kubectl get netpol -n bank-mall
```

输出：
```
allow-services-ingress    app.kubernetes.io/part-of=bank-mall    2m16s
```

```bash
kubectl describe netpol allow-services-ingress -n bank-mall
```

关键字段：
```
Allowing ingress traffic:
  From:
    PodSelector: app=auth-service          ←
    PodSelector: app=notification-service  ← 只有这两个
  To Port: 8081/TCP, 8082/TCP, 8083/TCP, 8084/TCP
```

**根因确认**：`app: payment-service` 不在入站白名单。所有来自 payment Pod 的 HTTP 请求被 Calico iptables 规则 DROP。

### 排障总结

| 步骤 | 命令 | 关键发现 |
|------|------|---------|
| 1 | 压测 | 全 FAILED |
| 2 | `kubectl logs` payment | `Connect timed out` — 网络层问题 |
| 3 | `kubectl get pods` account | 1/1 Running — 不是 Pod 挂了 |
| 4 | `kubectl describe netpol` | 白名单缺 `app: payment-service` |

---

## 恢复

```bash
kubectl apply -f allow-services-ingress.yaml
bash tests/payment-load.sh 5 30
# ✅ 161/161 SUCCESS
```

恢复时间：< 30 秒（apply → Calico 2-5s 传播 → `kubectl describe netpol` 确认 → 压测验证）。

---

## 面试话术

**"NetworkPolicy 误配你怎么排查？"**
> 先看现象。压测全失败，但 payment 日志不是代码错误，是 `Connect timed out` ——
这说明 account-service Pod 在运行但不可达。第二步确认 account Pod 状态：1/1 Running，
而且它的业务日志没有任何来自 payment 的请求 —— 流量根本没到。
这就是典型的网络层拦截。第三步 `kubectl get netpol` 列出所有策略，
`kubectl describe netpol` 看具体规则，发现入站白名单只有 auth-service 和 notification-service，
payment-service 被漏了。根因确认：运维在 apply 时少写了一行 `app: payment-service`。

**"deny-all 和白名单这个架构有什么坑？"**
> 最大坑是**策略并集叠加**。NetworkPolicy 是 union，多条规则同时作用。
如果你有一条规则放行了所有服务，再创建一条 minus-payment 规则，两条并集之后
payment 还是通的——因为老规则还在放行。这也是为什么用同名覆盖比新建更危险：
你在 YAML 里删了一行 `app: payment-service`，`kubectl apply` 直接替换，
不像 `kubectl delete + kubectl create` 那样有删规则的断流窗口让人察觉。

**"Calico 规则变更多久生效？"**
> 我们有观察到 ~2 分钟的延迟窗口。`kubectl apply` 后立刻压测，前几十个请求还是 SUCCESS，
然后才全部变成 FAILED。这是 Calico 把 Kubernetes NetworkPolicy 翻译成 iptables 规则
然后下发到各节点的时间。生产环境这个窗口可能导致一部分请求成功、一部分失败，
排查起来比全失败更隐蔽。

---

**结论**：NetworkPolicy deny-all + 白名单架构在安全和隔离上非常有效，
但故障排查需要三步走：区分应用错误 vs 网络错误 → 确认目标服务存活 →
`kubectl describe netpol` 找白名单缺口。误配窗口期（规则传播延迟）是生产监控盲区。
