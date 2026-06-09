#!/usr/bin/env bash
set -euo pipefail

REGISTRY="${REGISTRY:-10.0.0.61}"
NAMESPACE="${NAMESPACE:-bank-mall}"
VERSION="${VERSION:-1.0.0}"
HARBOR_USER="${HARBOR_USER:-admin}"
HARBOR_PASS="${HARBOR_PASS:-<HARBOR_PASSWORD>}"

BUSINESS_IMAGES=(
  "auth-service"
  "account-service"
  "payment-service"
  "notification-service"
)

THIRD_PARTY_IMAGES=(
  "mysql:8.0"
  "grafana/grafana:10.4.0"
  "prom/prometheus:v2.53.0"
  "grafana/loki:2.9.12"
  "grafana/promtail:2.9.8"
)

K8S_IMAGES=(
  "registry.aliyuncs.com/google_containers/pause:3.10.2"
)

COLOR_GREEN='\033[0;32m'
COLOR_RED='\033[0;31m'
COLOR_YELLOW='\033[0;33m'
COLOR_RESET='\033[0m'

log_pass()   { echo -e "${COLOR_GREEN}[PASS]${COLOR_RESET} $1"; }
log_fail()   { echo -e "${COLOR_RED}[FAIL]${COLOR_RESET} $1"; }
log_warn()   { echo -e "${COLOR_YELLOW}[WARN]${COLOR_RESET} $1"; }

echo "=== Pre-flight Check for bank-mall Kubernetes Cluster ==="
echo ""

FAIL=0

# ========== 1. Check cluster connectivity ==========
echo "[1/7] Checking cluster connectivity..."
if kubectl cluster-info &>/dev/null; then
  log_pass "Kubernetes cluster is reachable"
else
  log_fail "Cannot connect to Kubernetes cluster"
  FAIL=$((FAIL+1))
fi

NODES=$(kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || echo "")
READY_NODES=$(kubectl get nodes --no-headers 2>/dev/null | grep -c " Ready" || echo "0")
TOTAL_NODES=$(echo "$NODES" | wc -l)

if [ "$READY_NODES" -eq "$TOTAL_NODES" ] && [ "$TOTAL_NODES" -gt 0 ]; then
  log_pass "All $TOTAL_NODES nodes are Ready"
else
  log_fail "Only $READY_NODES/$TOTAL_NODES nodes are Ready"
  FAIL=$((FAIL+1))
fi
echo ""

# ========== 2. Check Metrics Server ==========
echo "[2/7] Checking Metrics Server..."
if kubectl top nodes &>/dev/null; then
  log_pass "Metrics Server is working"
  kubectl top nodes --no-headers 2>/dev/null | head -5
else
  log_fail "Metrics Server not working (HPA will not function)"
  echo "  Fix: kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml"
  echo "  Then: kubectl patch deployment metrics-server -n kube-system --type=json -p='[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--kubelet-insecure-tls\"}]'"
  FAIL=$((FAIL+1))
fi
echo ""

# ========== 3. Check business images on worker nodes ==========
echo "[3/7] Checking business images on worker nodes..."
for node in $NODES; do
  echo "  Node: $node"
  for img in "${BUSINESS_IMAGES[@]}"; do
    FULL_IMG="${REGISTRY}/${NAMESPACE}/${img}:${VERSION}"
    if kubectl get pods -A -o jsonpath="{range .items[?(@.spec.nodeName==\"${node}\")]}{range .spec.containers[*]}{.image}{\"\\n\"}{end}{end}" 2>/dev/null | grep -q "${FULL_IMG}" 2>/dev/null; then
      log_pass "  ${img}: already in use on this node"
    else
      log_warn "  ${img}: not in use on ${node} - if HPA scales here, needs pre-pull"
      echo "    sudo ctr -n k8s.io images pull --plain-http --user ${HARBOR_USER}:${HARBOR_PASS} ${FULL_IMG}"
    fi
  done
done
echo ""

# ========== 4. Check third-party images ==========
echo "[4/7] Checking third-party images..."
for node in $NODES; do
  echo "  Node: $node"
  for img in "${THIRD_PARTY_IMAGES[@]}"; do
    log_warn "  ${img}: verify availability on ${node} (docker pull + ctr import if needed)"
  done
done
echo ""

# ========== 5. Check HPA ==========
echo "[5/7] Checking HPA..."
HPA_COUNT=$(kubectl get hpa -n bank-mall --no-headers 2>/dev/null | wc -l || echo "0")
if [ "$HPA_COUNT" -ge 4 ]; then
  log_pass "All 4 HPA resources found"
  kubectl get hpa -n bank-mall --no-headers 2>/dev/null | while read line; do
    echo "  $line"
  done
else
  log_fail "Only $HPA_COUNT/4 HPA resources found"
  echo "  Fix: kubectl apply -f infra/kubernetes/base/hpa/"
  FAIL=$((FAIL+1))
fi
echo ""

# ========== 6. Check Ingress on worker01 ==========
echo "[6/7] Checking Ingress controller..."
INGRESS_NODE=$(kubectl get pods -n ingress-nginx -o jsonpath='{.items[0].spec.nodeName}' 2>/dev/null || echo "")
INGRESS_READY=$(kubectl get pods -n ingress-nginx -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "False")

if [ "$INGRESS_NODE" = "k8s-worker01" ] && [ "$INGRESS_READY" = "True" ]; then
  log_pass "Ingress controller running on k8s-worker01 (Ready)"
else
  log_fail "Ingress controller on $INGRESS_NODE (Ready=$INGRESS_READY) - should be on k8s-worker01"
  FAIL=$((FAIL+1))
fi
echo ""

# ========== 7. Check critical services ==========
echo "[7/7] Checking critical services..."
for svc in mysql auth-service account-service payment-service notification-service; do
  READY=$(kubectl get pods -n bank-mall -l app="${svc}" --no-headers 2>/dev/null | grep -c "Running" || echo "0")
  TOTAL=$(kubectl get pods -n bank-mall -l app="${svc}" --no-headers 2>/dev/null | wc -l || echo "0")
  if [ "$READY" -eq "$TOTAL" ] && [ "$TOTAL" -gt 0 ]; then
    log_pass "${svc}: ${READY}/${TOTAL} Running"
  else
    log_fail "${svc}: ${READY}/${TOTAL} Running"
    FAIL=$((FAIL+1))
  fi
done
echo ""

# ========== Summary ==========
echo "=========================================="
if [ "$FAIL" -eq 0 ]; then
  log_pass "All pre-flight checks passed!"
else
  log_fail "$FAIL check(s) failed. Fix issues before proceeding."
fi
echo ""
echo "Quick commands:"
echo "  kubectl get hpa -n bank-mall          # HPA status"
echo "  kubectl top pods -n bank-mall          # Resource usage"
echo "  curl http://10.0.0.41:30080/auth/actuator/health  # Health check"
echo "  ab -n 10000 -c 50 http://10.0.0.41:30080/auth/actuator/health  # Stress test"