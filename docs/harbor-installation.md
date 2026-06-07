# Harbor 安装与 containerd 集成

> **环境**：Ubuntu 24.04, Docker 26+, Harbor 2.12+
> **IP**：10.0.0.61（harbor01）
> **协议**：HTTP only（实验集群）

## 1. Harbor 服务器安装

```bash
# 下载 Harbor offline installer
wget https://github.com/goharbor/harbor/releases/download/v2.12.2/harbor-offline-installer-v2.12.2.tgz
tar xf harbor-offline-installer-v2.12.2.tgz
cd harbor

# 配置 harbor.yml
# hostname: 10.0.0.61
# http:
#   port: 80
# https:  ← 注释掉整个 https 段
# harbor_admin_password: Harbor12345

# 安装
./install.sh

# 验证（9 个容器 Running）
docker compose ps
```

## 2. containerd 节点配置（worker01/02）

containerd v2.x 使用 `hosts.toml` 而非旧版 `config.toml` 的 `[plugins."io.containerd.grpc.v1.cri"]`：

```bash
# 创建 Harbor 镜像源配置目录
mkdir -p /etc/containerd/certs.d/10.0.0.61

# /etc/containerd/certs.d/10.0.0.61/hosts.toml
cat > /etc/containerd/certs.d/10.0.0.61/hosts.toml <<'TOML'
server = "http://10.0.0.61"

[host."http://10.0.0.61"]
  capabilities = ["pull", "resolve"]
  skip_verify = true
  plain-http = true
TOML

# 重启 containerd
systemctl restart containerd
```

## 3. 手动预拉镜像（worker 节点）

```bash
# GFW 阻断 ghcr.io/docker.io 时，从 Harbor 预拉
sudo ctr -n k8s.io images pull --plain-http --user admin:Harbor12345 \
  10.0.0.61/bank-mall/auth-service:2.0.0
```

## 4. Harbor 重启

```bash
cd /root/harbor && docker compose up -d
# 等待 9/9 Running
```

## 5. Docker daemon 配置

```json
// /etc/docker/daemon.json
{
  "insecure-registries": ["10.0.0.61"]
}
```

## 6. 本地镜像 → Harbor 推送流程

```bash
docker tag auth-service:2.0.0 10.0.0.61/bank-mall/auth-service:2.0.0
docker push 10.0.0.61/bank-mall/auth-service:2.0.0
```

---

参见 `docs/27-worker-harbor-config.md` 了解 containerd `hosts.toml` 的详细排障流程。
