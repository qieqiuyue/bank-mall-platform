#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# quick-check.sh — 1-minute cluster health snapshot for bank-mall-platform
#
# Usage:   bash scripts/quick-check.sh
# Output:  GREEN (all ok) / YELLOW (some degraded) / RED (critical failures)
# =============================================================================

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

status_color() { printf "${GREEN}GREEN${NC}"; }
status_color_yellow() { printf "${YELLOW}YELLOW${NC}"; }
status_color_red() { printf "${RED}RED${NC}"; }

ok=0
warn=0
crit=0

echo ""
echo -e "${CYAN}=== bank-mall-platform Quick Health Check ===${NC}"
echo ""

# --- Nodes ---
echo ">>> kubectl get nodes"
kubectl get nodes 2>/dev/null || true
not_ready=$(kubectl get nodes --no-headers 2>/dev/null | grep -vc ' Ready ' || echo "99")
if [ "${not_ready}" -eq 0 ]; then
    echo -e "[NODES] ${GREEN}OK${NC} — all nodes Ready"
else
    echo -e "[NODES] ${RED}DEGRADED${NC} — ${not_ready} node(s) not Ready"
    crit=$((crit + 1))
fi
echo ""

# --- Bank-mall pods ---
echo ">>> kubectl get pods -n bank-mall"
kubectl get pods -n bank-mall 2>/dev/null || true
bank_not_ready=$(kubectl get pods -n bank-mall --no-headers 2>/dev/null | grep -vc 'Running' || echo "0")
if [ "${bank_not_ready}" -eq 0 ]; then
    echo -e "[BANK-MALL] ${GREEN}OK${NC} — all pods Running"
    ok=$((ok + 1))
elif [ -z "$(kubectl get pods -n bank-mall --no-headers 2>/dev/null)" ]; then
    echo -e "[BANK-MALL] ${YELLOW}WARN${NC} — namespace exists but no pods found"
    warn=$((warn + 1))
else
    echo -e "[BANK-MALL] ${RED}DEGRADED${NC} — ${bank_not_ready} pod(s) not Running"
    crit=$((crit + 1))
fi
echo ""

# --- Monitoring pods ---
echo ">>> kubectl get pods -n monitoring"
kubectl get pods -n monitoring 2>/dev/null || true
mon_not_ready=$(kubectl get pods -n monitoring --no-headers 2>/dev/null | grep -vc 'Running' || echo "0")
if [ "${mon_not_ready}" -eq 0 ]; then
    echo -e "[MONITORING] ${GREEN}OK${NC} — all pods Running"
    ok=$((ok + 1))
elif [ -z "$(kubectl get pods -n monitoring --no-headers 2>/dev/null)" ]; then
    echo -e "[MONITORING] ${YELLOW}WARN${NC} — namespace exists but no pods found"
    warn=$((warn + 1))
else
    echo -e "[MONITORING] ${YELLOW}WARN${NC} — ${mon_not_ready} pod(s) not Running"
    warn=$((warn + 1))
fi
echo ""

# --- Jaeger/Tempo pods ---
echo ">>> kubectl get pods -n jaeger (Tempo)"
if kubectl get pods -n jaeger 2>/dev/null | grep -q .; then
    kubectl get pods -n jaeger 2>/dev/null || true
    jaeger_not_ready=$(kubectl get pods -n jaeger --no-headers 2>/dev/null | grep -vc 'Running' || echo "0")
    if [ "${jaeger_not_ready}" -eq 0 ]; then
        echo -e "[JAEGER] ${GREEN}OK${NC} — all pods Running"
        ok=$((ok + 1))
    else
        echo -e "[JAEGER] ${YELLOW}WARN${NC} — ${jaeger_not_ready} pod(s) not Running"
        warn=$((warn + 1))
    fi
else
    echo "[JAEGER] namespace empty or not found — checking monitoring namespace..."
    if kubectl get pods -n monitoring -l app=tempo 2>/dev/null | grep -q .; then
        kubectl get pods -n monitoring -l app=tempo 2>/dev/null || true
        tempo_not_ready=$(kubectl get pods -n monitoring -l app=tempo --no-headers 2>/dev/null | grep -vc 'Running' || echo "0")
        if [ "${tempo_not_ready}" -eq 0 ]; then
            echo -e "[TEMPO(monitoring)] ${GREEN}OK${NC} — all pods Running"
            ok=$((ok + 1))
        else
            echo -e "[TEMPO(monitoring)] ${YELLOW}WARN${NC} — ${tempo_not_ready} pod(s) not Running"
            warn=$((warn + 1))
        fi
    else
        echo -e "[TEMPO] ${YELLOW}SKIP${NC} — not deployed"
    fi
fi
echo ""

# --- Auth service health ---
echo ">>> Auth service health endpoint"
auth_status=$(curl -s --max-time 10 "http://10.0.0.41:30080/auth/actuator/health" 2>/dev/null | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4 || echo "UNKNOWN")
if [ "${auth_status}" == "UP" ]; then
    echo -e "[AUTH] ${GREEN}OK${NC} — status: UP"
    ok=$((ok + 1))
elif [ "${auth_status}" == "DOWN" ]; then
    echo -e "[AUTH] ${RED}CRITICAL${NC} — status: DOWN"
    crit=$((crit + 1))
else
    echo -e "[AUTH] ${RED}CRITICAL${NC} — unreachable (${auth_status})"
    crit=$((crit + 1))
fi
echo ""

# --- Final verdict ---
echo -e "${CYAN}==========================================${NC}"
echo ""
echo "  OK: ${ok}  WARN: ${warn}  CRIT: ${crit}"
echo ""

if [ "${crit}" -gt 0 ]; then
    echo -e "  STATUS: ${RED}RED${NC} — critical failures detected. Run post-boot.sh."
    exit 2
elif [ "${warn}" -gt 0 ]; then
    echo -e "  STATUS: ${YELLOW}YELLOW${NC} — some components degraded. Review above."
    exit 1
else
    echo -e "  STATUS: ${GREEN}GREEN${NC} — all checks passed."
    exit 0
fi
