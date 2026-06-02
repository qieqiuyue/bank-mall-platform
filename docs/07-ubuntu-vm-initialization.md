# Ubuntu Server 24.04 虚拟机初始化手册

## 目标

初始化 4 台 Ubuntu Server 24.04 LTS 虚拟机，为 Kubernetes 和 Harbor 做准备：

| 主机名 | 角色 | 示例 IP |
| --- | --- | --- |
| `k8s-master01` | Kubernetes 控制平面 | `10.0.0.31` |
| `k8s-worker01` | Kubernetes 工作节点 | `10.0.0.41` |
| `k8s-worker02` | Kubernetes 工作节点 | `10.0.0.42` |
| `harbor01` | Harbor 镜像仓库 | `10.0.0.61` |

这份手册只负责 Linux 节点初始化。Kubernetes、containerd、kubeadm 和 Harbor 的安装会在后续步骤中完成。

## VMware 虚拟机设置

所有虚拟机统一使用 VMware NAT 网络。

推荐虚拟机资源：

| 虚拟机 | CPU | 内存 | 磁盘 |
| --- | ---: | ---: | ---: |
| `k8s-master01` | 2 | 4 GB | 50 GB |
| `k8s-worker01` | 2 | 5 GB | 60 GB |
| `k8s-worker02` | 2 | 5 GB | 60 GB |
| `harbor01` | 2 | 6 GB | 100 GB |

推荐流程：

1. 先创建并安装 `k8s-master01`。
2. 完成基础系统初始化。
3. 以它为模板克隆出 `k8s-worker01`、`k8s-worker02` 和 `harbor01`。
4. 克隆完成后，再分别修改每台虚拟机的主机名和静态 IP。

## Ubuntu 安装注意事项

安装 Ubuntu Server 24.04 时建议：

- 使用最小化服务器安装。
- 不安装桌面环境。
- 创建一个普通管理用户，例如 `ubuntu`。
- 勾选 `Install OpenSSH server`。
- 保证所有虚拟机都接在同一个 VMware NAT 网络。

## 确认 VMware NAT 子网

本文示例使用 `10.0.0.0/24`。

如果你的 VMware NAT 子网不是这个网段，只要保持主机角色映射不变，替换 IP 即可。例如 NAT 网段是 `192.168.88.0/24`，则可以改成：

```text
192.168.88.180 k8s-master01
192.168.88.181 k8s-worker01
192.168.88.182 k8s-worker02
192.168.88.62  harbor01
```

在 Ubuntu 中查看当前网卡和网关：

```bash
ip addr
ip route
```

网卡名称通常会类似 `ens33`、`ens160` 或 `enp0s3`。

## 使用 Netplan 配置静态 IP

先看 netplan 文件：

```bash
ls /etc/netplan/
```

编辑已有文件，文件名可能和下面示例不同：

```bash
sudo vim /etc/netplan/50-cloud-init.yaml
```

`k8s-master01` 示例：

```yaml
network:
  version: 2
  ethernets:
    ens33:
      dhcp4: false
      addresses:
        - 10.0.0.31/24
      routes:
        - to: default
          via: 10.0.0.2
      nameservers:
        addresses:
          - 223.5.5.5
          - 8.8.8.8
```

注意：

- `ens33` 要替换成你机器上的真实网卡名。
- `10.0.0.2` 要替换成你通过 `ip route` 查到的 VMware NAT 网关。
- 每台节点要填自己的 IP 地址。

应用配置：

```bash
sudo netplan apply
ip addr
ip route
ping -c 3 223.5.5.5
ping -c 3 archive.ubuntu.com
```

## 配置主机名

在每台虚拟机上执行对应命令。

```bash
sudo hostnamectl set-hostname k8s-master01
```

其他节点示例：

```bash
sudo hostnamectl set-hostname k8s-worker01
sudo hostnamectl set-hostname k8s-worker02
sudo hostnamectl set-hostname harbor01
```

验证：

```bash
hostname
hostnamectl
```

## 配置 `/etc/hosts`

在每台虚拟机上都编辑：

```bash
sudo vim /etc/hosts
```

加入：

```text
10.0.0.31 k8s-master01
10.0.0.32 k8s-master02
10.0.0.33 k8s-master03
10.0.0.41 k8s-worker01
10.0.0.42 k8s-worker02
10.0.0.61  harbor01 harbor.bank.local
```

验证：

```bash
ping -c 2 k8s-master01
ping -c 2 k8s-master02
ping -c 2 k8s-master03
ping -c 2 k8s-worker01
ping -c 2 k8s-worker02
ping -c 2 harbor01
```

## 验证 SSH 访问

从 Windows PowerShell 或其他 SSH 客户端测试：

```powershell
ssh ubuntu@10.0.0.31
ssh ubuntu@10.0.0.32
ssh ubuntu@10.0.0.41
ssh ubuntu@10.0.0.61
```

如果需要，也可以从 `k8s-master01` 测试 Linux 到 Linux 的 SSH：

```bash
ssh ubuntu@k8s-worker01
ssh ubuntu@k8s-worker02
ssh ubuntu@harbor01
```

## 安装基础工具

在所有虚拟机上执行：

```bash
sudo apt update
sudo apt install -y \
  curl \
  wget \
  vim \
  net-tools \
  lrzsz \
  ca-certificates \
  gnupg \
  apt-transport-https \
  software-properties-common
```

## 检查时间同步

在所有虚拟机上执行：

```bash
timedatectl
```

如果 NTP 没有启用，则打开：

```bash
sudo timedatectl set-ntp true
timedatectl
```

## 关闭 Swap

Kubernetes 默认要求关闭 swap。

临时关闭：

```bash
sudo swapoff -a
```

永久关闭：

```bash
sudo sed -i.bak '/ swap / s/^/#/' /etc/fstab
```

验证：

```bash
swapon --show
free -h
```

`swapon --show` 正常情况下应没有任何输出。

## 在实验环境中禁用 UFW

为了减少前期网络排障噪音，学习环境先禁用 Ubuntu 防火墙：

```bash
sudo systemctl disable --now ufw
sudo ufw status
```

预期状态：

```text
Status: inactive
```

## 配置 Kubernetes 前置内核模块

在 `k8s-master01`、`k8s-worker01`、`k8s-worker02` 上执行。

如果 `harbor01` 后续不跑 Kubernetes 工作负载，则不需要这些内核配置。

立即加载：

```bash
sudo modprobe overlay
sudo modprobe br_netfilter
```

持久化加载：

```bash
cat <<EOF | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF
```

验证：

```bash
lsmod | grep overlay
lsmod | grep br_netfilter
```

## 配置 Kubernetes Sysctl 参数

在 `k8s-master01`、`k8s-worker01`、`k8s-worker02` 上执行：

```bash
cat <<EOF | sudo tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward = 1
EOF
```

应用参数：

```bash
sudo sysctl --system
```

验证：

```bash
sysctl net.bridge.bridge-nf-call-iptables
sysctl net.bridge.bridge-nf-call-ip6tables
sysctl net.ipv4.ip_forward
```

预期值：

```text
net.bridge.bridge-nf-call-iptables = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward = 1
```

## 克隆虚拟机后的修正

以 `k8s-master01` 克隆出其他节点后，按顺序处理：

1. 修改主机名。
2. 修改 netplan 中的静态 IP。
3. 执行 `sudo netplan apply`。
4. 确认 `/etc/hosts` 正确。
5. 重启虚拟机。

```bash
sudo reboot
```

如果克隆后的机器在 DHCP 或主机身份上表现异常，可以刷新 machine-id：

```bash
sudo rm -f /etc/machine-id
sudo systemd-machine-id-setup
sudo reboot
```

这一步只在克隆之后做，不要在已经装好 Kubernetes 组件的节点上随意执行。

## 最终初始化检查清单

在每台虚拟机上执行：

```bash
hostname
ip addr
ip route
free -h
swapon --show
timedatectl
ping -c 2 k8s-master01
ping -c 2 k8s-worker01
ping -c 2 k8s-worker02
ping -c 2 harbor01
```

只在 Kubernetes 节点上执行：

```bash
lsmod | grep br_netfilter
lsmod | grep overlay
sysctl net.bridge.bridge-nf-call-iptables
sysctl net.ipv4.ip_forward
```

验收标准：

- 所有虚拟机都使用 VMware NAT 子网中的静态 IP。
- Windows 宿主机可以 SSH 到所有虚拟机。
- 所有虚拟机都能通过主机名互相解析。
- 所有虚拟机的 swap 都已关闭。
- 所有虚拟机的时间同步正常。
- `k8s-master01`、`k8s-worker01`、`k8s-worker02` 已具备 Kubernetes 所需内核模块和 sysctl 参数。
- `harbor01` 已准备好进入 Docker 和 Harbor 安装阶段。
