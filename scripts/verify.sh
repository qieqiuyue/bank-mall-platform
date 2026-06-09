#!/usr/bin/env bash
# verify.sh — 部署后全量验证（审计修复 30 项 + 运行状态）
# 用法: bash scripts/verify.sh
# 对应文档: docs/production-readiness-checklist.md
set -euo pipefail

NODE_IP="${NODE_IP:-10.0.0.41}"
NODE_PORT="${NODE_PORT:-30080}"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

FAILS=0

echo "=========================================="
echo " Bank Mall — Deployment Verification"
echo "=========================================="
echo ""

# ── 1. Cluster ──
echo "── 1. Cluster ──"
kubectl get nodes --no-headers 2>/dev/null | tee /tmp/verify_nodes.txt
NODE_COUNT=$(wc -l < /tmp/verify_nodes.txt)
READY_COUNT=$(grep -c " Ready" /tmp/verify_nodes.txt || echo 0)
if [ "$READY_COUNT" -eq "$NODE_COUNT" ] && [ "$NODE_COUNT" -gt 0 ]; then
  pass "All $NODE_COUNT nodes Ready"
else
  fail "Only $READY_COUNT/$NODE_COUNT nodes Ready"
  FAILS=$((FAILS + 1))
fi

# ── 2. Pods ──
echo ""
echo "── 2. Pods ($NODE_IP:$NODE_PORT) ──"
kubectl get pods -n bank-mall --no-headers | tee /tmp/verify_pods.txt
NOT_RUNNING=$(grep -cv "Running" /tmp/verify_pods.txt || echo 0)
if [ "$NOT_RUNNING" -eq 0 ]; then
  pass "All pods Running"
else
  fail "$NOT_RUNNING pod(s) not Running"
  FAILS=$((FAILS + 1))
fi

for svc in auth-service account-service payment-service notification-service; do
  count=$(grep -c "$svc" /tmp/verify_pods.txt 2>/dev/null | grep -v "^0$" || echo 0)
  running=$(grep "$svc" /tmp/verify_pods.txt 2>/dev/null | grep -c "Running" || echo 0)
  [ "$running" -ge 2 ] && pass "  $svc: $running replicas" || { fail "  $svc: $running/$count Running (need ≥2)"; FAILS=$((FAILS + 1)); }
done

# ── 3. MySQL StatefulSet ──
echo ""
echo "── 3. MySQL ──"
kubectl get statefulset mysql -n bank-mall >/dev/null 2>&1 && pass "StatefulSet exists" || { fail "StatefulSet not found"; FAILS=$((FAILS + 1)); }
kubectl get pvc mysql-pvc -n bank-mall -o jsonpath='{.status.phase}' 2>/dev/null | grep -q Bound && pass "PVC Bound" || { fail "PVC not Bound"; FAILS=$((FAILS + 1)); }

# ── 4. HPA ──
echo ""
echo "── 4. HPA ──"
for svc in auth-service account-service payment-service notification-service; do
  min=$(kubectl get hpa ${svc}-hpa -n bank-mall -o jsonpath='{.spec.minReplicas}' 2>/dev/null || echo "0")
  [ "$min" -ge 2 ] && pass "$svc minReplicas=$min" || { fail "$svc minReplicas=$min"; FAILS=$((FAILS + 1)); }
done

# ── 5. Probes ──
echo ""
echo "── 5. Probes ──"
for svc in auth-service account-service payment-service notification-service; do
  probe=$(kubectl get deployment $svc -n bank-mall -o jsonpath='{.spec.template.spec.containers[0].livenessProbe.httpGet.path}' 2>/dev/null || echo "MISSING")
  [[ "$probe" == "/actuator/health/liveness" ]] && pass "$svc: $probe" || { fail "$svc: $probe (expected /actuator/health/liveness)"; FAILS=$((FAILS + 1)); }
done

# ── 6. Prometheus PVC ──
echo ""
echo "── 6. Prometheus ──"
kubectl get pvc prometheus-pvc -n monitoring -o jsonpath='{.status.phase}' 2>/dev/null | grep -q Bound && pass "PVC Bound" || { fail "PVC not Bound"; FAILS=$((FAILS + 1)); }

# ── 7. Grafana ──
echo ""
echo "── 7. Grafana ──"
GRAFANA_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://${NODE_IP}:30300/api/dashboards/home" 2>/dev/null || echo "000")
[ "$GRAFANA_CODE" = "401" ] && pass "Requires login (401)" || { fail "Returned $GRAFANA_CODE (expected 401)"; FAILS=$((FAILS + 1)); }

# ── 8. Smoke Test ──
echo ""
echo "── 8. Smoke Test ──"
bash scripts/smoke-test.sh 2>&1 | tail -4

# ── 9. Jaeger ──
echo ""
echo "── 9. Jaeger ──"
JAEGER_INGRESS=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://${NODE_IP}:${NODE_PORT}/jaeger/" 2>/dev/null || echo "000")
JAEGER_NP=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://${NODE_IP}:31686/jaeger/" 2>/dev/null || echo "000")
if [ "$JAEGER_INGRESS" = "200" ] || [ "$JAEGER_INGRESS" = "302" ]; then
  pass "Ingress accessible ($JAEGER_INGRESS)"
elif [ "$JAEGER_NP" = "200" ] || [ "$JAEGER_NP" = "302" ]; then
  warn "Ingress=$JAEGER_INGRESS, NodePort=$JAEGER_NP (In  gress broken, use :31686)"
else
  fail "Unreachable — Ingress=$JAEGER_INGRESS, NodePort=$JAEGER_NP"
  FAILS=$((FAILS + 1))
fi

# ── Summary ──
echo ""
echo "=========================================="
if [ "$FAILS" -eq 0 ]; then
  echo -e " Status: ${GREEN}ALL CLEAR${NC}"
else
  echo -e " Status: ${RED}$FAILS issue(s) remain${NC}"
fi
echo "=========================================="
