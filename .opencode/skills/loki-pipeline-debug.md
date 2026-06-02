# Log Pipeline Debug Skill

Diagnose and fix data loss in Promtail-Loki pipeline when `sent_entries_total` is zero despite `read_lines_total > 0`.

## Trigger Conditions

- Loki query returns `Streams: 0` but Promtail targets show active log files
- Promtail logs show no errors but `promtail_sent_entries_total = 0`
- After any Promtail config change, data stops flowing in Grafana

## Execution Steps

### Step 1: Confirm Pipeline Is the Problem

```bash
POD=$(kubectl get pods -n monitoring -l app=promtail -o wide | grep worker02 | awk '{print $1}')
kubectl port-forward -n monitoring $POD 9080:9080 &
sleep 2

echo "=== Read lines ==="
curl -s http://localhost:9080/metrics | grep "promtail_read_lines_total"

echo "=== Sent entries ==="
curl -s http://localhost:9080/metrics | grep "promtail_sent_entries_total"

echo "=== Dropped entries ==="
curl -s http://localhost:9080/metrics | grep "promtail_dropped_entries_total"

kill %1 2>/dev/null
```

If `read_lines > 0` and `sent_entries = 0` and `dropped_entries = 0` → **pipeline_stages is silently discarding lines**

### Step 2: Remove All Pipeline Stages

```bash
LOIP=$(kubectl get pod -n monitoring -l app=loki -o jsonpath='{.items[0].status.podIP}')

cat > /tmp/promtail-no-pipeline.yaml << ENDOFFILE
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
kubectl create configmap promtail-config -n monitoring --from-file=promtail.yaml=/tmp/promtail-no-pipeline.yaml
kubectl rollout restart daemonset/promtail -n monitoring
```

### Step 3: Verify Data Flows (Wait 60 Seconds)

```bash
sleep 60
START=$(($(date +%s)-180))000000000
END=$(date +%s)000000000
curl -s "http://NODE_IP:30310/loki/api/v1/query_range" \
  --data-urlencode 'query={namespace="bank-mall"}' \
  --data-urlencode "start=$START" \
  --data-urlencode "end=$END" > /tmp/loki_result.json
python3 -c "
import json
d=json.load(open('/tmp/loki_result.json'))
r=d['data']['result']
print(f'Streams: {len(r)}')
for s in r[:3]:
    print(f'  {len(s[\"values\"])} entries | ns={s[\"stream\"].get(\"namespace\",\"?\")} pod={s[\"stream\"].get(\"pod\",\"?\")[:30]}')
"
```

### Step 4: If Still No Data, Check Targets and Paths

```bash
POD=$(kubectl get pods -n monitoring -l app=promtail -o wide | grep worker02 | awk '{print $1}')

# Check positions file for read progress
kubectl exec -n monitoring $POD -- cat /run/promtail/positions.yaml 2>/dev/null | head -20

# Read actual log content to confirm files exist
kubectl exec -n monitoring $POD -- ls -la /var/log/pods/bank-mall_*/ 2>/dev/null | head -20

# Generate traffic to create new log lines
for s in auth account payment notification; do
  kubectl exec -n bank-mall deploy/${s}-service -- wget -qO- http://localhost:8080/api/ 2>/dev/null
done
```

## Expected Output

After removing pipeline_stages: `sent_entries_total > 0` and Loki query returns >= 1 stream with entries.

## Important Notes

1. **`cri: {}` is a silent killer** — Promtail 2.9.8's default `cri: {}` parser silently discards all containerd CRI-formatted log lines. No error or warning is logged.
2. **Do NOT re-add `pipeline_stages` without first testing with a single target** — use a separate test job_name to validate parsing before applying broadly.
3. Raw CRI log lines (with `stdout F` prefix) are fully queryable via LogQL — removing the pipeline has no functional impact beyond log message formatting.
4. This issue is NOT related to network, kube-proxy, or version compatibility — always check `sent_entries` first before investigating those layers.
