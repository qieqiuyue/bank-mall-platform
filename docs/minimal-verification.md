# V1 最小部署验证

本文档用于验证 `bank-mall-cloudnative` 的最小工程闭环：Ingress 能把请求转发到 4 个核心服务，服务返回统一响应结构。它不替代完整验收清单；Prometheus、Grafana、Loki、HPA、NetworkPolicy 仍按 `docs/26-final-verification-checklist.md` 验证。

## 验证前提

- `bank-mall` 命名空间存在。
- 4 个业务服务 Pod 为 `Ready`。
- 4 个业务 Service 存在。
- Ingress Controller 正常运行，NodePort `30080` 可访问。
- 执行节点已安装 `curl` 和 `jq`。

基础检查：

```bash
kubectl get pods -n bank-mall
kubectl get svc -n bank-mall
kubectl get ingress -n bank-mall
kubectl get svc -n ingress-nginx ingress-nginx-controller
```

## 一条命令验证

默认验证 `http://10.0.0.41:30080`：

```bash
chmod +x scripts/smoke-test.sh
./scripts/smoke-test.sh
```

如需切换节点或端口：

```bash
NODE_IP=10.0.0.42 NODE_PORT=30080 ./scripts/smoke-test.sh
```

## 验证内容

脚本会按顺序验证：

| 步骤 | 请求 | 通过标准 |
| --- | --- | --- |
| auth health | `GET /auth/api/auth/health` | HTTP 2xx，`code=SUCCESS` |
| account balance | `GET /account/api/accounts/A1001/balance` | HTTP 2xx，`code=SUCCESS` |
| payment create | `POST /payment/api/payments` | HTTP 2xx，`code=SUCCESS` |
| notification send | `POST /notification/api/notifications` | HTTP 2xx，`code=SUCCESS` |

通过输出示例：

```text
===[ Bank Mall V1 smoke test ]===
Base URL: http://10.0.0.41:30080

[PASS] auth health: auth-service is healthy
[PASS] account balance: OK
[PASS] payment create: Payment created
[PASS] notification send: Notification sent

[PASS] V1 minimal Ingress smoke test completed
```

## 手工 curl 兜底

如果脚本失败，可逐条执行：

```bash
curl -s http://10.0.0.41:30080/auth/api/auth/health | jq .
curl -s http://10.0.0.41:30080/account/api/accounts/A1001/balance | jq .
curl -s -X POST http://10.0.0.41:30080/payment/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORDER-SMOKE-001","payerAccount":"A1001","amount":299.00,"currency":"CNY"}' | jq .
curl -s -X POST http://10.0.0.41:30080/notification/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"channel":"SMS","receiver":"13800000000","template":"PAYMENT_SUCCESS"}' | jq .
```

## 面试表达

可以这样描述：

> V1 不追求继续堆新服务，而是先把 4 个核心服务的接口契约、健康检查和 Ingress 验证闭环收口。部署后我用 `scripts/smoke-test.sh` 一条命令验证 Ingress 到服务的最小链路，所有接口都返回统一的 `code/message/data/timestamp` 结构。
