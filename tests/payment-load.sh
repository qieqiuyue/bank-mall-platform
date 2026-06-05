#!/bin/bash
# ============================================================================
# payment-load.sh — 零依赖压测脚本
# 用法: ./payment-load.sh [并发数] [持续时间(秒)] [URL]
# 示例: ./payment-load.sh 50 180 http://10.0.0.31:30080/api/payments
# ============================================================================

CONCURRENCY="${1:-50}"
DURATION="${2:-180}"
URL="${3:-http://10.0.0.31:30080/payment/api/payments}"
ACCOUNTS=(A1001)  # 唯一真实账户，S4 前补其他测试账户

# 创建临时目录存放结果
TMPDIR=$(mktemp -d)
# trap "rm -rf $TMPDIR" EXIT  # keep results for debug

echo "=== Payment Load Test ==="
echo "URL: $URL"
echo "Concurrency: $CONCURRENCY"
echo "Duration: ${DURATION}s"
echo "Results: $TMPDIR/results.txt"
echo "========================="

START_TIME=$(date +%s)

# 压测函数
run_request() {
    local idx=$((RANDOM % ${#ACCOUNTS[@]}))
    local account=${ACCOUNTS[$idx]}
    local amount=$((RANDOM % 5 + 1))
    local idem="load-$$-$RANDOM-$RANDOM-$RANDOM"

    local resp
    local http_code

    resp=$(curl -s -w "\nHTTP:%{http_code}" \
        -X POST "$URL" \
        -H "Content-Type: application/json" \
        -d "{\"payerAccount\":\"$account\",\"payeeAccount\":\"MALL-SETTLEMENT\",\"amount\":$amount,\"idempotencyKey\":\"$idem\"}" \
        2>/dev/null)

    http_code=$(echo "$resp" | sed -n 's/.*HTTP:\([0-9]*\).*/\1/p')

    if [ "$http_code" = "200" ]; then
        echo "SUCCESS"
    else
        echo "FAILED $http_code"
    fi
}

# Ramp-up 阶段：逐步启动并发
DELAY_MS=$((10000 / CONCURRENCY))  # 10s ramp-up

for i in $(seq 1 $CONCURRENCY); do
    (
        # ramp-up delay
        sleep $(echo "scale=3; $i * $DELAY_MS / 1000" | bc 2>/dev/null || echo "0.$((i * DELAY_MS / 1000))")

        local_start=$(date +%s)
        while [ $(($(date +%s) - local_start)) -lt $DURATION ]; do
            result=$(run_request)
            echo "$result" >> "$TMPDIR/results.txt"
            sleep "0.$((RANDOM % 3 + 1))"
        done
    ) &
done

wait

ELAPSED=$(($(date +%s) - START_TIME))

echo ""
echo "=== Summary ==="
echo "Elapsed: ${ELAPSED}s"

total=$(wc -l < "$TMPDIR/results.txt" 2>/dev/null || echo 0)
success=$(grep -c "^SUCCESS" "$TMPDIR/results.txt" 2>/dev/null || echo 0)
failed=$(grep -c "^FAILED" "$TMPDIR/results.txt" 2>/dev/null || echo 0)

qps=$(echo "scale=2; $total / $DURATION" | bc 2>/dev/null || echo "N/A")

echo "Total Requests: $total"
echo "Success: $success"
echo "Failed: $failed"
echo "QPS: $qps"
echo "Latency: see Grafana SLO dashboard"
echo "==============="
