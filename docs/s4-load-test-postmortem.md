# S4 压测复盘 — 100/200 并发 + HPA 扩容

> **日期**：2026-06-07 下午
> **环境**：VMware 4 节点 K8s（master01 + worker01/02 + harbor01）
> **工具**：`tests/payment-load.sh`（零依赖 curl 并发脚本）

---

## 压测结果

| 指标 | 100 并发 | 200 并发 |
|------|----------|----------|
| 总请求 | 5644 | 2713 |
| 成功 | 358 | 2268 |
| 失败 | 5286 | 445 |
| **成功率** | **6.3%** | **83.6%** |
| QPS | 18.8 | 9.0 |
| 耗时 | 325s | 328s |

---

## 失败分布

### 100 并发

| HTTP 状态码 | 数量 | 占比 | 含义 |
|-------------|------|------|------|
| 503 | 4926 | 93.2% | Ingress 找不到健康后端 |
| 502 | 291 | 5.5% | 上游连接中断 |
| 500 | 68 | 1.3% | 业务异常 |
| 504 | 1 | <0.1% | 网关超时 |

### 200 并发

| HTTP 状态码 | 数量 | 占比 |
|-------------|------|------|
| SUCCESS (200) | 2268 | 83.6% |
| 其他 | 445 | 16.4% |

---

## HPA 扩容行为

### 100 并发（扩容过程中 Pod 仍在冷启动）

| 服务 | CPU 峰值 | 最终副本 | 备注 |
|------|---------|---------|------|
| account-service | 235% | 3 | 扩容过程中旧 Pod 被打崩 |
| notification-service | 16% | 3 | 被动扩容（payment→notification 调用链） |
| payment-service | 39% | 3 | 首次扩容触发后稳住 |
| auth-service | 10% | 1 | 未触发（JWT 无状态，无 DB 压力） |

### 200 并发（JVM 已预热）

| 服务 | CPU 峰值 | 最终副本 |
|------|---------|---------|
| account-service | 13% | 3 |
| notification-service | **265%** | 3 |
| payment-service | 32% | 3 |
| auth-service | 13% | 1 |

---

## 根因分析

### 根因 1：冷启动死亡螺旋（最主要）

```
压测开始 → 100 并发打进来
    → payment-service CPU 飙升 → HPA 触发扩容（min=1 → target=2）
    → 新 Pod 启动 ~60s（JPA + Flyway + Hibernate validate）
    → 新 Pod 未 Ready，流量全打在老 Pod 上
    → 老 Pod 被打崩（CrashLoopBackOff）→ 更少健康后端
    → Ingress 返回 503（no healthy backend）
    → 更多并发请求重试 → 更多 503 → 恶性循环
```

**证据**：payment-service Pod `xq84b` 在 100 并发期间重启 14 次，最后一次在压测开始后 4m31s。

### 根因 2：JIT 预热差异

100 并发的 JVM 是冷的 → JIT 未编译热点代码 → 每请求耗时长 → CPU 容易打满。200 并发时 JVM 已被前一轮压测预热 → 处理速度更快 → 成功率反而更高。

### 根因 3：503 占比 93%（非业务错误）

这说明不是代码 bug，是 **K8s 调度层面的容量问题**：
- Service Endpoints 在 Pod 重启期间短暂为空
- Ingress Nginx 的 `upstream` 没有健康后端
- `initialDelaySeconds: 120` 的 liveness probe 让新 Pod 在 60s 启动完成后还要额外等 60s

---

## 教训

| # | 教训 | 生产对策 |
|---|------|---------|
| 1 | 冷 Pod 60s 启动是瓶颈 | 生产至少 2 副本常驻 + PDB，不让扩容从 1 开始 |
| 2 | liveness probe 过长延迟接入流量 | readiness probe 单独配置（5s period），与 liveness 解耦 |
| 3 | JIT 冷热差异巨大 | 生产用 `-XX:+TieredCompilation` + pre-warm health endpoint |
| 4 | HPA 从 1→2 是最危险的一步 | 设置 `minReplicas: 2` + PodDisruptionBudget |
| 5 | 503 淹没了真正业务错误 | 单独统计 Ingress 层 vs 应用层失败率 |

---

## 面试话术

**"HPA 扩容过程中发生了什么？"**
> HPA 根据 CPU 指标触发扩容，但新 Pod 从 Pending 到 Ready 需要 ~60 秒——JPA 实体扫描 + Flyway 迁移 + Hibernate 表校验。这 60 秒里流量全压在老 Pod 上。100 并发下老 Pod 直接被击穿，开始 CrashLoopBackOff。Ingress Nginx 发现没有健康后端，返回 503。这是一条非常典型的扩容延迟死亡螺旋，解决办法是常驻 min=2 副本 + readiness probe 解耦。

**"为什么 200 并发的成功率比 100 并发高？"**
> 这恰好验证了 JIT 预热的重要性。100 并发是冷启动——JVM 在解释执行模式，热点代码还没编译，单个请求处理时间更长，CPU 更容易打满。200 并发前 JIT 已经编译了热点——C2 编译后的 native code 比解释执行快 10-100 倍，所以反而 QPS 更稳。这也说明为什么 Java 服务要做启动预热，不能一启动就接全量流量。

**"这些 503 你怎么排查的？"**
> 先看 HTTP 状态码分布——93% 是 503，这就不是业务 bug 了，是 K8s 层面的问题。接着 `kubectl describe hpa` 看扩容事件，`kubectl get pods -w` 看 Pod 状态变化——发现扩出来的 Pod 在 `ContainerCreating` → `Running` 但 `READY 0/1`，readiness probe 还没通过。这段时间 Ingress 的 upstream 里没有这个 Pod，所有流量打老 Pod。老 Pod 重启次数从 2 飙升到 14，确认是容量问题而非代码问题。

---

**结论**：HPA 扩容有效（3/4 服务从 1→3），但冷启动 60s 窗口期 + minReplicas=1 导致容量不足。生产环境最小 2 副本 + readiness probe 5s period 可以彻底解决这个问题。
