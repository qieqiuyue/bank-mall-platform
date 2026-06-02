# 实际操作记录

本文档记录从零搭建 bank-mall K8s 集群的实际步骤，包含与原始文档的差异和踩坑记录。

## 集群拓扑（实际）

| 主机名 | IP | 角色 | CPU | 内存 | 磁盘 |
|--------|-----|------|-----|------|------|
| k8s-master01 | 10.0.0.31 | 控制平面 | 2 | 4GB | 50GB |
| k8s-worker01 | 10.0.0.41 | 工作节点 | 2 | 5GB | 60GB |
| k8s-worker02 | 10.0.0.42 | 工作节点 | 2 | 5GB | 60GB |
| k8s-harbor01 | 10.0.0.61 | Harbor 仓库 | 2 | 6GB | 100GB |

环境：VMware NAT (10.0.0.0/24), 网关 10.0.0.2
OS: Ubuntu Server 24.04 LTS

## 阶段 1：Ubuntu VM 初始化 ✅

已完成，参照 `docs/07-ubuntu-vm-initialization.md`。

关键操作：
- 静态 IP 配置（netplan）
- 关闭 swap
- 配置 /etc/hosts
- 加载内核模块 overlay、br_netfilter
- 配置 sysctl 参数（ip_forward 等）
- 禁用 UFW、启用 NTP

## 阶段 2：安装 containerd ✅

已完成，参照 `docs/08-containerd-installation.md`。

```bash
sudo apt install -y containerd
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml
# 修改 SystemdCgroup = true
sudo systemctl restart containerd
```

## 阶段 3：安装 Kubernetes 组件 ✅

已于三台 K8s 节点完成，参照 `docs/09-kubernetes-components-installation.md`。

安装版本：v1.36

## 阶段 4：初始化控制平面 ✅

在 k8s-master01 上执行。

### 踩坑：registry.k8s.io 无法拉取

原始命令：
```bash
sudo kubeadm init \
  --apiserver-advertise-address=10.0.0.31 \
  --pod-network-cidr=10.244.0.0/16
```

报错：`failed to pull and unpack image "registry.k8s.io/kube-scheduler:v1.36.1"` - TLS handshake timeout。

根因：境内环境访问 registry.k8s.io 不稳定（ping 通但 HTTPS 拉镜像被干扰）。

### 解决方案：使用阿里云镜像源拉取

保持 v1.36.1 版本不变，通过 `--image-repository` 指定阿里云镜像站：

```bash
sudo kubeadm init \
  --apiserver-advertise-address=10.0.0.31 \
  --pod-network-cidr=10.244.0.0/16 \
  --image-repository=registry.aliyuncs.com/google_containers \
  --kubernetes-version=v1.36.1
```

### 踩坑 2：containerd sandbox_image 指向 registry.k8s.io

kubeadm init 成功生成了 manifests 文件，但 kubelet 启动静态 Pod 时失败：
```
RunPodSandbox for "etcd-k8s-master01" failed: 
failed to get sandbox image "registry.k8s.io/pause:3.10.1"
```

根因：containerd 的 `sandbox_image` 仍指向 `registry.k8s.io/pause:3.10.1`，kubelet 创建 Pod sandbox 时 containerd 会独立拉取 pause 镜像——这个不受 `--image-repository` 参数影响。

修复：
```bash
sudo sed -i "s|sandbox = 'registry.k8s.io/pause:3.10.1'|sandbox = 'registry.aliyuncs.com/google_containers/pause:3.10.2'|" /etc/containerd/config.toml
# 同时确保 SystemdCgroup = true（kubeadm reset 会重置）
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
sudo systemctl restart containerd
```
```bash
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config
```

### 与文档的差异

| 项目 | 文档原值 | 实际值 | 原因 |
|------|---------|--------|------|
| 镜像仓库 | registry.k8s.io | registry.aliyuncs.com/google_containers | 境内拉取问题 |
| master IP | 10.0.0.180 | 10.0.0.31 | VMware NAT 子网不同 |
| harbor IP | 10.0.0.62 | 10.0.0.61 | 实际分配 |

## 阶段 5：安装 CNI 网络插件 ✅

### Calico CIDR 不匹配

默认 `custom-resources.yaml` 的 IPPool CIDR 是 `192.168.0.0/16`，与 kubeadm init 的 `10.244.0.0/16` 不一致，导致 operator 报错：
```
IPPool 192.168.0.0/16 is not within the platform's configured pod network CIDR(s) [10.244.0.0/16]
```

修复：
```bash
kubectl patch installation default --type merge -p '{"spec":{"calicoNetwork":{"ipPools":[{"cidr":"10.244.0.0/16"}]}}}'
```

### Calico 镜像拉取问题

Calico 组件镜像也需要从 registry.k8s.io 拉取，同样被墙。先不特殊处理，CIDR 修复后 Calico 会自行重试直到成功（等待时间会稍长）。

## 阶段 6：加入工作节点 ✅

### Worker 节点踩坑

**问题 1：swap 未永久关闭**
- kubeadm reset 后 swap 又启用，worker join 时 kubelet 启动失败
- 修复：`sudo swapoff -a && sudo sed -i '/swap/s/^/#/' /etc/fstab`

**问题 2：sandbox_image 未修改**
- Worker 节点的 containerd 默认 `sandbox = 'registry.k8s.io/pause:3.10.1'`，导致 kubelet 启动静态 pod 时拉 pause 镜像失败
- 修复：
```bash
sudo sed -i "s|sandbox = 'registry.k8s.io/pause:3.10.1'|sandbox = 'registry.aliyuncs.com/google_containers/pause:3.10.2'|" /etc/containerd/config.toml
```

**问题 3：kubelet 证书残留**
- 前几次失败的 join 留下 `/etc/kubernetes/kubelet.conf` 和 `/var/lib/kubelet/pki/` 中的过期证书，kubelet 启动时无法加载
- 修复：
```bash
sudo systemctl stop kubelet
sudo rm -rf /etc/kubernetes /var/lib/kubelet /etc/cni/net.d
```

### Worker join 成功

两台 worker 节点最终成功加入集群，3 节点全部 Ready。

## 阶段 7：安装 Harbor ✅

在 k8s-harbor01 (10.0.0.61) 上安装 Harbor v2.10.3。

```bash
sudo apt install -y docker.io docker-compose-v2
wget https://github.com/goharbor/harbor/releases/download/v2.10.3/harbor-offline-installer-v2.10.3.tgz
tar xzf harbor-offline-installer-v2.10.3.tgz
cd harbor
cp harbor.yml.tmpl harbor.yml
```

harbor.yml 关键配置：
```yaml
hostname: 10.0.0.61  # 注意冒号不能漏！
http:
  port: 80
# https 部分全部注释掉
harbor_admin_password: <HARBOR_PASSWORD>
```

```bash
sudo ./install.sh
```

### Harbor 踩坑

- `hostname:` 后面的冒号容易漏写（`hostname 10.0.0.61`），导致 YAML 解析报错

### K8s 节点配置 HTTP 镜像仓

所有 K8s 节点（master + 2 worker）需配置 containerd 允许 HTTP 拉取：

```bash
sudo mkdir -p /etc/containerd/certs.d/10.0.0.61
cat <<EOF | sudo tee /etc/containerd/certs.d/10.0.0.61/hosts.toml
server = "http://10.0.0.61"

[host."http://10.0.0.61"]
  capabilities = ["pull", "resolve"]
  skip_verify = true
EOF
sudo systemctl restart containerd
```

## 阶段 8：部署 Bank Mall 服务 ✅

### 踩坑 1：hosts.toml 和 config.toml 都不生效

尝试过的方案：
1. `hosts.toml` (certs.d) — containerd CRI 插件未读取
2. `config.toml` 追加 `registry.mirrors` — 仍走 HTTPS

根因：containerd 2.2.1 对 HTTP 仓库的配置处理与 docker 不同，`--plain-http` 标志仅对 `ctr` CLI 有效，kubelet 通过 CRI 拉取时不传递此参数。

### 最终解决方案：手动预拉镜像

在每台 worker 上，用 `ctr` 命令直接拉取镜像到 `k8s.io` namespace（kubelet 从此处读取）：

```bash
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/auth-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/account-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/payment-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/notification-service:1.0.0
```

> 注：生产环境应配置 Harbor HTTPS（自签证书 + CA 导入），或使用 `imagePullSecrets` + containerd SSL 配置。

### 踩坑 2：k8s-worker01 containerd 也崩了

k8s-worker01 的 containerd 配置被之前的错误 TOML 追加破坏，重新生成默认配置后恢复。

### 踩坑 3：master 重启后 swap 重新启用

`swapoff -a` 是临时的，重启后 `/etc/fstab` 中的 swap 条目仍然加载。永久修复：
```bash
sudo sed -i '/swap/s/^/#/' /etc/fstab
```

### 部署验证

```
NAME     READY   STATUS    AGE
auth-service          1/1  Running   63s
account-service       1/1  Running   63s
payment-service       1/1  Running   63s
notification-service  1/1  Running   63s
```

接口测试（port-forward）：
```bash
curl -X POST localhost:18081/api/auth/login -d '{"username":"admin","password":"123456"}'
# → {"code":"SUCCESS","token":"mock-token-...",...}

curl localhost:18082/api/accounts/A1001/balance
# → {"availableBalance":8888.88,"currency":"CNY",...}
```

ConfigMap 注入验证：日志显示 `profile is active: "prod"` ✅

```bash
# 构建镜像
REGISTRY=10.0.0.61 NAMESPACE=bank-mall VERSION=1.0.0 PUSH=true bash scripts/build-images.sh

# 部署
bash scripts/deploy.sh

# 验证
kubectl get pods -n bank-mall
kubectl get svc -n bank-mall
```

## 阶段 8：部署 Ingress Nginx ✅

参照 `docs/15-ingress-deployment.md`。

关键配置：
- Ingress Nginx Controller v1.10.1（阿里云镜像）
- NodePort 30080/30443
- 4 条路由规则 + rewrite-target

## 阶段 9：全栈部署验证与排障 ✅

参照 `docs/18-deployment-verification.md`。

排障要点：
1. auth-service `characterEncoding=utf8mb4` 不合法 → 改为 UTF-8
2. Docker build 缓存导致代码修改未生效 → 加 `--no-cache`
3. containerd HTTP 仓库兼容性 → `ctr --plain-http` 预拉或 `docker save | ctr import`
4. MySQL livenessProbe 太激进 → 移除 liveness，只保留 readiness
5. auth-service livenessProbe 太激进 → initialDelaySeconds 30→120
6. Grafana/Prometheus worker02 网络问题 → 强制调度到 worker01
7. MySQL 镜像改用 Docker Hub 原始名 (mysql:8.0) 避免 Harbor 拉取问题
8. 所有监控组件改用 IfNotPresent + nodeName 固定调度

最终状态（2026-05-24）：
```
bank-mall namespace:
  auth-service          1/1 Running   (k8s-worker01)
  account-service       1/1 Running   (k8s-worker01)
  payment-service       1/1 Running   (k8s-worker01)
  notification-service   1/1 Running   (k8s-worker01)
  mysql                 1/1 Running   (k8s-worker01)

monitoring namespace:
  grafana               1/1 Running   (k8s-worker01)
  prometheus            1/1 Running   (k8s-worker01)

ingress-nginx namespace:
  ingress-nginx-controller  1/1 Running  (k8s-worker01)
```

接口验证：
```bash
curl http://10.0.0.41:30080/auth/actuator/health       → {"status":"UP"}
curl http://10.0.0.41:30080/account/actuator/health      → {"status":"UP"}
curl http://10.0.0.41:30080/payment/actuator/health      → {"status":"UP"}
curl http://10.0.0.41:30080/notification/actuator/health → {"status":"UP"}
```

Grafana: http://10.0.0.41:30030 (admin/<GRAFANA_PASSWORD>)
Prometheus: http://10.0.0.41:30090

## 阶段 10：HPA 自动扩缩容 ✅

参照 `docs/19-hpa.md`。

配置概要：
- 4 个微服务各配 HPA：minReplicas=1, maxReplicas=3
- CPU 阈值 70%（只用 CPU，不用内存——JVM 内存不释放导致无法缩容）
- autoscaling/v2 API（多指标支持）
- 缩容冷却 300s（防抖动），scaleDown Pods:1 + selectPolicy:Min
- 扩容 selectPolicy: Max（快速扩容）
- auth-service 移除 nodeName: k8s-worker01（HPA 需要自由调度）

关键变更：
1. `k8s/base/auth-service/deployment.yaml` 移除 `nodeName: k8s-worker01`，memory requests 改回 256Mi
2. 新增 `k8s/base/hpa/` 目录下 4 个 HPA YAML（CPU-only 指标）
3. `scripts/deploy.sh` 追加 `[10/10]` HPA 部署步骤
4. `scripts/ci.sh` 修复 rollout restart 竞态条件
5. `k8s/base/ingress/controller-deploy.yaml` 加回 `nodeName: k8s-worker01`
6. `k8s/base/ingress/controller-service.yaml` 去掉 `externalTrafficPolicy: Local`

踩坑记录：
1. Metrics Server 需加 `--kubelet-insecure-tls`（kubeadm 自签证书不含 IP SANs）
2. HPA 默认 10% 容差：80% 阈值实际需 88% 才触发
3. JVM 内存不随负载释放，内存指标导致 HPA 无法缩容 → 改为 CPU-only
4. scaleDown Percent:10 在 3 副本时取整为 0 → 加 Pods:1 兜底策略
5. Deployment 变更触发滚动更新→新 Pod 调度到 worker02→ImagePullBackOff（需预拉镜像）
6. ci.sh 的 `rollout restart` 在 `apply` 之前导致 kubectl wait 引用已销毁 Pod 名
7. Ingress 被 `rollout restart` 调度到 worker02→NodePort 不可达
8. `externalTrafficPolicy: Local` 导致没有 Ingress Pod 的节点 DROP NodePort 流量

## 阶段 11：安全加固 ✅

参照 `docs/20-security-hardening.md`。

### NetworkPolicy（白名单模型）

默认拒绝所有流量，只放行明确允许的：

| Policy | 方向 | 允许流量 |
|--------|------|---------|
| bank-mall-deny-all | Ingress+Egress | 默认拒绝所有 |
| allow-ingress-to-services | Ingress | ingress-nginx → 服务 8081-8084 |
| allow-services-to-mysql | Ingress | auth-service → mysql:3306 |
| allow-monitoring-to-services | Ingress | monitoring namespace → metrics 端口 |
| allow-services-egress | Egress | 服务 → mysql:3306 |
| allow-dns-egress | Egress | 所有 Pod → kube-dns:53 |

验证：deny-all 加回后 4 个服务健康检查全部 `{"status":"UP"}`，auth-service 登录正常。

### PodSecurity（PSA Labels）

bank-mall namespace 标签：
- `pod-security.kubernetes.io/enforce: baseline`
- `pod-security.kubernetes.io/audit: restricted`
- `pod-security.kubernetes.io/warn: restricted`

### securityContext（所有 Deployment）

| 服务 | runAsUser | runAsNonRoot | 说明 |
|------|-----------|-------------|------|
| auth-service | 1000 | true | Spring Boot 不需要 root |
| account-service | 1000 | true | 同上 |
| payment-service | 1000 | true | 同上 |
| notification-service | 1000 | true | 同上 |
| mysql | 999 | true (container) | UID 999 是 mysql 用户 |
| grafana | 472 | true | UID 472 是 grafana 用户 |
| prometheus | 65534 | true | UID 65534 是 nobody 用户 |
| ingress-nginx | 101 | true | 已有 securityContext，未修改 |

所有容器都加了 `capabilities: drop ALL` + `allowPrivilegeEscalation: false`。

### 关键变更
1. 新增 `k8s/base/security/` 目录（6 个 NetworkPolicy + 1 个 PSA namespace）
2. 8 个 Deployment YAML 加 securityContext
3. ingress-nginx 和 monitoring namespace 加 `name` 标签（NetworkPolicy 跨命名空间选择）
4. `scripts/deploy.sh` 更新为 12 步部署流程

## 阶段 12-附录：S0 前置验证（2026-06-02）✅

在后续工作启动前，完成三条前置验证：

| # | 验证项 | 结果 | 备注 |
|---|--------|------|------|
| 1 | `curl https://github.com`（4 VM） | ✅ 通 | ArgoCD 用公网 Git |
| 2 | `curl ghcr.io`（4 VM） | ❌ 不通 | 镜像用 `ghcr.nju.edu.cn` 代替 `ghcr.io` |
| 3 | Spring Boot 4.0.6 + RestClient 编译 + auth-service 启动 | ✅ 通过 | 从 3.1.3/JDK 17 升级到 4.0.6/JDK 21 |

### S0-3：auth-service Spring Boot 升级详情

**当前状态**：仅 auth-service 升级到 SB 4.0.6，其他 3 个服务仍为 SB 3.1.3，后续统一升级。

**变更清单**：

| 文件 | 变更 |
|------|------|
| `pom.xml` | 引入 `spring-boot-starter-parent:4.0.6`，JDK 17→21，版本号由 BOM 托管 |
| `Dockerfile` | `maven:3.9-eclipse-temurin-21-alpine` + `eclipse-temurin:21-alpine` |
| `RestClientConfig.java` | 新增，注册 `RestClient` Bean（Spring Framework 7.0） |
| `application-h2.yml` | 新增，H2 内存库 profile，不依赖 MySQL 即可启动 |

**踩坑记录**：
1. `RestClientCustomizer` 在 Spring Boot 4.0 被移除 → 改用 `RestClient.Builder` 注入直接创建 Bean
2. H2 profile 需同时覆盖 `spring.jpa.properties.hibernate.dialect` 为 `H2Dialect`，否则被默认 `MySQLDialect` 覆盖导致 DDL 语法错误（`engine=InnoDB` 不兼容 H2）

**验证结果**：
```bash
# 编译
mvn clean package -DskipTests  → BUILD SUCCESS

# 启动
java -jar target/auth-service-1.0.0.jar --spring.profiles.active=h2
  → Started AuthApplication in 14.66 seconds
  → [auth-service] Seeded 3 default users.

# 健康检查
curl http://localhost:8081/api/auth/health
  → {"code":"SUCCESS","data":{"status":"UP","users":3,...}}
```

## 阶段 12：worker02 网络问题诊断与修复 ✅

### 问题现象
- `curl http://10.0.0.42:30080/` 超时（worker02 NodePort 不可达）
- Grafana/Prometheus 在 worker02 上 readiness probe 报 `invalid argument`

### 根因

Ingress Service 设了 `externalTrafficPolicy: Local`。

`externalTrafficPolicy: Local` 的含义：只有运行目标 Pod 的节点才响应 NodePort 流量，其他节点直接 DROP。这是为了保留客户端源 IP，但副作用是没有 Ingress Pod 的节点无法访问 NodePort。

worker02 上 iptables 规则明确显示：
```
DROP  ingress-nginx/ingress-nginx-controller:http has no local endpoints  tcp dpt:30080
DROP  ingress-nginx/ingress-nginx-controller:https has no local endpoints  tcp dpt:30443
```

### 验证确认
- 跨节点 Pod TCP 通信正常：auth-service(worker02) 能连接 mysql(worker01) ✅
- 跨节点 Pod ICMP 不通：Calico IPIP 隧道正常，ICMP 可能被安全策略阻断（非关键） ⚠️
- 节点间物理网络正常：`ping 10.0.0.41` 从 worker02 通 ✅

### 修复
去掉 `externalTrafficPolicy: Local`，使用默认的 `Cluster` 策略。kube-proxy 会在没有 Ingress Pod 的节点上转发流量到有 Pod 的节点。

验证：`curl http://10.0.0.42:30080/auth/actuator/health` → `{"status":"UP"}` ✅
