# Round 3 架构审计 — 主审架构师深度扫描

> **角色**：主审架构师 · **标准**：大厂核心业务线上标准 · **前提**：不接受"能跑就行"
> **前两轮**：Claude 11 条 + Kimi 3 条 = 14 条已合并。本轮聚焦**前两轮未覆盖的底层问题**。

---

## 一、新发现（本轮独有，8 条）

| # | 严重度 | 类别 | 问题 | 前两轮为什么漏了 |
|---|:---:|------|------|----------------|
| R3-1 | 🔴 | infra | **Ingress Jaeger 路由指向不存在的 Service**：`ingress-rules.yaml` 中 Jaeger backend 为 `name: jaeger-ui`，但集群中实际 Service 名为 `jaeger-query`。`/jaeger` 路径通过 Ingress 访问从 S2 起就是 503——从未通过。所有 Jaeger 访问都走 NodePort/port-forward | 扫太快——以为 Ingress 规则一定对。Kimi 建议给 Jaeger 加 Basic Auth，没发现它根本不通 |
| R3-2 | 🟡 | 代码 | **Transaction ID 生成存在并发冲突风险**：`generateTxnNo()` 用 `LocalDateTime.now().format(DateTimeFormatter)` 生成 `TXNyyyyMMddHHmmssSSS`（毫秒精度）。两个线程在同一毫秒、同一服务创建交易 → 同 ID → PK 冲突 → DataIntegrityViolationException。虽然有 `withRetry` 包住，第二次重试时时间戳不同可以过——但这不是设计意图，是碰巧修复。生产环境 qps > 1000 时碰撞概率显著 | 以为 `@Version` 乐观锁+retry 覆盖了所有冲突，没想到 PK 级别的碰撞 |
| R3-3 | 🟡 | 代码 | **AccountController `legacyBalance` 死代码**：`GET /api/accounts/balance/{id}` 端点直接委托到 `getBalance()`。这是 V1 mock 时期的向后兼容端点——account-service 在 S1 被重写后，没有调用方依赖此路径，但代码保留至今。违背了 YAGNI 原则 | 只有逐行读 Controller 才能发现——之前的审计重点在 Service 层 |
| R3-4 | 🟡 | 代码 | **AccountService.getTransactions() 无分页**：`findByAccountNoOrderByCreatedAtDesc` 返回此账户**全部**交易记录。当前数据库只有几千条记录没事，但如果 A1001 商户账户有 10 万笔流水 → 单次查询全量返回 → OOM。`List<TransactionResponse>` 没有 `Page` 包装 | 测试环境数据小，暴露不出。但这是银行商城——对账单查询必须有分页 |
| R3-5 | 🟡 | 代码 | **PaymentService.processPayment 幂等检查重复查询**：`findByIdempotencyKey`（行 60）+ `save`（行 85）之间 TOCTOU 窗口受 DB UNIQUE 保护——但异常处理不一致。`DataIntegrityViolationException` 被 `catch (Exception e)` 捕获 → 标记 `FAILED` 而非返回已有记录 idempotently。正确做法：**在 `GlobalExceptionHandler` 中捕获 `DataIntegrityViolationException` + unique index 冲突 → 判定为幂等重复 → 返回 200 而非 500** | 第 1 轮说"无 DB UNIQUE 约束"——两分钟后发现 **DDL 早就加了**。第 3 轮纠正：约束存在，只是异常处理方式不够幂等 |
| R3-6 | 🟡 | 代码 | **auth-service `DataInitializer` 只在 `@PostConstruct` 中执行一次**：启动时插入 admin/user01/testuser。但如果 DB 已有用户、或 Flyway 表被清空但 data 未清空、或 auth Pod 被 HPA 扩容后新 Pod 启动——新 Pod 的 `@PostConstruct` 再次执行 INSERT → `Duplicate entry` for UK username → DataIntegrityViolationException **被静默吞掉**（log 藏在一堆启动日志中）。DataInitializer 没有 `IF NOT EXISTS` 语义保证 | 前两轮都没读 DataInitializer——审计盲区 |
| R3-7 | 🟢 | 代码 | **NotifyController `templates()` 硬编码模板数据**：3 条通知模板（PAYMENT_SUCCESS/LOGIN_ALERT/ORDER_SHIPPED）以 `List.of(Map.of(...))` 形式硬写在 Controller 里。业务模板应该移到 ConfigMap 或 DB，避免"改一行模板文字需要重启 Pod" | 前两轮觉得"notification 是 mock"——但它实际上落地了 JPA 实体。模板硬编码是不一致的 |
| R3-8 | 🟢 | 文档 | **README 的架构 ASCII art 过期**：Ingress 路由表只展示了 4 个业务服务路径，漏掉 `/jaeger`。如果 Ingress 确有 Jaeger 路径但不工作（R3-1），文档就应该诚实标注"Jaeger：NodePort 31686 / port-forward" | 文档审查跳过了一个事实：那个 ASCII art 中 `All services → Jaeger` 是对的，但 Ingress 路径画错了 |

---

## 二、前两轮纠正

| 原发现 | 原判断 | 本轮纠正 |
|--------|--------|---------|
| #7 Claude "Payment idempotency 无 DB UNIQUE" | TOCTOU 无保护 | **错误**。DDL `V1__create_payment_table.sql` 明确有 `UNIQUE KEY uk_idempotency`。保护存在，只是异常处理方式不够幂等（R3-5） |
| Kimi INF-003 "initialDelaySeconds: 180" | 3 分钟无健康检测 | **错误**。实际值：account-service 120s，auth-service 30s。Kimi 看了错误的 YAML |

---

## 三、综合修复计划（前两轮 + 本轮）

### 阶段一：立即（P0，< 2h）

| # | 来源 | 项 | 方法 |
|---|------|------|------|
| 1 | R3-1 🔴 | Ingress Jaeger backend 修正 | `name: jaeger-ui` → `name: jaeger-query` |
| 2 | 两轮 | Swagger 注解补全 3 服务 | 参照 auth-service 模板 |
| 3 | 两轮 | JWT_SECRET 默认值移除 | 改为无默认值，未注入则启动失败 |
| 4 | Claude #2 | auth-service ApiResponse sync 注释 | +`// NOTE: Duplicated` |
| 5 | Claude #6 | CLAUDE.md 清除删除文件引用 | 删 `tests/k6/` `sql/` `libs/` `pipelines/` `SECURITY.md` 行 |

### 阶段二：本周（P1，< 10h）

| # | 来源 | 项 |
|---|------|------|
| 6 | R3-2 🟡 | Transaction ID 加纳秒精度 + `UUID.randomUUID()` fallback |
| 7 | R3-4 🟡 | getTransactions() 加分页参数 `Pageable` |
| 8 | R3-3 🟡 | 删除 `legacyBalance` 死代码 |
| 9 | R3-5 🟡 | PaymentService 幂等异常处理：`DataIntegrityViolationException` → 返回已有记录 |
| 10 | R3-6 🟡 | DataInitializer 全量改为 `INSERT ... ON DUPLICATE KEY UPDATE` |
| 11 | Kimi | RestClient 连接池化 |
| 12 | Kimi | MySQL SSL 参数加注释 `# mitm-safe: internal NAT network` |
| 13 | 两轮 | DTO Bean Validation |

### 阶段三：长期

| # | 来源 | 项 |
|---|------|------|
| 14 | R3-7 🟢 | 通知模板移到 ConfigMap |
| 15 | R3-8 🟢 | README 架构 ASCII art 更新，标注 Jaeger access 方式 |
| 16 | 两轮 | Repository 集成测试 |
| 17 | 两轮 | HPA minReplicas 1→2 |

---

## 总评

三轮审计从 14 条收敛到 17 条。**本轮最有价值的发现是 R3-1**（Jaeger Ingress 从 S2 起就没通过——因为 Service 名写错了）。这说明 Ingress 验证只跑过 4 个业务服务，从未测试过 `/jaeger` 路径。用一个从未通过的路径贴在 Ingress YAML 里摆了 3 周——这是"配置实际无效但没人发现"的经典反面教材。
