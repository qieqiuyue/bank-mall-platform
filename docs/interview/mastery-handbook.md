# 面试化用手册 (Interview Mastery Handbook)

> **用途**：金九银十求职前，把项目从"能跑"练到"能脱稿讲 80-90%"。目标岗位：全栈兼顾（Java 后端为主线，K8s/DevOps 为差异化加分）。
> **配套**：[interview-qa.md](interview-qa.md)（29 问）+ [interview-script.md](interview-script.md)（3/5/10 分钟话术）+ [resume-final.md](resume-final.md)（简历）。
> **本文档价值**：①校正过时信息（面试讲错版本=致命伤）②四大薄弱域高频问答卡 ③自测打分机制。

---

## 第 0 章：过时信息校正（必读！讲错=减分）

> 以下条目**面试文档里写过但已与代码不符**，口头回答以本表为准。

| # | 文档旧说法 | 代码现状 | 校正后口径 |
|---|-----------|---------|-----------|
| C-1 | Loki 2.9.12 + boltdb-shipper + schema v11 | **Loki 3.4.2 + TSDB + schema v13**（Loki 3 移除 boltdb-shipper） | "日志栈升级到 Loki 3.4，schema 同步改 TSDB——Loki 3 把 boltdb 索引换成了 TSDB" |
| C-2 | Jaeger 1.60 已落地，OTLP → Jaeger | **已迁移 Grafana Tempo**（jaeger/ 目录删除，tempo/ 部署，OTLP → tempo-collector:4317） | "追踪用 Tempo——Jaeger 1.60 2025-12 EOL，迁移到 Tempo 与 Grafana LGTM 栈统一" |
| C-3 | HPA min=2/max=3 仅 CPU | **min=1/max=2 + CPU 70% + 内存 80%**（对齐 2-worker） | "HPA min=1/max=2——之前 max=3 在 2 节点集群没有调度位；加了内存指标因为 payment 是 IO 型" |
| C-4 | 探针 `/api/<service>/health` | **`/actuator/health/liveness` + `/actuator/health/readiness`**（审计整改） | "探针指向 Spring Boot Actuator 的 liveness/readiness 分离端点" |
| C-5 | Grafana 10.4 + 告警 webhook localhost:9999 | **Grafana 11.5.2**；webhook 改 `alert-bridge.monitoring` 占位（待配真实端点） | "告警链路是 provisioning as code，webhook 端点待接入真实通知" |
| C-6 | Prometheus v2.53 | **v3.2.1** | "Prometheus 升级到 v3" |
| C-7 | 镜像 tag 手写 2.0.0，三套断裂 | **ci.sh 有真实 sed 回写**（构建后用 VERSION 替换 deployment YAML） | "镜像 tag 由 ci.sh 回写到清单，ArgoCD 拉取与 CI 产物一致" |

---

## 第 1 章：叙事主线（8 个故事线，按推荐顺序）

> 面试时项目讲解的骨架。每个故事线 = 背景 → 方案 → 踩坑 → 结果，30-60 秒讲完。

| # | 故事线 | 体现能力 | 关键素材 |
|---|--------|---------|---------|
| S-1 | **支付冲正 bug 从发现到修复**（P0-1） | 分布式一致性 + 测试工程 | `PaymentService.java:146`（传错 originalTransactionNo → 改用 debitResp.getTransactionNo()）；`PaymentServiceTest.java:82`（测试固化了错误行为，重写） |
| S-2 | **幂等并发 race 的 DB 兜底**（P1-1） | 并发安全 + 兜底思维 | `PaymentService.java:85`（save 撞 uk_idempotency → 捕获映射 409）；DB UNIQUE 是资金安全的最终防线 |
| S-3 | **失败支付不发成功通知**（P0-2） | 业务正确性 | `PaymentService.java:129`（仅 COMPLETED 发 PAYMENT_SUCCESS） |
| S-4 | **登录限流背后的代理 IP 问题**（P1-3） | 安全 + 网络 | `AuthController.getClientIp()`（XFF 取末尾，Ingress 后 RemoteAddr 全是 Pod IP）；账号级锁定 5 次/15 分钟 |
| S-5 | **Tempo 迁移 + 三处部署入口全漏**（A-1/A-2） | 迁移工程 + 排查 | Jaeger 1.60 EOL → Tempo；deploy.sh/kustomization/ArgoCD 三处都漏 tempo → 补全 + namespace.yaml |
| S-6 | **deploy.sh 部署断裂**（A-6） | 运维脚本可靠性 | 引用不存在的 gitignored secret.yaml → 改 SealedSecret + 存在性检查；initdb 字面占位符 → env 注入 |
| S-7 | **Loki 升级的 boltdb→TSDB 迁移**（A-9） | 版本升级踩坑 | Loki 2.9→3.4 移除 boltdb-shipper，schema v11→v13 + tsdb_shipper |
| S-8 | **Trivy 双门禁 + GFW 适配**（Q26） | CI/CD 设计取舍 | GHA 硬门（exit-code 1）vs ci.sh 软门（\|\| true）；NJU mirror 12s 拉 95MiB DB |

---

## 第 2 章：分布式一致性问答卡（薄弱域 1）

> 自测：🔴 讲不了 / 🟡 看提示能讲 / 🟢 脱稿。填到 §6 自测表。

### D-1 你的支付链路怎么做分布式事务？（最高频）
- **30 秒回答**：不用 2PC/Seata，用**补偿 Saga**。payment 编排：先调 account debit → 再 credit → 通知。credit 失败且 debit 已成功 → reverse 冲正（3 次指数退避 50/200/800ms）；冲正也失败 → 标 `ERROR_MANUAL_REVIEW` 转人工。这契合银行对账模型——日终清算发现"有扣款无入账"再冲正。
- **追问防线**：为什么不用 Seata AT？→ AT 要 undo_log 表 + TC 协调器，架构重；支付链路没有库存扣减那种强一致要求。追问"如果 debit 成功后服务崩溃呢？"→ 支付落 `PENDING`，V2 规划 outbox + reconciliation job 扫 PENDING。
- **项目佐证**：`PaymentService.java` processPayment + reverseWithRetry；`docs/redis-idempotency-design.md`

### D-2 幂等怎么做的？为什么用 DB UNIQUE 不用 Redis？（高频）
- **30 秒**：请求带 `idempotencyKey`，先查库——terminal 状态直接返回，in-flight 抛 `PAYMENT_ALREADY_PROCESSED`。账户侧幂等 key 前缀化（`debit-`/`credit-`/`reverse-`）。**最终防线是 DB 唯一约束 `uk_idempotency`**——并发穿透检查时后写者撞约束，捕获映射 409。
- **追问防线**：并发下两个请求同时通过检查？→ DB UNIQUE 兜底（TOCTOU 竞态，资金安全靠约束不靠代码）。为什么不用 Redis SETNX？→ V2 设计文档里有，Redis 拦 99% 重复 + DB 兜底，但 V1 用 DB 约束已够。
- **项目佐证**：`PaymentService.java:59-72,85-99`；`transactions`/`payments` 表 `uk_idempotency`

### D-3 乐观锁怎么用的？冲突怎么处理？
- **30 秒**：account 实体 `@Version` 字段，JPA 更新时带 version 条件，冲突抛 `ObjectOptimisticLockingFailureException` → 3 次重试 + 指数退避（20/80/320ms）。
- **追问防线**：为什么不用悲观锁？→ 乐观锁适合读多写少的账户场景，不加锁不阻塞。结算账户热点？→ 所有支付 credit 到同一 `MALL-SETTLEMENT`，冲突率随并发陡增，V2 规划结算账户分片。
- **项目佐证**：`Account.java:27-28` `@Version`；`AccountService.java:194-215` withRetry

### D-4 冲正逻辑有什么坑？（体现真实做过）
- **30 秒**：V1 的 reverseWithRetry 把 `"debit-"+idempotencyKey` 当 originalTransactionNo 传——account 按交易号主键 `TXN...` 查找，永远查不到，冲正必失败。**测试还固化了错误行为**（mock `debit-KEY-002`）。修复：传 debit 响应的真实 transactionNo + 重写测试断言。
- **追问防线**：为什么测试会固化错误？→ mock 层把"期望的参数"当契约，没穿透到 account 侧按主键查的实现。这个教训：单元测试要断言业务语义不是 mock 细节。
- **项目佐证**：`PaymentService.java:146`（旧）→ `debitResp.getTransactionNo()`；`PaymentServiceTest.java:82`

### D-5 数据一致性最终怎么兜底？
- **30 秒**：三层：①应用层幂等检查 + 乐观锁 ②DB UNIQUE 约束 + 外键 ③补偿 Saga + `ERROR_MANUAL_REVIEW` 人工介入。**任何一层穿透，下面一层兜住**。
- **追问防线**：为什么不用事务消息/本地消息表？→ V1 无 MQ，同步 REST + 补偿已覆盖业务闭环；V2 规划 outbox 表。

---

## 第 3 章：K8s/运维问答卡（薄弱域 2）

### K-1 集群拓扑和为什么这么搭？
- **30 秒**：4 节点 VMware（1 master + 2 worker + 1 Harbor），kubeadm init + Calico CNI + containerd 运行时。V1 实验集群单控制面；V2 设计 3 master + Keepalived VIP。
- **追问防线**：为什么 2 worker？→ 教学资源约束，但暴露了 HPA max=3 无调度位的真实问题（→ C-3）。master 不可调度？→ taint 默认。
- **项目佐证**：`README.md` 拓扑 + `scripts/post-boot.sh`

### K-2 NetworkPolicy 零信任怎么设计？
- **30 秒**：deny-all 默认拒绝（ingress+egress 双阻断）+ 白名单 allow-*.yaml：ingress-nginx→4 服务、4 服务→MySQL:3306、服务间、monitoring、DNS、Tempo OTLP。**只有白名单内的流量能通**。
- **追问防线**：混沌实验故意删 payment 的白名单 → 全 Pod Running 但业务不通（`Connect timed out`），MTTR 5 分钟。为什么 Pod 健康但流量不通？→ 网络层拦截，不是应用层问题。
- **项目佐证**：`security/deny-all.yaml` + 8 个 allow-*；`docs/chaos-engineering-postmortem.md`

### K-3 HPA 怎么设计？为什么 min=1/max=2？
- **30 秒**：CPU 70% + 内存 80% 双指标，min=1/max=2。**max=3 曾导致问题**：2 节点集群没有第 3 个调度位，扩到 3 形同虚设；payment 是 IO 型，纯 CPU 指标从不触发。
- **追问防线**：HPA 冷启动死亡螺旋？→ 100 并发压测 93% 503——HPA 触发扩容但新 Pod 启动 60s，流量全打老 Pod → 恶性循环。教训：冷启动窗口最危险，min 副本 + PDB 是基础。
- **项目佐证**：`hpa/*.yaml`（4 个，min1/max2/双指标）；`docs/chaos-engineering-postmortem.md`

### K-4 探针和资源限制怎么配？
- **30 秒**：探针用 Actuator：liveness `/actuator/health/liveness` + readiness `/actuator/health/readiness`（审计整改，原来探针指向业务端点且路径混乱）。resources：requests 100m/256Mi，limits 500m/512Mi（auth 1Gi）。
- **追问防线**：为什么不用业务端点做探针？→ 业务端点和 DB/依赖耦合，DB 挂了 readiness 应摘流量；liveness 和 readiness 分离——liveness 杀死重启，readiness 摘出 Service。启动慢怎么防误杀？→ startupProbe 30×10s=300s 窗口（AGENTS.md 协作约定）。
- **项目佐证**：`deployment.yaml` 探针 + resources；`AGENTS.md` 探针配置约定

### K-5 MySQL 单点怎么处理？
- **30 秒**：StatefulSet 单副本 + hostPath PV（nodeName=k8s-worker01），V1 实验环境单点。**已补 PDB minAvailable:1**——阻止 drain/节点维护静默摘掉数据库。V2 规划 nodeAffinity + StorageClass + 独立实例。
- **追问防线**：单点挂了怎么办？→ 备份脚本 mysqldump 到 /tmp（待改进：落持久存储 + 恢复演练）；PDB 阻止非自愿驱逐。
- **项目佐证**：`mysql/deployment.yaml`、`security/pdb-mysql.yaml`、`scripts/db-backup.sh`

---

## 第 4 章：可观测性问答卡（薄弱域 3）

### O-1 可观测性三件套怎么搭的？
- **30 秒**：Metrics=Prometheus + Micrometer（每服务 `*Metrics.java` 记 QPS/成功率/时长）；Logs=Loki + Promtail DaemonSet（containerd 日志，cri stage 解析）；Traces=Tempo + OTEL Java Agent（initContainer 注入，OTLP gRPC:4317）。
- **追问防线**：为什么 Tempo 不是 Jaeger？→ Jaeger 1.60 2025-12 EOL，迁 Tempo 与 Grafana LGTM 栈统一。promtail 怎么读 containerd 日志？→ /var/log/pods + cri stage（删过 docker 路径死挂载）。
- **项目佐证**：`monitoring/*.yaml`、`tempo/*.yaml`、`configmap.yaml:27`（OTLP endpoint）

### O-2 告警怎么设计的？通知链路？
- **30 秒**：Grafana Unified Alerting，3 条规则（service down 1m critical / high CPU 5m warning / high heap 85% 5m warning），provisioning as code（ConfigMap 挂载）。**webhook 端点待接真实通知**（原 localhost:9999 死端点已改占位）。
- **追问防线**：为什么 Grafana Alerting 不用 AlertManager？→ 零额外组件、统一 UI、Provisioning as Code；生产多 Prometheus 才需要 AlertManager 的 HA/去重。
- **项目佐证**：`grafana-configmap.yaml`（contact-points + 3 规则）

### O-3 分布式追踪怎么定位问题？（案例）
- **30 秒**：Jaeger（迁移前）慢调用 trace——payment 总耗时 5300ms 但业务逻辑 50ms，span tree 定位到 `AccountClient.debit` 占 5000ms，是 account 冷启动 JPA 初始化。不看代码不看日志，直接定位到微服务+方法。
- **追问防线**：Tempo 迁移后怎么用？→ Grafana datasource 指向 tempo-query；`/tempo` Ingress 已配 rewrite-target。
- **项目佐证**：`docs/chaos-engineering-postmortem.md` 慢调用案例

### O-4 JVM/性能指标看哪些？
- **30 秒**：JVM heap、GC pause（dashboard 有 panel）、HTTP p99、线程数。payment 有 `payment_duration_seconds` Timer。V2 规划 HeapDumpOnOutOfMemoryError + jcmd。
- **追问防线**：容器 OOM 和 JVM OOM 区别？→ 容器 limit 触发 OOMKilled（内核杀），JVM 堆满抛 OutOfMemoryError。V1 无 -Xmx 靠容器自适应（P2 待优化）。

---

## 第 5 章：CI/CD 与安全问答卡（薄弱域 4）

### CI-1 双 CI 路径为什么？门禁强度为什么不一样？
- **30 秒**：Path 1 GitHub Actions（gitleaks→semgrep→test→build+trivy**硬门**，仅 main）；Path 2 harbor01 ci.sh（真实 CD，推 Harbor→ArgoCD→verify，Trivy**软门**）。硬门在公网（不受 GFW 影响），软门在内网（GFW 挡 ghcr.io，DB 下载不稳定）。
- **追问防线**：为什么 GHA push:false？→ NJU mirror push 返回 500，定位为 PR 验证 + 硬门禁；真实 CD 走 ci.sh。
- **项目佐证**：`ci.yml` + `scripts/ci.sh`（Stage 1-6）

### CI-2 ArgoCD GitOps 怎么工作？
- **30 秒**：3 个 Application CR 分拆（bank-mall-apps 服务+MySQL / bank-mall-monitoring / bank-mall-infra ingress+security+hpa+configmap+secret），auto-sync + selfHeal。**selfHeal 3 分钟回滚 kubectl set/edit**——线上改动必须走 git。
- **追问防线**：为什么分 3 个 App？→ 避免单 App 越界同步；职责分离。为什么 Kustomize 不是 Helm？→ 一套 base + 少量 overlay，Kustomize 加法足够；Helm 多租户才值。
- **项目佐证**：`argocd/applications/bank-mall-apps.yaml`

### CI-3 安全扫描三轴是什么？
- **30 秒**：Gitleaks（密钥泄漏）+ Semgrep（SAST）+ Trivy（镜像 CVE）。pre-commit gitleaks + CI 三轴 + 镜像 Trivy hard gate。
- **追问防线**：Trivy 扫到什么修过？→ jackson-databind 2.21.2→2.21.4（HIGH CVE）；jackson-core 2.21.2→2.21.4；micrometer/spring-data/spring-framework 各修一批（PR #46）。为什么只 override 一个构件会漏？→ 只升 databind 没升 core，BOM 属性要覆盖全家族。
- **项目佐证**：`apps/pom.xml` 5 个 BOM 属性 override；`d5f90d7`（PR #46）

### CI-4 密钥怎么管理的？
- **30 秒**：SealedSecret 8 个 key（DB_PASSWORD/JWT_SECRET_KEY/MYSQL_*/HARBOR_*），namespace-wide scope，controller 自动解密。JWT 密钥启动强校验（≥256bit + 拒绝占位符）。
- **追问防线**：为什么不用明文 Secret？→ git 里明文=泄漏；SealedSecret 加密入 git，controller 解密。mysql 密码原先是啥问题？→ 引用不存在的 mysql-secret + initdb 字面占位符，改 SealedSecret 引用 + env 注入（A-6）。
- **项目佐证**：`sealed-bank-mall.yaml`、`mysql/deployment.yaml`（env 改 bank-mall-secret）

### CI-5 镜像构建怎么做的？
- **30 秒**：多阶段 Dockerfile——builder maven:3.9-eclipse-temurin-21-alpine 编译 + runtime eclipse-temurin:21-alpine 运行（180MB），非 root appuser + HEALTHCHECK。common-lib 必须先 install（monorepo 4 处同步）。
- **追问防线**：为什么 strip 父 POM modules？→ 避免 Maven 扫描不存在的兄弟模块（Docker 构建 hack）。镜像 tag 怎么统一？→ ci.sh sed 回写 deployment YAML（A-5）。

---

## 第 6 章：自测打分表

> 对着以上卡片自评，每张填 🔴/🟡/🟢。**目标：🔴 ≤ 30%**。逐周回填进度。

| 卡片 | 第 1 周 | 第 2 周 | 第 3 周 |
|------|--------|--------|--------|
| D-1 支付分布式事务 | | | |
| D-2 幂等 | | | |
| D-3 乐观锁 | | | |
| D-4 冲正坑 | | | |
| D-5 一致性兜底 | | | |
| K-1 集群拓扑 | | | |
| K-2 NetworkPolicy | | | |
| K-3 HPA | | | |
| K-4 探针/资源 | | | |
| K-5 MySQL 单点 | | | |
| O-1 三件套 | | | |
| O-2 告警 | | | |
| O-3 追踪案例 | | | |
| O-4 JVM 指标 | | | |
| CI-1 双 CI | | | |
| CI-2 ArgoCD | | | |
| CI-3 三轴扫描 | | | |
| CI-4 密钥管理 | | | |
| CI-5 镜像构建 | | | |
| **🔴 计数** | | | |

---

## 第 7 章：简历 bullet（两版）

### 后端版（Java 后端岗）
1. 独立设计 4 微服务银行商城（Spring Boot 4.0.6/Java 21/MySQL 8），**补偿 Saga 支付链路**（debit→credit→reverse 3 次指数退避）+ DB UNIQUE 幂等 + 乐观锁，49 单测全绿
2. 修复支付冲正 P0 bug（传错 originalTransactionNo）+ 幂等并发 race（撞约束映射 409）+ 失败支付误发成功通知，测试同步重写杜绝契约固化
3. 登录限流加固：XFF 真实 IP 解析 + 账号级锁定（5 次/15 分钟），修复 Ingress 代理后全用户共享限流 key 问题
4. 升级 5 个 BOM 清零 9 个 Trivy HIGH CVE（jackson/micrometer/spring-data/spring-framework），CI 硬门禁通过
5. 统一 common-lib 契约（ApiResponse/ErrorCode/BusinessException，零 Spring 依赖），4 服务复用

### DevOps 版（DevOps/SRE 岗）
1. 独立搭建 4 节点 K8s 集群（kubeadm + Calico + containerd + Harbor），4 微服务容器化 + GitOps（ArgoCD 3 App 分拆 + selfHeal 3 分钟回滚）
2. 全栈可观测性：Prometheus + Grafana（3 告警 provisioning as code）+ Loki 3.4（TSDB schema 迁移）+ Tempo（OTLP），OTEL Agent initContainer 注入
3. 双 CI 链路：GitHub Actions 5 job（gitleaks/semgrep/trivy 硬门禁）+ 内网 ci.sh 6 阶段真实 CD（211s），GFW 适配（NJU mirror/阿里云）
4. 零信任 NetworkPolicy（deny-all + 8 白名单）+ SealedSecret 8 key + PodSecurity baseline；混沌工程 2/3 场景通过（NetworkPolicy 误配 MTTR 5min）
5. 基础设施治理：deploy.sh 断裂修复（SealedSecret 引用）、HPA 对齐 2-worker、MySQL/Ingress PDB、监控组件全量升级（Loki 3.4/Grafana 11.5/Prom 3.2）

---

## 使用建议
1. **先过第 0 章**——确保所有口头说法与代码一致（尤其 Loki/Tempo/HPA/探针）
2. **每天 3 张卡**——先 D-1/D-2/D-4（最高频）+ K-2/K-3（最有深度）优先
3. **口述练习**：每张卡的 30 秒回答**念出声**，卡壳就是 🟡/🔴
4. **每周回填自测表**，🔴 项集中突破
5. 时间够再做模拟面试（agent 扮演面试官随机抽 10 题）
