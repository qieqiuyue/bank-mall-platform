#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# post-boot.sh — Cold-boot recovery automation for 4-VM bank-mall K8s cluster
#
# Usage:   bash scripts/post-boot.sh
# Run from master01 after all 4 VMs have booted and SSH is available.
#
# Dependency order: Harbor → K8s nodes → CoreDNS/Calico → Bank-mall → Observability
#
# Fixes 8 known cold-boot failure patterns discovered during Day 3 ops session:
#   1. Harbor docker0 bridge loses IPv4
#   2. Harbor docker compose project stopped
#   3. Calico node routes stale after containerd/kubelet restart
#   4. Jaeger/Tempo needs memory + liveness check
#   5. Bank-mall pods probe timing (startupProbe config)
#   6. kube-proxy iptables rules stale after worker reboot
#   7. swap re-enables on cold boot
#   8. containerd/kubelet state stale after cold boot
# =============================================================================

HARBOR_IP="10.0.0.61"
MASTER_IP="10.0.0.31"
WORKER_IPS=("10.0.0.41" "10.0.0.42")
SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=5 -o BatchMode=yes"

PASS=()
FAIL=()
SKIP=()
START_TIME=$(date +%s)

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

mark_pass()  { PASS+=("$1"); printf "  ${GREEN}[PASS]${NC} %s\n" "$1"; }
mark_fail()  { FAIL+=("$1"); printf "  ${RED}[FAIL]${NC} %s\n" "$1"; }
mark_skip()  { SKIP+=("$1"); printf "  ${YELLOW}[SKIP]${NC} %s\n" "$1"; }

elapsed() {
    local now delta
    now=$(date +%s)
    delta=$((now - START_TIME))
    printf "%dm%ds" $((delta / 60)) $((delta % 60))
}

# -----------------------------------------------------------------------------
# Section 1: Harbor health (harbor01 10.0.0.61)
# Fixes: docker0 IPv4 loss, iptables FORWARD, compose stopped
# -----------------------------------------------------------------------------
section1() {
    echo ""
    echo -e "${CYAN}=== [1/6] Harbor Health (${HARBOR_IP}) ===${NC}"

    local ok=0

    # 1a — Restart docker (recreates docker0 bridge with IPv4)
    printf "  Restarting docker service ... "
    if timeout 30 ssh ${SSH_OPTS} "root@${HARBOR_IP}" "systemctl restart docker" 2>/dev/null; then
        sleep 3
        printf "done\n"
    else
        printf "FAILED\n"
        ok=1
    fi

    # 1b — Fix iptables FORWARD policy
    printf "  Setting iptables FORWARD ACCEPT ... "
    if timeout 10 ssh ${SSH_OPTS} "root@${HARBOR_IP}" "iptables -P FORWARD ACCEPT" 2>/dev/null; then
        printf "done\n"
    else
        printf "FAILED\n"
        ok=1
    fi

    # 1c — Start Harbor compose
    printf "  Starting Harbor (docker compose up -d) ... "
    if timeout 60 ssh ${SSH_OPTS} "root@${HARBOR_IP}" "cd /root/harbor && docker compose up -d" 2>/dev/null; then
        printf "done\n"
    else
        printf "FAILED\n"
        ok=1
    fi

    # 1d — Wait for harbor-core healthy
    printf "  Waiting for harbor-core (up to 120s) ... "
    local healthy=0
    for i in $(seq 1 24); do
        if timeout 10 ssh ${SSH_OPTS} "root@${HARBOR_IP}" "curl -s http://127.0.0.1/api/v2.0/health | grep -q healthy" 2>/dev/null; then
            healthy=1
            break
        fi
        sleep 5
    done
    if [ "${healthy}" -eq 1 ]; then
        printf "done\n"
    else
        printf "FAILED\n"
        ok=1
    fi

    if [ "${ok}" -eq 0 ]; then
        mark_pass "Harbor health (restart + iptables + compose up)"
    else
        mark_fail "Harbor health — check docker0, iptables, docker compose manually"
    fi
}

# -----------------------------------------------------------------------------
# Section 2: K8s node health
# Fixes: swap re-enable, stale containerd/kubelet state
# -----------------------------------------------------------------------------
section2() {
    echo ""
    echo -e "${CYAN}=== [2/6] K8s Node Health ===${NC}"

    local ok=0
    local NODES=("${MASTER_IP}" "${WORKER_IPS[@]}")

    for ip in "${NODES[@]}"; do
        printf "  Node %s: swapoff + restart containerd + restart kubelet\n" "${ip}"

        # 2a — disable swap
        printf "    swapoff -a ... "
        if timeout 15 ssh ${SSH_OPTS} "root@${ip}" "swapoff -a" 2>/dev/null; then
            printf "ok\n"
        else
            printf "FAILED\n"
            ok=1
        fi

        # 2b — restart containerd
        printf "    systemctl restart containerd ... "
        if timeout 15 ssh ${SSH_OPTS} "root@${ip}" "systemctl restart containerd" 2>/dev/null; then
            printf "ok\n"
            sleep 2
        else
            printf "FAILED\n"
            ok=1
        fi

        # 2c — restart kubelet
        printf "    systemctl restart kubelet ... "
        if timeout 15 ssh ${SSH_OPTS} "root@${ip}" "systemctl restart kubelet" 2>/dev/null; then
            printf "ok\n"
        else
            printf "FAILED\n"
            ok=1
        fi
    done

    # Wait for nodes to register and become Ready
    printf "  Waiting 60s for nodes to register ...\n"
    sleep 60

    printf "  kubectl get nodes:\n"
    kubectl get nodes -o wide 2>/dev/null || true

    local not_ready
    not_ready=$(kubectl get nodes --no-headers 2>/dev/null | grep -vc ' Ready ' || echo 99)
    if [ "${not_ready}" -eq 0 ]; then
        mark_pass "All 3 K8s nodes Ready"
    else
        mark_fail "${not_ready} node(s) not Ready — check kubelet/journalctl"
    fi
}

# -----------------------------------------------------------------------------
# Section 3: CoreDNS + Calico + kube-proxy
# Fixes: Calico stale BGP routes, kube-proxy stale iptables
# -----------------------------------------------------------------------------
section3() {
    echo ""
    echo -e "${CYAN}=== [3/6] CoreDNS + Calico + kube-proxy ===${NC}"

    local ok=0

    # 3a — Wait for CoreDNS
    printf "  CoreDNS wait (120s) ... "
    if kubectl wait --for=condition=ready pod -n kube-system -l k8s-app=kube-dns --timeout=120s 2>/dev/null; then
        mark_pass "CoreDNS pods Ready"
    else
        mark_fail "CoreDNS not ready after 120s"
        ok=1
    fi

    # 3b — Recycle Calico node pods (rebuild BGP routes)
    printf "  Recycling Calico node pods ... "
    kubectl delete pod -n kube-system -l k8s-app=calico-node 2>/dev/null || true
    printf "done\n"
    if kubectl wait --for=condition=ready pod -n kube-system -l k8s-app=calico-node --timeout=120s 2>/dev/null; then
        mark_pass "Calico node pods Ready"
    else
        mark_fail "Calico node pods not ready after 120s"
        ok=1
    fi

    # 3c — Recycle kube-proxy (flush stale iptables rules)
    printf "  Recycling kube-proxy pods ... "
    kubectl delete pod -n kube-system -l k8s-app=kube-proxy 2>/dev/null || true
    printf "done\n"
    if kubectl wait --for=condition=ready pod -n kube-system -l k8s-app=kube-proxy --timeout=120s 2>/dev/null; then
        mark_pass "kube-proxy pods Ready"
    else
        mark_fail "kube-proxy pods not ready after 120s"
        ok=1
    fi
}

# -----------------------------------------------------------------------------
# Section 4: Bank-mall pods (fresh start)
# Fixes: probe timing, stale network state
# -----------------------------------------------------------------------------
section4() {
    echo ""
    echo -e "${CYAN}=== [4/6] Bank-mall Pods ===${NC}"

    # 4a — Delete and redeploy for clean Calico network state
    printf "  Deleting all bank-mall pods (fresh start) ... "
    kubectl delete pods -n bank-mall --all 2>/dev/null || true
    printf "done\n"

    local services=(
        "mysql,app=mysql,180"
        "auth-service,app=auth-service,300"
        "account-service,app=account-service,300"
        "payment-service,app=payment-service,300"
        "notification-service,app=notification-service,300"
    )

    for svc_spec in "${services[@]}"; do
        IFS=',' read -r name label timeout_sec <<< "${svc_spec}"
        printf "  ${name} wait (${timeout_sec}s) ... "
        if kubectl wait --for=condition=ready pod -n bank-mall -l "${label}" --timeout="${timeout_sec}s" 2>/dev/null; then
            mark_pass "${name} Ready"
        else
            mark_fail "${name} not ready — check startupProbe/livenessProbe config"
        fi
    done
}

# -----------------------------------------------------------------------------
# Section 5: Observability (Prometheus, Grafana, Loki, Tempo)
# Fixes: memory + liveness for Jaeger/Tempo
# -----------------------------------------------------------------------------
section5() {
    echo ""
    echo -e "${CYAN}=== [5/6] Observability ===${NC}"

    # 5a — Prometheus
    printf "  Prometheus wait (300s) ... "
    if kubectl wait --for=condition=ready pod -n monitoring -l app=prometheus --timeout=300s 2>/dev/null; then
        mark_pass "Prometheus Ready"
    else
        mark_fail "Prometheus not ready"
    fi

    # 5b — Grafana
    printf "  Grafana wait (300s) ... "
    if kubectl wait --for=condition=ready pod -n monitoring -l app=grafana --timeout=300s 2>/dev/null; then
        mark_pass "Grafana Ready"
    else
        mark_fail "Grafana not ready"
    fi

    # 5c — Loki
    printf "  Loki wait (300s) ... "
    if kubectl wait --for=condition=ready pod -n monitoring -l app=loki --timeout=300s 2>/dev/null; then
        mark_pass "Loki Ready"
    else
        mark_fail "Loki not ready"
    fi

    # 5d — Promtail
    printf "  Promtail wait (120s) ... "
    if kubectl wait --for=condition=ready pod -n monitoring -l app=promtail --timeout=120s 2>/dev/null; then
        mark_pass "Promtail Ready"
    else
        mark_fail "Promtail not ready"
    fi

    # 5e — Tempo (check both jaeger and monitoring namespaces)
    printf "  Tempo wait (300s) ... "
    local tempo_ns=""
    local tempo_label="app=tempo"

    if kubectl get pods -n jaeger -l "${tempo_label}" 2>/dev/null | grep -q .; then
        tempo_ns="jaeger"
    elif kubectl get pods -n monitoring -l "${tempo_label}" 2>/dev/null | grep -q .; then
        tempo_ns="monitoring"
    fi

    if [ -n "${tempo_ns}" ]; then
        if kubectl wait --for=condition=ready pod -n "${tempo_ns}" -l "${tempo_label}" --timeout=300s 2>/dev/null; then
            mark_pass "Tempo Ready (namespace: ${tempo_ns})"
        else
            mark_fail "Tempo not ready (namespace: ${tempo_ns})"
        fi
    else
        mark_skip "Tempo not deployed"
    fi
}

# -----------------------------------------------------------------------------
# Section 6: Smoke test
# -----------------------------------------------------------------------------
section6() {
    echo ""
    echo -e "${CYAN}=== [6/6] Smoke Test ===${NC}"

    local smoke_script="${HOME}/bank-mall-platform/scripts/smoke-test.sh"

    if [ -f "${smoke_script}" ]; then
        printf "  Running smoke-test.sh ...\n"
        if bash "${smoke_script}"; then
            mark_pass "Smoke test passed"
        else
            mark_fail "Smoke test failures detected"
        fi
    else
        mark_skip "Smoke test script not found at ${smoke_script}"
        printf "  Basic health check (curl auth-service):\n"
        if curl -s --max-time 10 "http://10.0.0.41:30080/auth/actuator/health" | grep -q '"status":"UP"'; then
            mark_pass "Auth service health endpoint UP"
        else
            mark_fail "Auth service health endpoint unreachable or DOWN"
        fi
    fi
}

# -----------------------------------------------------------------------------
# Section 7: Summary
# -----------------------------------------------------------------------------
section7() {
    echo ""
    echo -e "${CYAN}========== POST-BOOT SUMMARY ==========${NC}"
    echo ""

    local has_fail=0

    printf "PASS (%d):\n" "${#PASS[@]}"
    for item in "${PASS[@]}"; do
        printf "  ${GREEN}[PASS]${NC} %s\n" "${item}"
    done

    if [ "${#SKIP[@]}" -gt 0 ]; then
        echo ""
        printf "SKIP (%d):\n" "${#SKIP[@]}"
        for item in "${SKIP[@]}"; do
            printf "  ${YELLOW}[SKIP]${NC} %s\n" "${item}"
        done
    fi

    if [ "${#FAIL[@]}" -gt 0 ]; then
        echo ""
        has_fail=1
        printf "FAIL (%d):\n" "${#FAIL[@]}"
        for item in "${FAIL[@]}"; do
            printf "  ${RED}[FAIL]${NC} %s\n" "${item}"
        done
    fi

    echo ""
    echo "Total elapsed: $(elapsed)"
    echo ""

    if [ "${has_fail}" -eq 0 ]; then
        echo -e "${GREEN}All checks passed. Cluster is healthy.${NC}"
        return 0
    else
        echo -e "${RED}Some checks failed. Review output above.${NC}"
        return 1
    fi
}

# =============================================================================
# Main execution
# =============================================================================
echo ""
echo -e "${CYAN}bank-mall-platform post-boot recovery${NC}"
echo "Started at $(date)"
echo "Target cluster: master01=${MASTER_IP}, worker01=${WORKER_IPS[0]}, worker02=${WORKER_IPS[1]}, harbor01=${HARBOR_IP}"
echo ""

section1
section2
section3
section4
section5
section6
section7
