#!/usr/bin/env bash
# CI/CD Pipeline: test → build → scan → push → deploy (ArgoCD GitOps)
# Run on harbor01 or any node with Docker + Maven + kubectl.
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────
REGISTRY="${REGISTRY:-10.0.0.61}"
NAMESPACE="${NAMESPACE:-bank-mall}"
VERSION="${VERSION:-$(git describe --tags --always 2>/dev/null || echo '1.0.0')}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPS_DIR="${ROOT_DIR}/apps"
K8S_BASE="${ROOT_DIR}/infra/kubernetes/base"
START_TIME=$(date +%s)

SERVICES=(
  "auth-service"
  "account-service"
  "payment-service"
  "notification-service"
)

COLOR_GREEN='\033[0;32m'
COLOR_BLUE='\033[0;34m'
COLOR_RED='\033[0;31m'
COLOR_RESET='\033[0m'

log_section() { echo -e "${COLOR_BLUE}===[ $1 ]===${COLOR_RESET}"; }
log_pass()   { echo -e "${COLOR_GREEN}[PASS]${COLOR_RESET} $1"; }
log_fail()   { echo -e "${COLOR_RED}[FAIL]${COLOR_RESET} $1"; }

# ── Stage 1/6: Maven Test (optional) ─────────────────────────────
if [[ "${RUN_TESTS:-false}" == "true" ]]; then
  log_section "Stage 1/6: Maven Test"
  for service in "${SERVICES[@]}"; do
    cd "${APPS_DIR}/${service}"
    mvn test -q
    log_pass "${service} tests passed"
  done
else
  log_section "Stage 1/6: Maven Test (skipped — set RUN_TESTS=true to enable)"
fi

# ── Stage 2/6: Maven Package ────────────────────────────────────
log_section "Stage 2/6: Maven Package"

for service in "${SERVICES[@]}"; do
  cd "${APPS_DIR}/${service}"
  mvn clean package -DskipTests -q
  log_pass "${service} packaged"
done

# ── Stage 3/6: Docker Build + Push Harbor ───────────────────────
log_section "Stage 3/6: Docker Build & Push"

for service in "${SERVICES[@]}"; do
  image="${REGISTRY}/${NAMESPACE}/${service}:${VERSION}"
  echo "Building ${image}..."
  docker build -t "${image}" -f "${APPS_DIR}/${service}/Dockerfile" "${APPS_DIR}"
  echo "Pushing ${image}..."
  docker push "${image}"
  log_pass "${image} pushed (plain-http)"
done

# ── Stage 4/6: Trivy Scan (soft gate) ───────────────────────────
log_section "Stage 4/6: Trivy Scan (soft gate — records, does not block)"

# trivy-db is an OCI artifact (NOT a container image).
# Hosted on ghcr.io/aquasecurity/trivy-db — GFW throttles to 30-50 KiB/s.
# One-time cost: first download caches DB to ~/.cache/trivy/db/
# After that, --skip-db-update makes all scans instant (GFW-safe).
#
# Pre-cache (run once on harbor01):
#   trivy image --download-db-only
#   # ~95 MiB, ~30 min over GFW. One-time cost.

TRIVY_CACHE_DIR="${TRIVY_CACHE_DIR:-${HOME}/.cache/trivy}"

if command -v trivy >/dev/null 2>&1; then
  if [[ -d "${TRIVY_CACHE_DIR}/db" ]] && [[ -f "${TRIVY_CACHE_DIR}/db/metadata.json" ]]; then
    echo "[INFO] Trivy DB cached — --skip-db-update (GFW-safe)"
    DB_FLAGS="--skip-db-update"
  else
    echo "[WARN] Trivy DB not cached — skipping scan (GFW blocks ghcr.io download)"
    echo "  Pre-cache once: trivy image --download-db-only"
    echo "  ($(du -sh ${TRIVY_CACHE_DIR}/db 2>/dev/null || echo '0') cached so far)"
    DB_FLAGS="__SKIP__"
  fi

  if [[ "${DB_FLAGS}" != "__SKIP__" ]]; then
    for service in "${SERVICES[@]}"; do
      image="${REGISTRY}/${NAMESPACE}/${service}:${VERSION}"
      echo "Scanning ${image}..."
      trivy image ${DB_FLAGS} --severity HIGH,CRITICAL --exit-code 0 "${image}" 2>&1 || true
    done
  fi
else
  echo "[WARN] Trivy not installed — skipping scan"
  echo "  Install: curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh"
  echo "  Pre-cache DB (one-time): trivy image --download-db-only"
fi

# ── Stage 5/6: Deploy (GitOps via ArgoCD) ───────────────────────
log_section "Stage 5/6: Deploy (Git commit + push → ArgoCD sync)"

echo "Committing image tag ${VERSION} to Git..."
cd "${ROOT_DIR}"

# Update image tags in deployment YAMLs (if needed)
# ArgoCD watches infra/kubernetes/base/ and auto-syncs on push
if git diff --quiet && git diff --cached --quiet; then
  echo "No changes to commit — ArgoCD will maintain current state"
else
  git add -A
  git commit -m "deploy: update image tags to ${VERSION}

[skip ci] — ArgoCD auto-sync triggers deployment"
  git push origin "$(git branch --show-current)"
  log_pass "Pushed to Git — ArgoCD will auto-sync"
fi

# ── Stage 6/6: Verify + Feishu ──────────────────────────────────
log_section "Stage 6/6: Verify"

echo ""
echo "Pods (bank-mall):"
kubectl get pods -n bank-mall -o wide

echo ""
echo "Pods (jaeger):"
kubectl get pods -n jaeger -o wide 2>/dev/null || echo "(jaeger namespace not found)"

echo ""
bash "${ROOT_DIR}/scripts/smoke-test.sh"

# ── Feishu Notification ─────────────────────────────────────────
FEISHU_WEBHOOK="${FEISHU_WEBHOOK:-}"
if [[ -n "${FEISHU_WEBHOOK}" ]]; then
  if curl -s --max-time 5 https://open.feishu.cn >/dev/null 2>&1; then
    curl -X POST "${FEISHU_WEBHOOK}" \
      -H "Content-Type: application/json" \
      -d "{\"msg_type\":\"text\",\"content\":{\"text\":\"Bank Mall CI/CD: SUCCESS (${VERSION})\"}}" \
      >/dev/null 2>&1 || true
  else
    echo "[WARN] Feishu unreachable from internal network"
  fi
fi

# ── Summary ─────────────────────────────────────────────────────
ELAPSED=$(( $(date +%s) - START_TIME ))
echo ""
log_section "Pipeline Complete in ${ELAPSED}s"
echo ""
echo "  Prometheus:  http://<node-ip>:30090"
echo "  Grafana:     http://<node-ip>:30300"
echo "  Jaeger:      http://<node-ip>:31686/jaeger"
echo "  Dashboard:   Bank Mall - Service Overview"
echo ""
