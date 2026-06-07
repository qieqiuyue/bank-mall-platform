# Bank Mall Cloud-Native Platform

一个从裸金属搭建到 GitOps 交付的银行电商云原生平台。区别于 Hello World demo：它跑在 4 台 VMware 虚拟机的真实 K8s 集群上，有 4 个 Spring Boot 微服务（BCrypt+JWT、JPA+乐观锁、RestClient+补偿事务）、完整可观测性（Prometheus+Grafana+Loki+Jaeger）、NetworkPolicy 零信任安全模型、以及 ArgoCD 驱动的一站式 CI/CD 流水线。每个组件都经历过故障验证——不只是能部署，还能在 NetworkPolicy 误配、冷启动风暴、慢调用等故障下正确降级。

A cloud-native microservices platform for a commercial bank e-commerce system — built from bare-metal Kubernetes to full-stack observability, GitOps delivery, and security hardening. Unlike tutorial demos, this platform has been battle-tested through chaos engineering: NetworkPolicy misconfigurations, HPA cold-start death spirals, and distributed tracing root-cause analysis.

[![Java](https://img.shields.io/badge/Java_21-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.0.6-6DB33F?logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)
[![Kubernetes](https://img.shields.io/badge/Kubernetes_v1.36-326CE5?logo=kubernetes&logoColor=white)](https://kubernetes.io/)
[![License](https://img.shields.io/badge/License-MIT-3DA639)](LICENSE)

## What Makes This Different

> Unlike typical CRUD demos, this platform runs on a real 4-node VMware K8s cluster with zero-trust NetworkPolicy, ArgoCD GitOps, and a compensation-based payment chain that was stress-tested through chaos engineering. It doesn't just deploy — it survives misconfigured network policies, cold-start death spirals, and distributed tracing failures, then documents the root cause analysis in postmortems.

**不是 Demo 的证据**：
- **真实 K8s 集群**：4 节点 VMware NAT 私有网络，kubeadm 部署，containerd + Calico，非 minikube/kind
- **零信任安全**：NetworkPolicy deny-all 基线 + 白名单，PodSecurity baseline，Sealed Secrets 加密凭证
- **混沌工程验证**：NetworkPolicy 误配排障（MTTR 5min），100/200 并发 HPA 扩容分析，Jaeger 分布式追踪
- **生产级决策**：补偿事务（非 Seata）、VARCHAR 状态（非 ENUM）、幂等键 UNIQUE 约束（非 Redis 锁）
- **GFW 适配**：NJU 镜像代理、containerd v2 `hosts.toml`、snap 沙箱兼容、Trivy DB 离线缓存

> 不堆砌组件——每个组件都有它留在这里的明确理由。不做的不回避，写在 [ROADMAP.md](ROADMAP.md) 的 Explicitly Excluded 里。

## 项目数据 / By the Numbers

| 指标 | 数值 |
|------|------|
| 微服务 | 4（auth, account, payment, notification） |
| 单元测试 | 45 |
| K8s 资源 | 16（含监控/安全/网络策略） |
| 活跃文档 | 13 篇（47 篇归档至 Git 历史） |
| CI/CD 全流程 | 211 秒（harbor01 `bash scripts/ci.sh`） |
| PR 合并 | 20 |
| Git commits | 153 |
| 版本 Tags | 2（v1.0.0-s3, v1.0.0-s4） |
| 运维 Skill | 9 个 |
| 集群节点 | 4 台 VMware VM（1 master + 2 worker + 1 harbor） |
| 故障演练场景 | 2/3 通过（NetworkPolicy 误配 + Jaeger trace 验证） |

## 交付总结 / Delivery Journey

| Phase | 内容 | 状态 |
|-------|------|:---:|
| S0 | 集群抢救 + Spring Boot 4.0.6 升级 | ✅ |
| S1 | 业务闭环：auth JWT + account JPA + payment 补偿 + notification | ✅ |
| S2 | 平台矩阵：ArgoCD, Jaeger, Prometheus, Grafana, Loki, Sealed Secrets, NetworkPolicy | ✅ |
| S3 | CI/CD：GitHub Actions 5-job pipeline + harbor01 `scripts/ci.sh` | ✅ |
| S4 | 故障演练：100/200 压测 + HPA 扩容 + NetworkPolicy 排障 + Jaeger trace | ✅ |
| S5 | 润色：Swagger, Helm, HA 设计, README 重写 | 🔵 |
| S6 | 加分：Velero, Argo Rollouts, Kyverno | ⚪ |

## Architecture

```
Ingress Nginx (NodePort 30080)
  ├── /auth/*          → auth-service:8081       (BCrypt + JWT)
  ├── /account/*       → account-service:8082     (JPA + optimistic locking)
  ├── /payment/*       → payment-service:8083     (RestClient + compensation)
  └── /notification/*  → notification-service:8084 (event logging)

payment-service → account-service (debit / credit / reverse)
payment-service → notification-service (log)
All services → Jaeger (OTLP gRPC / HTTP) for distributed tracing
```

```
4 × VM (VMware NAT, 10.0.0.0/24)
├── k8s-master01  10.0.0.31   Control Plane
├── k8s-worker01  10.0.0.41   Worker
├── k8s-worker02  10.0.0.42   Worker
└── harbor01      10.0.0.61   Harbor Registry + Build Node
```

## Tech Stack

| Domain | Choice |
|--------|--------|
| Language | Java 21 LTS |
| Framework | Spring Boot 4.0.6 (Spring Framework 7.0) |
| HTTP Client | RestClient (SB 3.2+, synchronous) |
| Database | MySQL 8.0 — one database per service |
| Migrations | Flyway |
| Auth | BCrypt + JWT (jjwt 0.12.6) |
| Transactions | Compensation-based eventual consistency + idempotency keys |
| API Docs | Swagger / OpenAPI (springdoc v3.0.0) |
| Container Runtime | containerd + Calico (IPIP) |
| Ingress | Ingress Nginx (DaemonSet, NodePort 30080) |
| Registry | Harbor (private, 10.0.0.61) |
| GitOps | ArgoCD |
| Secrets | Bitnami Sealed Secrets |
| Metrics | Prometheus + Micrometer |
| Dashboards | Grafana (provisioned, 3 alert rules) |
| Logs | Loki + Promtail |
| Tracing | Jaeger 1.60 LTS + OpenTelemetry Java Agent |
| SAST / Secret Detection | Semgrep + Gitleaks |
| CI/CD | GitHub Actions (5 jobs) + `scripts/ci.sh` (Trivy soft gate, NJU mirror) |
| Packaging | Helm Chart skeleton (Kustomize is V1 source of truth) |

## Quick Start

```bash
# Build a single service (skip tests)
cd apps/auth-service && mvn clean package -DskipTests

# Run with H2 in-memory database
java -jar target/auth-service-1.0.0.jar --spring.profiles.active=h2

# Makefile shortcuts (master01 / WSL2; harbor01: use bash scripts/ci.sh directly)
make help
make build    # mvn clean package -DskipTests × 4
make test     # mvn test × 4
make ci       # 一键 CI/CD（harbor01: bash scripts/ci.sh）
make smoke-test  # 端到端验证
```

**CI/CD 通知**：GitHub Actions 和 `scripts/ci.sh` 均支持飞书 Webhook。在 GitHub 仓库 Settings → Secrets and variables → Actions 中添加 `FEISHU_WEBHOOK` secret 即可启用。

## Repository Structure

```
bank-mall-platform/
├── apps/                              # 4 Spring Boot microservices (Maven parent POM)
│   ├── auth-service/                  # BCrypt + JWT authentication
│   ├── account-service/               # JPA + Flyway + optimistic locking
│   ├── payment-service/               # RestClient + compensation + idempotency
│   └── notification-service/          # Notification persistence
├── infra/                             # Infrastructure as Code
│   ├── kubernetes/base/               # K8s manifests (deployments, services, ingress, monitoring, security, hpa, jaeger)
│   ├── kubernetes/cloud/              # Kustomize overlay for ACK cloud (LB ingress, no OTEL)
│   ├── kubernetes/argocd/             # ArgoCD Application CRs
│   ├── helm/bank-mall/                # Helm Chart skeleton (V1: Kustomize is source of truth)
│   └── dashboards/                    # Grafana dashboard JSON (business + SLI/SLO)
├── scripts/                           # build-images.sh, deploy.sh, smoke-test.sh, ci.sh, preflight.sh, teardown.sh, db-backup.sh, db-seed-accounts.sh
├── tests/                             # k6 load test + payment-load.sh
├── .github/workflows/ci.yml           # 5-job pipeline: gitleaks → semgrep/test → build+trivy → feishu
├── docs/                              # 28 technical docs + 3 postmortems + HA design
├── Makefile
├── ROADMAP.md
└── SECURITY.md
```

## Key Documents

| Document | Content |
|----------|---------|
| [`ROADMAP.md`](ROADMAP.md) | Phase status, explicit exclusions, V2 plans |
| [`docs/project-journal.md`](docs/project-journal.md) | S0–S6 timeline: decisions, pitfalls, key data |
| [`docs/13-design-decisions.md`](docs/13-design-decisions.md) | Technology choices with rationale |
| [`docs/14-troubleshooting-handbook.md`](docs/14-troubleshooting-handbook.md) | Debugging guide by problem category |
| [`docs/chaos-engineering-postmortem.md`](docs/chaos-engineering-postmortem.md) | S4 chaos engineering: load test + NetworkPolicy + Jaeger |
| [`docs/ha-architecture-design.md`](docs/ha-architecture-design.md) | 3-master HA + Keepalived brain-split protection |
| [`docs/redis-idempotency-design.md`](docs/redis-idempotency-design.md) | Idempotency design: DB UNIQUE vs Redis SETNX |
| [`docs/interview/interview-qa.md`](docs/interview/interview-qa.md) | Interview Q&A (29 questions) |
| [`docs/interview/interview-script.md`](docs/interview/interview-script.md) | 3/5/10 minute interview scripts |
| [`SECURITY.md`](SECURITY.md) | Security practices and production gaps |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Dev setup, pre-commit hooks, doc naming conventions |

## License

MIT — see [LICENSE](LICENSE).
