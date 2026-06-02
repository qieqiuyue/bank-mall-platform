# 27 - Worker 节点 containerd Harbor HTTP 配置

> 适用版本：containerd v2.2.1 | 日期：2026-05-29

## 背景

项目使用 Harbor HTTP 模式（非 HTTPS），Harbor 部署在 `10.0.0.61:80`。worker 节点的 containerd 默认以 HTTPS 443 访问 registry，需显式配置 HTTP 端点。

## 容器运行时：containerd vs Docker vs crictl

| 工具 | 所属 | 本机路径 | 用途 |
|------|------|----------|------|
| `docker` | Docker Engine | 仅 harbor01 | 构建/推送镜像 |
| `containerd` | 独立运行时 | 所有节点 | 容器生命周期管理 |
| `ctr` | containerd 自带 | 所有节点 | containerd 原生 CLI 调试 |
| `crictl` | cri-tools | 如安装 | CRI 标准调试 CLI |

本项目 worker 未安装 `crictl`，使用 `sudo ctr -n k8s.io` 管理 Kubelet 命名空间下的镜像。

## containerd v2 配置变化

containerd v2.x 改变了插件路径格式，与 v1.x 的 `config.toml` 不兼容：

| 配置方式 | containerd v1.x | containerd v2.x |
|----------|----------------|----------------|
| hosts.toml | ✅ 支持 | ✅ 推荐方式 |
| config.toml mirror | `[plugins."io.containerd.grpc.v1.cri"...]` | ❌ 路径不同，会冲突 |

**关键教训：** 不要在 v2.x 的 `config.toml` 中混合使用 v1 和 v2 格式的 registry 配置，两者冲突会导致 containerd 启动失败。

## 推荐配置方案：hosts.toml

```bash
sudo mkdir -p /etc/containerd/certs.d/10.0.0.61

sudo tee /etc/containerd/certs.d/10.0.0.61/hosts.toml <<'EOF'
server = "http://10.0.0.61"

[host."http://10.0.0.61"]
  capabilities = ["pull", "resolve"]
  skip_verify = true
EOF

sudo systemctl restart containerd
```

### 前提

- `config.toml` 中**不能**有 `10.0.0.61` 的旧格式配置（`sed -i '/10\.0\.0\.61/d' /etc/containerd/config.toml` 清理）
- 重启 containerd 不会影响正在运行的 Pod

### 验证

```bash
sudo ctr -n k8s.io image pull --plain-http 10.0.0.61/bank-mall/auth-service:1.0.0
```

## 手动绕过方案（配置无效时的备用）

如果 `hosts.toml` 因配置冲突未生效，可用 `ctr` 手动拉取镜像到 Kubelet 命名空间。配合 `imagePullPolicy: IfNotPresent`，Pod 可直接使用本地镜像。

```bash
sudo ctr -n k8s.io image pull --plain-http --user admin:HARBOR_PASSWORD \
  10.0.0.61/bank-mall/auth-service:1.0.0
sudo ctr -n k8s.io image pull --plain-http --user admin:HARBOR_PASSWORD \
  10.0.0.61/bank-mall/account-service:1.0.0
sudo ctr -n k8s.io image pull --plain-http --user admin:HARBOR_PASSWORD \
  10.0.0.61/bank-mall/payment-service:1.0.0
sudo ctr -n k8s.io image pull --plain-http --user admin:HARBOR_PASSWORD \
  10.0.0.61/bank-mall/notification-service:1.0.0
```

### 注意事项

- `--plain-http` 必需，否则 containerd 默认尝试 HTTPS 443
- `--user admin:HARBOR_PASSWORD` 只有在 Harbor 项目非公开时需要
- `-n k8s.io` 是 Kubelet 使用的 containerd namespace（非 K8s namespace）

## harbor-pull Secret 清理

部署 YAML 中已移除 `imagePullSecrets: - name: harbor-pull` 引用。

**原因：**
- `harbor-pull` 是一个 `docker-registry` 类型的 Secret
- Harbor HTTP 模式下，containerd 不需要镜像拉取凭据（项目公开或 IP 白名单场景）
- 该 Secret 的 YAML 未纳入 Git 版本控制，`deploy.sh` 也不创建它
- Pod Events 持续报 `FailedToRetrieveImagePullSecret` Warning

**替代方案：** `imagePullPolicy: IfNotPresent` + 手动 `ctr pull` 或正确配置 `hosts.toml`。

## 踩坑时间线

| 步骤 | 尝试 | 错误 | 根因 |
|------|------|------|------|
| 1 | Docker 多阶段构建 | `maven.aliyun.com: Try again` | 容器内 DNS 解析不到 aliyun（GFW），换简单 Dockerfile |
| 2 | Harbor 重启后 `docker push` | `connect: connection refused` | Harbor 只有 harbor-log 一个容器运行，`docker compose up -d` |
| 3 | `docker push` 到 Harbor HTTP | `Head https://10.0.0.61/... EOF` | Docker daemon 配置了 insecure-registries 但重启后未加载，`systemctl restart docker` |
| 4 | worker `imagePullPolicy: Always` | `ImagePullBackOff: dial tcp 10.0.0.61:443` | containerd 走 HTTPS 443 而非 HTTP 80 |
| 5 | `hosts.toml` 配置后仍失败 | 同 443 错误 | config.toml 中残留 v1 格式 `[plugins."io.containerd.grpc.v1.cri"...]` 与 hosts.toml 冲突 |
| 6 | `ctr pull --plain-http` | `authorization failed: no basic auth credentials` | Harbor 项目需要认证 |
| 7 | `ctr pull --plain-http --user admin:xxx` | ✅ 成功 | 手动拉取绕过配置问题，配合 IfNotPresent 使用 |

## 相关文件

| 文件 | 说明 |
|------|------|
| `/etc/containerd/certs.d/10.0.0.61/hosts.toml` | containerd v2 Harbor HTTP 配置 |
| `/etc/containerd/config.toml` | containerd 主配置（不应含 Harbor 旧格式） |
| `k8s/base/*-service/deployment.yaml` | `imagePullPolicy: IfNotPresent`，无 imagePullSecrets |
