# Bank Mall CloudNative — Agent Guide

## Architecture (what an agent won't infer from filenames)

- **4 Spring Boot 3.1 microservices** in `bank-digital-platform/`: auth, account, payment, notification.
- **Unified K8s manifests** in `k8s/base/` (supersedes `bank-digital-platform/*/k8s/` — those are legacy).
- **3 namespaces**: `bank-mall` (services), `monitoring` (Prometheus/Grafana/Loki), `ingress-nginx`.
- **All services use `com.bank.<svc>.api.ApiResponse<T>`** (code/message/data/timestamp). No raw String/Map returns.
- **V1 COMPLETE** (2026-05-29). V2 items (Redis, Jaeger, AlertManager HA, multi-master) are planned, NOT deployed. Never claim they exist.

## Execution Boundary

| Task | Where |
|------|-------|
| Edit code/docs, Git | Windows workstation |
| `mvn clean package -DskipTests` | Windows (has Maven + Java 17) |
| Docker build & push | k8s-harbor01 (10.0.0.61) — `docker` on root |
| `kubectl apply/delete/logs` | k8s-master01 (10.0.0.31) — root |
| `ctr pull` (manual image pull) | workers (10.0.0.41/42) — qian user, `sudo ctr -n k8s.io` |
| `kubectl --dry-run=client` | Windows (offline YAML validation) |

## Critical Environment Constraints

- **GFW**: registry.k8s.io / Docker Hub / GitHub HTTPS are blocked. Use `registry.aliyuncs.com/google_containers` for K8s images.
- **Harbor HTTP only** (10.0.0.61:80). No HTTPS. All pulls/pushes must use `--plain-http`.
- **containerd v2.2.1** on workers. Config path: `[plugins."io.containerd.grpc.v1.cri"]` is WRONG for v2. Use `hosts.toml` at `/etc/containerd/certs.d/10.0.0.61/`.
- **VMware NAT**. Suspending VMs can cause Calico `connection is unauthorized` → delete `calico-node` pod on affected node to fix.
- **Docker multi-stage builds fail on harbor01** (container DNS can't resolve `maven.aliyun.com`). Workaround: compile JARs on Windows → scp to harbor01 → use single-stage `COPY target/*.jar app.jar`.

## Hard-Earned Gotchas (skip these and you'll waste hours)

### Image Build & Push
```bash
# On Windows: compile JARs
cd bank-mall-cloudnative\bank-digital-platform
foreach ($svc in @("auth-service","account-service","payment-service","notification-service")) {
    Set-Location $svc; mvn clean package -DskipTests; Set-Location ..
}
# SCP JARs to harbor01 then build with one-liner Dockerfile:
docker build -f - -t 10.0.0.61/bank-mall/$svc:1.0.0 . <<'EOF'
FROM eclipse-temurin:17-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
EOF
# Harbor must be running (9 containers). Restart: cd /root/harbor && docker compose up -d
# If docker push fails with "connection refused", restart docker: systemctl restart docker
```

### Prometheus Scrape & Dashboard
- **Prometheus port relabel BUG**: the old config replaced `__address__` (Pod IP) with just the port number, causing all spring-boot targets to be DOWN. Fixed by removing the port relabel entirely — k8s_sd provides the correct address.
- **Dashboard queries MUST use `{job=~"bank-mall/.*"}`**, not `{namespace="bank-mall"}`. The k8s_sd relabel creates `job`/`pod`/`node` labels, NOT `namespace`.
- **CPU metric**: use `process_cpu_usage` (gauge 0-1), not `process_cpu_seconds_total` (only Prometheus itself has this).
- **Panel type**: `"graph"` is deprecated in Grafana 10.x, use `"timeseries"`.
- Dashboard JSON is provisioned in `k8s/base/monitoring/grafana-configmap.yaml`. Apply + `kubectl rollout restart deployment/grafana -n monitoring` to pick up changes.

### Loki / Promtail
- **`cri: {}` pipeline stage silently drops ALL log lines** in Promtail 2.9.8 with containerd CRI logs. Remove `pipeline_stages` entirely — raw CRI format works with LogQL.
- **kube-proxy ClusterIP POST fails** (EOF/Empty reply) for Loki push. Workaround: use Pod IP direct instead of ClusterIP.
- **Version match is necessary but NOT sufficient**: Loki 2.9.12 + Promtail 2.9.8 still EOF until cri:{} was removed.
- **Loki 2.9.x uses boltdb-shipper + schema v11**, NOT TSDB/v13. `wal.dir: /loki/wal` is required (UID 10001 can't write `/wal`).

### Secret / Security
- **`secret.yaml` contains PLACEHOLDER values only**. Real secrets were removed via `git filter-branch`. Never commit real credentials.
- **`harbor-pull` imagePullSecrets removed** from all 4 deployment YAMLs (was a dangling reference, not provisioned by deploy.sh).

## Verification

```bash
# Fast smoke test (runs on master01, tests Ingress → all 4 services):
bash ./scripts/smoke-test.sh   # expects NODE_IP=10.0.0.41 NODE_PORT=30080

# Full YAML dry-run (runs on Windows):
kubectl apply --dry-run=client -f k8s/base

# Grafana Dashboard: http://10.0.0.41:30300/d/bank-mall-overview
# Grafana Alerting: http://10.0.0.41:30300/alerting/list
# Prometheus Targets: http://10.0.0.41:30090/targets
```

## Project Skills

All at `.opencode/skills/`. Load with `skill` tool when relevant:
- `k8s-cluster-setup`, `k8s-deploy`, `microservice-dev` — operations
- `china-gfw-k8s-debugging` — comprehensive GFW/network/security troubleshooting
- `interview-guide` — 23 Q&A + 6 blind-spot deep dives
- `loki-health-check`, `loki-pipeline-debug`, `loki-cost-audit`, `promtail-config-validate` — logging ops

## Key Documents

- `README.md` — full component inventory, doc index, V1/V2 boundary
- `docs/26-final-verification-checklist.md` — offline audit: what's deployed, what's planned, verify commands
- `docs/27-worker-harbor-config.md` — containerd v2 Harbor HTTP setup, ctr pull workaround, 7-step troubleshooting timeline
- `docs/28-dashboard-fix-postmortem.md` — Prometheus/Grafana dashboard 4-round fix, 5 lessons learned
- `docs/22-loki-promtail-postmortem.md` — Loki silent data loss investigation
- `docs/interview/interview-qa.md` — 23 interview Q&A (Chinese)
