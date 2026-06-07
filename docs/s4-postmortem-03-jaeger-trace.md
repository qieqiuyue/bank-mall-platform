# S4 故障复盘 03 — Jaeger 分布式追踪验证

> **日期**：2026-06-07 / 2026-06-08
> **场景**：Jaeger 恢复 + 全栈 trace 在线验证
> **内核**：Jaeger all-in-one 1.60 + Badger emptydir + OTEL Java Agent 2.28.1

---

## 前置故障：Jaeger 三次崩溃修复

### 崩溃历史

| 次数 | 现象 | 根因 |
|------|------|------|
| 1 | CrashLoopBackOff, 702 restarts | liveness probe `path: /` → 404（`QUERY_BASE_PATH=/jaeger` 下无 `/` handler） |
| 2 | 修复后 Pod Pending | PV `Retain` 策略，PVC 无法绑定新 PV |
| 3 | 修复后端口连接拒绝 | worker02 hostPath `/data/jaeger-badger/` 残留脏数据，Badger 打开表后崩溃 |

### 最终方案

```yaml
# 关键变更：emptydir 替代 hostPath PVC
BADGER_EPHEMERAL: "true"
BADGER_DIRECTORY_VALUE: /tmp/jaeger/data
BADGER_DIRECTORY_KEY: /tmp/jaeger/key
livenessProbe.httpGet.path: /jaeger/   # 而非 /
nodeName: k8s-worker01                  # 固定调度
```

**理由**：V1 是实验集群，trace 数据不需要持久化。emptydir 随 Pod 生命周期自动清理，从根源消除 hostPath 脏数据问题。生产环境若需持久化 trace，建议用 S3 backend 或 Elasticsearch，而非 Badger + hostPath。

---

## 验证结果

### Jaeger API

```
http://localhost:16686/jaeger/api/services
→ 5 服务在线:
  account-service
  auth-service
  jaeger-all-in-one
  notification-service
  payment-service
```

### 端到端 Smoke Test

```
[PASS] auth health: auth-service is healthy
[PASS] account balance: OK
[PASS] payment create: Payment processed
[PASS] notification list: OK
[PASS] Smoke test completed
```

### OTEL 注入验证

Jaeger 中每个服务 span 包含完整 OTEL 元数据：

```json
{
  "serviceName": "payment-service",
  "telemetry.distro.name": "opentelemetry-java-instrumentation",
  "telemetry.distro.version": "2.28.1",
  "telemetry.sdk.language": "java",
  "telemetry.sdk.version": "1.62.0"
}
```

证明 initContainer → emptyDir → `JAVA_TOOL_OPTIONS: -javaagent:/otel/opentelemetry-javaagent.jar` 链路工作正常。

---

## 慢调用 trace 未复现的原因

尝试了两种方式触发跨服务慢调用，均未产生预期效果：

| 方法 | 结果 | 原因 |
|------|------|------|
| account 冷启动 + 立即压测 | 全部 FAILED，trace 无 payment→account span | account 未就绪时 Ingress 不转发，payment 从未收到请求 |
| 高并发乐观锁竞争 | 无 >100ms span | 10 并发不够触发严重锁竞争，正常 debit 耗时 <10ms |

### 技术分析

1. **冷启动窗口不可观测**：Pod 从 `Terminating`→`Pending`→`ContainerCreating`→`Running`→`Ready` 各阶段中，只有在 `Ready` 后才能被 Service Endpoints 选中。冷启动期间的请求在 Ingress 层就被丢弃（503），从未到达 payment，无法生成 span。

2. **OTEL sampling 默认行为**：Jaeger 默认 sampling 策略可能丢弃短耗时 trace，导致 <100ms 的快速请求不可见。

3. **乐观锁竞争阈值**：`@Version` 冲突需要两个请求在 1ms 内同时更新同一行——10 并发很难触发。

### 边界记录

> 分布式追踪在 V1 中的价值是**基础设施可用性证明**（5 服务 OTEL 注入成功）和**运维排障能力**（三次 Jaeger 崩溃修复），而非跨服务慢调用 demo。真实生产环境的慢调用排查需要：持续高负载、sampling rate 调高、或主动注入延迟代码。V2 可在 `@Profile("chaos")` 下注入 `Thread.sleep`，形成可观测的时间线。

---

## 面试话术

**"Jaeger 跑起来了吗？"**
> 五服务 trace 在线。中间也踩了些坑：liveness probe 路径和 QUERY_BASE_PATH 不匹配导致 CrashLoopBackOff，hostPath PV 在 Recreate 策略下残留脏数据导致 Badger 打开表后崩溃。最后把存储切成了 emptydir——实验环境 trace 不需要持久化，Pod 重启丢了就丢了。

**"跨服务 trace 你看了吗？"**
> payment→account 的调用链在正常负载下 span 耗时 <10ms，OTEL 默认 sampling 可能不会保留每条 trace。真正触发慢调用需要注入延迟——V2 打算用 `@Profile("chaos")` 隔离的控制器来做，不在 V1 里混入测试代码。

**"Jaeger 在生产环境下你会怎么部署？"**
> 实验集群用 emptydir 就够了。生产环境如果 trace 量大，Jaeger all-in-one 扛不住——需要拆成 collector + query + ingester，后端切到 S3 或 Elasticsearch。而且 V1 是 NodePort 暴露 UI，生产应该走 Ingress 加基础认证。

---

**结论**：Jaeger 分布式追踪在 V1 中已完成**基础设施验证**（5 服务 + OTEL agent + 全链路 span 上报）。冷启动/慢调用场景因 Pod 就绪时序和 OTEL sampling 限制未能复现，已记录边界，V2 规划中补充。
