# Security Policy

## Reporting a Vulnerability

Please open a **GitHub Issue**. For sensitive disclosures, contact the repository owner directly.

## Implemented Practices

| Measure | Implementation |
|---------|---------------|
| **Sealed Secrets** | All credentials encrypted via Bitnami Sealed Secrets controller. `kubeseal` encrypts at rest; zero plaintext secrets in Git. [`infra/kubernetes/base/sealed-bank-mall.yaml`](infra/kubernetes/base/sealed-bank-mall.yaml) |
| **NetworkPolicy** | Default-deny in `bank-mall` namespace with explicit whitelist rules for DNS, ingress, MySQL, cross-service communication, monitoring ingress, and Jaeger egress/ingress. [`infra/kubernetes/base/security/`](infra/kubernetes/base/security/) |
| **PodSecurity** | [`namespace-psa.yaml`](infra/kubernetes/base/security/namespace-psa.yaml) enforces `baseline` with `restricted` audit warnings on `bank-mall` and `monitoring` namespaces. |
| **Pod Security Context** | All service containers and init containers enforce `runAsNonRoot`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, and `seccompProfile: RuntimeDefault`. |
| **PodDisruptionBudget** | 4 services each have `minAvailable: 1` to prevent voluntary disruption from draining all replicas. |
| **ResourceQuota + LimitRange** | Namespace-level resource caps prevent a single service from exhausting cluster resources. |
| **BCrypt** | Password hashing via `BCryptPasswordEncoder` in auth-service. |
| **JWT** | Stateless authentication with signed tokens (jjwt 0.12.6, HMAC-SHA). Tokens carry expiration; validation requires no server-side session store. |

## Planned (S3)

| Measure | Description |
|---------|-------------|
| **Gitleaks** | Pre-commit hook + CI gate for secret detection. Will include a documented block-and-fix case study. |
| **Trivy** | Container image vulnerability scanning. HIGH/CRITICAL findings will block deployment in GitHub Actions; soft-gate in internal `ci.sh`. |
| **Semgrep** | Static analysis security testing (SAST) in CI pipeline. |

## Production Gaps

This is an experimental lab deployment. The following are production requirements covered by design documentation but not implemented in the current cluster:

- **Secret auto-rotation** — External Secrets Operator + HashiCorp Vault (design documented; Sealed Secrets handles bootstrap credentials)
- **RBAC hardening** — fine-grained ClusterRole / Role bindings per service account
- **Audit logging** — Kubernetes audit policy + log shipping to external SIEM
- **Image signing** — Cosign / Sigstore for supply chain integrity

---

**Last updated**: 2026-06-04 | S2 Complete
