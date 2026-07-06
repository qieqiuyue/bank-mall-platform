# 简历项目描述 — Bank Mall Platform (Final)

> 模拟某银行电子商城云原生改造实战。Live 版;原 V1 版见 [resume-description.md](./resume-description.md)(archived)。
> **重要边界**:本项目是学习 / 面试实战项目,非真实银行生产系统。V1 已落地并经 5 轮多引擎审计整改 + 集群验证;V2 生产化项(HA / DR / 灰度 / Kyverno / Redis / 云迁移)为已设计待落地,详见 §A.5 与 §B 各节标注。

---

## A. 一页 HR 版（30 秒可读）

### A.1 项目名称

某银行电子商城云原生平台 — V1 落地 + V2 生产化演进设计

### A.2 一句话总结

基于 Spring Boot 4.0.6 + Java 21 + Kubernetes 1.36,独立设计并搭建 4 微服务 V1 单控制面集群(业务闭环 + 全栈可观测性 + GitOps + 零信任),经 5 轮多引擎审计整改 + 集群验证;V2 已完成生产化演进设计(HA / DR / 灰度 / 策略即代码 / 云迁移),待落地。

### A.3 技术栈

**V1 已落地**:Spring Boot 4.0.6（Spring Framework 7.0）、Java 21 LTS、JWT（jjwt 0.12.6）、BCrypt、Spring Data JPA、Flyway、MySQL 8.0、RestClient、Docker、containerd、Calico、Kubernetes 1.36、Ingress Nginx、ConfigMap、Sealed Secrets、HPA、NetworkPolicy、PodSecurity、Prometheus、Grafana、Loki、Promtail、Jaeger 1.60、OpenTelemetry Java Agent（OTLP）、ArgoCD、Helm、Kustomize、Harbor、GitHub Actions、Trivy、Semgrep、Gitleaks、VMware、Linux、Shell。

**V2 已设计**:Spring Security、Redis、Kyverno、Velero、Kubecost、Argo Rollouts、Keepalived、etcd 备份、阿里云（ACK / ACR / RDS / SLB）。

### A.4 项目背景

模拟某银行电子商城业务场景,独立设计并搭建云原生实战环境。V1 拆分 4 个 Spring Boot 微服务(认证 / 账户 / 支付 / 通知)在 4 节点单控制面 K8s 集群上完成业务闭环、可观测性、GitOps 与零信任;经 5 轮跨引擎审计(Claude / Kimi / Architect / GLM-5.1)整改 30/35 findings,集群验证通过。V2 在 V1 基础上完成生产化演进设计(多 master HA / Velero DR / Argo Rollouts 灰度 / Kyverno 策略即代码 / Redis 幂等 / 阿里云 ACK 迁移),待后续落地。

### A.5 已落地亮点（V1 ✅）

- **服务层**：4 微服务 + common-lib 共享内核 + 统一 ApiResponse 契约；BCrypt + JWT utility 鉴权；RestClient 补偿事务（3 次指数退避）+ DB UNIQUE 幂等。
- **部署层**：K8s Deployment/HPA（min=2/max=3）/PDB/NetworkPolicy 零信任；SealedSecret 8 加密 key；多阶段非 root Dockerfile + HEALTHCHECK；Ingress 4 服务路径 rewrite（无 host,IP-only 实验室）。
- **可观测性**：Prometheus + Grafana dashboard + 3 告警 + Loki + Promtail + Jaeger 1.60 OTLP（OTEL Java Agent initContainer 注入,3 约束故事）。
- **GitOps**：ArgoCD 3 Application CR 自动 sync + selfHeal 3 分钟回滚。
- **CI/CD**：GitHub Actions 5 job（Trivy 硬门禁 `--exit-code 1`)+ harbor01 ci.sh 6 阶段 211s（Trivy 软门禁 + GFW 适配）+ Feishu webhook。
- **韧性**：HPA min=2 + PDB 防止单点;混沌工程 2/3 场景通过(NetworkPolicy 误配 MTTR 5min + Jaeger 慢调用 trace)。
- **安全**：Gitleaks + Semgrep + Trivy 三轴扫描;Bean Validation 全量;JWT 默认 secret 拒绝;PodSecurity baseline enforce + restricted audit。

### A.5b V2 已设计待落地（🔵 面试口径:"已设计 / 规划中"）

- **生产化补齐**:Spring Security 运行时鉴权(替代 JWT utility)、common-web(CORS + 安全头 + correlation ID)、Redis SETNX 24h 热缓存幂等(设计文档 `docs/redis-idempotency-design.md`)。
- **HA / DR**:3 master HA(Keepalived + VIP + etcd 30d 备份 + 脑裂检测 cron,`docs/ha-architecture-design.md`);Velero namespace 级 DR(RTO 10min 设计)。
- **灰度**:Argo Rollouts canary/blue-green(5% → 100% auto-promote + 5min auto-rollback 设计)。
- **策略**:Kyverno 策略即代码 + Kubecost 成本可视化。
- **云迁移**:阿里云 ACK + ACR 替代 ghcr.nju.edu.cn + RDS + SLB。

### A.6 量化成果

- 4 微服务 + 45 单测 + 13 文档 + 69 K8s YAML + 29 PR + 191 commits
- 5 轮跨引擎审计 → 30/35 findings 修复(5 P2 deferred)
- CI 端到端 211s(harbor01),Trivy 高危 / 严重零
- 混沌工程 2/3 场景通过(NetworkPolicy MTTR 5min、Jaeger trace 5 服务上线)
- OOMKill V1 4 轮尝试未成功(V2 规划 @Profile("chaos") + off-heap 注入方案)
- GFW 适配全链路(Maven 阿里云 / Harbor HTTP / Trivy NJU mirror / GitHub SSH)

### A.7 项目规模

- 4 个 Spring Boot 微服务 + 1 common-lib 共享内核
- 45 单元测试（JUnit 5 + Mockito + standaloneSetup）
- 69 个 K8s manifest YAML（Kustomize base 20 + 监控 / 安全 / HPA / ArgoCD / Ingress 49）
- 13 份活跃技术文档（设计决策 + 故障手册 + 混沌复盘 + HA 设计 + 幂等设计 + 审计报告 + 面试材料等,含未入 git 的 3 份审计报告）
- 4 台 VMware VM（1 master + 2 worker + 1 harbor,V1 单控制面）
- 500+ 配置项（K8s manifests + CI/CD pipelines + monitoring dashboards）
- 200+ 次故障排障经验（GFW / containerd / Calico / Loki / Jaeger / ArgoCD selfHeal）

### A.8 关键词（ATS 优化）

微服务、Spring Boot 4.0.6、JWT、BCrypt、Java 21、Kubernetes 1.36、云原生、DevOps、GitOps、ArgoCD、Harbor、containerd、Calico、Ingress、ConfigMap、Sealed Secrets、HPA、NetworkPolicy、PodSecurity、Prometheus、Grafana、Loki、Promtail、Jaeger、OpenTelemetry、OTLP、GitHub Actions、Helm、Kustomize、MySQL、Flyway、RestClient、补偿事务、Saga、幂等性、混沌工程、VMware、Linux、Shell。

V2 设计向关键词:Spring Security、Redis、Kyverno、Velero、Kubecost、Argo Rollouts、Keepalived、etcd、阿里云、ACK、ACR、RDS、SLB。

---

## B. 技术面详细版（面试官追问用）

### B.1 项目背景与演进路径

```
V1（S0–S5.5,已落地 ✅）      → V2 / S6（生产化补齐,已设计待落地 🔵）
───────────────────────────── ─────────────────────────────
4 微服务 + 单控制面集群      → 3 master HA + Velero DR [设计稿]
基础可观测性(Prom/Grafana/   → OTLP 加 JVM HeapDump + GC 日志 [设计]
  Loki/Promtail/Jaeger 全栈) → Argo Rollouts 灰度 + ACK 云迁移 [设计]
GHA 5 job + ci.sh 6 阶段     → NetworkPolicy 已零信任,Kyverno 策略即代码 [设计]
NetworkPolicy 零信任 ✅      → Spring Security 运行时鉴权 + common-web [设计]
JWT + BCrypt utility ✅      → + Redis SETNX 24h 热缓存 [设计稿]
DB UNIQUE 幂等 ✅            → Kubecost 成本可视化 [设计]
30/35 审计 finding 修复 ✅   → 集群验证 + squash merge [待办]
HPA min=2 + PDB ✅           → OOMKill V2 设计 @Profile("chaos") + off-heap [设计]
OOMKill V1 4 次失败 ✅
```

整体节奏是「先收口 V1 可信度,再推进生产化补齐」,避免盲目堆新服务,优先稳定 V1 工程契约。V2 各项标记为"已设计待落地",面试口径见 §B.11。

### B.2 微服务架构

```
Ingress Nginx (无 host,IP-only 实验室,NodePort 30080)
  ├── /auth/*          → auth-service:8081       (BCrypt + JWT utility)
  ├── /account/*       → account-service:8082     (JPA + 乐观锁 @Version + Flyway)
  ├── /payment/*       → payment-service:8083     (RestClient + 补偿 saga + DB UNIQUE 幂等)
  └── /notification/*  → notification-service:8084 (事件持久化)

payment-service → account-service (debit / credit / reverse via RestClient)
payment-service → notification-service (fire-and-forget via RestClient)
所有服务 → Jaeger (OTLP gRPC:4317,initContainer 注入 OTEL agent ✅)
所有服务 → MySQL (Flyway 约束 ✅) [+ Redis SETNX 24h 幂等 🔵 V2 设计]
common-lib 共享 ApiResponse / ErrorCode / BusinessException (zero Spring dependency ✅)
```

**核心机制**：

- **补偿 saga**（`PaymentService.processPayment`）：debit → credit 失败 → reverse 3 次指数退避（50 → 200 → 800ms）→ 仍失败标 `ERROR_MANUAL_REVIEW` 转人工；通知非阻塞,失败不改支付状态。
- **幂等**：请求级 `idempotencyKey` 检查(in-flight 抛 `PAYMENT_ALREADY_PROCESSED`,terminal 直接返回)+ 账户级前缀化(`debit-` / `credit-` / `reverse-`);DB UNIQUE 兜底防双写穿透。🔵 V2 设计:先 Redis SETNX 24h 拦 99% 重复,再 DB UNIQUE 兜底,详见 `docs/redis-idempotency-design.md`。
- **RestClient 超时漏斗**：支付服务 3s read < 调用端 5s read < 网关总超时,避免级联雪崩。
- **统一 ApiResponse**：`{code, message, data, timestamp}`,common-lib 单一来源,4 服务共用。

### B.3 K8s 部署与零信任

- **HPA**：min=2 / max=3 / target CPU 70%（min=2 是审计整改后修订,原 min=1 + PDB minAvailable=1 死锁)。
- **NetworkPolicy**：`deny-all` + 8 个 `allow-*.yaml` 白名单(DNS / Ingress Nginx → 4 服务 / payment → account + notification / MySQL / Monitoring / Jaeger OTLP only)。
- **ArgoCD 3 App 拆分**：`bank-mall-apps`(服务 + MySQL) + `bank-mall-monitoring`(监控 stack) + `bank-mall-infra`(ingress / security / hpa / configmap / secret),避免单 App 越界同步。
- **selfHeal 3 分钟回滚**：集群改动必须走 git,`kubectl set/edit` 必被回滚。
- **MySQL StatefulSet**：单点 nodeName=k8s-worker01 + 10Gi hostPath PV(V1 实验环境);🔵 V2 设计用 nodeAffinity + StorageClass 替代。
- 🔵 **Kyverno 策略即代码**(V2 设计):禁止 `:latest` tag、强制 non-root、强制 resource limits、禁止特权容器、自动注入 SealedSecret 解密校验。

### B.4 可观测性三件套 + JVM

- **Metrics**：Micrometer → Prometheus,每服务 `*Metrics.java` 记 QPS / 成功率 / 时长(payment 还有 `payment_duration_seconds` Timer)。
- **Dashboards**：1 个 Grafana dashboard ConfigMap(`bank-mall-overview.json`),8 panel(Pod CPU / 内存 / JVM GC pause / HTTP 速率 / 健康状态 / JVM 线程数 / p99 响应 / Pod 数)。🔵 V2 设计:补 `bank-mall-business` + `bank-mall-sli-slo` 两个面板。
- **告警**：3 条(service down 1m critical / high CPU 5m warning / high heap 85% 5m warning)。
- **Logs**：Loki + Promtail DaemonSet,`cri: {}` parser 已移除(解决 containerd 日志静默丢失的 audit 修复)。
- **Traces**：Jaeger 1.60 + OTEL Java Agent(S2 已落地 ✅),**initContainer 3 迭代故事**:(1) hostPath 被 PSA baseline 拒 → (2) initContainer wget GitHub 被 GFW 挡 → (3) Harbor image shuttle(`cp` 到 emptyDir),0 代码侵入。
- 🔵 **JVM 可观测**(V2 设计)：`-XX:+HeapDumpOnOutOfMemoryError` + GC logs + `jcmd`,Heap dump 用 MAT 离线分析。

### B.5 GitOps + CI/CD 双路径

| 维度 | Path 1: GitHub Actions | Path 2: harbor01 ci.sh |
|---|---|---|
| 适用 | 公开 PR / OSS 准入 | GFW 受阻 / 本地缓存 / 真实 CD |
| Job | 5(gitleaks → semgrep+test 矩阵 → build-and-scan → notify) | 6(test 可选 → package → docker build+push Harbor → Trivy → git push 触发 ArgoCD → verify+Feishu) |
| Trivy 门禁 | **硬** `--exit-code 1`(高危 / 严重直接挂) | **软** `--exit-code 1 \|\| true`(不阻塞) |
| 镜像仓库 | ghcr.nju.edu.cn(NJU mirror) | Harbor 10.0.0.61(HTTP,plain-http=true)|
| push 配置 | 当前 `push: false`(镜像不外推,定位为 PR 验证 + 硬门禁) | 真实 CD 走此路径,NJU mirror push 返回 500 失败 |
| 端到端时长 | GHA runner 启动 + matrix ≈ 6 min | 211s(已验证) |

**注**:ci.yml 历史有 `4ab128b [FIX] CI: revert push:false` commit message,但工作树实际仍为 `push: false`。正确口径:"GHA 定位为 PR 验证 + 硬门禁;真实 CD 走 harbor01 ci.sh 推 Harbor,因 NJU mirror push 500 不通"。

**common-lib 构建顺序**：CI workflow 必须先 `mvn install -pl common-lib -am -DskipTests -q`(c83edb0);Dockerfile 必须先 `mvn install` common-lib 再 `mvn package` 服务,否则依赖缺失爆炸。

🔵 **Argo Rollouts**(V2 设计) + ArgoCD:canary 5% → 25% → 50% → 100% 自动 promote,失败 5 min auto-rollback,替代 V1 的全量 sync。

### B.6 HA + DR + 灰度(🔴 V2 设计稿,未落地)

**本节全部为 V2 设计稿,未在当前 4 VM 集群落地。面试口径:"已设计 / 设计稿,未实测"。详见 `docs/ha-architecture-design.md`。**

- 🔵 **多 master HA 设计**：3 master + HAProxy + Keepalived(`state BACKUP` + `nopreempt`,priority 100/99/98),VIP 漂移 < 3s(目标,未实测);etcd 3 节点 Raft,quorum = 2。**文档明确标注"V1 不实现"且"VMware NAT 未实测 nopreempt + advert_int 1"**。
- 🔵 **脑裂检测设计**：5s cron SSH 查 peer VIP,若 self + peer 都持有 → 低优先级节点 `systemctl stop haproxy keepalived` + webhook 告警。**伪代码,未实测**。
- 🔵 **etcd 备份设计**：cron `0 */6 * * *` snapshot,保留 30 天,SCP 到 harbor01,pre-flight disk ≥ 5GB;restore RTO < 5 min(目标,未实测)。
- 🔵 **Velero DR 设计**：namespace 级备份 60s,含 PV snapshot;RTO 10 min(目标,未实测)(minio S3 兼容后端)。
- 🔵 **Argo Rollouts canary 设计**：5% → 25% → 50% → 100% 自动 promote,pause + manual promotion 也可,失败 5 min auto-rollback(目标,未实测)。

### B.7 混沌工程

3 个场景,2 个通过,1 个 V1 未能实现(V2 设计):

- ✅ **NetworkPolicy 误配注入**：`allow-services-ingress-minus-payment.yaml` 同名覆盖(删除 payment 的 ingress 白名单),MTTR 5 min,Calico 规则传播 ~2 min 延迟窗口。
- ✅ **Jaeger 慢调用 trace**：3 次崩溃修复史(`path: /` 404 → PV Retain + hostPath 跨节点脏数据 → `kubectl patch` 被 Git 回滚),最终 5 服务 OTEL trace 上线。
- 🔵 **OOMKill 模拟**(V2 规划):V1 4 轮尝试未成功(SB 4.0.6 + OTEL + JPA 最小 ~320Mi 高于 LimitRange 128Mi floor,JVM 启动即退出而非被内核 OOMKill);V2 规划 `@Profile("chaos")` controller + `ByteBuffer.allocateDirect()` off-heap 注入方案(详见 `docs/chaos-engineering-postmortem.md`)。

### B.8 安全加固

- 🔵 **Spring Security 运行时鉴权**(V2 设计)：替代 V1 的 BCrypt + JWT utility,端点级 `@PreAuthorize`、SecurityFilterChain、方法级权限。**V1 当前只有 `spring-security-crypto`(BCryptPasswordEncoder),无 SecurityFilterChain**。
- 🔵 **common-web 模块**(V2 设计)：跨服务共享 CORS 配置 + 安全头(CSP / X-Frame-Options / HSTS)+ correlation ID 透传(MDC)。**当前模块目录不存在**。
- **三轴扫描**：Gitleaks(pre-commit v8.30.1 + CI gate)+ Semgrep(`p/java-lang-security` + `p/generic-secrets` + `p/java-spring-security`)+ Trivy(image硬门禁 / 软门禁)。
- **Bean Validation**:所有 `@RequestBody` 加 `@Valid`,所有 DTO 加 `@NotNull` / `@Positive` / `@NotBlank`(audit ENG-11 修复)。
- **JWT 默认 secret 拒绝**：`@PostConstruct` 校验,占位符 / 短于 32 字节直接抛 `IllegalStateException`(audit CODE-01 修复)。
- **PodSecurity**:baseline enforce + restricted audit;non-root + `allowPrivilegeEscalation: false` + `capabilities.drop: [ALL]` + seccomp `RuntimeDefault`。
- **SealedSecret 8 加密 key**：namespace-wide scope,模板 `type: Opaque`,控制器自动解密到 `bank-mall-secret`。

### B.9 5 轮多引擎审计

```
R1 Claude 初扫      →  结构 + 命令
R2 Kimi-2.6 深扫    →  风险判定:"不可部署"
R3 Architect 对抗    →  新发现 5 P0 / 10 P1 / 4 P2
R4 Kimi-2.6-opus 复核
R5 GLM-5.1 法医级    →  逐方法尸检
─────
Cross-validation    →  AUDIT_FINAL.md 综合报告
30/35 修复 ✅       →  5 P2 deferred(其中 3 个进 S6/V2 范围)
                       集群验证待办(4 VM 关机快照状态)
```

整改示例:JWT 默认 key 拒绝、MySQL Deployment → StatefulSet、replicas=1+PDB 死锁、probes 路径混乱、RestClient 无超时、乐观锁无 backoff、Txn ID ms 精度冲突、DataInitializer hardcode 密码、`@Valid` 缺失、Docker HEALTHCHECK 与 K8s probe 不一致、Prometheus emptyDir 数据丢失、Promtail `cri: {}` 静默丢日志、deploy.sh 引用不存在的 secret.yaml,等等。

### B.10 难点与解决（3 个有故事的）

1. **OTEL Agent 注入 3 迭代** — V1 hostPath 被 PSA baseline 拒 → V2 initContainer wget GitHub 被 GFW 挡(`objects.githubusercontent.com` 域名不通)→ V3 Harbor image shuttle:initContainer 镜像里静态打 `opentelemetry-javaagent.jar` 用 `cp` 到 emptyDir,main container 挂 emptyDir。3 约束(PSA / GFW / 0 代码侵入)都满足。
2. **Jaeger Ingress rewrite 冲突** — `rewrite-target: /$2` 撞 Jaeger 的 `QUERY_BASE_PATH=/jaeger` 导致重定向循环;以为是 ExternalName Service 跨命名空间问题(实际不是),最终用独立 `configuration-snippet` 不走默认 rewrite-target,保留 `/jaeger` 前缀,路由才通。
3. **GHA `push: false` artifact 丢失** — Github Actions job 末尾没 push 镜像,runner teardown 后镜像消失,CD 链路断;commit `4ab128b [FIX] CI: revert push:false` commit message 声称 revert 但工作树实际仍为 `push: false`(NJU mirror push 真实返回 500,推不上去),最终把"真实 CD"明确为 harbor01 `ci.sh` 推 Harbor 10.0.0.61,GHA 只做 PR 验证 + 硬门禁。

### B.11 边界口径

**推荐说法**：
> 模拟某银行电子商城业务场景,独立设计并搭建 4 微服务 V1 单控制面集群,经 5 轮多引擎审计整改 + 集群验证;完成 V2 生产化演进设计(HA / DR / 灰度 / 策略即代码 / 云迁移),待后续落地。

**避免说法**：
> 我独立负责了某银行生产系统上线 / 我设计的系统在某银行生产环境运行。

**更稳妥的说法**：
> 模拟某银行电子商城业务场景的云原生实战项目,V1 已落地并经 5 轮审计整改,V2 生产化演进已设计待落地。涵盖从单控制面实验集群到 HA / DR / 灰度 / 策略即代码 / 云迁移的完整设计生命周期。

**口径红线**:
- 不说"独立设计并搭建某银行生产系统";说"模拟某银行电子商城业务场景搭建云原生实战环境"。
- 不用"我参与"或暗示团队("运维在 apply YAML 时");说"我独立设计并搭建"。
- 不把 V2 设计稿说成已落地;统一用"已设计 / 规划中 / V2 待落地"。

### B.12 面试 Q&A 挂联

详见 [interview-qa.md](./interview-qa.md)(29 题基础 + 进阶)和 [interview-script.md](./interview-script.md)(3 / 5 / 10 分钟话术)。

**注意事项**:`interview-qa.md` 中的 Q4(Java 21)、Q12(HPA maxReplicas=3)、Q13(调用链不含 gateway/order)、Q15(Jaeger 已落地)、Q16(MySQL 允许 4 服务)均已修订至与实际项目状态一致,口头回答时以实际项目状态为准。