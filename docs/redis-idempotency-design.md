# Redis 幂等方案设计文档

> S1 CP1 产出 | 2026-06-03 | 只写不实现，当前选 DB UNIQUE，生产建议 Redis

## 一、问题定义

支付场景中，网络重试、客户端重复提交、消息队列重投递都可能导致同一笔扣款请求被处理多次。幂等机制确保**同一笔请求只生效一次**，重复请求返回已有结果而不重复扣款。

## 二、方案对比

### 方案 A：DB UNIQUE 约束（当前方案 ✅）

account-service 的 Transaction 表已实现：

```sql
CREATE TABLE transactions (
    ...
    idempotency_key VARCHAR(128) DEFAULT NULL,
    UNIQUE KEY uk_idempotency (idempotency_key)
);
```

**工作流程**：
1. 调用方传入 `idempotencyKey`
2. AccountService 在 `checkIdempotency()` 中先查 `SELECT * FROM transactions WHERE idempotency_key = ?`
3. 如果存在 → 抛出 `DUPLICATE_IDEMPOTENCY_KEY`，调用方应返回已有结果
4. 如果不存在 → 执行业务操作，插入新 transaction（UNIQUE 约束兜底）

**优点**：
- 零额外依赖，不需要 Redis
- 与业务数据在同一个事务中（ACID 保证一致性）
- 幂等键和数据永不分离（删表即丢幂等，数据即真相）

**缺点**：
- 数据库压力：高并发下每次请求都查一次 DB
- 存储膨胀：幂等键通常保留 7-30 天即可，但 DB 里难以自动清理
- 跨服务幂等：如果 payment-service 也要幂等，需要各服务各自建表
- 性能上限：MySQL 单表写入 ~5000 TPS，幂等查询占用宝贵连接池

### 方案 B：Redis SETNX（生产推荐 🔵）

**工作流程**：
1. 调用方传入 `idempotencyKey`
2. 支付前：`SETNX payment:idempotency:{key} PENDING EX 86400`（24h 过期）
3. 如果返回 0 → 已有相同 key，查询状态返回已有结果
4. 如果返回 1 → 获得锁，执行业务，完成后更新值为 `COMPLETED` 或 `FAILED`
5. 24 小时后自动删除，不占用存储

**优点**：
- **高性能**：Redis 单机 ~10 万 TPS，远超数据库
- **自动过期**：`EX 86400` 自带 TTL，无需手动清理
- **跨服务共享**：payment、account、notification 共用一个 Redis 集群
- **状态可见**：`GET payment:idempotency:{key}` 可查询处理状态（PENDING / COMPLETED / FAILED）
- **与 DB 互补**：Redis 做热数据幂等（24h），DB UNIQUE 做冷数据兜底

**缺点**：
- 引入 Redis 运维成本（集群、主从、持久化）
- 双写一致性问题：Redis 写入成功但 DB 回滚时需补偿清理
- 极端场景：Redis 宕机丢失幂等键，需 DB UNIQUE 做兜底

### 方案对比表

| 维度 | DB UNIQUE（当前） | Redis SETNX（生产） |
|------|:---:|:---:|
| 性能（TPS） | ~5,000 | ~100,000 |
| 额外依赖 | 无 | Redis 集群 |
| 自动过期 | ❌ 需手动清理 | ✅ TTL 自动过期 |
| 跨服务共享 | ❌ 各自建表 | ✅ 共享 key 空间 |
| 事务一致性 | ✅ 与业务数据同事务 | ⚠️ 需补偿逻辑 |
| 运维复杂度 | ⭐ 低 | ⭐⭐⭐ 中 |
| 适合规模 | 开发/测试/低并发 | 生产高并发 |

## 三、结论

**当前选择 DB UNIQUE**（方案 A）。理由：
- S1 阶段为开发验证阶段，TPS < 10，DB UNIQUE 完全够用
- 不引入 Redis 避免增加运维复杂度（集群搭建、持久化配置、监控告警）
- 实现已经就位：`account-service` 的 `transactions.idempotency_key UNIQUE` + `AccountService.checkIdempotency()`

**生产环境建议走双通道**：
1. Redis SETNX 做热数据幂等（24h TTL），拦截 99% 的重复请求
2. DB UNIQUE 做冷数据兜底（永久保留），极端情况下 Redis 宕机仍有保护
3. 定期归档清理 transactions 表中的旧幂等键（>30 天）

## 四、Redis Key 设计（S2 或 V2 实施参考）

```
# 支付幂等
payment:idempotency:{idempotencyKey}  →  {status, transactionNo, createdAt}
TTL: 86400 (24h)

# 扣款幂等（account-service）
account:idempotency:{idempotencyKey}  →  {status, transactionNo, createdAt}
TTL: 86400

# 冲正幂等
reverse:idempotency:{idempotencyKey}  →  {status, transactionNo, createdAt}
TTL: 86400
```

## 五、面试话术

> "幂等机制我做了两层设计。当前 S1 阶段用数据库 UNIQUE 约束实现——Transaction 表的 idempotency_key 字段有唯一索引，插入重复 key 会直接报错。这个方案适合当前开发验证阶段的低并发场景，零额外依赖。
>
> 生产环境我会加一层 Redis SETNX。调用方传入幂等键，先 SETNX 到 Redis 带 24 小时 TTL，成功才执行业务逻辑。Redis 单机能扛 10 万 TPS，远超 MySQL。而且 Redis 的 key 可以跨服务共享——payment-service 和 account-service 共用同一个 Redis 集群做幂等，不用各自建表。
>
> 为什么不全用 Redis？因为 Redis 有数据丢失风险。如果 Redis 宕机，重启后 AOF 可能还未刷盘，这期间的幂等键就丢了。所以 DB UNIQUE 作为冷数据兜底一直保留——极端情况下 Redis 全丢，MySQL 的 UNIQUE 约束也不会让重复请求通过。这就是典型的缓存穿透防护模式。"

## 六、参考资料

- Redis 官方文档：[SETNX](https://redis.io/docs/latest/commands/setnx/)
- 阿里云 Redis 最佳实践：幂等设计模式
- Stripe API 设计：[Idempotency Keys](https://stripe.com/docs/api/idempotent_requests)（外部参考，Stripe 用 DB UNIQUE + 24h 过期策略）
