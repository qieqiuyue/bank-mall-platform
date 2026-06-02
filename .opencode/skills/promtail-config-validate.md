# Promtail Config Change Validation Skill

Safely validate Promtail configuration changes before/after applying, preventing silent data loss from pipeline misconfiguration.

## Trigger Conditions

- Before applying ANY Promtail ConfigMap change
- After ConfigMap change, data stops appearing in Loki
- When changing `pipeline_stages`, `relabel_configs`, or `clients` URL

## Pre-Change Validation (Phase 1)

### Step 1: Snapshot Current Metrics

```bash
POD=$(kubectl get pods -n monitoring -l app=promtail -o wide | grep worker02 | awk '{print $1}')
kubectl port-forward -n monitoring $POD 9080:9080 &
sleep 2
echo "=== Before change ===" > /tmp/promtail-before.txt
curl -s http://localhost:9080/metrics | grep -E "read_lines_total|sent_entries_total|dropped_entries_total" >> /tmp/promtail-before.txt
cat /tmp/promtail-before.txt
kill %1 2>/dev/null
```

### Step 2: Apply Config Change (Correct Method)

**DO NOT use `kubectl patch` for multi-line YAML — it silently fails with nested quotes.**

```bash
# Correct: delete + create from file
kubectl delete configmap promtail-config -n monitoring
kubectl create configmap promtail-config -n monitoring --from-file=promtail.yaml=/path/to/new-config.yaml

# Verify URL replaced correctly
kubectl get configmap promtail-config -n monitoring -o jsonpath='{.data.promtail\.yaml}' | grep "url:"
```

### Step 3: Restart DaemonSet

```bash
kubectl rollout restart daemonset/promtail -n monitoring
echo "Waiting 30s for new pods..."
sleep 30
```

## Post-Change Validation (Phase 2)

### Step 4: Check Pod Health

```bash
echo "=== Promtail Pods ==="
kubectl get pods -n monitoring -l app=promtail -o wide

# Verify new pods have restarted
echo "=== Restart counts ==="
kubectl get pods -n monitoring -l app=promtail
```

### Step 5: Verify Data Flow (Wait 60s, Then Check Metrics)

```bash
sleep 60

POD=$(kubectl get pods -n monitoring -l app=promtail -o wide | grep worker02 | awk '{print $1}')
kubectl port-forward -n monitoring $POD 9080:9080 &
sleep 2

echo "=== After change ==="
echo "=== Read Lines ==="
curl -s http://localhost:9080/metrics | grep "promtail_read_lines_total"

echo "=== Sent Entries ==="
curl -s http://localhost:9080/metrics | grep "promtail_sent_entries_total"

echo "=== Dropped Entries ==="
curl -s http://localhost:9080/metrics | grep "promtail_dropped_entries_total"

kill %1 2>/dev/null
```

### Step 6: Verify Loki Query

```bash
START=$(($(date +%s)-300))000000000
END=$(date +%s)000000000
curl -s "http://NODE_IP:30310/loki/api/v1/query_range" \
  --data-urlencode 'query={namespace="bank-mall"}' \
  --data-urlencode "start=$START" \
  --data-urlencode "end=$END" | python3 -c "
import sys,json
d=json.load(sys.stdin)
r=d.get('data',{}).get('result',[])
print(f'Streams: {len(r)}')
if not r: print('FAIL: NO DATA')
else: print('OK')
"
```

### Step 7: Rollback on Failure

If `sent_entries_total` is zero or query returns empty:

```bash
# Recreate working config (no pipeline_stages, direct Pod IP)
LOIP=$(kubectl get pod -n monitoring -l app=loki -o jsonpath='{.items[0].status.podIP}')

cat > /tmp/promtail-rollback.yaml << ENDOFFILE
server:
  http_listen_port: 9080
  grpc_listen_port: 0
positions:
  filename: /run/promtail/positions.yaml
clients:
- url: http://${LOIP}:3100/loki/api/v1/push
  batchwait: 1s
  batchsize: 131072
  timeout: 10s
scrape_configs:
- job_name: kubernetes-pods
  kubernetes_sd_configs:
  - role: pod
    namespaces:
      names: [bank-mall, monitoring, ingress-nginx]
  relabel_configs:
  - action: drop
    source_labels: [__meta_kubernetes_pod_container_init]
    regex: "true"
  - source_labels: [__meta_kubernetes_namespace]
    target_label: namespace
  - source_labels: [__meta_kubernetes_pod_name]
    target_label: pod
  - source_labels: [__meta_kubernetes_pod_label_app]
    target_label: app
  - source_labels: [__meta_kubernetes_pod_container_name]
    target_label: container
  - source_labels: [__meta_kubernetes_namespace, __meta_kubernetes_pod_name, __meta_kubernetes_pod_uid]
    separator: "_"
    target_label: __path__
    replacement: /var/log/pods/\$1/**/*.log
ENDOFFILE

kubectl delete configmap promtail-config -n monitoring
kubectl create configmap promtail-config -n monitoring --from-file=promtail.yaml=/tmp/promtail-rollback.yaml
kubectl rollout restart daemonset/promtail -n monitoring
```

## Expected Output

```
=== Validation Report ===
Before:  read_lines=393  sent_entries=393
After:   read_lines=412  sent_entries=412
Delta:   +19 lines, +19 entries OK
Loki:    Streams: 4 OK
Status:  CHANGE SAFE - data flowing normally
```

## CRITICAL Notes

1. **Never use `kubectl patch`** for multi-line ConfigMap updates — nested quotes fail silently and Promtail keeps using old cached config
2. **Pipeline changes are silent killers** — `cri: {}` drops data without any error. Always compare `read_lines` vs `sent_entries`
3. **Wait at least 60s** after restart before checking `sent_entries` — batchwait + batch accumulation takes time
4. **Check BOTH Promtail Pods** (worker01 + worker02) — bank-mall runs on worker02
5. **ClusterIP URL may NOT work** — test with Pod IP first, then try ClusterIP
6. ConfigMap `create --from-file` encodes newlines as `\n` in JSON — this is correct, Promtail handles it fine
