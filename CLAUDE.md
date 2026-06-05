# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bank Mall Cloud-Native Platform — 4 Java microservices (auth, account, payment, notification) on a VMware K8s cluster with full observability stack. Tech: Java 21, Spring Boot 4.0.6, MySQL 8.0, containerd, Calico.

**4 VM cluster (NAT 10.0.0.0/24):** k8s-master01(31), k8s-worker01(41), k8s-worker02(42), harbor01(61).

## Repository Structure

```
bank-mall-platform/
├── apps/                              # 4 Spring Boot microservices (Maven parent POM)
│   ├── auth-service/                  # BCrypt + JWT, jjwt 0.12.6
│   ├── account-service/               # JPA + Flyway + @Version optimistic lock + idempotency keys
│   ├── payment-service/               # RestClient + compensation retry (3x) + ERROR_MANUAL_REVIEW
│   └── notification-service/          # Notification persistence, event logging
├── infra/                             # Infrastructure as Code
│   ├── kubernetes/base/               # K8s manifests — deployments, services, ingress, HPA, monitoring, security, Jaeger, SealedSecret
│   ├── kubernetes/cloud/              # Kustomize overlay for ACK cloud (MySQL emptydir, LB ingress, no nodeName/OTEL)
│   ├── kubernetes/argocd/             # ArgoCD Application CRs (bank-mall, monitoring, ingress-nginx)
│   └── dashboards/                    # Grafana dashboard JSON (business overview + SLI/SLO)
├── scripts/                           # build-images.sh, deploy.sh, smoke-test.sh, ci.sh, preflight.sh, teardown.sh, recover.sh
├── .github/workflows/ci.yml           # 5-job pipeline: gitleaks → semgrep/test → build+trivy → feishu
├── tests/                             # k6 load test + shell payment-load
├── docs/                              # 25+ technical documents
├── sql/initdb/                        # MySQL bootstrap SQL
├── pipelines/                         # gitleaks / semgrep / trivy configs
├── Makefile
├── ROADMAP.md                         # Phase status, V2 plans, explicit exclusions
└── SECURITY.md                        # Security practices and production gaps
```

## Architecture

```
Ingress Nginx (NodePort 30080)
  ├── /auth/*          → auth-service:8081       (BCrypt + JWT, jjwt 0.12.6)
  ├── /account/*       → account-service:8082     (JPA + @Version optimistic lock)
  ├── /payment/*       → payment-service:8083     (RestClient + compensation retry)
  └── /notification/*  → notification-service:8084 (event logging)

payment-service → account-service (debit/credit/reverse)
payment-service → notification-service (log)
All services → Jaeger (OTLP gRPC 4317 / HTTP 4318)
```

## Network Constraints (GFW)

- `ghcr.io` and `docker.io` blocked — use `ghcr.nju.edu.cn` mirror or Harbor proxy
- `objects.githubusercontent.com` blocked — download binaries on Windows, scp to VM
- Harbor runs HTTP-only on `10.0.0.61` — `insecure-registries` must be in `/etc/docker/daemon.json`
- containerd v2 uses `hosts.toml` at `/etc/containerd/certs.d/10.0.0.61/` (NOT the old `plugins."io.containerd.grpc.v1.cri"` path)
- Maven uses `maven.aliyun.com` mirror; Dockerfile `settings.xml` uses Maven Central (for GitHub Actions)

## Common Commands

```bash
# Build single service
cd apps/auth-service && mvn clean package -DskipTests

# Run with H2 (no MySQL needed)
java -jar target/auth-service-1.0.0.jar --spring.profiles.active=h2

# Run tests
mvn test                                      # single service
mvn test -pl apps/auth-service                # from root
cd apps && for svc in */; do cd $svc && mvn test && cd ..; done  # all services

# Docker (on harbor01 — context is apps/, not per-service)
docker build -t 10.0.0.61/bank-mall/auth-service:2.0.0 -f apps/auth-service/Dockerfile apps/
docker push 10.0.0.61/bank-mall/auth-service:2.0.0

# K8s (on master01)
kubectl apply -f infra/kubernetes/base/
kubectl get pods -n bank-mall

# Makefile shortcuts
make help          # list all targets
make build         # build all 4 services
make build-auth    # build single service
make test          # run all JUnit tests
make lint          # Semgrep + Gitleaks
make clean         # Maven clean all
make push          # build & push Docker images to Harbor
make preflight     # pre-deploy env check
make deploy        # deploy full platform to K8s
make smoke-test    # quick Ingress → 4 services health check
make ci            # one-command internal CI (build → push → deploy → smoke)
make teardown      # destroy all bank-mall resources
```

## Key Technical Decisions

- **RestClient** (not RestTemplate/WebClient) for sync HTTP between services
- **Compensation logic** (not Seata) — try-catch + 3x manual retry on reverse failure, `ERROR_MANUAL_REVIEW` status. Payment has idempotency key on `orderId` via DB UNIQUE constraint.
- **Jaeger all-in-one 1.60 LTS** + Badger storage + PVC, Recreate strategy (not Operator)
- **OTEL agent injection** via initContainer — image pulled from Harbor to emptyDir, then `JAVA_TOOL_OPTIONS: -javaagent:/otel/opentelemetry-javaagent.jar`. Cloud overlay (`infra/kubernetes/cloud/patches/remove-otel.yaml`) strips this for ACK deployments.
- **Sealed Secrets** for GitOps-native secret management — `infra/kubernetes/base/sealed-bank-mall.yaml` is the only encrypted secret format in Git. Plaintext `secret.yaml.example` is a template only. Real credentials never committed.
- **NetworkPolicy** deny-all baseline with explicit whitelist rules per service (DNS, ingress, monitoring, MySQL, cross-service, Jaeger)
- **`ddl-auto: validate`** in production; use `update` only for first deploy on empty databases
- **status field is VARCHAR** not ENUM — adding states doesn't require DDL
- **Two deployment targets**: `infra/kubernetes/base/` (VMware lab — NodePort, hostPath, nodeName pinning) and `infra/kubernetes/cloud/` (ACK — LoadBalancer, emptydir, no nodeName, no OTEL)

## Build Context (Critical)

Dockerfiles use `apps/` as build context (not per-service directory) because the
Maven parent POM (`apps/pom.xml`) must be reachable via `<relativePath>../pom.xml</relativePath>`.

```bash
# Correct build command:
docker build -t <image> -f apps/auth-service/Dockerfile apps/
# NOT: docker build -t <image> apps/auth-service/
```

## CI/CD Pipeline

5 sequential jobs in `.github/workflows/ci.yml`:
1. **gitleaks** — secret detection, first gate, blocks on finding
2. **semgrep** + **test** — parallel: SAST (java-lang-security, generic-secrets, java-spring-security) and JUnit matrix (4 services)
3. **build-and-scan** (main only) — Maven package → Docker build → Trivy scan (HIGH/CRITICAL = hard gate, exit-code 1)
4. **notify** (main only) — Feishu webhook result notification

`pipelines/` contains local gitleaks/semgrep/trivy configs for pre-commit and internal CI parity.

## Git Branches

Use `feature/<descriptive-name>` naming. Past branches deleted after merge.

## Known Pitfalls

1. **K8s liveness probe**: minimum `initialDelaySeconds: 120` — SB 4.0.6 + JPA boots in 60-80s
2. **`JAVA_TOOL_OPTIONS: -javaagent:/otel/...`** — cloud deployments without OTEL must set this to `""` (see cloud patches)
3. **NetworkPolicy**: jaeger namespace ingress needs `name=bank-mall` label on bank-mall ns
4. **SSH heredoc** never works in VM terminals — push to GitHub then pull
5. **PV `Retain` policy**: PVC deletion doesn't release PV — must `kubectl delete pv` manually
6. **containerd v2 config**: use `hosts.toml` not `plugins."io.containerd.grpc.v1.cri"` — wrong path causes silent pull failures
7. **Prometheus port relabel**: do NOT replace `__address__` with port-only — k8s_sd provides correct Pod IP:port
8. **Loki pipeline**: `cri: {}` in Promtail `pipeline_stages` silently drops all log lines with containerd CRI — remove it entirely
9. **Calico after VM suspend**: `connection is unauthorized` → `kubectl delete pod -n calico-system <calico-node-pod>` on affected node

## Key Docs

| Doc | Purpose |
|-----|---------|
| `docs/execution-plan.md` | S0-S6 full plan |
| `docs/execution-record.md` | Design deviations, pitfalls, interview material |
| `docs/13-design-decisions.md` | Technology choices with rationale |
| `docs/14-troubleshooting-handbook.md` | Debugging guide by problem category |
| `docs/26-final-verification-checklist.md` | Delivery verification |
| `docs/polish-list.md` | Non-blocking improvements for S5 |
| `ROADMAP.md` | Phase status, explicit V2 plans, explicit exclusions |
| `SECURITY.md` | Security practices and production gaps |
| `CONTRIBUTING.md` | Dev setup, pre-commit hooks, PR checklist |
