# K8s Base Manifests

Kustomization 入口。`kubectl apply -f` 或 `kubectl apply -k` 均可。

## 目录结构

```
infra/kubernetes/base/
├── namespace.yaml                   # bank-mall namespace
├── configmap.yaml                   # 公共配置（4 服务 DB 连接、日志级别）
├── sealed-bank-mall.yaml            # Sealed Secrets（加密后），非明文
├── kustomization.yaml               # 16 个资源的 kustomize 入口
├── auth-service/                    # Deployment + Service (8081)
├── account-service/                 # Deployment + Service (8082, liveness 120s)
├── payment-service/                 # Deployment + Service (8083, RestClient)
├── notification-service/            # Deployment + Service (8084)
├── mysql/                           # Deployment + Service (3306) + PV/PVC + InitDB ConfigMap
├── ingress/                         # Controller RBAC + ConfigMap + Deployment + Service + IngressClass + Ingress Rules
├── monitoring/                      # Prometheus + Grafana + Loki + Promtail (namespace: monitoring)
│   ├── prometheus-*                 # Deployment + ConfigMap + RBAC + Service (NodePort 30090)
│   ├── grafana-*                    # Deployment + ConfigMap（含 3 alert rules）+ Service (NodePort 30300)
│   ├── loki-*                       # Deployment + ConfigMap + Service (NodePort 30310) + Storage
│   └── promtail-*                   # DaemonSet + ConfigMap + RBAC
├── security/                        # NetworkPolicy deny-all + 白名单 + PSA + PDB + LimitRange + ResourceQuota
├── hpa/                             # 4 个 HPA（CPU 70%, min=1 max=3）
└── jaeger/                          # Deployment (emptydir, nodeName: k8s-worker01) + Service (ClusterIP 4317/4318 + NodePort 31686)
```

## 部署方式

```bash
# V1：kubectl apply 全量
kubectl apply -f infra/kubernetes/base/

# 或单组件：
kubectl apply -f infra/kubernetes/base/auth-service/
kubectl apply -f infra/kubernetes/base/security/
```

## 注意

- `sealed-bank-mall.yaml` 为 SealedSecret 格式，不可直接编辑。编辑原始 secret 后需 `kubeseal` 重新加密。
- `secret.yaml.example` 为明文占位模板，*永不部署*。
- HPA 最小副本 1、最大副本 3。压测时需提前 `kubectl scale` 或等 HPA 自然扩容。
