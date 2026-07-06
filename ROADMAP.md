# ROADMAP

> Delivered incrementally following the 6-phase execution plan defined in [`docs/execution-plan.md`](docs/execution-plan.md).
> ✅ = Done / 🔵 = In Progress / ⚪ = Planned

---

## V1 — Current Release

### S0：Platform Recovery & Baseline Verification ✅

- [x] Project scaffold：README, .gitignore, .dockerignore, Makefile, ROADMAP, CONTRIBUTING
- [x] K8s cluster recovery and health check
- [x] Spring Boot 4.0.6 + RestClient compile verification
- [x] Network connectivity verification (GitHub, ghcr.io alternatives)
- [x] Harbor registry recovery
- [x] `scripts/preflight.sh`
- [x] `.github/workflows/ci.yml`

### S1：Business Closure ✅

- [x] auth-service — BCrypt password hashing + JWT stateless tokens
- [x] account-service — JPA entities + Flyway migrations + optimistic locking + idempotency keys
- [x] payment-service — RestClient cross-service calls + compensation logic with manual retry
- [x] notification-service — notification persistence
- [x] Unified ApiResponse + global exception handling
- [x] MySQL 4 databases bootstrap
- [x] `scripts/build-images.sh` / `deploy.sh` / `smoke-test.sh`
- [x] 41 unit tests across 4 services

### S2：Platform Capability Matrix ✅

- [x] ArgoCD — 3 Application CRs with auto-sync, prune, and self-heal
- [x] Jaeger all-in-one 1.60 — Badger storage, PVC, Recreate strategy
- [x] OpenTelemetry Java Agent — initContainer injection (hostPath→GitHub→Harbor, 3 iterations)
- [x] Prometheus + Micrometer custom metrics — QPS, success rate, P99 latency
- [x] Grafana dashboards — business overview + SLI/SLO panels + 3 alert rules
- [x] Loki + Promtail — log aggregation with structured metadata
- [x] Sealed Secrets — all 8 credentials encrypted, zero plaintext in Git
- [x] NetworkPolicy — deny-all baseline + whitelist for DNS, ingress, monitoring, MySQL, cross-service, Jaeger
- [x] PodSecurity — baseline enforced, restricted audit
- [x] PDB ×4, LimitRange, ResourceQuota
- [x] Dockerfile — multi-stage builds, non-root user, HEALTHCHECK (all 4 services)
- [x] Maven parent POM — unified dependency management (SB 4.0.6, JDK 21, jjwt 0.12.6)
- [x] `.dockerignore` — 55-line exclusion rules

### S3：Dual-Platform CI/CD ✅

- [x] Semgrep SAST rules and CI gate — `.semgrep.yml` + GitHub Actions semgrep job
- [x] Gitleaks pre-commit hook and CI gate — `.pre-commit-config.yaml` + `.gitleaks.toml` + CI gitleaks job
- [x] Trivy image scanning — CI hard gate (HIGH/CRITICAL) + `scripts/ci.sh` soft gate (NJU mirror for GFW)
- [x] GitHub Actions full pipeline — 5 jobs: gitleaks → semgrep+test → build+trivy → feishu
- [x] `scripts/ci.sh` internal delivery automation — 2026-06-07 harbor01 端到端验证通过（211s，4/4 Trivy 零高危，NJU 镜像 17s 拉 DB）
- [x] Feishu bot CI/CD notifications — GitHub Actions notify job + ci.sh webhook
- [x] Gitleaks block case study — `docs/29-gitleaks-blocking-case.md`（pre-commit 阻断 → 修复 → 重新提交全流程）

### S4：Chaos Engineering & Load Testing ✅ Complete

- [x] JMeter load test — baseline 50/100/200 concurrent ✅（2545/3332/3637 成功）
- [x] Preflight — test accounts (×10) + DB baseline backup + Jaeger fix + Grafana SLO 验证
- [x] Extended load — 100/200 concurrent + HPA 扩容观察 + 冷启动死亡螺旋复盘 ✅
- [x] Scenario 1: OOMKilled — 已删除（SB 4.0.6 + OTEL agent ≥320Mi, V2 混沌工程规划）
- [x] Scenario 2: NetworkPolicy — 切断 payment→account ingress ✅（postmortem-02）
- [x] Scenario 3: Jaeger trace — 5 服务 OTEL 注入验证 ✅（postmortem-03）
- [x] DB cleanup — 备份恢复验证 ✅
- [x] 压测复盘 ×3 — load-test + networkpolicy + jaeger postmortems ✅

### S5：Polish & Packaging ✅ Complete

- [x] Swagger/OpenAPI — springdoc v3.0.0 for SB 4.0.6, `mvn compile` 通过
- [x] Helm Chart skeleton — 2 服务样本 + 3 env values + drift warning README
- [x] HA architecture design — Keepalived brain-split protection + etcd backup/restore
- [x] README rewrite — 价值陈述 + 数据栏 + 中英双语 + Key Documents 表
- [x] Interview materials — 6 CI/CD Q&A + 3 fault case narratives + design decisions
- [x] Polish fixes — egress 16686 removed, Mockito JDK21 argLine, Dockerfile HEALTHCHECK
- [x] Document cleanup — 50→13 active docs（merged 2 journals + 4 postmortems, archived 47 to Git history）
- [x] `.opencode/AGENTS.md` — SB 4.0.6 breaking changes + 8 team conventions + GFW matrix

### S5.5：Audit Remediation ✅ Complete（2026-06-09）

> 五轮交叉验证审计（Claude + Kimi + 架构师 + GLM-5.1）+ 主审架构师灾难预演 → 修复 28/31 P0、26/31 P1、2/10 P2

- [x] common-lib 共享模块 — 统一 ApiResponse / ErrorCode / BusinessException
- [x] JWT 密钥默认值移除 + 启动校验
- [x] auth GlobalExceptionHandler 补齐
- [x] DataInitializer 全部 `@Profile("dev")` 隔离
- [x] auth-service ddl-auto: update → validate + Flyway V1 迁移
- [x] MySQL Deployment → StatefulSet（含 serviceName）
- [x] 4 服务 replicas: 2 + HPA minReplicas: 2 + PDB 生效
- [x] K8s 探针全部改 Actuator /actuator/health/liveness + /readiness
- [x] Docker HEALTHCHECK 与 K8s 探针对齐
- [x] deploy.sh 修复（SealedSecret 替代不存在的 secret.yaml）
- [x] Cloud overlay ACR 凭据移除
- [x] Prometheus emptyDir → PVC（10Gi）
- [x] Grafana 匿名访问关闭 + 密码 SecretKeyRef
- [x] Promtail cri:{} 移除
- [x] ArgoCD exclude 语法修正（shell brace → gitignore glob）
- [x] Trivy exit-code 统一（ci.sh --exit-code 1）
- [x] 脚本执行权限恢复（chmod +x）
- [x] db-backup / db-seed 密码走环境变量（MYSQL_PWD）
- [x] RestClient 超时统一（connect 2s / read 5s）
- [x] 乐观锁指数退避（20ms→80ms→320ms）+ 冲正退避
- [x] Transaction ID 纳秒+随机后缀防碰撞
- [x] legacyBalance 死代码删除
- [x] Swagger 注解补全（account/payment/notification 控制器）
- [x] Bean Validation 补全（4 个 DTO @Valid @NotBlank @NotNull @Positive）
- [x] 补偿事务 reverseWithRetry 返回 TransactionData（修复 transactionNo=null）
- [x] AuthController 登录速率限制（LoginRateLimiter: 60s/10 次）
- [x] AuthController userProfile JWT subject 校验防越权
- [x] NotificationClient Micrometer counter（notification.send.failures）
- [x] Jaeger nodeName 硬编码移除
- [x] Prometheus RBAC nodes/proxy 权限移除
- [x] teardown.sh / preflight.sh 路径修正
- [x] MySQL StatefulSet serviceName 补充
- [x] 4 份项目文档更新/新建（troubleshooting + design-decisions + CONTRIBUTING + production-readiness-checklist）
- [x] 第二轮修复（Jaeger PVC 持久化 + Ingress 拆分 + 分页 + DataInitializer 竞态防护 + Docker SHA）
- [x] 最终审计报告定稿（AUDIT_FINAL.md — 30/35 清零 + ARCHITECT_ADVERSARIAL_AUDIT.md）

### S6：Bonus (Time Permitting) ⚪ Planned

V2 范畴（从审计转入 — 见 AUDIT_FINAL.md 终版）：

- [ ] **Spring Security 运行时认证** — 4 服务 API 加 JWT Filter Chain（原 P0 审计缺陷）
- [ ] **Ingress host 规则** — 生产化域名绑定（原 P0 审计缺陷）
- [ ] **common-web 安全模块** — SecurityHeadersFilter + CorrelationIdFilter（原 P1 审计缺陷）

原 S6 计划：

- [ ] Velero backup and restore demonstration
- [ ] Argo Rollouts canary deployment
- [ ] Kyverno custom policies
- [ ] Kubecost cost visualization

---

## V2 — Productionization Roadmap

| Capability | Description |
|------------|-------------|
| Multi-master HA | keepalived + VIP + etcd backup |
| Argo Rollouts | Canary and blue-green deployment strategies |
| Kyverno | Policy-as-code for compliance enforcement |
| Velero | Scheduled backups + disaster recovery |
| Redis | Hot data caching, distributed locking |
| **Chaos Engineering — 真实 OOMKill 模拟** | S4 遗留技术遗憾：4 轮尝试证明 SB 4.0.6 无法通过降 memory limit 触发内核级 OOMKill。V2 引入 `@Profile("chaos")` 混沌控制器，利用 `-XX:MaxDirectMemorySize` 解除封印 + `ByteBuffer.allocateDirect()` 堆外攻击 + Heap dump 分析（MAT/jcmd）+ JVM GC 日志解读，形成完整内存排障体系 |
| **JVM 可观测性增强** | `-XX:+HeapDumpOnOutOfMemoryError` + `-Xlog:gc*` + Grafana JVM Dashboard + jcmd 运行时诊断 |

---

## Explicitly Excluded

| Item | Rationale |
|------|-----------|
| Spring Cloud Gateway | Ingress Nginx handles routing for 4 services; K8s Service + CoreDNS replaces service discovery |
| Redis (current release) | Platform engineering narrative does not require a cache layer; design doc compares DB UNIQUE vs Redis SETNX |
| Frontend UI | Project scope is platform engineering / SRE, not full-stack |
| Multi-master HA (current cluster) | Experimental 1-node control plane; HA topology documented in architecture design |
| SonarQube | Semgrep covers the same SAST use case with zero deployment overhead |
| Seata distributed transactions | Compensation logic + daily reconciliation more closely model real payment systems |
| **OOMKilled 场景（S4）** | Spring Boot 4.0.6 + OTEL agent + JPA 启动最小可行内存 ~320Mi。LimitRange 强制最低 128Mi 但不允许设低值；设 128-256Mi 则 JVM 启动即退（0/1 循环），设 320Mi+ 则压测无法触发 OOM。**试图模拟 OOMKill 的 4 轮尝试全部失败：account-service 128Mi→JVM 退出、256Mi→liveness 超时、320Mi→能启动但压不崩；notification-service 128Mi→同样 0/1 循环。结论：Spring Boot 4.0.6 在生产级 K8s 环境中，OOMKill 通常发生在流量峰值或线程泄露阶段，无法通过单纯降 memory limit 复现。** 从 S4 正式移除。 |

---

**Last updated**: 2026-06-08 | S5 Complete ✅
