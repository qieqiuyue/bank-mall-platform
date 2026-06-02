# Kubernetes 集群实施计划

## 目标

为银行商城云原生项目搭建一个基于 Linux 的 Kubernetes 实验集群。

第一阶段目标不是完整生产级高可用，而是一个真实、可解释、可演示的实验集群：

```text
1 个控制节点 + 2 个工作节点 + 1 个 Harbor 节点
```

等项目跑通之后，再把文档扩展为高可用设计：

```text
2 个负载均衡节点 + 3 个控制节点 + 2 个工作节点 + Harbor
```

## 实施阶段

### 阶段 1：准备 Linux 虚拟机

请先按照 `docs/07-ubuntu-vm-initialization.md` 完成 Ubuntu Server 24.04 的初始化。

任务包括：

- 在 VMware NAT 网络中创建 4 台 Ubuntu Server 24.04 虚拟机。
- 配置静态 IP。
- 配置主机名。
- 配置 `/etc/hosts`。
- 关闭 swap。
- 配置时间同步。
- 配置 Kubernetes 所需的内核模块和 sysctl 参数。

预期结果：

```bash
hostname
ip addr
free -h
timedatectl
```

所有节点应具备稳定主机名、静态 IP 和正常时间同步。

### 阶段 2：安装容器运行时

只有在所有节点都完成初始化检查后，才进入这一阶段。

具体操作请按照 `docs/08-containerd-installation.md` 执行。

推荐运行时：

```text
containerd
```

任务包括：

- 在所有 Kubernetes 节点安装 containerd。
- 配置 `SystemdCgroup = true`。
- 如果 Harbor 使用 HTTP 或自签证书，配置镜像仓库访问。
- 重启 containerd 并验证运行状态。

预期结果：

```bash
systemctl status containerd
ctr version
```

### 阶段 3：安装 Kubernetes 组件

只有在 containerd 安装并验证通过后，才进入这一阶段。

具体操作请按照 `docs/09-kubernetes-components-installation.md` 执行。

在所有 Kubernetes 节点安装：

- `kubelet`
- `kubeadm`
- `kubectl`

所有节点版本保持一致。

`harbor01` 不安装 `kubeadm`、`kubelet`、`kubectl`。

预期结果：

```bash
kubeadm version
kubelet --version
kubectl version --client
```

### 阶段 4：初始化控制节点

在 `k8s-master01` 上执行：

```bash
kubeadm init \
  --apiserver-advertise-address=10.0.0.31 \
  --pod-network-cidr=10.244.0.0/16 \
  --image-repository=registry.aliyuncs.com/google_containers \
  --kubernetes-version=v1.36.1
```

然后配置 kubeconfig：

```bash
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config
```

预期结果：

```bash
kubectl get nodes
kubectl get pods -A
```

### 阶段 5：安装网络插件

第一轮实践可以从下面两个方案中选一个：

| 插件 | 适用场景 |
| --- | --- |
| Flannel | 学习路径更简单 |
| Calico | 更适合讲 NetworkPolicy，也更接近生产环境 |

本项目推荐：

```text
Calico
```

原因：

- 更常见于生产化 Kubernetes 场景。
- 支持 NetworkPolicy。
- 方便后续继续做安全加固。

注意：Calico 默认 IPPool CIDR 为 `192.168.0.0/16`，需 patch 为与 kubeadm init 一致的 `10.244.0.0/16`：

```bash
kubectl patch installation default --type merge -p '{"spec":{"calicoNetwork":{"ipPools":[{"cidr":"10.244.0.0/16"}]}}}'
```

### 阶段 6：加入工作节点

在以下节点执行 `kubeadm init` 生成的 `kubeadm join` 命令：

- `k8s-worker01`
- `k8s-worker02`

预期结果：

```bash
kubectl get nodes -o wide
```

所有节点状态都应为 `Ready`。

### 阶段 7：安装 Harbor

在 `harbor01` 上执行。

任务包括：

- 安装 Harbor 所需的 Docker 或兼容运行时。
- 如果所选 Harbor 版本需要 Docker Compose，则一并安装。
- 配置 Harbor 主机名、存储路径、管理员密码和 HTTP/HTTPS 模式。
- 创建项目 `bank-mall`。
- 推送一个测试镜像。

预期结果：

```bash
docker login harbor.bank.local
docker pull nginx:stable
docker tag nginx:stable harbor.bank.local/bank-mall/nginx:stable
docker push harbor.bank.local/bank-mall/nginx:stable
```

### 阶段 8：部署 Bank Mall 服务

任务包括：

- 在 Linux 构建节点构建 4 个服务镜像。
- 推送镜像到 Harbor。
- 更新 Kubernetes 中的镜像引用。
- 应用 Namespace、ConfigMap、Secret、Deployment、Service 等 YAML。
- 通过 NodePort 验证访问。

预期结果：

```bash
kubectl get pods -n bank-mall
kubectl get svc -n bank-mall
curl http://10.0.0.41:30082/api/accounts/A1001/balance
```

## 面试表达

简洁说法：

> 我搭建了一套 Kubernetes 实验集群，包含 1 个控制节点、2 个工作节点和 1 个独立的 Harbor 镜像仓库。业务服务先打成 Docker 镜像，再推送到 Harbor，最后通过 Kubernetes Deployment 和 Service 完成部署，形成了完整的镜像构建、镜像仓库、调度、服务暴露和验证链路。

高可用延展说法：

> 如果进一步做生产级高可用，我会把集群扩展为 3 个控制节点，并引入 HAProxy 和 Keepalived 作为 API Server 入口，同时补齐 etcd 快照备份与恢复方案。
