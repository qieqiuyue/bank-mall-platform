# Jaeger to Grafana Tempo Migration

## Why

- **Jaeger 1.60** reaches end-of-life on 2025-12-31.
- **Grafana Tempo** is the current mainstream distributed tracing backend, with active development and community support.
- The project already uses Grafana + Loki. Adding Tempo completes the **Grafana LGTM stack** (Loki-Grafana-Tempo-Prometheus), allowing metrics, logs, and traces all in a single Grafana UI.

## What Changed

### New: `infra/kubernetes/base/tempo/`

| File | Purpose |
|------|---------|
| `tempo-configmap.yaml` | Tempo all-in-one config (OTLP gRPC :4317, OTLP HTTP :4318, query :3200, local filesystem storage) |
| `tempo-deployment.yaml` | Single-replica Deployment in `jaeger` namespace (non-root, 300s startup window) |
| `tempo-service.yaml` | Services: `jaeger-collector` (OTLP) + `jaeger-query` (NodePort 31686→:3200) |
| `tempo-pvc.yaml` | PV (hostPath `/data/tempo-data`) + PVC (5Gi) |

### Modified

| File | Change |
|------|--------|
| `monitoring/grafana-configmap.yaml` | Added Tempo datasource (`http://jaeger-query.jaeger.svc.cluster.local:16686`) |
| `security/allow-jaeger-ingress.yaml` | Pod selector `app: jaeger` → `app: tempo` |
| `security/allow-ingress-to-jaeger.yaml` | Pod selector `app: jaeger` → `app: tempo` |

### Unchanged (backwards compatibility preserved)

| Resource | Why unchanged |
|----------|---------------|
| OTEL endpoint (`configmap.yaml`) | `http://jaeger-collector.jaeger.svc.cluster.local:4317` — same Service name, same port |
| Service name `jaeger-collector` | OTLP clients don't need to reconfigure |
| Service name `jaeger-query` | NodePort 31686 still works, screenshots/URLs preserved |
| `jaeger` namespace | No namespace changes needed |
| `allow-services-egress.yaml` | References `jaeger` namespace by label — unchanged |
| Bank-mall service deployments | No changes at all |

### Old Jaeger (deprecated but kept for reference)

The files in `infra/kubernetes/base/jaeger/` remain on disk for reference. Do **not** deploy them — `tempo/` is the replacement.

## Verification Steps

1. **Deploy Tempo:**
   ```bash
   kubectl apply -f infra/kubernetes/base/tempo/
   ```

2. **Check Tempo pod is running:**
   ```bash
   kubectl get pods -n jaeger -l app=tempo
   ```

3. **Verify OTLP ingestion** (send a trace or check logs):
   ```bash
   kubectl logs -n jaeger -l app=tempo | grep -i otlp
   ```

4. **Verify Query API:**
   ```bash
   kubectl port-forward -n jaeger svc/jaeger-query 3200:16686
   curl http://localhost:3200/ready   # should return "ready"
   ```

5. **Verify Grafana datasource:**
   - Open Grafana → Configuration → Data Sources
   - Confirm Tempo is listed and "Save & test" succeeds

6. **Verify OTEL trace flow:**
   ```bash
   # Check bank-mall service logs for successful trace export
   kubectl logs -n bank-mall -l app=auth-service | grep -i "export"
   ```

7. **Remove old Jaeger** (after confirming Tempo works):
   ```bash
   kubectl delete -f infra/kubernetes/base/jaeger/jaeger-deployment.yaml
   kubectl delete -f infra/kubernetes/base/jaeger/jaeger-service.yaml
   kubectl delete -f infra/kubernetes/base/jaeger/jaeger-storage.yaml
   ```

## Observability Stack (Post-Migration)

```
┌─────────────────────────────────────────────────┐
│                   Grafana                        │
│         (single pane of glass)                   │
├──────────┬──────────────┬───────────────────────┤
│  Metrics │    Logs      │      Traces           │
│Prometheus│    Loki      │      Tempo            │
│   :9090  │   :3100      │ :4317/:4318 → :3200   │
└──────────┴──────────────┴───────────────────────┘
```

Grafana LGTM stack — all three pillars in one UI.
