# S4 混沌工程复盘

> **日期**：2026-06-07~08 · **场景**：3 个计划场景，2 个完成闭环，1 个因技术限制删除

---

## 场景总览

| # | 场景 | 故障注入 | 排障工具 | MTTR | 结果 |
|---|------|---------|---------|------|:---:|
| 1 | OOMKilled | 降低 memory limit | — | — | ❌ 删除（V2 规划） |
| 2 | NetworkPolicy 误配 | 替换 ingress 白名单，移除 payment | `kubectl describe netpol` | 5min | ✅ |
| 3 | Jaeger 慢调用 | 冷启动 + 乐观锁竞争 | Jaeger UI | — | ⚠️ trace 在线，慢调用未捕获 |

---

## 100/200 并发压测

| 并发 | 总请求 | 成功 | 失败 | 成功率 | QPS |
|------|--------|------|------|:---:|------|
| 100 | 5644 | 358 | 5286 | 6.3% | 18.8 |
| 200 | 2713 | 2268 | 445 | 83.6% | 9.0 |

### 100 并发失败分布

| 状态码 | 数量 | 占比 | 含义 |
|--------|------|:---:|------|
| 503 | 4926 | 93.2% | Ingress 找不到健康后端 |
| 502 | 291 | 5.5% | 上游连接中断 |
| 500 | 68 | 1.3% | 业务异常 |
| 504 | 1 | <0.1% | 网关超时 |

### HPA 扩容行为

| 服务 | CPU 峰值 | 扩容后副本 | 备注 |
|------|---------|:---:|------|
| account-service | 235% | 3 | 首次扩容旧 Pod 被打崩 |
| notification-service | 265% | 3 | 被动扩容 |
| payment-service | 39% | 3 | 首次扩容触发后稳住 |
| auth-service | 13% | 1 | 无状态，未触发 |

### 根因：冷启动死亡螺旋

```
压测 → CPU 飙升 → HPA 触发扩容（1→2）
  → 新 Pod 启动 ~60s（JPA + Flyway + Hibernate validate）
  → 新 Pod 未 Ready，流量全打在老 Pod 上
  → 老 Pod 被打崩（14 次重启）→ Ingress 返回 503
  → 更多并发请求 → 更多 503 → 恶性循环
```

### JIT 预热效应

200 并发的成功率是 100 并发的 13 倍——JVM 在第一轮压测中编译了热点代码（C2 compiled native code 比解释执行快 10-100 倍）。

### 教训

- `minReplicas: 1` 是生产最危险的配置——冷启动 60s 窗口无容错
- Readiness probe 应与 liveness probe 解耦（5s period vs 120s initialDelay）
- JIT 预热差异可达一个数量级——Java 服务应该 `curl /health` 做 warmup 再接流量

---

## 场景 2：NetworkPolicy 误配

### 故障注入

`allow-services-ingress-minus-payment.yaml` 同名覆盖入站规则——ingress 白名单故意不给 `app: payment-service`。

### 排障链

```
1. 压测全 FAILED → 排除代码 bug
2. payment 日志: Connect timed out → account:8082 → account 在跑但不可达
3. kubectl get pods → account 1/1 Running → 但日志无 DEBIT 请求 → 流量被拦截
4. kubectl describe netpol → 白名单只有 auth/notification → payment 不在列 ✓ 根因
```

### 恢复

`kubectl apply -f` 原版 → Calico ~2min 传播 → 压测 161/161 SUCCESS。MTTR 5min。

### 关键发现

- Calico 规则传播有 ~2min 延迟窗口——部分请求成功、部分失败，比全失败更隐蔽
- NetworkPolicy 是并集（union），两条规则同时放行时，删一条不影响——同名覆盖更危险

---

## 场景 3：Jaeger 分布式追踪验证

### 三次崩溃修复历程

| 次 | 根因 | 修复 |
|:---:|------|------|
| 1 | liveness probe `path: /` → 404（`QUERY_BASE_PATH=/jaeger` 下无 `/` handler） | `path: /jaeger/` |
| 2 | PV `Retain` 策略 + hostPath 跨节点脏数据 | emptydir + `BADGER_EPHEMERAL=true` + nodeName pin |
| 3 | `kubectl patch` 临时修复被 Git 中旧 YAML 覆盖 | Git 文件 commit + `kubectl apply` 固化 |

### 最终状态

5 服务 OTEL trace 在线（含 `jaeger-all-in-one` 自身）。`JAVA_TOOL_OPTIONS: -javaagent:/otel/opentelemetry-javaagent.jar` initContainer → emptyDir 链路验证通过。

### 慢调用未捕获的边界

- 冷启动场景：所有请求在 account 就绪前被 Ingress 503，无 span 生成
- 10 并发乐观锁竞争：不足以产生 >100ms span
- V2 需 `@Profile("chaos")` 隔离控制器注入延迟

---

## 场景 1 删除说明

OOMKilled 场景经 4 轮尝试全部失败：

| 轮 | 服务 | Limit | Xmx | 结果 |
|:---:|------|------|-----|------|
| 1 | account | 128Mi | 128m | JVM 启动即退 |
| 2 | account | 256Mi | 128m | liveness 超时，0/1 循环 |
| 3 | account | 320Mi | 256m | 能启动，50 并发压不崩 |
| 4 | notification | 128Mi | 64m | 同轮 1 |

SB 4.0.6 + OTEL agent + JPA 启动最小可行内存 ~320Mi，与 LimitRange 强制最低 128Mi 之间存在死区。**V2 将引入 `@Profile("chaos")` 混沌控制器 + `ByteBuffer.allocateDirect()` 堆外攻击 + Heap dump 分析（MAT/jcmd），形成完整内存排障体系。**

---

**结论**：V1 完成 2/3 故障场景 + 1 项压测深度复盘。每个场景形成了可讲述的排障故事线。NetworkPolicy 案例是面试高价值素材。
