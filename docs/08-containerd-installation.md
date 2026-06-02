# containerd 安装与验证手册

## 目标

在以下节点上安装并验证 `containerd`：

- `k8s-master01`
- `k8s-worker01`
- `k8s-worker02`

当前阶段先不处理 `harbor01`，Harbor 节点后续走单独路线。

## 前置条件

在开始前，以下条件必须已满足：

- 已完成 [07-ubuntu-vm-initialization.md](/C:/LearningResources/k8s项目/bank-mall-cloudnative/docs/07-ubuntu-vm-initialization.md) 中的初始化步骤。
- 三台 Kubernetes 节点都能正常联网。
- `swap` 已关闭。
- `br_netfilter`、`overlay` 和相关 `sysctl` 参数已配置完成。

建议先验证：

```bash
hostname
swapon --show
timedatectl
lsmod | grep br_netfilter
sysctl net.ipv4.ip_forward
```

## 安装 containerd

在 `k8s-master01`、`k8s-worker01`、`k8s-worker02` 上执行：

```bash
sudo apt update
sudo apt install -y containerd
```

安装完成后确认二进制存在：

```bash
containerd --version
ctr version
```

## 生成默认配置

创建配置目录：

```bash
sudo mkdir -p /etc/containerd
```

生成默认配置：

```bash
containerd config default | sudo tee /etc/containerd/config.toml
```

## 配置 SystemdCgroup

编辑配置文件：

```bash
sudo vim /etc/containerd/config.toml
```

找到这一段：

```toml
[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc.options]
  SystemdCgroup = false
```

改成：

```toml
[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runc.options]
  SystemdCgroup = true
```

可以用下面命令快速确认修改结果：

```bash
grep -n "SystemdCgroup" /etc/containerd/config.toml
```

预期输出中应包含：

```text
SystemdCgroup = true
```

## 启动并设置开机自启

执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable containerd
sudo systemctl restart containerd
```

## 基础验证

检查服务状态：

```bash
systemctl status containerd --no-pager
```

检查版本：

```bash
containerd --version
ctr version
```

检查服务是否开机自启：

```bash
systemctl is-enabled containerd
```

预期输出：

```text
enabled
```

## 可选验证：拉取测试镜像

如果节点当前可以访问外网，可以做一次最小镜像拉取验证：

```bash
sudo ctr -n k8s.io images pull docker.io/library/busybox:latest
sudo ctr -n k8s.io images ls | grep busybox
```

如果外网访问受限，这一步可以暂时跳过，后续在 Harbor 安装完成后再用内网镜像验证。

## 当前阶段验收标准

三台节点都满足以下条件：

- `containerd --version` 能正常输出版本信息。
- `ctr version` 能正常输出版本信息。
- `grep -n "SystemdCgroup" /etc/containerd/config.toml` 显示为 `true`。
- `systemctl status containerd --no-pager` 显示服务正在运行。
- `systemctl is-enabled containerd` 返回 `enabled`。

## 完成后的下一步

containerd 验证通过后，进入 Kubernetes 组件安装阶段：

- 安装 `kubeadm`
- 安装 `kubelet`
- 安装 `kubectl`
- 保持三台节点版本一致

