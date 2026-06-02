#!/usr/bin/env bash
set -euo pipefail

K8S_BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../k8s/base" && pwd)"

echo "=== Deleting bank-mall resources ==="

kubectl delete -f "${K8S_BASE}/notification-service/service.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/notification-service/deployment.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/payment-service/service.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/payment-service/deployment.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/account-service/service.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/account-service/deployment.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/auth-service/service.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/auth-service/deployment.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/secret.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/configmap.yaml" --ignore-not-found || true
kubectl delete -f "${K8S_BASE}/namespace.yaml" --ignore-not-found || true

echo "=== Cleanup complete ==="