# Kubernetes 组件安装手册

## 目标

在以下 Kubernetes 节点上安装并验证 `kubeadm`、`kubelet`、`kubectl`：

- `k8s-master01`
- `k8s-worker01`
- `k8s-worker02`

`harbor01` 不安装这些 Kubernetes 组件，它后续只作为 Harbor 镜像仓库节点使用。

## 前置条件

开始前请确认：

- 已完成 Ubuntu Server 24.04 虚拟机初始化。
- 已完成 containerd 安装与验证。
- `swap` 已关闭。
- Kubernetes 所需内核模块和 sysctl 参数已生效。
- 三台 Kubernetes 节点都可以访问外网或可访问 `pkgs.k8s.io`。

建议先执行：

```bash
hostname
swapon --show
systemctl status containerd --no-pager
grep -n "SystemdCgroup" /etc/containerd/config.toml
sysctl net.ipv4.ip_forward
```

## 安装基础依赖

在 `k8s-master01`、`k8s-worker01`、`k8s-worker02` 上执行：

```bash
sudo apt-get update
sudo apt-get install -y apt-transport-https ca-certificates curl gpg
```

## 添加 Kubernetes 官方软件源

本项目使用 Kubernetes 官方当前推荐的 `pkgs.k8s.io` 软件源，不使用已经废弃的旧版 Kubernetes apt 源。

创建 keyrings 目录：

```bash
sudo mkdir -p -m 755 /etc/apt/keyrings
```

导入 Kubernetes 仓库签名 key：

```bash
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.36/deb/Release.key \
  | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
```

添加 Kubernetes v1.36 软件源：

```bash
echo 'deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.36/deb/ /' \
  | sudo tee /etc/apt/sources.list.d/kubernetes.list
```

更新软件源：

```bash
sudo apt-get update
```

## 安装 kubelet、kubeadm、kubectl

在三台 Kubernetes 节点上执行：

```bash
sudo apt-get install -y kubelet kubeadm kubectl
```

锁定版本，避免后续 `apt upgrade` 误升级 Kubernetes 组件：

```bash
sudo apt-mark hold kubelet kubeadm kubectl
```

设置 kubelet 开机自启：

```bash
sudo systemctl enable --now kubelet
```

注意：在执行 `kubeadm init` 或 `kubeadm join` 之前，`kubelet` 可能不会处于完全正常的 Running 状态。如果日志中表现为等待集群配置，这是预期现象。

## 验证安装结果

在三台 Kubernetes 节点上执行：

```bash
kubeadm version
kubelet --version
kubectl version --client
```

确认组件已锁定：

```bash
apt-mark showhold
```

预期至少包含：

```text
kubeadm
kubectl
kubelet
```

确认 kubelet 已设置为开机自启：

```bash
systemctl is-enabled kubelet
```

预期输出：

```text
enabled
```

查看 kubelet 状态：

```bash
systemctl status kubelet --no-pager
```

如果此时状态不是 `active (running)`，先不要慌。只要 `kubeadm`、`kubelet`、`kubectl` 版本能正常输出，并且 kubelet 已启用，下一步执行 `kubeadm init/join` 后会继续完成集群配置。

## 当前阶段验收标准

三台 Kubernetes 节点都满足以下条件：

- `kubeadm version` 能正常输出版本信息。
- `kubelet --version` 能正常输出版本信息。
- `kubectl version --client` 能正常输出客户端版本信息。
- `apt-mark showhold` 包含 `kubeadm`、`kubelet`、`kubectl`。
- `systemctl is-enabled kubelet` 返回 `enabled`。
- 没有在 `harbor01` 上安装 Kubernetes 组件。

## 常见问题

### 无法访问 pkgs.k8s.io

如果 `curl` 或 `apt-get update` 访问 `pkgs.k8s.io` 失败，优先确认：

- 虚拟机是否能访问外网。
- DNS 是否正常。
- VMware NAT 是否能正常出网。
- 是否需要临时配置代理。

### kubelet 状态异常

在 `kubeadm init/join` 之前，`kubelet` 可能因为缺少集群配置而启动不完整。这个阶段重点看版本命令和开机自启是否正常。

## 完成后的下一步

三台 Kubernetes 节点全部通过验收后，进入控制节点初始化阶段：

- 在 `k8s-master01` 上执行 `kubeadm init`
- 配置 kubeconfig
- 准备安装 CNI 网络插件
- 保存 worker 节点加入集群所需的 `kubeadm join` 命令
