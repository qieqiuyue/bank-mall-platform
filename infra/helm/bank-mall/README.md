# Bank Mall Helm Chart — V1 Skeleton

**⚠️ NOT FOR DEPLOYMENT. Kustomize is the source of truth for V1.**

This Helm chart demonstrates the ability to package K8s manifests as a Helm chart.
Only `auth-service` and `account-service` are fully templated as samples.
The remaining services (`payment-service`, `notification-service`, MySQL, HPA,
monitoring, NetworkPolicy, Jaeger) use placeholder values and reference the
Kustomize YAMLs as the authoritative source.

## Usage (demonstration only)

```bash
# Dry-run (never applied to cluster)
helm template bank-mall . -f values-dev.yaml

# Lint
helm lint .
```

## Drift Policy

If the Kustomize YAMLs in `infra/kubernetes/base/` change, update the
corresponding Helm templates or mark them with `# TODO: sync from Kustomize`.

V1 deploys via `kubectl apply -f infra/kubernetes/base/`.
Helm deployment is a V2 capability.
