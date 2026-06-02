# Log Storage Cost Audit Skill

Audit Loki + Promtail storage usage and compute cost, identify optimization opportunities.

## Trigger Conditions

- Periodic monthly/quarterly cost review
- After log volume spikes or retention policy changes
- Disk space warning on Loki PV

## Execution Steps

### Step 1: Measure Current Storage Usage

```bash
# Loki Pod disk usage
LOPOD=$(kubectl get pods -n monitoring -l app=loki -o jsonpath='{.items[0].metadata.name}')
echo "=== Loki PV usage ==="
kubectl exec -n monitoring $LOPOD -- du -sh /loki/* 2>/dev/null

# PVC allocated vs used
echo "=== PVC status ==="
kubectl get pvc -n monitoring loki-pvc -o jsonpath='{.status.capacity.storage} allocated, {.spec.resources.requests.storage} requested'

# Node-level disk usage
echo "=== Node disk (worker01) ==="
ssh qian@10.0.0.41 "sudo du -sh /data/loki/ 2>/dev/null && df -h /data"
```

### Step 2: Analyze Data Volume and Growth Rate

```bash
# Count total log entries in the last 24h
curl -s "http://NODE_IP:30310/loki/api/v1/query_range" \
  --data-urlencode 'query=sum(count_over_time({namespace=~"bank-mall|monitoring|ingress-nginx"}[24h]))' \
  --data-urlencode "start=$(($(date +%s)-86400))000000000" \
  --data-urlencode "end=$(date +%s)000000000" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);v=d['data']['result'][0]['values'];print(f'24h entries: {v[-1][1]}')" 2>/dev/null

# Estimate bytes per day
curl -s "http://NODE_IP:30310/loki/api/v1/query_range" \
  --data-urlencode 'query=sum(bytes_over_time({namespace=~"bank-mall|monitoring"}[24h]))' \
  --data-urlencode "start=$(($(date +%s)-86400))000000000" \
  --data-urlencode "end=$(date +%s)000000000" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);v=d['data']['result'][0]['values'];print(f'24h bytes: {int(v[-1][1]):,}')" 2>/dev/null
```

### Step 3: Identify Waste Sources

```bash
# Duplicate data from Promtail restart (positions on emptyDir)
echo "=== Check for positions reset ==="
POD=$(kubectl get pods -n monitoring -l app=promtail -o jsonpath='{.items[0].metadata.name}')
kubectl logs -n monitoring $POD | grep "Seeked" | tail -3

# If Offset:0 appears frequently, Promtail is re-reading old data

# Orphaned/dangling images
echo "=== Orphaned container images ==="
ssh qian@10.0.0.41 "sudo ctr -n k8s.io images ls | grep -E 'loki|promtail' | head -10"
ssh qian@10.0.0.42 "sudo ctr -n k8s.io images ls | grep -E 'loki|promtail' | head -10"
```

### Step 4: Apply Optimizations

```bash
# Clean orphaned container images (keep only current versions)
ssh qian@10.0.0.41 "sudo ctr -n k8s.io images prune --all"
ssh qian@10.0.0.42 "sudo ctr -n k8s.io images prune --all"

# Harbor cleanup (on harbor01)
ssh qian@10.0.0.61 "sudo docker system prune -a --filter 'until=72h'"  # keep 3 days

# Adjust retention in Loki ConfigMap if growth exceeds PV capacity
# limits_config:
#   retention_period: 168h       # 7 days
#   max_entries_limit_per_query: 5000
```

## Expected Output

```
=== Loki Cost Audit ===
PV Usage:       2.1Gi / 5Gi allocated (42%)
24h Entry Count: 15,234
24h Data Volume: 4.2 MB
Projected 7-day: 29.4 MB (well within 5Gi PV)
Orphaned Images: 3 old versions removed, freed ~1.4 GB
Harbor Cleanup:  freed 2.7 GB
Status:           HEALTHY - no action needed
```

## Cost Reference Table

| Component | Current Cost | Optimization | Savings |
|-----------|-------------|--------------|---------|
| Loki PV | 5Gi hostPath | N/A (local disk) | $0 |
| Harbor storage | ~8GB images | prune old tags weekly | ~3GB |
| Compute (Loki Pod) | 100m CPU / 256Mi RAM | Already minimal | $0 |
| Positions emptyDir → hostPath | N/A | Reduces restart overhead | Avoids 100% re-read |

## Critical Notes

1. **positions.yaml on emptyDir = 100% re-read on restart** — every Promtail restart sends ALL old log data again, doubling effective storage write volume for that period
2. `reject_old_samples: false` masks this but doesn't fix it; migrate to hostPath for production
3. Loki chunks are compacted automatically (`compactor.working_directory`); no manual compaction needed
4. In lab environment with 4 microservices + MySQL + Grafana, expected daily volume is 5-10 MB
5. 5Gi PV fills in ~6 months at current rates; increase to 20Gi for multi-month retention
