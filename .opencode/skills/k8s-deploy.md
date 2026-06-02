# K8s Deploy Skill

Deploy, update, and troubleshoot bank-mall microservices on Kubernetes.

## Deployment Flow

### 1. Build & Push Images

```bash
cd ~/bank-mall-cloudnative
chmod +x scripts/build-images.sh

# Build only (no push)
REGISTRY=10.0.0.61 NAMESPACE=bank-mall VERSION=1.0.0 ./scripts/build-images.sh

# Build and push to Harbor
REGISTRY=10.0.0.61 NAMESPACE=bank-mall VERSION=1.0.0 PUSH=true ./scripts/build-images.sh
```

Image naming convention: `<REGISTRY>/<NAMESPACE>/<service>:<VERSION>`

Produced images:
- `10.0.0.61/bank-mall/auth-service:1.0.0`
- `10.0.0.61/bank-mall/account-service:1.0.0`
- `10.0.0.61/bank-mall/payment-service:1.0.0`
- `10.0.0.61/bank-mall/notification-service:1.0.0`

### 2. Deploy to K8s

```bash
bash scripts/deploy.sh
```

This applies in order: namespace -> configmap -> secret -> deployments -> services

### 3. Verify Deployment

```bash
kubectl get pods -n bank-mall -o wide
kubectl get svc -n bank-mall
kubectl get configmap -n bank-mall
kubectl get secret -n bank-mall
```

### 4. Test Service Endpoints

If using NodePort (legacy, per-service k8s/ manifests):
```bash
curl -X POST http://NODE_IP:30081/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}'
curl http://NODE_IP:30082/api/accounts/A1001/balance
curl -X POST http://NODE_IP:30083/api/payments -H "Content-Type: application/json" -d '{"orderId":"ORDER1001","payerAccount":"A1001","amount":299.00,"currency":"CNY"}'
curl -X POST http://NODE_IP:30084/api/notifications -H "Content-Type: application/json" -d '{"channel":"SMS","receiver":"13800000000","template":"PAYMENT_SUCCESS"}'
```

If using ClusterIP + Ingress (k8s/base/ manifests):
```bash
# Port-forward for testing
kubectl port-forward -n bank-mall svc/auth-service 8081:8081 &
kubectl port-forward -n bank-mall svc/account-service 8082:8082 &

# Or test from within cluster
kubectl run curl-test --image=curlimages/curl -n bank-mall --rm -it -- restart=Never -- curl http://auth-service:8081/api/auth/health
```

### 5. Tear Down

```bash
bash scripts/teardown.sh
```

## K8s Manifest Structure

All production manifests are under `k8s/base/`:

```
k8s/base/
├── namespace.yaml              # bank-mall namespace
├── configmap.yaml              # Shared config: LOG_LEVEL, service URLs, TZ, etc.
├── secret.yaml                 # Base64-encoded: JWT_SECRET_KEY, DB creds, Harbor creds
├── auth-service/
│   ├── deployment.yaml         # With probes, resources, envFrom
│   └── service.yaml            # ClusterIP
├── account-service/
│   ├── deployment.yaml
│   └── service.yaml
├── payment-service/
│   ├── deployment.yaml
│   └── service.yaml
└── notification-service/
    ├── deployment.yaml
    └── service.yaml
```

Legacy per-service manifests also exist at `bank-digital-platform/<service>/k8s/` (NodePort style, no probes/resources).

## Deployment Best Practices

- Always apply namespace first, then configmap/secret, then deployments/services
- Use `envFrom` with `configMapRef` and `secretRef` to inject config
- Each deployment must include: `namespace: bank-mall`, `livenessProbe`, `readinessProbe`, `resources` limits/requests
- Services should use ClusterIP by default; use Ingress for external access
- Image tag should match the VERSION variable in build-images.sh
- Use `imagePullPolicy: IfNotPresent` for Harbor images to reduce pull times

## Common Troubleshooting

```bash
# Pod not starting
kubectl describe pod <pod-name> -n bank-mall
kubectl logs <pod-name> -n bank-mall

# Service not reachable
kubectl get endpoints <service-name> -n bank-mall
kubectl run curl-test --image=curlimages/curl -n bank-mall --rm -it -- restart=Never -- curl http://<service-name>:<port>/api/<service>/health

# ConfigMap/Secret issues
kubectl get configmap bank-mall-config -n bank-mall -o yaml
kubectl get secret bank-mall-secret -n bank-mall -o yaml

# Image pull errors
kubectl describe pod <pod-name> -n bank-mall | grep -A5 "Events"
# Check: Harbor credentials, image name/tag, containerd config for insecure registry
```

## Adding a New Service

1. Create service source code in `bank-digital-platform/<service-name>/`
2. Create `Dockerfile` following existing pattern (multi-stage maven build)
3. Create `k8s/base/<service-name>/deployment.yaml` and `service.yaml`
4. Add service URL to `k8s/base/configmap.yaml`
5. Add entry to `scripts/build-images.sh` SERVICES array
6. Add deploy/teardown steps to respective scripts