#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_BASE="${SCRIPT_DIR}/../k8s/base"

echo "=== Deploying bank-mall to Kubernetes ==="

echo "[0/12] Creating namespace with Pod Security Standards..."
kubectl apply -f "${K8S_BASE}/security/namespace-psa.yaml"
kubectl label namespace ingress-nginx name=ingress-nginx --overwrite 2>/dev/null || true
kubectl label namespace monitoring name=monitoring --overwrite 2>/dev/null || true

echo "[1/12] Creating Secrets..."
kubectl apply -f "${K8S_BASE}/secret.yaml"
kubectl apply -f "${K8S_BASE}/mysql/secret.yaml"

echo "[2/12] Deploying MySQL (storage + deployment)..."
kubectl apply -f "${K8S_BASE}/mysql/storage.yaml"
kubectl apply -f "${K8S_BASE}/mysql/initdb-configmap.yaml"
kubectl apply -f "${K8S_BASE}/mysql/deployment.yaml"
kubectl apply -f "${K8S_BASE}/mysql/service.yaml"

echo "[3/12] Creating ConfigMap..."
kubectl apply -f "${K8S_BASE}/configmap.yaml"

echo "Waiting for MySQL to be ready..."
kubectl wait --for=condition=ready pod -l app=mysql -n bank-mall --timeout=180s || echo "WARNING: MySQL not ready yet"

echo "[4/12] Deploying auth-service..."
kubectl apply -f "${K8S_BASE}/auth-service/deployment.yaml"
kubectl apply -f "${K8S_BASE}/auth-service/service.yaml"

echo "[5/12] Deploying account-service..."
kubectl apply -f "${K8S_BASE}/account-service/deployment.yaml"
kubectl apply -f "${K8S_BASE}/account-service/service.yaml"

echo "[6/12] Deploying payment-service..."
kubectl apply -f "${K8S_BASE}/payment-service/deployment.yaml"
kubectl apply -f "${K8S_BASE}/payment-service/service.yaml"

echo "[7/12] Deploying notification-service..."
kubectl apply -f "${K8S_BASE}/notification-service/deployment.yaml"
kubectl apply -f "${K8S_BASE}/notification-service/service.yaml"

echo "[8/12] Deploying monitoring stack..."
kubectl create namespace monitoring 2>/dev/null || true
# Create Loki data directory on worker01
ssh root@10.0.0.41 "mkdir -p /data/loki && chown 10001:10001 /data/loki" 2>/dev/null || echo "WARNING: Could not create /data/loki on worker01"
kubectl apply -f "${K8S_BASE}/monitoring/"

echo "[9/12] Deploying Ingress Controller..."
kubectl create namespace ingress-nginx 2>/dev/null || true
kubectl apply -f "${K8S_BASE}/ingress/controller-rbac.yaml"
kubectl apply -f "${K8S_BASE}/ingress/controller-configmap.yaml"
kubectl apply -f "${K8S_BASE}/ingress/controller-deploy.yaml"
kubectl apply -f "${K8S_BASE}/ingress/controller-service.yaml"
kubectl apply -f "${K8S_BASE}/ingress/ingressclass.yaml"
kubectl apply -f "${K8S_BASE}/ingress/ingress-rules.yaml"

echo "[10/12] Deploying HPA..."
kubectl apply -f "${K8S_BASE}/hpa/"

echo "[11/12] Deploying NetworkPolicy..."
kubectl apply -f "${K8S_BASE}/security/"

echo "[12/12] Verifying deployment..."

echo ""
echo "=== Deployment complete. Waiting for pods to be ready... ==="
kubectl wait --for=condition=ready pod -l app.kubernetes.io/part-of=bank-mall -n bank-mall --timeout=180s || echo "WARNING: Some bank-mall pods are not ready"
kubectl wait --for=condition=ready pod -l app.kubernetes.io/part-of=bank-mall -n monitoring --timeout=180s || echo "WARNING: Some monitoring pods are not ready"
kubectl wait --for=condition=ready pod -l app.kubernetes.io/component=ingress -n ingress-nginx --timeout=180s || echo "WARNING: Ingress controller not ready yet"

echo ""
echo "=== Pod status (bank-mall) ==="
kubectl get pods -n bank-mall -o wide

echo ""
echo "=== Pod status (monitoring) ==="
kubectl get pods -n monitoring -o wide

echo ""
echo "=== Service status (bank-mall) ==="
kubectl get svc -n bank-mall

echo ""
echo "=== Service status (monitoring) ==="
kubectl get svc -n monitoring

echo ""
echo "=== Access ==="
echo "  Ingress:     http://<node-ip>:30080"
echo "    /auth/api/auth/login  (POST → token)"
echo "    /account/api/accounts/A1001/balance"
echo "  Prometheus:  http://<node-ip>:30090"
echo "  Grafana:     http://<node-ip>:30300"
echo "  Loki:        http://<node-ip>:30310"
echo "  Dashboard:   Bank Mall - Service Overview"