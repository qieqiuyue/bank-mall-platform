# S4 实施方案综合评估与落地文档

> 评估人视角：PMP 认证 + 10 年大厂全栈架构经验
> 评估对象：OpenCode 生成的 S4 初版实施方案

---

## 一、整体评估摘要 (Executive Summary)

**亮点**：方案覆盖了压测、OOM、NetworkPolicy、慢调用四大经典云原生故障域；补偿逻辑与可观测性三位一体的观察体系设计到位；使用真实业务链路（payment→account）作为故障注入靶点，而非单纯杀掉 Pod，具备工程价值。

**核心硬伤**：① OOM 场景的 200Mi 内存限制过于激进，Spring Boot 4 + JPA + OTEL agent 在 200Mi 容器内大概率启动即 OOM，无法进入“运行中再被压爆”的预期状态；② NetworkPolicy 误配采用“删除共享 egress 规则中的 8082 端口”，误伤其他服务且恢复存在手动编辑 diff 风险；③ Jaeger 慢调用仅发 10 个请求，P99 不可能飙升到 3s+，未将慢调用与并发负载结合；④ 全方案缺少“DB 清理”环节，压测后数据库残留 3.6 万条 payment 记录，将污染后续故障场景的基线数据；⑤ 未显式处理「debit 已执行但响应丢失」的数据一致性边界条件。

---

## 二、深度评估报告 (Critique & Risk Analysis)

### [维度 1：可行性穿透]

| 问题 | 严重度 | 理由 |
|------|--------|------|
| OOM 场景 -Xmx150m + memory: 200Mi | 高 | Spring Boot 4.0.6 + JPA + Micrometer + OTEL agent 启动期 Metaspace + Heap + 线程栈开销通常 > 250Mi。设置 200Mi 容器 limit 后 JVM 甚至无法完成类加载即被 K8s OOMKill，无法观测到“服务运行中堆内存泄漏 → Heap alert → OOMKill”的正常时间线。 |
| NetworkPolicy 误配方式 | 中 | 从 allow-services-egress.yaml 中删除全局 8082 端口，会导致所有服务都无法访问 account-service:8082，误伤面过大。应改为创建一条仅针对 app=payment-service 到 app=account-service 的临时 deny 规则。 |
| Jaeger 慢调用样本量 | 中 | 10 次串行请求下 P99 ≈ P100 ≈ 2s，不可能出现“飙升至 3s+”。必须将慢调用与压测并发叠加（如 50 并发同时命中加了 2s sleep 的 debit），线程池饱和后排队延迟才会把 P99 推到 3s+。 |
| JMeter 单一 payerAccount | 中 | 5 个账户轮换仍可能在 200 并发下耗尽余额。方案未给出余额监控和自动补数据的策略。 |
| 压测数据污染 | 中 | 36,000 条 payment + transaction 记录会改变 account 表的 row count、索引深度、JPA 二级缓存命中率，后续场景的基线已被破坏。 |

**可行性修正建议**：
- OOM 采用「正常 limit + 应用层内存炸弹」或「256Mi limit + 压测触发」模式；
- NetworkPolicy 采用临时独立 deny 规则 + kubectl apply -f / kubectl delete -f 的显式注入/回滚；
- 慢调用场景必须与并发负载叠加；
- 每个场景结束后执行 DB truncate 或重建。

### [维度 2：隐性风险与反思]

| 风险项 | 说明 | Right-B（替代/兜底方案） |
|--------|------|-------------------------|
| **响应丢失导致的数据不一致** | account-service 执行 debit 成功后、返回响应前被 OOMKill 或网络中断。payment-service 收到 RestClient timeout，判定 debit 失败，不会触发 reverse。但 account 余额已被扣减，出现「资金已扣、payment 失败」的不一致状态。 | ① 在 debit 接口增加幂等查询能力（GET /api/accounts/{no}/transactions?referenceId=），payment 超时后主动查询确认；② 或增加对账任务（daily reconciliation）作为最终一致性兜底；③ OOM 场景下，恢复后手动 DB 校验 payment.status=FAILED 但 transaction 表中存在对应 DEBIT 记录。 |
| **GFW 导致 JMeter 无法下载/运行** | jmeter.apache.org 或插件下载可能被墙。 | Right-B：优先使用单二进制工具 k6（Golang 编写，单文件可运行），或手写 Python asyncio 脚本。本方案保留 JMeter 为主、附 k6 备用脚本。 |
| **HPA 扩容与 ResourceQuota 冲突** | 4 个服务 HPA maxReplicas=3，理论上可同时需要 12 Pod。但 ResourceQuota pods: 20 包含 MySQL、monitoring、jaeger 等，高负载下可能因 Quota 耗尽导致 HPA 无法扩容。 | 压测前执行 kubectl get resourcequota -n bank-mall，确认当前 usage 与 hard limit 的余量。若 pods 余量 < 6，先缩减非业务 Pod（如 monitoring 可临时缩到 1 副本）。 |
| **恢复后验证不足** | 方案仅写“重新发送支付请求确认”，未定义“确认到什么程度算恢复”。 | 定义恢复验证 Checklist：① kubectl get pods 全部 Ready；② 3 次支付请求全部 COMPLETED；③ Grafana P99 < 500ms 持续 2 分钟；④ payment_requests_total{status="FAILED"} 5 分钟内无增长。 |
| **Thread.sleep 代码污染** | 在 AccountService.java 中硬编码 Thread.sleep(2000) 并 commit 到 feature 分支，容易误合并到 main。 | 改为通过 application.yml 配置 account.chaos.debit-delay-ms，默认值 0。故障注入时通过 ConfigMap 覆盖为 2000，无需代码变更、无需重新打包镜像。 |
| **JVM Heap alert 与容器 OOM 的时序盲区** | Grafana 的 "High JVM Heap" alert 基于 jvm_memory_used_bytes / jvm_memory_max_bytes > 0.85，但 OOMKill 由 cgroup 的 memory.limit_in_bytes 触发，两者指标来源不同。存在「Heap 未达 85% 但容器 RSS 已超 limit」的盲区。 | 补充监控 container_memory_working_set_bytes / container_spec_memory_limit_bytes > 0.9 的容器级 alert，作为 OOM 的更早信号。 |

### [维度 3：资源与排期合理性]

**原方案排期：4 个场景 x 6h = 24h + 复盘 6h = 30h**

**问题诊断**：
1. **过于乐观**：未包含「DB 恢复/重建」「基线数据采集」「环境预热稳定化」时间。每个场景实际需：环境准备(0.5h) + 基线记录(0.5h) + 故障注入(0.5h) + 观察分析(1h) + 恢复验证(1h) + 文档输出(1.5h) + DB 清理(0.5h) = 5.5h，但各场景间还有上下文切换损耗。
2. **未考虑串行依赖**：压测后必须 DB 清理才能做下一个场景；OOM 场景后 Pod 可能需要多次重启才能稳定；NetworkPolicy 修改后需要等待规则生效（CNI 同步有延迟）。
3. **文档时间被低估**：每份复盘含截图、时间线、MTTR 计算、预防措施，熟练者也需 2h+。

**修正后排期建议**：

| 阶段 | 时长 | 说明 |
|------|------|------|
| Day 1 上午 | 3h | 环境检查 + DB 基线备份 + JMeter 脚本调试 + 50 并发基线 |
| Day 1 下午 | 4h | 100/200 并发压测 + HPA 观察 + 数据清理 |
| Day 2 上午 | 4h | OOMKilled 场景 + 数据一致性校验 + 复盘文档 |
| Day 2 下午 | 4h | NetworkPolicy 场景 + 复盘文档 |
| Day 3 上午 | 4h | 慢调用场景（叠加并发）+ 复盘文档 |
| Day 3 下午 | 3h | 三份复盘整合 + SLO 对比报告 |
| **总计** | **22h** | 更务实，含缓冲 |

---

## 三、整合优化版 S4 实施方案 (Optimized Execution Plan)

### 前置条件 (Pre-flight Checklist)

```bash
# 1. 集群健康
kubectl get pods -n bank-mall
kubectl get pods -n jaeger
kubectl get pods -n monitoring

# 2. 可观测性端点可达
curl http://10.0.0.31:30300/api/health   # Grafana
curl http://10.0.0.31:30090/-/healthy     # Prometheus
curl http://10.0.0.31:31686/jaeger        # Jaeger UI

# 3. DB 基线：确认测试账户余额充足
kubectl exec -it mysql-0 -n bank-mall -- mysql -ubankapp -p<password> bank_account -e \
  "SELECT account_no, balance, version FROM account WHERE account_no LIKE 'USER%';"

# 4. ResourceQuota 余量检查
kubectl get resourcequota -n bank-mall

# 5. 全量 DB 备份（mysqldump 到本地或 NFS）
kubectl exec mysql-0 -n bank-mall -- mysqldump -ubankapp -p<password> --all-databases > /tmp/bank-mall-baseline-$(date +%Y%m%d-%H%M).sql
```

> 关键约束：每个场景结束后必须执行「DB 清理」步骤，否则基线漂移。

---

### 场景 0：基线压测（Load Testing Baseline）

**目标**：获取 50/100/200 并发下的 QPS、P99 RT、HPA 扩容行为、错误率基线。所有后续故障场景均与此基线对比。

#### 0.1 压测工具选型

**主方案：JMeter**（功能丰富，适合生成可视化报告）

```bash
# 在能访问 10.0.0.31:30080 的节点上安装 JMeter
# 若网络受限，使用已下载的离线包
mkdir -p tests/jmeter && cd tests/jmeter
# 放置 jmeter 二进制或解压包到 tests/jmeter/apache-jmeter-5.6/
```

**Right-B 备选：k6**（单二进制、Go 编写、GFW 友好）

```bash
# 若 JMeter 无法获取，直接用 k6
# tests/k6/payment-load.js
```

#### 0.2 JMeter 测试计划 (tests/jmeter/payment-load-test.jmx)

关键参数：

| 参数 | 值 | 说明 |
|------|-----|------|
| 目标 URL | http://10.0.0.31:30080/api/payments | 通过 Ingress NodePort |
| HTTP 方法 | POST | |
| Content-Type | application/json | |
| Body | {"payerAccount":"${payer}","payeeAccount":"MALL-SETTLEMENT","amount":${amount},"idempotencyKey":"load-${__UUID()}"} | idempotencyKey 必须用 UUID 避免重复 |
| 并发梯度 | 50 → 100 → 200 | 每个梯度预热 30s，持续 180s |
| Ramp-Up | 10s / 20s / 30s | 逐步加压，避免冷启动冲击 |
| 数据文件 | tests/jmeter/accounts.csv | 10 个账户轮换，每账户余额 ≥ 50,000 |

**accounts.csv 格式**：

```csv
account_no,initial_balance
USER001,100000
USER002,100000
USER003,100000
USER004,100000
USER005,100000
USER006,100000
USER007,100000
USER008,100000
USER009,100000
USER010,100000
```

**压测脚本中的金额策略**：
- amount = ${__Random(1,5)}，确保 10 账户 × 100,000 余额可支撑 200 并发 × 180s × 平均 3元 ≈ 108,000 元总消耗
- 若余额不足，payment 返回 FAILED 状态码 200（业务失败），JMeter 需通过 JSON Assertion 区分 system_error vs insufficient_balance

#### 0.3 观察指标与基线记录

压测前必须记录以下基线指标（写入 tests/baseline-report.md）：

| 指标 | 记录方式 | 基线值（预期） |
|------|----------|---------------|
| QPS @ 50并发 | JMeter Summary | > 100 req/s |
| P99 RT @ 50并发 | Grafana dashboard "bank-mall-sli-slo" | < 200ms |
| QPS @ 100并发 | JMeter Summary | > 180 req/s |
| P99 RT @ 100并发 | Grafana | < 300ms |
| QPS @ 200并发 | JMeter Summary | > 250 req/s |
| P99 RT @ 200并发 | Grafana | < 500ms |
| HPA 扩容时间 | kubectl get hpa -w | 1→2 < 60s, 2→3 < 120s |
| 错误率 | JMeter Summary | < 0.1% |
| payment_requests_total{status=COMPLETED} | Prometheus | 占总请求 > 99% |

#### 0.4 压测后清理 (Mandatory)

```bash
# 清理 payment 和 transaction 数据，恢复账户余额
kubectl exec -it mysql-0 -n bank-mall -- mysql -ubankapp -p<password> -e "
USE bank_payment; TRUNCATE TABLE payment; TRUNCATE TABLE payment_transaction;
USE bank_account; UPDATE account SET balance = 100000, version = 1 WHERE account_no LIKE 'USER%';
"
```

---

### 场景 1：OOMKilled（6h → 优化后 4h）

#### 1.1 制造故障（优化：避免启动即 OOM）

**原方案问题**：200Mi 容器 limit 导致 JVM 启动即 OOM，无法观测到“运行中堆内存上升 → alert → OOMKill”的正常时序。

**优化方案**：保持 512Mi 容器 limit（正常值），通过降低 JVM 堆上限 + 压测触发 OOM：

```yaml
# 临时 patch account-service deployment
spec:
  containers:
    - name: account-service
      env:
        - name: JAVA_TOOL_OPTIONS
          value: "-javaagent:/otel/opentelemetry-javaagent.jar -Xmx280m"
```

解释：
- 容器 limit = 512Mi（不变）
- JVM max heap = 280m
- 压测并发请求触发大量对象创建（JPA entity、transaction 记录、RestClient 对象）
- JVM heap 迅速逼近 280m → GC 频繁 → 最终 Heap 耗尽 → OOM → K8s 杀掉容器
- 此时 Grafana "High JVM Heap" alert（> 85%）会先触发，形成「alert → OOMKill」的可观测时间线

应用 patch：

```bash
kubectl patch deployment account-service -n bank-mall --type='merge' -p='{"spec":{"template":{"spec":{"containers":[{"name":"account-service","env":[{"name":"JAVA_TOOL_OPTIONS","value":"-javaagent:/otel/opentelemetry-javaagent.jar -Xmx280m"}]}]}}}}'
```

#### 1.2 触发压力

用 JMeter 50 并发打 payment API 持续 5 分钟：

```bash
# 运行 JMeter 50 并发场景（reuse 基线压测脚本）
# 目标：让 account-service 处理大量 debit/credit 请求，JPA entity + transaction 对象迅速填充 280m heap
```

#### 1.3 观察要点（扩展：增加数据一致性校验）

| 时间线 | 预期现象 | 排查命令 |
|--------|----------|----------|
| T+0 ~ T+60s | account-service heap 快速上升至 85%+ | Grafana: jvm_memory_used_bytes{area="heap"} |
| T+60s ~ T+120s | Grafana "High JVM Heap" alert 触发（warning） | Grafana alerting UI |
| T+120s+ | account-service Pod 被 OOMKill，RESTARTS+1 | kubectl get pods -n bank-mall |
| T+120s+ | payment 请求大量 FAILED（debit 连接超时或断开） | kubectl logs -f deploy/payment-service |
| T+120s+ | payment_requests_total{status="FAILED"} 飙升 | Prometheus query |

**新增：数据一致性边界校验**：

OOMKill 发生在 debit 已执行但响应未返回的瞬间，会导致「资金已扣、payment 失败」的不一致。恢复后必须校验：

```bash
# 查询：payment 状态为 FAILED，但 account transaction 中存在对应 DEBIT 记录
kubectl exec -it mysql-0 -n bank-mall -- mysql -ubankapp -p<password> -e "
SELECT p.payment_no, p.status, p.fail_reason, p.amount,
       t.transaction_type, t.amount, t.reference_id
FROM bank_payment.payment p
LEFT JOIN bank_account.transaction t ON t.reference_id = p.idempotency_key
WHERE p.status = 'FAILED' AND t.transaction_type = 'DEBIT';
"
```

若查询返回记录，说明出现了不一致，需记录到复盘中。

#### 1.4 恢复

```bash
# 恢复 JVM 堆上限
kubectl patch deployment account-service -n bank-mall --type='merge' -p='{"spec":{"template":{"spec":{"containers":[{"name":"account-service","env":[{"name":"JAVA_TOOL_OPTIONS","value":"-javaagent:/otel/opentelemetry-javaagent.jar"}]}]}}}}'

# 等待 Pod 重启稳定
kubectl rollout status deployment/account-service -n bank-mall

# 执行恢复验证 Checklist
# ① 全部 Pod Ready
kubectl get pods -n bank-mall

# ② 3 次支付全部 COMPLETED
for i in 1 2 3; do
  curl -s -X POST http://10.0.0.31:30080/api/payments \
    -H "Content-Type: application/json" \
    -d "{\"payerAccount\":\"USER001\",\"payeeAccount\":\"MALL-SETTLEMENT\",\"amount\":1,\"idempotencyKey\":\"recover-$i\"}"
done

# ③ Grafana P99 < 500ms 持续 2 分钟（人工确认 dashboard）
# ④ payment_requests_total{status="FAILED"} 5 分钟内无增长
```

#### 1.5 DB 清理 (Mandatory)

同 0.4 清理步骤，恢复账户余额。

---

### 场景 2：NetworkPolicy 误配（6h → 优化后 4h）

#### 2.1 制造故障（优化：精准注入，不误伤）

**原方案问题**：从全局 egress 规则删除 8082，误伤所有服务。

**优化方案**：创建独立的临时 deny 规则，仅阻断 payment-service → account-service 的流量：

```yaml
# infra/kubernetes/base/security/chaos-deny-payment-to-account.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: chaos-deny-payment-to-account
  namespace: bank-mall
spec:
  podSelector:
    matchLabels:
      app: account-service
  policyTypes:
    - Ingress
  ingress: []
```

解释：
- 该规则仅作用于 account-service Pod（通过 label `app: account-service`）
- deny-all ingress → 任何来源都无法访问 account-service
- 比删除 egress 规则更精准：只影响 account-service 的入站，不影响其他服务访问 mysql/jaeger 等

注入故障：

```bash
kubectl apply -f infra/kubernetes/base/security/chaos-deny-payment-to-account.yaml
```

#### 2.2 验证故障生效

```bash
# 从 payment pod 内部直接 curl account-service
kubectl exec -it deploy/payment-service -n bank-mall -- \
  wget -qO- --timeout=5 http://account-service:8082/api/accounts/health || echo "CONNECTION REFUSED/TIMEOUT"

# 同时验证：从 auth-service pod 内 curl account-service 也应失败（验证 deny 的广泛性）
kubectl exec -it deploy/auth-service -n bank-mall -- \
  wget -qO- --timeout=5 http://account-service:8082/api/accounts/health || echo "DENIED"
```

#### 2.3 触发业务故障

发送正常支付请求：

```bash
curl -X POST http://10.0.0.31:30080/api/payments \
  -H "Content-Type: application/json" \
  -d '{"payerAccount":"USER001","payeeAccount":"MALL-SETTLEMENT","amount":10}'
```

**预期行为链**：
1. payment → account:8082 被 NetworkPolicy 拒绝（连接无法建立）
2. RestClient connectTimeout 2s 内连接失败
3. PaymentService 捕获异常，debit 失败
4. 无需补偿（debit 未成功）
5. payment 状态 = FAILED，failReason 包含连接错误信息

#### 2.4 观察要点

| 观察 | 方法 | 预期 |
|------|------|------|
| NetworkPolicy 规则确认 | kubectl get networkpolicy chaos-deny-payment-to-account -n bank-mall -o yaml | 规则存在，ingress: [] |
| payment 调用失败日志 | kubectl logs -f deploy/payment-service -n bank-mall | 搜索 "debit" 或 "ConnectException" |
| payment_requests_total | Prometheus: payment_requests_total{status="FAILED"} | 飙升 |
| Jaeger trace | Jaeger UI 搜索最近 payment trace | debit span 缺失或显示连接失败 |
| Pod 状态 | kubectl get pods -n bank-mall | 全部 Running，但支付持续失败 |

**关键洞察**：全部 Pod Running 但支付持续失败 → 这是 NetworkPolicy 误配的典型特征（与 OOMKill 的 Pod 重启不同）。

#### 2.5 恢复

```bash
# 删除临时 deny 规则（比手动编辑原文件更安全）
kubectl delete -f infra/kubernetes/base/security/chaos-deny-payment-to-account.yaml

# 验证恢复：payment pod 内可正常访问 account-service
kubectl exec -it deploy/payment-service -n bank-mall -- \
  wget -qO- --timeout=5 http://account-service:8082/api/accounts/health

# 执行恢复验证 Checklist（同场景 1.4）
```

#### 2.6 DB 清理 (Mandatory)

同 0.4 清理步骤。

---

### 场景 3：Jaeger 定位慢调用（6h → 优化后 4h）

#### 3.1 制造故障（优化：无需代码变更，ConfigMap 注入）

**原方案问题**：硬编码 Thread.sleep(2000) 在 Java 代码中，需要重新打包镜像，且容易误合并到 main。

**优化方案**：通过 Spring Boot 外部化配置 + ConfigMap 动态注入延迟。

**Step 1：先在 account-service 代码中增加可配置延迟（仅一次代码变更，后续通过配置切换）**：

在 `AccountService.java` 的 debit 方法中：

```java
@Value("${account.chaos.debit-delay-ms:0}")
private long debitDelayMs;

public Account debit(...) {
    if (debitDelayMs > 0) {
        try { Thread.sleep(debitDelayMs); } catch (InterruptedException ignored) {}
    }
    // ... 正常扣款逻辑
}
```

在 `application.yml` 中：

```yaml
account:
  chaos:
    debit-delay-ms: 0
```

**Step 2：通过 ConfigMap 覆盖配置（无需重新打包）**：

```bash
# 修改 ConfigMap 增加延迟配置
kubectl patch configmap bank-mall-config -n bank-mall --type='merge' -p='{"data":{"ACCOUNT_CHAOS_DEBIT_DELAY_MS":"2000"}}'

# 触发 account-service 滚动重启以加载新配置
kubectl rollout restart deployment/account-service -n bank-mall
kubectl rollout status deployment/account-service -n bank-mall
```

> 注意：Spring Boot 原生不支持 ConfigMap 热加载（除非使用 Spring Cloud Kubernetes 或 RefreshScope）。本项目无 Spring Cloud，因此必须重启 Pod。重启时间约 60-80s（SB 4.0.6 + JPA 启动时间）。

#### 3.2 触发并观察（优化：叠加并发负载）

**原方案问题**：仅发 10 个串行请求，P99 不可能飙升到 3s+。

**优化方案**：将慢调用与 JMeter 50 并发叠加，让线程池饱和后排队延迟叠加：

```bash
# 运行 JMeter 50 并发场景（reuse 基线压测脚本）
# 每笔请求都会触发 account-service debit 方法中的 2000ms sleep
# 50 并发同时到达 → Tomcat 线程池（max 200）被占用 → 新请求排队 → 总延迟 = 2s sleep + 排队时间 + 网络开销
```

#### 3.3 观察要点

| 观察 | 预期 |
|------|------|
| **Jaeger Trace** | 搜索 service=account-service，找到 debit span 耗时 ≈ 2000ms+ |
| **Grafana P99** | payment P99 latency 从 <200ms（基线）飙到 3s+（50 并发叠加 2s sleep + 排队） |
| **Prometheus** | rate(http_server_requests_seconds_bucket{le="5",uri="/api/accounts/*/debit"}[5m]) 飙升 |
| **Grafana alert** | "High CPU Usage" 可能触发（线程繁忙导致 CPU 使用率上升）；"High JVM Heap" 不触发 |
| **RestClient timeout** | 每次 debit 调用耗时 ≥ 2s，在 3s readTimeout 范围内；但若排队严重，部分请求可能超时 |
| **Payment 状态** | 大部分 COMPLETED（ debit 成功只是慢），但少量可能因排队 + sleep > 3s 而进入 FAILED/补偿 |

**关键排查路径**：
1. Grafana dashboard 看到 P99 飙升至 3s+ → 打开 Jaeger
2. Jaeger 按 service=account-service 搜索，按 duration 降序排列
3. 找到最慢的 trace，展开 span 列表
4. 定位到 debit span 耗时 2s，确认是 account-service 的瓶颈

#### 3.4 恢复

```bash
# 移除延迟配置
kubectl patch configmap bank-mall-config -n bank-mall --type='merge' -p='{"data":{"ACCOUNT_CHAOS_DEBIT_DELAY_MS":"0"}}'

# 触发滚动重启
kubectl rollout restart deployment/account-service -n bank-mall
kubectl rollout status deployment/account-service -n bank-mall

# 执行恢复验证 Checklist
# 重跑 JMeter 50 并发，确认 P99 恢复基线值
```

#### 3.5 DB 清理 (Mandatory)

同 0.4 清理步骤。

---

### 场景 4：复盘文档 x 3

每个场景各写一份复盘，保存在 docs/ 目录：

```
docs/s4-postmortem-01-oomkilled.md
docs/s4-postmortem-02-networkpolicy-misconfig.md
docs/s4-postmortem-03-slow-call.md
```

#### 复盘模板（每个场景填写）

```markdown
# 故障演练 X：[场景名]

## 故障现象
- 时间线（故障注入时刻 → 观测到异常时刻 → 恢复时刻）
- 业务影响（支付失败率、QPS 变化、用户侧体验）
- Grafana/Jaeger 截图

## 根因分析
- 直接原因（如：内存限制 200Mi < JVM 实际需要 350Mi）
- 为什么监控没有更早发现
- 补偿/容错机制是否按预期工作

## 修复步骤
1. 具体操作命令
2. 恢复验证结果

## MTTR
| 阶段 | 耗时 |
|------|------|
| 发现故障 | Xm |
| 定位根因 | Xm |
| 执行修复 | Xm |
| 验证恢复 | Xm |
| **合计** | **Xm** |

## 预防措施
- 基础设施层（如：调整 memory limit、添加 OOM alert）
- 应用层（如：补偿重试降级策略）
- 监控层（如：新增 alert rule）

## 关键截图
- Grafana 告警截图
- Loki 日志截图
- Jaeger Trace 截图
```

#### 新增：SLO 对比报告（所有场景共用）

在第三份复盘之后，附加一份 SLO 对比报告：

```markdown
# S4 SLO 对比报告

## 基线 SLO（压测场景）
| SLO | 目标 | 实际 |
|-----|------|------|
| 可用性 | > 99.5% | ? |
| P99 Latency | < 500ms | ? |
| 错误率 | < 0.1% | ? |
| 支付成功率 | > 99.9% | ? |

## 故障场景 SLO 偏离
| 场景 | 可用性 | P99 | 错误率 | 支付成功率 | 偏离分析 |
|------|--------|-----|--------|------------|----------|
| OOMKilled | ? | ? | ? | ? | ? |
| NetworkPolicy | ? | ? | ? | ? | ? |
| 慢调用 | ? | ? | ? | ? | ? |

## 结论与建议
```

---

### 文件清单（需创建/修改）

| 文件 | 操作 | 说明 |
|------|------|------|
| tests/jmeter/payment-load-test.jmx | 新建 | JMeter 测试计划 |
| tests/jmeter/accounts.csv | 新建 | 压测数据（10 账户轮换） |
| tests/k6/payment-load.js | 新建 | k6 备用脚本 |
| tests/baseline-report.md | 新建 | 基线指标记录 |
| apps/account-service/.../AccountService.java | 修改 | 增加 account.chaos.debit-delay-ms 配置 |
| apps/account-service/.../application.yml | 修改 | 增加 account.chaos.debit-delay-ms 默认值 0 |
| infra/kubernetes/base/security/chaos-deny-payment-to-account.yaml | 新建 | 临时 NetworkPolicy deny 规则 |
| docs/s4-postmortem-01-oomkilled.md | 新建 | OOM 复盘 |
| docs/s4-postmortem-02-networkpolicy-misconfig.md | 新建 | NetworkPolicy 复盘 |
| docs/s4-postmortem-03-slow-call.md | 新建 | 慢调用复盘 |
| docs/s4-slo-comparison-report.md | 新建 | SLO 对比报告 |

---

### 执行顺序

```
Day 1 上午：环境检查 + DB 备份 + JMeter 调试 + 50 并发基线记录
Day 1 下午：100/200 并发压测 + HPA 观察 + 数据清理 + baseline-report.md
Day 2 上午：OOMKilled 场景 + 数据一致性校验 + postmortem-01
Day 2 下午：NetworkPolicy 场景 + postmortem-02
Day 3 上午：慢调用场景（叠加 50 并发）+ postmortem-03
Day 3 下午：三份复盘整合 + SLO 对比报告
```

---

> **版本**：v2.0（红蓝对抗评审后修正版）
> **评估日期**：2026-06-04 / **红蓝对抗**：2026-06-05
> **下次评审**：S4 完成后对照本方案进行偏差分析

---

## 四、🔴 红蓝对抗评审（Adversarial Review）

> 评审人：Claude Code（红队视角）。8 个攻击点逐一穿透方案，不为通过而放水。

---

### 🔴 A1：代码变更 = 全 CI 链路触发

**攻击**：方案"仅一次代码变更"加 `account.chaos.debit-delay-ms`。但 Java 代码变更 = `mvn package` → `docker build` → `docker push Harbor` → worker `ctr images pull` → `kubectl apply` → rollout restart。此链路至少 30 分钟，且在 harbor01 上需拉 Maven 依赖、打镜像。

**影响**：S4 还没开始就要先走一遍 CI。如果当前分支改 Java 代码，GitHub Actions test job 也会触发。

**修正**：代码变更提前推到分支、镜像提前打好（S4 前一天完成）。S4 当天只操作 ConfigMap + restart，不碰代码。或者用 `@ConditionalOnProperty` 包一层，默认禁用。

---

### 🔴 A2：NetworkPolicy deny-all ingress 误伤健康检查

**攻击**：方案规则 `ingress: []`（拒绝所有入站）会同时阻断：
- Ingress Controller → account-service（外部请求全断）
- monitoring → account-service（Prometheus scrape 失败，Grafana 看不到指标）
- kubelet → account-service（liveness/readiness probe 失败 → Pod 被杀重启）

故障注入 30 秒后 account-service Pod 会被 kubelet 杀掉。OOM 场景和 NetworkPolicy 场景**混在一起**——分不清 Pod 重启是 OOM 还是 probe 失败。

**修正**：精准阻断，只 deny `app: payment-service`：

```yaml
ingress:
- from:
  - podSelector:
      matchLabels:
        app: payment-service
```

---

### 🔴 A3：`-Xmx280m` 不保证优雅 OOM

**攻击**：JVM `-Xmx280m` 限制堆，但容器 RSS = heap + Metaspace + 线程栈 × 线程数 + OTEL agent + Native memory。Spring Boot + Tomcat 200 线程的 Metaspace 约 80-100MB，线程栈 × 200 ≈ 200MB。总 RSS 轻松超 512Mi。

但在 OOM 之前，GC 可能先疯狂回收 → Pod 假死（Running 但无响应）→ liveness probe 超时 → Kubelet 杀 Pod。Grafana alert 可能先触发 "High CPU" 而非 "High JVM Heap"。

**修正**：降低 heap 到 `-Xmx200m` + 压测 100 并发，让堆更快填满。同时监控 GC 频率（`jvm_gc_pause_seconds_count`）作为更早的预警信号。

---

### 🔴 A4：JMeter 离线包不存在

**攻击**：方案说"若网络受限，使用已下载的离线包"，但 `tests/jmeter/` 目录里没有 `apache-jmeter-5.6.tgz`。harbor01 从 `jmeter.apache.org` 下载会被 GFW 阻断。k6 备用脚本也没写。

**修正**：S4 前 Windows 下载 JMeter 二进制 + scp 到 harbor01。或用 `curl` + `xargs` + `seq` 手写并发脚本（零依赖）：

```bash
seq 50 | xargs -P50 -I{} curl -s -X POST http://10.0.0.31:30080/api/payments \
  -H "Content-Type: application/json" \
  -d '{"payerAccount":"USER00'$((RANDOM%10+1))'","amount":1,"idempotencyKey":"load-'$(uuidgen)'"}'
```

---

### 🔴 A5：DB 清理命令 Pod 名是错的

**攻击**：方案写 `mysql-0`（StatefulSet 命名），但实际 MySQL 是 Deployment，Pod 名每次部署都变（如 `mysql-694849ffd-mwnrl`）。

**修正**：全部改用 label selector：

```bash
kubectl exec -it deploy/mysql -n bank-mall -- mysql ...
```

---

### 🔴 A6：SLO 面板 PromQL 是否真实可用？

**攻击**：方案引用 "Grafana dashboard bank-mall-sli-slo"，S2 产物存在。但 `payment_requests_total` 是 Counter 类型，成功率计算需 `rate()`。如果面板 PromQL 用了 bare counter，值毫无意义。

**修正**：S4 前打开 Grafana（`http://10.0.0.31:30300`），确认 SLI/SLO 面板 4 个指标（可用性、P99、错误率、成功率）都有真实数据。

---

### 🟡 A7：22h 排期没算截图时间

**攻击**：每场景要截图 Grafana/Jaeger/Loki 放进复盘。本地 VMware 里截图需要打开浏览器 → 登录 → 截图 → 传到 WSL2 → 提交 Git。每场景至少 20 分钟，3 场景 = 1h 未计入。

**修正**：用 Grafana 渲染 API 直接导出 PNG：

```bash
curl "http://admin:admin@10.0.0.31:30300/render/d-solo/bank-mall-business?orgId=1&panelId=2&width=1200&height=600" > panel.png
```

---

### 🟡 A8：数据一致性校验 SQL 有列名错误

**攻击**：方案 SQL 第 239 行 `LEFT JOIN bank_account.transaction t`，但实际表名是 `transactions`（复数，Flyway 建表名）。且 `reference_id` 存的是关联交易号（如 `debit-KEY-001`），不是 `idempotency_key`。

**修正**：

```sql
SELECT p.payment_no, p.status, p.fail_reason,
       t.type, t.amount
FROM bank_payment.payments p
LEFT JOIN bank_account.transactions t ON t.idempotency_key = p.idempotency_key
WHERE p.status = 'FAILED' AND t.type = 'DEBIT';
```

---

### 红蓝对抗结论

| 维度 | 原评分 | 修正后 | 关键修复 |
|------|:---:|:---:|------|
| 技术可行性 | 7/10 | 8/10 | A1 提前打镜像、A4 JMeter 备用方案、A5 SQL 修正 |
| 安全性（不破坏集群） | 6/10 | 9/10 | A2 NetworkPolicy 精准阻断、A3 OOM 方式调整 |
| 可恢复性 | 8/10 | 9/10 | A2 probe 不受影响 → Pod 不会被误杀 |
| 面试杀伤力 | 9/10 | 9/10 | 无变化——3 个场景 + SLO 对比仍然是顶级素材 |

**总评**：8 个攻击点中 6 个有明确修正方案（A1-A6），2 个需要提前验证（A7-A8）。修正后方案可执行。
