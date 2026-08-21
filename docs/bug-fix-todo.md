# Bug 修复清单 (Bug Fix Todo)

> 范围：**仅可复现的代码错误行为**。架构/性能优化见 [optimization-roadmap.md](optimization-roadmap.md)；基础设施配置类问题见本文档[附录](#附录-基础设施配置类非代码-bug)。
> 状态：`[ ]` 待修 / `[~]` 修复中 / `[x]` 已修复（修复后回填 commit）。
> 来源标注：历史审计 / WSL 会话 / Windows 会话。

---

## P0 — 资金安全 / 数据一致性（优先修）

### P0-1 支付冲正必失败（传错 originalTransactionNo）`[x]`

- **位置**：`apps/payment-service/src/main/java/com/bank/payment/service/PaymentService.java:146`
- **现象**：`reverseWithRetry` 把 `"debit-"+idempotencyKey` 当 `originalTransactionNo` 传给 account-service。account 侧 `AccountService.reverse()` 用 `txnRepo.findById()` 按**交易号主键**（`TXN+时间戳+纳秒` 格式，`Transaction.java:11-13`）查找 → `"debit-<key>"` 永远查不到 → 冲正必失败 → 扣款成功 + 入账失败的支付全部落 `ERROR_MANUAL_REVIEW`，需人工介入
- **根因**：`AccountClient.reverse(String accountNo, String originalTxnNo, ...)` 的参数语义是"原交易号"，payment 却传了幂等 key 前缀字符串
- **修法**：`processPayment` 捕获 `debitResp` 的真实 `transactionNo`（第 92 行已有 `debitResp.getTransactionNo()`，被丢弃），改 `reverseWithRetry` 方法签名接收真实交易号并传给 `accountClient.reverse`
- **测试影响（关键）**：`PaymentServiceTest.java:82` mock `when(accountClient.reverse(eq("A1001"), eq("debit-KEY-002"), eq("KEY-002")))` **固化了错误行为**，修复后此断言必挂。必须重写为传真实交易号（如 `TXN...`），并补"冲正使用 debit 真实交易号"的断言
- **验收点**：mock 冲正调用参数 = `debitResp.getTransactionNo()`；`processPayment_creditFails_reverseSucceeds` 测试绿
- **来源**：历史审计 CODE-04 / WSL 会话 / Windows 会话
- **状态**：✅ **已修复** `c1be4e9`（PR #47）

### P0-2 失败支付仍发成功通知 `[x]`

- **位置**：`PaymentService.java:127-133`
- **现象**：通知调用在 catch 之外、不检查支付 status，`FAILED` / `ERROR_MANUAL_REVIEW` 的支付照样发送 `PAYMENT_SUCCESS` 模板——用户收到"交易成功"而实际失败
- **根因**：通知逻辑与支付结果状态解耦，无条件发成功模板
- **修法**：按 `payment.getStatus()` 选择模板——仅 `COMPLETED` 发成功通知；失败状态发 `PAYMENT_FAILED` 或跳过
- **测试影响**：`PaymentServiceTest` 补断言：FAILED 状态不调用/不发送成功通知
- **验收点**：FAILED 支付的通知模板 ≠ `PAYMENT_SUCCESS`
- **来源**：Windows 会话独有发现
- **状态**：✅ **已修复** `c1be4e9`（PR #47）

---

## P1 — 功能错误

### P1-1 幂等并发 race 直出 500 `[x]`

- **位置**：`PaymentService.java:59-72`（幂等检查）→ `:85`（`paymentRepo.save(payment)` 在 try 块**外**）
- **现象**：同 `idempotencyKey` 两个并发请求同时通过 `findByIdempotencyKey` 检查 → 后写者 `save(payment)` 撞 `uk_idempotency` 唯一约束 → `DataIntegrityViolationException` 直出 500（应返回"已处理"语义）
- **根因**：检查-写入之间存在 TOCTOU 竞态；DB 唯一约束兜底了资金安全，但异常未映射为业务错误
- **修法**：捕获 `DataIntegrityViolationException` 映射为 `ErrorCode.PAYMENT_ALREADY_PROCESSED`（HTTP 409），或把初始 save 纳入幂等重查逻辑
- **测试影响**：补并发/重复提交测试
- **验收点**：同 key 二次提交返回 409 而非 500
- **来源**：Windows 会话独有发现
- **状态**：✅ **已修复** `c1be4e9`（PR #47），新增 2 个 race 测试用例

### P1-2 NotificationResponse 字段语义错位 `[降级]`

- **位置**：`apps/notification-service/src/main/java/com/bank/notification/dto/NotificationResponse.java:18-19`
- **复查结论（2026-08-21）**：**非可复现 bug**。请求-响应自洽：`channel`→实体 `type`→响应 `channel`、`template`→实体 `title`→响应 `template`，映射正确还原。这属于 **DTO 与实体字段命名不一致**（`channel`/`template` vs `type`/`title`），直接查 DB 的人会困惑，但 API 行为正确
- **处理**：从"代码 bug"降级为**命名重构项**，移入 `optimization-roadmap.md` P2 规划（若重构，注意保持 API 响应字段名 `channel`/`template` 不变，避免破坏调用方）
- **来源**：WSL 会话（初次误判为 bug）

### P1-3 登录限流失效（Ingress 后全同 IP）`[ ]`

- **位置**：`apps/auth-service/src/main/java/com/bank/auth/controller/AuthController.java:51`（`request.getRemoteAddr()`）、`apps/auth-service/src/main/java/com/bank/auth/service/LoginRateLimiter.java`
- **现象**：① 集群经 Ingress 代理，`getRemoteAddr()` 全是 Ingress Pod IP → **所有用户共享一个限流 key**（10 次/60s 全集群用），暴力破解防护形同虚设；② 限流器内存态，多副本各自独立；③ `store` 只在访问时惰性驱逐，被刷的 IP 永不清理 → 内存无限膨胀
- **根因**：限流 key 取客户端 IP 的方式在代理后失效 + 无过期清理机制
- **修法**：① 改用 `X-Forwarded-For` 首个非代理 IP（配可信代理白名单，防伪造）；② 加账号级失败计数与临时锁定（`AUTH_FAILED` 时累加）；③ `store` 定时清理过期窗口（如 `@Scheduled`）
- **测试影响**：`AuthControllerTest` 限流用例需同步（mock header 而非 RemoteAddr）
- **验收点**：不同 XFF IP 独立计数；同一 IP 超限被拒；内存不随刷 IP 增长
- **来源**：Windows 会话 / WSL 会话

### P1-4 k6 压测路径错（从未跑通）`[ ]`

- **位置**：`tests/k6/payment-load.js:47`
- **现象**：请求 `${BASE_URL}/api/payments`，但 Ingress 前缀是 `/payment`（`ingress-rules.yaml:36-38`）→ 压测请求全部 404，**该脚本从未真正跑通**
- **根因**：路径未带 Ingress 前缀
- **修法**：改为 `${BASE_URL}/payment/api/payments`（对照 `tests/payment-load.sh` 的正确 URL）
- **测试影响**：无（脚本类）
- **验收点**：脚本对部署环境跑通（200/非 404）
- **来源**：历史审计 DOC-003/OPS-008 / WSL 会话

---

## P2 — 小问题 / 死代码

### P2-1 死代码：PaymentTransactionRepository 未使用方法 `[ ]`

- **位置**：`apps/payment-service/src/main/java/com/bank/payment/repository/PaymentTransactionRepository.java:8`（`findByPaymentIdOrderByCreatedAtAsc`）
- **现象**：方法无任何调用方（grep 确认）
- **修法**：删除，或补使用（如查询支付的交易流水）
- **验收点**：编译通过，无死方法告警
- **来源**：WSL 会话

### P2-2 db-seed-accounts.sh ROW_COUNT 误报 `[ ]`

- **位置**：`scripts/db-seed-accounts.sh:52-64`
- **现象**：`ON DUPLICATE KEY UPDATE` 的 `ROW_COUNT()` 语义：插入=1、更新=2、**值未变=0**。脚本只处理 1/2，值不变（balance 已是 100000.00）时走 else 误报 FAIL
- **根因**：对 `ROW_COUNT()` 语义理解不全
- **修法**：0 视为已存在（成功分支），或改用 `SELECT COUNT(*)` 预判
- **验收点**：重跑脚本全 PASS
- **来源**：WSL 会话

### P2-3 verify.sh Jaeger 残留 + typo `[ ]`

- **位置**：`scripts/verify.sh:94-106`
- **现象**：整段仍在检查 `/jaeger/` Ingress 和 NodePort 31686 `/jaeger/` 路径（Tempo 迁移后应为 `/tempo`）；`:102` 有字符串 typo `"In  gress broken"`（双空格）
- **修法**：改为 Tempo 引用（`/tempo` + `tempo-query`），修 typo
- **验收点**：verify 脚本对 Tempo 部署返回预期
- **来源**：WSL 会话

---

## 附录：基础设施配置类（非代码 bug，仅提示）

> 已详细记录于 [optimization-roadmap.md](optimization-roadmap.md)，此处只列指针避免上下文过长。

| # | 问题 | 关键位置 |
|---|------|----------|
| A-1 | Tempo 端口错乱：Ingress ExternalName 转发 16686，Tempo pod 监听 3200 → `/tempo` 链路断 | `infra/kubernetes/base/ingress/tempo-external-svc.yaml` |
| A-2 | Tempo 三处部署入口全漏（deploy.sh / kustomization / ArgoCD exclude）→ trace 部署不上 | `scripts/deploy.sh`、`base/kustomization.yaml`、`bank-mall-apps.yaml:16` |
| A-3 | promtail 读 docker 路径但集群是 containerd → Loki 日志采集为空 | `monitoring/promtail-daemonset.yaml:65-71` |
| A-4 | Grafana 告警 webhook 指向 localhost:9999 无服务监听；admin 密码 key 缺失回落 admin/admin | `grafana-configmap.yaml:46-51`、`grafana-deployment.yaml:38-43` |
| A-5 | 镜像 tag 三套断裂（GH sha / git describe / 手写 2.0.0）→ ArgoCD 拉取与 CI 产物对不上 | `ci.yml`、`scripts/ci.sh`、`base/*/deployment.yaml` |
| A-6 | deploy.sh 部署断裂：引用不存在的 `mysql/secret.yaml` + initdb 密码占位符 | `scripts/deploy.sh:16`、`base/mysql/initdb-configmap.yaml:21` |
| A-7 | HPA max=3 在 2-worker 集群形同虚设；仅 CPU 指标 | `base/hpa/*.yaml` |
| A-8 | 反亲和缺失、MySQL/Ingress 单点无 PDB | `base/*/deployment.yaml`、`security/pdb.yaml` |
| A-9 | 监控组件版本陈旧：Loki 2.9.12(EOL) / Grafana 10.4 / Prometheus 2.53 | `base/monitoring/*` |

---

## 建议修复顺序

1. **P0-1 冲正 bug**（含测试重写）→ 单独 PR
2. **P0-2 失败通知** → 可并入 P0-1（同文件 PaymentService）
3. **P1-1 幂等 race** → 同批（PaymentService 相关）
4. P1-2 / P1-3 / P1-4 → 各服务独立 PR
5. P2 三项 → 顺手清理
6. 附录 A-1~A-9 → 进入 optimization-roadmap.md 排期

> 修复后回填本表状态列 + commit 号。
