# Bank Mall Cloud-Native Platform

A cloud-native microservices platform for a commercial bank e-commerce system — built from bare-metal Kubernetes to full-stack observability, GitOps delivery, and security hardening.

[![Java](https://img.shields.io/badge/Java_21-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.0.6-6DB33F?logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)
[![Kubernetes](https://img.shields.io/badge/Kubernetes_v1.36-326CE5?logo=kubernetes&logoColor=white)](https://kubernetes.io/)
[![License](https://img.shields.io/badge/License-MIT-3DA639)](LICENSE)

## Overview

The platform runs a payment chain across four microservices — authentication, accounts, payments, and notifications — on a VMware-based Kubernetes cluster. Every infrastructure component (ingress, monitoring, logging, tracing, secret management, network policies) is declared as code under `infra/kubernetes/`.

| Component | Capability |
|-----------|-----------|
| **GitOps** | ArgoCD watches this repo; auto-sync with prune and self-heal |
| **Observability** | Prometheus + Micrometer custom metrics + Grafana dashboards + Loki/Promtail log aggregation |
| **Tracing** | Jaeger all-in-one (Badger + PVC) with OpenTelemetry Java Agent injection |
| **Secret Management** | Bitnami Sealed Secrets — encrypted at rest in Git, decrypted in-cluster |
| **Security** | NetworkPolicy deny-all / whitelist, PodSecurity baseline, PDB, ResourceQuota, LimitRange |
| **CI/CD** | GitHub Actions for code quality gates; internal `scripts/ci.sh` for Harbor-based delivery |

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
| HTTP Client | RestClient (SB 3.2+, synchronous, streaming API) |
| Database | MySQL 8.0 — one database per service |
| Migrations | Flyway |
| Auth | BCrypt password hashing + JWT (jjwt 0.12.6) |
| Transactions | Compensation-based eventual consistency with idempotency keys |
| Container Runtime | containerd + Calico (IPIP) |
| Ingress | Ingress Nginx (DaemonSet, NodePort 30080) |
| Registry | Harbor (private, 10.0.0.61) |
| GitOps | ArgoCD |
| Secrets | Bitnami Sealed Secrets |
| Metrics | Prometheus + Micrometer |
| Dashboards | Grafana (provisioned) |
| Logs | Loki + Promtail |
| Tracing | Jaeger 1.60 LTS (Badger + PVC) + OpenTelemetry Java Agent |
| SAST / Secret Detection | Semgrep / Gitleaks (planned S3) |

## Quick Start

```bash
# Build a single service (skip tests)
cd apps/auth-service
mvn clean package -DskipTests

# Run with H2 in-memory database
java -jar target/auth-service-1.0.0.jar --spring.profiles.active=h2

# Run tests
mvn test

# Use Makefile for common operations
make help
make build
make test
make smoke-test
make ci
```

## Repository Structure

```
bank-mall-platform/
├── apps/                              # Application source (4 microservices)
│   ├── auth-service/                  # BCrypt + JWT authentication
│   ├── account-service/               # Account CRUD + double-entry transactions
│   ├── payment-service/               # Payment orchestration + compensation
│   └── notification-service/          # Notification persistence
├── infra/                             # Infrastructure as Code
│   ├── kubernetes/base/               # K8s manifests (deployments, services, ingress, monitoring, security, hpa)
│   ├── kubernetes/argocd/             # ArgoCD Application CRs
│   └── dashboards/                    # Grafana dashboard JSON
├── .github/workflows/                 # GitHub Actions CI
├── scripts/                           # Build, deploy, smoke-test, ci
├── docs/                              # Technical documentation
├── sql/initdb/                        # MySQL bootstrap SQL
├── Makefile
├── ROADMAP.md
└── SECURITY.md
```

## Documentation

| Document | Content |
|----------|---------|
| [`docs/execution-plan.md`](docs/execution-plan.md) | Full execution plan (S0–S6) |
| [`docs/execution-record.md`](docs/execution-record.md) | Execution log with design deviations and lessons learned |
| [`docs/13-design-decisions.md`](docs/13-design-decisions.md) | Key technology decisions with rationale |
| [`docs/14-troubleshooting-handbook.md`](docs/14-troubleshooting-handbook.md) | Troubleshooting guide by problem category |
| [`docs/26-final-verification-checklist.md`](docs/26-final-verification-checklist.md) | Delivery verification checklist |
| [`ROADMAP.md`](ROADMAP.md) | Capability roadmap and explicit exclusions |
| [`SECURITY.md`](SECURITY.md) | Security practices and production gaps |

## License

MIT — see [LICENSE](LICENSE).
