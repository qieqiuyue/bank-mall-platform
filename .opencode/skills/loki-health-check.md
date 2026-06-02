# Loki + Promtail Health Check Skill

Comprehensive health verification for Loki + Promtail logging stack on K8s.

## Trigger Conditions

- After deploying or restarting Loki/Promtail
- When `{namespace="bank-mall"}` returns no data in Grafana
- During periodic cluster health audits
- When Promtail or Loki Pods restart unexpectedly

## Execution Steps

### Step 1: Check Pod Status

```bash
kubectl get pods -n monitoring -l app=loki -o wide
kubectl get pods -n monitoring -l app=promtail -o wide
```

Expected: All pods `Running`, age < 1h for recent deploy. Note which node each Pod runs on.

### Step 2: Check Loki Readiness

```bash
curl -s http://NODE_IP:30310/ready
```

Expected: `Ready`

### Step 3: Check Promtail Metrics (Critical)

```bash
POD=$(kubectl get pods -n monitoring -l app=promtail -o wide | grep worker02 | awk '{print $1}')
kubectl port-forward -n monitoring $POD 9080:9080 &
sleep 2
curl -s http://localhost:9080/metrics | grep -E "promtail_read_lines_total|promtail_sent_entries_total"
kill %1 2>/dev/null
```

Interpretation:
- `read_lines > 0 && sent_entries > 0` → Working correctly
- `read_lines > 0 && sent_entries == 0` → **Pipeline is dropping data** (see `loki-pipeline-debug` skill)
- Both zero → No targets or permission issue

### Step 4: Query Loki for Actual Data

```bash
START=$(($(date +%s)-600))000000000
END=$(date +%s)000000000
curl -s "http://NODE_IP:30310/loki/api/v1/query_range" \
  --data-urlencode 'query={namespace="bank-mall"}' \
  --data-urlencode "start=$START" \
  --data-urlencode "end=$END" | python3 -c "
import sys,json; d=json.load(sys.stdin)
r=d.get('data',{}).get('result',[])
print(f'Streams found: {len(r)}')
for s in r[:3]:
    print(f'  {s[\"stream\"][\"namespace\"]}/{s[\"stream\"][\"pod\"]} -> {len(s[\"values\"])} entries')
"
```

Expected: >= 1 stream with entries > 0.

### Step 5: Verify Grafana Integration

```bash
GFPOD=$(kubectl get pods -n monitoring -l app=grafana -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n monitoring $GFPOD -- wget -qO- http://loki:3100/ready
```

Expected: HTTP `Ready` response.

## Expected Output

```
=== Health Check Report ===
Loki Pod:     Running (node: k8s-worker01)
Promtail Pods: 2/2 Running
Loki Ready:   200 OK
Promtail Read: 393 lines
Promtail Sent: 393 entries
Loki Streams:  4 (bank-mall namespace)
Grafana->Loki: Reachable
Status:        HEALTHY
```

## Critical Notes

1. **Always check `sent_entries` before investigating network** — pipeline drops are undetectable from logs
2. `cri: {}` pipeline silently drops all containerd CRI log lines in Promtail 2.9.8
3. ClusterIP POST to Loki may fail even if GET works — test with port-forward first
4. NodePort URLs are NOT reachable from inside Pod network
5. bank-mall services run on worker02 — check worker02's Promtail Pod, not worker01's
