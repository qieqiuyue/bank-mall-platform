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
- [x] `scripts/ci.sh` internal delivery automation — build→push→scan→deploy→verify（含 Trivy GFW 镜像回退）
- [x] Feishu bot CI/CD notifications — GitHub Actions notify job + ci.sh webhook
- [x] Gitleaks block case study — `docs/29-gitleaks-blocking-case.md`（pre-commit 阻断 → 修复 → 重新提交全流程）

### S4：Chaos Engineering & Load Testing 🔵 In Progress (Day 1 PM)

- [x] JMeter load test — baseline 50/100/200 concurrent ✅（2545/3332/3637 成功）
- [x] Preflight — test accounts (×10) + DB baseline backup + Jaeger fix + Grafana SLO 验证
- [ ] Extended load — 100/200 concurrent + HPA 扩容观察 + Grafana 指标采集
- [ ] Scenario 1: OOMKilled — account-service memory exhaustion
- [ ] Scenario 2: NetworkPolicy misconfiguration
- [ ] Scenario 3: Jaeger slow-call root cause analysis
- [ ] DB cleanup — 备份恢复验证
- [ ] 3 postmortem documents

### S5：Polish & Packaging ⚪ Planned

- [ ] Swagger/OpenAPI (springdoc-openapi v2)
- [ ] Helm Charts (dev/staging/prod)
- [ ] High-availability architecture design document
- [ ] Redis idempotency design document

### S6：Bonus (Time Permitting) ⚪ Planned

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

---

**Last updated**: 2026-06-07 | S3 Complete, S4 In Progress (Day 1 PM)
