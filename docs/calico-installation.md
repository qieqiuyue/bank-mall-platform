# Calico IPIP 模式安装

> **环境**：kubeadm 部署的 K8s 1.36+ 集群，containerd 运行时
> **CIDR**：10.244.0.0/16

## 安装步骤

```bash
# 1. 下载 Calico manifest（Tigera operator 方式或 raw manifest）
curl -O https://raw.githubusercontent.com/projectcalico/calico/v3.29.0/manifests/calico.yaml

# 2. 修改 Pod CIDR（如果非默认 192.168.0.0/16）
# 在 calico.yaml 中搜索 CALICO_IPV4POOL_CIDR，改为：
# - name: CALICO_IPV4POOL_CIDR
#   value: "10.244.0.0/16"

# 3. IP 自动检测机制
# Calico 默认使用 node IP（kubelet 对外 IP）作为 IPIP 隧道 endpoint。
# 多网卡环境需显式指定：
# - name: IP_AUTODETECTION_METHOD
#   value: "interface=eth0"

# 4. Apply
kubectl apply -f calico.yaml

# 5. 等待所有 calico-node Pod Running
kubectl wait --for=condition=ready pod -l k8s-app=calico-node -n kube-system --timeout=300s
```

## 常见问题

### 节点间 Pod 不通

```bash
# 检查 IPIP 隧道
ip tunnel show tunl0

# 检查 Calico BGP 状态
kubectl exec -n kube-system deploy/calico-node -- calicoctl node status
```

### VMware 暂停/恢复导致 Calico `connection is unauthorized`

```bash
# 删掉对应节点的 calico-node Pod，让它重建
kubectl delete pod -n kube-system -l k8s-app=calico-node --field-selector spec.nodeName=k8s-master01
```

### containerd v2 与 Calico 兼容性

containerd v2.x 的 CRI 接口变更不影响 Calico CNI 插件。Calico 使用 CNI 规范（非 CRI），与 containerd 版本无关。
