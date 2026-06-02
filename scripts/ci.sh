#!/usr/bin/env bash
# CI/CD Pipeline: build → test → push → deploy → verify
# 在 Harbor 节点或任何有 Docker + kubectl 的节点上运行
set -euo pipefail

REGISTRY="${REGISTRY:-10.0.0.61}"
NAMESPACE="${NAMESPACE:-bank-mall}"
VERSION="${VERSION:-1.0.0}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_DIR="${ROOT_DIR}/bank-digital-platform"
K8S_BASE="${ROOT_DIR}/k8s/base"
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

# ========== Stage 1: Maven Build ==========
log_section "Stage 1/5: Maven Build"

for service in "${SERVICES[@]}"; do
  dir="${PLATFORM_DIR}/${service}"
  echo "Building ${service}..."
  cd "${dir}"
  mvn clean package -DskipTests -q
  log_pass "${service} built"
done

# ========== Stage 2: Docker Build ==========
log_section "Stage 2/5: Docker Build & Push"

for service in "${SERVICES[@]}"; do
  image="${REGISTRY}/${NAMESPACE}/${service}:${VERSION}"
  echo "Building ${image}..."
  docker build -t "${image}" "${PLATFORM_DIR}/${service}"
  echo "Pushing ${image}..."
  docker push "${image}"
  log_pass "${image} pushed"
done

# ========== Stage 3: K8s Deploy (Infra) ==========
log_section "Stage 3/5: Deploy Infrastructure"

kubectl apply -f "${K8S_BASE}/namespace.yaml"
kubectl apply -f "${K8S_BASE}/configmap.yaml"
kubectl apply -f "${K8S_BASE}/secret.yaml"

echo "Applying MySQL storage + deployment..."
kubectl apply -f "${K8S_BASE}/mysql/"

echo "Waiting for MySQL to be ready..."
kubectl wait --for=condition=ready pod -l app=mysql -n bank-mall --timeout=180s || { log_fail "MySQL failed to start"; exit 1; }
log_pass "MySQL deployed"

# ========== Stage 4: K8s Deploy (Apps) ==========
log_section "Stage 4/5: Deploy Applications"

for service in "${SERVICES[@]}"; do
  echo "Deploying ${service}..."
  kubectl apply -f "${K8S_BASE}/${service}/"
  kubectl rollout restart "deployment/${service}" -n bank-mall
done

echo "Waiting for all pods to be ready..."
kubectl wait --for=condition=ready pod -l app.kubernetes.io/part-of=bank-mall -n bank-mall --timeout=180s || { log_fail "Some pods failed to start"; }

# ========== Stage 5: Deploy Monitoring ==========
log_section "Stage 5/5: Deploy Monitoring"

kubectl create namespace monitoring 2>/dev/null || true
kubectl apply -f "${K8S_BASE}/monitoring/"
kubectl wait --for=condition=ready pod -l app.kubernetes.io/part-of=bank-mall -n monitoring --timeout=180s || { log_fail "Monitoring pods failed to start"; }

# ========== Verify ==========
log_section "Verification"

echo ""
echo "Pods (bank-mall):"
kubectl get pods -n bank-mall -o wide

echo ""
echo "Pods (monitoring):"
kubectl get pods -n monitoring -o wide

echo ""
echo "Services:"
kubectl get svc -n bank-mall
kubectl get svc -n monitoring

# Quick API smoke test via port-forward
echo ""
echo "Smoke test: auth-service health..."
kubectl port-forward -n bank-mall "svc/auth-service" 18081:8081 &
PF_PID=$!
sleep 2
RESP=$(curl -s --max-time 5 http://localhost:18081/api/auth/health 2>/dev/null || echo "FAIL")
kill "${PF_PID}" 2>/dev/null || true

if echo "${RESP}" | grep -q "UP"; then
  log_pass "auth-service is healthy"
else
  log_fail "auth-service health check failed (response: ${RESP})"
fi

# ========== Summary ==========
ELAPSED=$(( $(date +%s) - START_TIME ))
echo ""
log_section "Pipeline Complete in ${ELAPSED}s"
echo ""
echo "  Prometheus:  http://<node-ip>:30090"
echo "  Grafana:     http://<node-ip>:30300"
echo "  Dashboard:   Bank Mall - Service Overview"
echo ""
