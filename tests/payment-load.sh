#!/bin/bash
# ============================================================================
# payment-load.sh — 零依赖压测脚本（JMeter 不可用时备用）
# 用法: ./payment-load.sh [并发数] [持续时间(秒)] [URL]
# 示例: ./payment-load.sh 50 180 http://10.0.0.31:30080/api/payments
# ============================================================================

CONCURRENCY="${1:-50}"
DURATION="${2:-180}"
URL="${3:-http://10.0.0.31:30080/api/payments}"
ACCOUNTS=(A1001)  # 唯一真实账户，S4 前补其他测试账户

# 创建临时目录存放结果
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

echo "=== Payment Load Test ==="
echo "URL: $URL"
echo "Concurrency: $CONCURRENCY"
echo "Duration: ${DURATION}s"
echo "Results: $TMPDIR/results.txt"
echo "========================="

START_TIME=$(date +%s)
REQUEST_COUNT=0
SUCCESS_COUNT=0
FAIL_COUNT=0

# 压测函数
run_request() {
    local idx=$((RANDOM % 10))
    local account=${ACCOUNTS[$idx]}
    local amount=$((RANDOM % 5 + 1))
    local idem="load-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo $$-$RANDOM-$RANDOM)"

    local http_code
    local total_time
    local resp

    resp=$(curl -s -w "\n%{http_code},%{time_total}" \
        -X POST "$URL" \
        -H "Content-Type: application/json" \
        -d "{\"payerAccount\":\"$account\",\"payeeAccount\":\"MALL-SETTLEMENT\",\"amount\":$amount,\"idempotencyKey\":\"$idem\"}" \
        2>/dev/null)

    http_code=$(echo "$resp" | tail -1 | cut -d',' -f1)
    total_time=$(echo "$resp" | tail -1 | cut -d',' -f2)

    if [ "$http_code" = "200" ]; then
        echo "SUCCESS $total_time"
    else
        echo "FAILED $http_code $total_time"
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
            # think time: 100-300ms，模拟真实用户行为
            sleep "0.$((RANDOM % 3 + 1))"
        done
    ) &
done

wait

ELAPSED=$(($(date +%s) - START_TIME))

echo ""
echo "=== Summary ==="
echo "Elapsed: ${ELAPSED}s"

# 统计
total=$(wc -l < "$TMPDIR/results.txt" 2>/dev/null || echo 0)
success=$(grep -c "^SUCCESS" "$TMPDIR/results.txt" 2>/dev/null || echo 0)
failed=$(grep -c "^FAILED" "$TMPDIR/results.txt" 2>/dev/null || echo 0)

# 计算 QPS
qps=$(echo "scale=2; $total / $DURATION" | bc 2>/dev/null || echo "N/A")

# 计算平均 RT
avg_rt=$(grep "^SUCCESS" "$TMPDIR/results.txt" 2>/dev/null | awk '{sum+=$2; count++} END {if(count>0) printf "%.3f", sum/count; else print "N/A"}')

# 计算 P99 RT（简单近似：排序后取 99% 位置）
p99_rt=$(grep "^SUCCESS" "$TMPDIR/results.txt" 2>/dev/null | awk '{print $2}' | sort -n | awk '
    NR==1 {min=$1; max=$1}
    {a[NR]=$1; max=$1}
    END {
        n=NR
        if(n==0) {print "N/A"; exit}
        idx=int(n*0.99)
        if(idx<1) idx=1
        if(idx>n) idx=n
        print a[idx]
    }
')

echo "Total Requests: $total"
echo "Success: $success"
echo "Failed: $failed"
echo "QPS: $qps"
echo "Avg RT: ${avg_rt}s"
echo "P99 RT: ${p99_rt}s"
echo "==============="
