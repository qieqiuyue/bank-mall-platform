# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bank Mall Cloud-Native Platform — 4 Java microservices (auth, account, payment, notification) on a VMware K8s cluster with full observability stack. Tech: Java 21, Spring Boot 4.0.6, MySQL 8.0, containerd, Calico.

**4 VM cluster (NAT 10.0.0.0/24):** k8s-master01(31), k8s-worker01(41), k8s-worker02(42), harbor01(61).

## Network Constraints (GFW)

- `ghcr.io` and `docker.io` blocked — use `ghcr.nju.edu.cn` mirror or Harbor proxy
- `objects.githubusercontent.com` blocked — download binaries on Windows, scp to VM
- Harbor runs HTTP-only on `10.0.0.61` — `insecure-registries` must be in `/etc/docker/daemon.json`
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

# Docker (on harbor01)
docker build -t 10.0.0.61/bank-mall/auth-service:2.0.0 -f apps/auth-service/Dockerfile apps/
docker push 10.0.0.61/bank-mall/auth-service:2.0.0

# K8s (on master01)
kubectl apply -f infra/kubernetes/base/
kubectl get pods -n bank-mall

# Makefile shortcuts
make build-auth    # build one service
make test          # run all tests
make ci            # full internal CI pipeline
make smoke-test    # quick health check
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

## Key Technical Decisions

- **RestClient** (not RestTemplate/WebClient) for sync HTTP between services
- **Compensation logic** (not Seata) — try-catch + 3x manual retry on reverse failure, `ERROR_MANUAL_REVIEW` status
- **Jaeger all-in-one 1.60 LTS** + Badger storage + PVC, Recreate strategy (not Operator)
- **Sealed Secrets** for GitOps-native secret management, zero plaintext in Git
- **NetworkPolicy** deny-all baseline with explicit whitelist rules per service
- **OTEL agent injection** via initContainer (Harbor image → emptyDir), not hostPath
- **`ddl-auto: validate`** in production; use `update` only for first deploy on empty databases
- **status field is VARCHAR** not ENUM — adding states doesn't require DDL

## Build Context

Dockerfiles use `apps/` as build context (not per-service directory) because the
Maven parent POM (`apps/pom.xml`) must be reachable via `<relativePath>../pom.xml</relativePath>`.

```bash
# Correct build command:
docker build -t <image> -f apps/auth-service/Dockerfile apps/
# NOT: docker build -t <image> apps/auth-service/
```

## Git Branches

Use `feature/<descriptive-name>` naming. Past branches deleted after merge. Active branch: `feature/ci-pipeline` (S3 CI/CD + ACK cloud).

## Known Pitfalls

1. **K8s liveness probe**: minimum `initialDelaySeconds: 120` — SB 4.0.6 + JPA boots in 60-80s
2. **`JAVA_TOOL_OPTIONS: -javaagent:/otel/...`** — cloud deployments without OTEL must set this to `""`
3. **NetworkPolicy**: jaeger namespace ingress needs `name=bank-mall` label on bank-mall ns
4. **SSH heredoc** never works in VM terminals — push to GitHub then pull
5. **PV `Retain` policy**: PVC deletion doesn't release PV — must `kubectl delete pv` manually

## Key Docs

| Doc | Purpose |
|-----|---------|
| `docs/execution-plan.md` | S0-S6 full plan |
| `docs/execution-record.md` | Design deviations, pitfalls, interview material |
| `docs/13-design-decisions.md` | Technology choices with rationale |
| `docs/14-troubleshooting-handbook.md` | Debugging guide by problem category |
| `docs/26-final-verification-checklist.md` | Delivery verification |
| `docs/polish-list.md` | Non-blocking improvements for S5 |
| `ROADMAP.md` | Phase status, V2 plans, explicit exclusions |
