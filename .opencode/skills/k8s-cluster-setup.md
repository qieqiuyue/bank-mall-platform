# K8s Cluster Setup Skill

Set up a Kubernetes lab cluster from scratch for the bank-mall project.

## Lab Topology

| Node | Hostname | IP | Role | CPU | RAM | Disk |
|------|----------|-----|------|-----|-----|------|
| vm-1 | k8s-master01 | 10.0.0.31 | Control plane | 2 | 4GB | 50GB |
| vm-2 | k8s-worker01 | 10.0.0.41 | Worker | 2 | 5GB | 60GB |
| vm-3 | k8s-worker02 | 10.0.0.42 | Worker | 2 | 5GB | 60GB |
| vm-4 | harbor01 | 10.0.0.61 | Harbor registry | 2 | 6GB | 100GB |

HA expansion (Phase 5): 2 LB + 3 master + 2-3 worker + 1 Harbor + 1+ storage

Network: VMware NAT, Pod CIDR 10.244.0.0/16, Service CIDR 10.96.0.0/12
OS: Ubuntu Server 24.04 LTS

## Phase 1: Ubuntu VM Initialization

Reference: `docs/07-ubuntu-vm-initialization.md`

### Steps (on ALL nodes)

1. Create k8s-master01 VM first, finish init, then clone for other nodes
2. Configure static IP via Netplan:

```yaml
# /etc/netplan/50-cloud-init.yaml
network:
  version: 2
  ethernets:
    ens33:
      dhcp4: false
      addresses:
        - 10.0.0.xxx/24
      routes:
        - to: default
          via: 10.0.0.2
      nameservers:
        addresses:
          - 223.5.5.5
          - 8.8.8.8
```

3. Set hostname: `sudo hostnamectl set-hostname <hostname>`
4. Configure `/etc/hosts`:

```
10.0.0.31 k8s-master01
10.0.0.41 k8s-worker01
10.0.0.42 k8s-worker02
10.0.0.61  harbor01 harbor.bank.local
```

5. Disable swap:

```bash
sudo swapoff -a
sudo sed -i.bak '/ swap / s/^/#/' /etc/fstab
```

6. Enable time sync: `sudo timedatectl set-ntp true`
7. Disable UFW: `sudo systemctl disable --now ufw`
8. Install base tools: `sudo apt install -y curl wget vim net-tools lrzsz ca-certificates gnupg apt-transport-https software-properties-common`

### K8s nodes only (NOT harbor01)

```bash
# Kernel modules
sudo modprobe overlay
sudo modprobe br_netfilter
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

# Sysctl params
cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward = 1
EOF
sudo sysctl --system
```

### Verification checklist

```bash
hostname && ip addr && free -h && swapon --show && timedatectl
ping -c 2 k8s-master01 && ping -c 2 k8s-worker01
# On K8s nodes only:
lsmod | grep br_netfilter && sysctl net.ipv4.ip_forward
```

## Phase 2: Install containerd

Reference: `docs/08-containerd-installation.md`

On k8s-master01, k8s-worker01, k8s-worker02 only.

```bash
sudo apt update && sudo apt install -y containerd
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml
# Edit: SystemdCgroup = true
sudo vim /etc/containerd/config.toml
# Verify: grep -n "SystemdCgroup" /etc/containerd/config.toml
sudo systemctl daemon-reload
sudo systemctl enable containerd
sudo systemctl restart containerd
```

Verify: `containerd --version`, `systemctl status containerd`, `systemctl is-enabled containerd`

## Phase 3: Install Kubernetes Components

Reference: `docs/09-kubernetes-components-installation.md`

On k8s-master01, k8s-worker01, k8s-worker02 only.

```bash
sudo apt-get update && sudo apt-get install -y apt-transport-https ca-certificates curl gpg
sudo mkdir -p -m 755 /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.36/deb/Release.key \
  | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.36/deb/ /' \
  | sudo tee /etc/apt/sources.list.d/kubernetes.list
sudo apt-get update
sudo apt-get install -y kubelet kubeadm kubectl
sudo apt-mark hold kubelet kubeadm kubectl
sudo systemctl enable --now kubelet
```

Verify: `kubeadm version`, `kubelet --version`, `kubectl version --client`

## Phase 4: Initialize Control Node

On k8s-master01 only:

```bash
sudo kubeadm init \
  --apiserver-advertise-address=10.0.0.31 \
  --pod-network-cidr=10.244.0.0/16 \
  --image-repository=registry.aliyuncs.com/google_containers \
  --kubernetes-version=v1.36.1
```

**重要**：init 前必须修改 containerd 的 sandbox_image，否则 kubelet 启动静态 Pod 时仍会从 registry.k8s.io 拉 pause 镜像：

```bash
# 修改 sandbox_image + 确保 SystemdCgroup = true
sudo sed -i "s|sandbox = 'registry.k8s.io/pause:.*'|sandbox = 'registry.aliyuncs.com/google_containers/pause:3.10.2'|" /etc/containerd/config.toml
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
sudo systemctl restart containerd
```

## Phase 5: Install CNI Plugin

Recommended: Calico (supports NetworkPolicy, closer to production)

```bash
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.26.1/manifests/tigera-operator.yaml
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.26.1/manifests/custom-resources.yaml
```

**关键**：默认 Calico IPPool CIDR 是 `192.168.0.0/16`，必须 patch 为与 `--pod-network-cidr` 一致的 `10.244.0.0/16`：

```bash
kubectl patch installation default --type merge -p '{"spec":{"calicoNetwork":{"ipPools":[{"cidr":"10.244.0.0/16"}]}}}'
```

等待 Calico pod 就绪后 `kubectl get nodes` 会显示 Ready。

## Phase 6: Join Worker Nodes

On k8s-worker01 and k8s-worker02, run the `kubeadm join` command output from Phase 4.

Verify: `kubectl get nodes -o wide` (all Ready)

## Phase 7: Install Harbor

On harbor01 only. Install Docker + Docker Compose, then deploy Harbor with `harbor.bank.local` hostname.

Create project `bank-mall` in Harbor UI.

Test push:
```bash
docker login harbor.bank.local
docker pull nginx:stable
docker tag nginx:stable harbor.bank.local/bank-mall/nginx:stable
docker push harbor.bank.local/bank-mall/nginx:stable
```

## Phase 8: Deploy Bank Mall Services

See `k8s-deploy` skill for details.

## High Availability Extension (Phase 5)

| Component | Count | Details |
|-----------|-------|---------|
| Load Balancer | 2 | HAProxy + Keepalived, API Server VIP |
| Control Plane | 3 | kube-apiserver, scheduler, controller-manager, stacked etcd |
| Worker | 2-3 | Business pods, Ingress, monitoring |
| Harbor | 1 | Private registry |
| Storage | 1+ | NFS (入门) or Longhorn/Ceph (进阶) |

Key HA concepts to understand:
- Stacked vs external etcd topology
- API Server VIP via HAProxy + Keepalived
- etcd backup: `etcdctl snapshot save`
- etcd restore: `etcdctl snapshot restore`
- Node failure scenarios and Pod recovery