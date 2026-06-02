# 接口清单

本文档记录银行商城微服务底座 V1 的可运行接口。V1 已完成接口响应收口：所有业务接口和健康检查接口都返回统一 JSON 结构。

## 统一响应结构

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {},
  "timestamp": "2026-05-29T12:00:00Z"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `code` | 业务响应码 |
| `message` | 响应说明，便于 curl、日志和面试展示 |
| `data` | 业务数据；错误响应中可为 `null` |
| `timestamp` | ISO-8601 UTC 时间，由服务端生成 |

## 最小错误码

| Code | 含义 | 典型场景 |
| --- | --- | --- |
| `SUCCESS` | 请求成功 | 所有正常业务响应 |
| `BAD_REQUEST` | 参数错误 | 用户名、密码、金额、消息内容等缺失 |
| `AUTH_FAILED` | 认证失败 | 登录失败、token 无效 |
| `NOT_FOUND` | 资源不存在 | 查询不存在的用户 |

> V1 为学习和展示环境，业务错误暂不强制映射完整 HTTP 4xx/5xx 语义；重点是保证响应结构一致。

## Health 响应示例

```json
{
  "code": "SUCCESS",
  "message": "auth-service is healthy",
  "data": {
    "status": "UP",
    "service": "auth-service"
  },
  "timestamp": "2026-05-29T12:00:00Z"
}
```

## auth-service

基础路径：`/api/auth`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 使用演示账号登录并返回模拟 token |
| POST | `/api/auth/validate` | 校验模拟 bearer token |
| GET | `/api/auth/users/{userId}` | 查询演示用户资料 |
| GET | `/api/auth/health` | 服务健康检查 |

示例请求：

```bash
curl -X POST http://NODE_IP:30080/auth/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

成功响应中的 `data` 示例：

```json
{
  "token": "token-demo",
  "userId": "U1001",
  "username": "admin",
  "roles": ["ADMIN"],
  "issuedAt": "2026-05-29T12:00:00Z"
}
```

失败响应示例：

```json
{
  "code": "AUTH_FAILED",
  "message": "Invalid username or password",
  "data": null,
  "timestamp": "2026-05-29T12:00:00Z"
}
```

## account-service

基础路径：`/api/accounts`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/accounts/{accountId}` | 查询账户资料 |
| GET | `/api/accounts/{accountId}/balance` | 查询可用余额和冻结金额 |
| GET | `/api/accounts/{accountId}/transactions` | 查询最近交易记录 |
| GET | `/api/accounts/balance/{id}` | 保留原始案例中的兼容余额接口 |
| GET | `/api/accounts/health` | 服务健康检查 |

示例请求：

```bash
curl http://NODE_IP:30080/account/api/accounts/A1001/balance
```

成功响应中的 `data` 示例：

```json
{
  "accountId": "A1001",
  "availableBalance": 8888.88,
  "frozenAmount": 200.0,
  "currency": "CNY",
  "updatedAt": "2026-05-29T12:00:00"
}
```

## payment-service

基础路径：`/api/payments`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/payments` | 创建一笔演示商城支付单 |
| GET | `/api/payments/{paymentId}` | 查询支付结果 |
| POST | `/api/payments/transfer` | 保留原始案例中的兼容转账接口 |
| GET | `/api/payments/health` | 服务健康检查 |

示例请求：

```bash
curl -X POST http://NODE_IP:30080/payment/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORDER1001","payerAccount":"A1001","amount":299.00,"currency":"CNY"}'
```

成功响应中的 `data` 示例：

```json
{
  "paymentId": "PAY-demo",
  "orderId": "ORDER1001",
  "payerAccount": "A1001",
  "payeeAccount": "MALL-SETTLEMENT",
  "amount": 299.0,
  "currency": "CNY",
  "status": "SUCCESS",
  "paidAt": "2026-05-29T12:00:00Z"
}
```

## notification-service

基础路径：`/api/notifications`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/notifications` | 发送一条演示短信或邮件通知 |
| GET | `/api/notifications/templates` | 查询可用通知模板 |
| GET | `/api/notifications/notify?msg=...` | 保留原始案例中的兼容通知接口 |
| GET | `/api/notifications/health` | 服务健康检查 |

示例请求：

```bash
curl -X POST http://NODE_IP:30080/notification/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"channel":"SMS","receiver":"13800000000","template":"PAYMENT_SUCCESS"}'
```

成功响应中的 `data` 示例：

```json
{
  "notificationId": "MSG-demo",
  "channel": "SMS",
  "receiver": "13800000000",
  "template": "PAYMENT_SUCCESS",
  "content": "Your bank mall order has been paid successfully.",
  "status": "SENT",
  "sentAt": "2026-05-29T12:00:00Z"
}
```
