# China GFW + K8s Debugging Hard-Learned Lessons

Pain points, gotchas, and solutions encountered during bank-mall-cloudnative K8s deployment in a China mainland VMware NAT environment.

## Environment Context

- **Network**: China mainland, GFW blocks registry.k8s.io, Docker Hub, GitHub HTTPS (443)
- **K8s version**: v1.36.1 (do NOT downgrade)
- **Cluster**: master01 (10.0.0.31) + worker01 (10.0.0.41) + worker02 (10.0.0.42)
- **Registry**: Harbor HTTP-only at 10.0.0.61 (harbor.bank.local)
- **OS**: Ubuntu 24.04 LTS on VMware (NAT networking)
- **CNI**: Calico with Pod CIDR 10.244.0.0/16
- **containerd**: SystemdCgroup=true, sandbox_image patched to aliyun mirror

## GFW & Image Handling

### Problem: registry.k8s.io and Docker Hub blocked
**Solution**: Use aliyun mirrors for K8s components; Harbor private registry for application images.

```bash
# Patch containerd sandbox image BEFORE kubeadm init
sudo sed -i "s|sandbox = 'registry.k8s.io/pause:.*'|sandbox = 'registry.aliyuncs.com/google_containers/pause:3.10.2'|" /etc/containerd/config.toml

# kubeadm init with aliyun mirror
sudo kubeadm init --image-repository=registry.aliyuncs.com/google_containers ...
```

### Problem: GitHub HTTPS (443) blocked
**Solution**: Use SSH key for git operations. Configure `~/.ssh/config` on master01.

### Problem: Harbor HTTP-only (no HTTPS)
**Solution**: Configure containerd insecure registry on all K8s nodes:

```toml
# /etc/containerd/config.toml
[plugins."io.containerd.grpc.v1.cri".registry.configs."10.0.0.61".tls]
  insecure_skip_verify = true

[plugins."io.containerd.grpc.v1.cri".registry.mirrors."10.0.0.61"]
  endpoint = ["http://10.0.0.61"]
```

**CRITICAL**: After editing containerd config, MUST `sudo systemctl restart containerd` AND `sudo systemctl restart kubelet`.

### Problem: `ctr pull` needs `--plain-http` for HTTP Harbor
```bash
# DOES NOT WORK:
ctr images pull 10.0.0.61/bank-mall/auth-service:1.0.0

# CORRECT:
ctr images pull --plain-http 10.0.0.61/bank-mall/auth-service:1.0.0

# Alternative workflow: docker pull -> docker save -> ctr import
sudo docker pull 10.0.0.61/bank-mall/auth-service:1.0.0
sudo docker save 10.0.0.61/bank-mall/auth-service:1.0.0 | sudo ctr -n k8s.io images import -
```

## K8s Networking Gotchas

### Problem: NodePort NOT reachable from Pod network
**Root cause**: NodePort is for external-to-cluster traffic only. Pod-to-Service communication MUST use ClusterIP.

**Evidence**: `nc -w3 10.0.0.41 30310` from inside a Pod returns PORT_FAIL, but `curl http://loki:3100` (ClusterIP Service DNS) works fine.

**Never do this**:
```yaml
# WRONG: Promtail inside cluster using NodePort URL
clients:
  - url: http://10.0.0.41:30310/loki/api/v1/push
```

**Correct**:
```yaml
# CORRECT: Use ClusterIP Service DNS
clients:
  - url: http://loki:3100/loki/api/v1/push
```

### Problem: `externalTrafficPolicy: Local` breaks cross-node Service access
**Symptom**: Service endpoints on one node unreachable from pods on another node.
**Fix**: Use `externalTrafficPolicy: Cluster` (default) unless you MUST preserve source IP.

### Problem: Calico IPPool CIDR mismatch
**Symptom**: Nodes show NotReady, pods stuck in ContainerCreating.
**Fix**: Patch Calico IPPool to match `--pod-network-cidr`:
```bash
kubectl patch installation default --type merge -p '{"spec":{"calicoNetwork":{"ipPools":[{"cidr":"10.244.0.0/16"}]}}}'
```

## Loki + Promtail: Two Critical Root Causes

> Complete postmortem: `docs/22-loki-promtail-postmortem.md`

### P0 Root Cause 1: `cri: {}` Pipeline Silently Discards All Lines

**Symptom**: Loki returns `Streams: 0` despite Promtail reading logs (364+ lines). Promtail has zero error/warn logs about it. `promtail_read_lines_total=364` but `promtail_sent_entries_total=0`.

**Root Cause**: Promtail 2.9.8's `cri: {}` pipeline stage silently discards ALL containerd CRI log lines. No error, no warning, no `dropped_entries` counter.

**Fix**: Remove `pipeline_stages: - cri: {}` from ConfigMap entirely. Raw CRI log lines (with `stdout F` prefix) work fine with LogQL queries.

**Detection**: Always check `promtail_sent_entries_total` vs `promtail_read_lines_total` before investigating network.

### P0 Root Cause 2: kube-proxy ClusterIP POST Failure

**Symptom**: Promtail `error="Post .../push: EOF"`. `curl -X POST` from inside Pod returns HTTP `000` / `Empty reply from server` through ClusterIP. Direct Pod IP POST returns `204 OK`.

**Root Cause**: kube-proxy (iptables/IPVS) drops/corrupts HTTP POST requests when proxied through ClusterIP Service. GET requests work fine. kube-proxy restart doesn't fix.

**Fix**: Promtail `clients[0].url = http://<Loki-Pod-IP>:3100/loki/api/v1/push` (Pod IP direct, NOT ClusterIP). Must update ConfigMap when Loki Pod restarts.

### Version Compatibility: Loki 2.9.12 + Promtail 2.9.8

Loki 3.0 + Promtail 3.0 combination was tried but abandoned. EOF errors persisted even after downgrade to 2.9.x, confirming the root causes were cri: {} and kube-proxy, not version mismatch. Version alignment is necessary but NOT sufficient.

## Container Security & File Permission

### Problem: `runAsNonRoot: true` + UID 10001 + data directory ownership
**Symptom**: Container fails with `permission denied` writing to /data.
**Fix**: Pre-create and chown the data directory on the host:
```bash
# On the node where PV hostPath lives
sudo mkdir -p /data/loki
sudo chown -R 10001:10001 /data/loki
```

### Problem: MySQL/MariaDB entrypoint needs `chown` (needs baseline PSA)
**Symptom**: Pod Security Admission blocks MySQL init container that runs `chown`.
**Fix**: Use `pod-security.kubernetes.io/enforce: baseline` label on the namespace. `restricted` level blocks `chown`.

```bash
kubectl label namespace bank-mall pod-security.kubernetes.io/enforce=baseline
```

### Problem: Promtail needs hostPath access (read log files + Docker position)
**Fix**: Promtail DaemonSet CANNOT use `runAsNonRoot: true` because it needs root access to read `/var/log/pods` and `/var/lib/docker/containers`. Set `runAsUser: 0` explicitly or omit securityContext.

## Harbor & Container Registry

### Problem: `docker` group = root access
**Security**: Never add non-root users to `docker` group. Equivalent to root. On harbor01, `qian` user uses `sudo docker` for privileged operations.

### Problem: Harbor storage fills up
**Fix**: Regular `docker system prune -a` on harbor01, delete old tarballs, and remove unused image tags from Harbor UI.

## VMware & VM Issues

### Problem: VM reboot corrupts filesystem
**Symptom**: Boot into emergency mode, `fsck` required.
**Fix**: Always `shutdown -h now` cleanly. Never hard-kill VMs. If corrupted, boot into recovery and run `fsck -y /dev/sda2`.

### Problem: VMware NAT networking drops after host suspend
**Fix**: Reset VM network or reboot. Ensure Netplan has correct gateway.

## K8s Administration

### Problem: `kubectl scale daemonset` not found on some K8s versions
**Fix**: Use `kubectl rollout restart daemonset/promtail -n monitoring` or delete/recreate.

### Problem: Metrics Server needs `--kubelet-insecure-tls`
**Symptom**: `kubectl top nodes` shows error, metrics-server logs show x509 verification failure.
**Fix**: Patch metrics-server deployment:
```bash
kubectl patch deployment metrics-server -n kube-system --type='json' \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

### Problem: HPA scaleDown `Percent: 10` doesn't work for low replica counts
**Fix**: Use `Pods: 1` instead of `Percent: 10` in HPA scaleDown policy.

## Security Hardening Lessons

### NetworkPolicy Design
- **Model**: deny-all + whitelist (5 allow rules for bank-mall)
- **Namespace isolation**: deny-all only in bank-mall ns; monitoring ns has no egress restrictions
- **Rules**: allow DNS (kube-system), allow ingress from same ns, allow ingress-controller, allow cross-service traffic, allow egress to monitoring

### PodSecurity Admission
- **enforce: baseline** on bank-mall (MySQL needs chown)
- **enforce: baseline** on monitoring (Loki needs UID 10001)
- Do NOT use `restricted` — too many init containers fail

## Debugging Workflow (Systematic)

1. `kubectl get pods -A -o wide` - cluster-wide status
2. `kubectl describe pod <name> -n <ns>` - events and conditions
3. `kubectl logs <pod> -n <ns> --tail=50` - recent logs
4. `kubectl logs <pod> -n <ns> -p` - previous container logs (if restarted)
5. `kubectl get events -n <ns> --sort-by='.lastTimestamp'` - namespace events
6. `kubectl exec -it <pod> -n <ns> -- sh` - shell into container (if available)
7. Network test from inside pod: `kubectl run netshoot --image=nicolaka/netshoot -n <ns> --rm -it -- bash`
8. DNS test: `kubectl exec <pod> -n <ns> -- nslookup <service>.<ns>.svc.cluster.local`
9. Endpoint test: `kubectl get endpoints <svc> -n <ns>`
10. **When stuck**: simplify. Disable NetworkPolicy, disable PSA, restart clean. Isolate variables one at a time.

## Quick Reference: Ports & IPs

| Service | Type | Access | Port |
|---------|------|--------|------|
| auth-service | ClusterIP | Ingress | 8081 |
| account-service | ClusterIP | Ingress | 8082 |
| payment-service | ClusterIP | Ingress | 8083 |
| notification-service | ClusterIP | Ingress | 8084 |
| MySQL | ClusterIP | Internal | 3306 |
| Redis | ClusterIP | Internal | 6379 |
| Prometheus | NodePort | 30090 | 9090 |
| Grafana | NodePort | 30300 | 3000 |
| Loki | NodePort | 30310 | 3100 |
| Promtail | DaemonSet | Internal | - |
| Ingress Controller | DaemonSet+hostNetwork | 80/443 | 80/443 |