# S4 故障演练复盘汇总

> **日期**：2026-06-07
> **场景**：3 个云原生故障场景（1 个因技术限制删除）
> **结论**：V1 完成了 2/3 场景 + 1 项压测复盘

---

## 总览

| 指标 | 数值 |
|------|------|
| 计划场景 | 3 |
| 完成场景 | 2/3 |
| 删除场景 | 1（OOMKilled — SB 4.0.6 启动 ≥320Mi） |
| 压测次数 | 5（100/200 并发 ×2 + 50 并发 ×3） |
| 产出的复盘文档 | 4 份 |

---

## 场景汇总

| # | 场景 | 故障注入方式 | 排障工具 | MTTR | 复盘 |
|---|------|-------------|---------|------|------|
| 1 | OOMKilled | 降低 memory limit → 4 轮全部失败 | — | — | 已删除（V2 混沌工程） |
| 2 | NetworkPolicy 误配 | 替换 ingress 白名单，移除 payment | `kubectl describe netpol` | ~5min | `s4-postmortem-02-networkpolicy.md` |
| 3 | Jaeger 慢调用 | 冷启动 + 乐观锁竞争 → trace 未捕获慢 span | Jaeger UI | — | `s4-postmortem-03-jaeger-trace.md` |
| — | 100/200 压测 | 基准 50→100→200 并发 + HPA 扩容 | `kubectl top` + `kubectl get hpa` | — | `s4-load-test-postmortem.md` |

---

## 压测数据摘要

| 并发 | 总请求 | 成功 | 失败 | 成功率 | 关键发现 |
|------|--------|------|------|:---:|------|
| 50（基线） | — | — | — | ~95% | 基线通过 |
| 100 | 5644 | 358 | 5286 | 6.3% | 冷启动死亡螺旋，503 占 93% |
| 200 | 2713 | 2268 | 445 | 83.6% | JIT 预热后大幅改善 |

---

## NetworkPolicy 误配数据

| 步骤 | 时间 | 操作 |
|------|------|------|
| 故障注入 | T+0 | `kubectl apply` minus-payment → Calico 2min 延迟生效 |
| 发现异常 | T+2min | 压测全 FAILED，`Connect timed out` |
| 确认 account 存活 | T+3min | `kubectl get pods` → 1/1 Running，但日志无 DEBIT 请求 |
| 定位根因 | T+4min | `kubectl describe netpol` → 白名单缺 `app: payment-service` |
| 恢复 | T+5min | `kubectl apply` 原版 → 压测 161/161 SUCCESS |

---

## 关键教训

1. **NetworkPolicy 排障三步法**：区分应用错误 vs 网络错误 → 确认目标存活 → `describe netpol` 查白名单
2. **Calico 传播延迟**：~2min 窗口期内部分请求成功、部分失败，比全失败更隐蔽
3. **冷启动是扩容最危险的时刻**：新 Pod 60s 启动期间老 Pod 被打爆 → 恶性循环 → 503 淹没问题
4. **JIT 预热差异可达 10×**：200 并发成功率比 100 并发高 13 倍，预热完全改变了吞吐

---

## 面试叙事建议

> S4 完成了 2 个真实故障场景的完整闭环——NetworkPolicy 误配和分布式追踪验证，加上 100/200 并发压测产生了 4 份深度复盘文档。OOMKilled 场景因为 Spring Boot 4.0.6 的最小内存门槛（~320Mi）被证明无法通过改 limit 复现——这个"失败"本身成了 V2 混沌工程规划的技术依据。
