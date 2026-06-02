# 故障排查手册

> 从 `docs/10-actual-progress.md` 提炼的通用排查指南，按问题类别组织（而非时间顺序），方便遇到类似问题时快速定位。

---

## 一、containerd 常见问题

### 1.1 sandbox_image 指向被墙地址

**症状：**
```
RunPodSandbox for "xxx" failed: failed to get sandbox image "registry.k8s.io/pause:3.10.1"
```
kubelet 启动静态 Pod (etcd/apiserver/scheduler/controller-manager) 时卡在 `ContainerCreating`。

**根因：**
`containerd` 的 `sandbox_image` 配置指向 `registry.k8s.io/pause`，国内环境 HTTPS 拉取会被干扰。kubelet 创建 Pod sandbox 时 containerd 独立拉取 pause 镜像，不受 `kubeadm init --image-repository` 参数影响。

**解决：**
```bash
sudo sed -i 's|sandbox_image = "registry.k8s.io/pause:.*"|sandbox_image = "registry.aliyuncs.com/google_containers/pause:3.10"|' /etc/containerd/config.toml
sudo systemctl restart containerd
```

**预防：** `kubeadm reset` 后 containerd 配置可能被重置，务必重新检查 `sandbox_image` 和 `SystemdCgroup`。

### 1.2 SystemdCgroup 未启用或 kubeadm reset 后重置

**症状：**
containerd 日志报 `failed to create shim task: OCI runtime create failed`，Pod 无法启动。

**根因：**
Kubernetes 推荐 containerd 使用 `SystemdCgroup = true` 以与 kubelet 的 cgroup 驱动一致。默认生成的 `config.toml` 此项为 `false`。

**解决：**
```bash
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
sudo systemctl restart containerd
```

**预防：** 每次 `kubeadm reset` 后，重新生成 containerd 默认配置并修改此项。

### 1.3 containerd 被错误 TOML 追加破坏

**症状：**
containerd 无法启动，`systemctl status containerd` 显示配置解析失败。

**根因：**
手动向 `/etc/containerd/config.toml` 追加内容时格式错误（如重复的 section、错误的缩进），破坏 TOML 结构。

**解决：**
```bash
sudo containerd config default | sudo tee /etc/containerd/config.toml
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
sudo sed -i 's|sandbox_image = "registry.k8s.io/pause:.*"|sandbox_image = "registry.aliyuncs.com/google_containers/pause:3.10"|' /etc/containerd/config.toml
sudo systemctl restart containerd
```

**预防：**
- 修改 `config.toml` 前先备份：`sudo cp /etc/containerd/config.toml /etc/containerd/config.toml.bak`
- 尽量用 `sed` 精确定位修改，避免追加未知内容

### 1.4 containerd 无法通过 hosts.toml 拉取 HTTP 镜像

**症状：**
- `hosts.toml` 和 `config.toml` 中的镜像配置均不生效
- kubectl apply Deployment 后 Pod 卡在 `ImagePullBackOff`
- 但 `ctr images pull --plain-http` 可以成功拉取

**根因：**
containerd 2.2.1 对 HTTP 镜像仓库的配置处理与 docker 不同。`--plain-http` 仅对 `ctr` CLI 有效，kubelet 通过 CRI 接口拉取时不传递此参数。`hosts.toml` 中的 `skip_verify` 在 CRI 路径下可能不生效（与 containerd 版本相关）。

**临时解决（手动预拉取）：**
```bash
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/<service>:1.0.0
```

**生产方案：**
1. Harbor 配置 HTTPS（自签证书 + 各节点导入 CA 证书）
2. 使用 `imagePullSecrets` + containerd SSL 配置
3. Harbor 前置 Nginx 做 TLS termination

---

## 二、网络与镜像拉取问题

### 2.1 registry.k8s.io 无法访问（GFW）

**症状：**
```bash
kubeadm init 报错: failed to pull "registry.k8s.io/kube-scheduler:v1.36.1"
# 日志: TLS handshake timeout / connection reset
```
`ping registry.k8s.io` 可能通，但 HTTPS 拉镜像被干扰。

**解决：**
```bash
sudo kubeadm init \
  --apiserver-advertise-address=10.0.0.31 \
  --pod-network-cidr=10.244.0.0/16 \
  --image-repository=registry.aliyuncs.com/google_containers \
  --kubernetes-version=v1.36.1
```

**适用组件：** kube-apiserver, kube-controller-manager, kube-scheduler, etcd, coredns, kube-proxy

**不适用：** pause (sandbox_image)、Calico 组件镜像

### 2.2 Calico 镜像拉取失败

**症状：**
Calico operator/tigera/calico-node Pod 卡在 `ImagePullBackOff`。

**根因：**
Calico 镜像来源也是 `registry.k8s.io`（或其他国外仓库），国内同样被墙。

**解决：**
- 等待重试（有时网络波动后能通）
- 或手动 `ctr pull` 从国内镜像源拉取后 `ctr tag` 为 Calico 需要的镜像名

### 2.3 Calico IPPool CIDR 不匹配

**症状：**
```
IPPool 192.168.0.0/16 is not within the platform's configured pod network CIDR(s) [10.244.0.0/16]
```

**根因：**
Calico 默认 IPPool 使用 `192.168.0.0/16`，与 `kubeadm init --pod-network-cidr=10.244.0.0/16` 冲突。

**解决：**
```bash
kubectl patch ippool default-ipv4-ippool -p '{"spec":{"cidr":"10.244.0.0/16"}}' --type=merge
```

**预防：** 部署 Calico 时直接修改 `custom-resources.yaml` 的 CIDR 配置，或始终统一使用 `192.168.0.0/16` 作为 pod network cidr。

---

## 三、工作节点加入失败

### 3.1 kubelet 证书残留

**症状：**
Worker join 时 `kubelet-check` 阶段报错：
```
[kubelet-check] The HTTP call equal to 'curl -sSL http://localhost:10248/healthz' failed with error: Get ... connection refused
```

**根因：**
前几次失败的 join 操作在 `/etc/kubernetes/kubelet.conf` 和 `/var/lib/kubelet/pki/` 中留下了过期/无效的 kubelet 客户端证书。

**解决：**
```bash
sudo systemctl stop kubelet
sudo rm -rf /etc/kubernetes /var/lib/kubelet /etc/cni/net.d
# 重新执行 kubeadm join
```

**预防：** 每次 `kubeadm reset` 后再执行 join，或 join 失败后总是清理上述目录。

### 3.2 swap 重新启用导致 kubelet 拒绝启动

**症状：**
kubelet 日志：
```
failed to run Kubelet: running with swap on is not supported
```
`kubectl get nodes` 显示节点 `NotReady`（已加入的节点重启后）。

**根因：**
`swapoff -a` 是临时的。重启后 `/etc/fstab` 中的 swap 条目仍然挂载。

**解决：**

临时修复（不持久）：
```bash
sudo swapoff -a
sudo systemctl restart kubelet
```

永久修复：
```bash
sudo swapoff -a
sudo sed -i '/swap/s/^/#/' /etc/fstab
```

预防：在 Ubuntu 初始化阶段（`docs/07` 手册）就应该执行永久关闭 swap。

---

## 四、Harbor 相关

### 4.1 harbor.yml hostname 格式错误

**症状：**
`./install.sh` 报 YAML 解析错误。

**根因：**
YAML 中 `hostname:` 后面的冒号容易漏写（写成 `hostname 10.0.0.61`），或 `https:` 块未正确注释导致解析失败。

**解决：**
```yaml
hostname: 10.0.0.61    # 冒号不能漏，后面有空格
http:
  port: 80
# https:
#   port: 443
```

**预防：** 修改 `harbor.yml` 后用 `python3 -c "import yaml; yaml.safe_load(open('harbor.yml'))"` 验证 YAML 格式。

### 4.2 Docker 登录 HTTP Harbor 失败

**症状：**
```
Error response from daemon: Get "https://10.0.0.61/v2/": http: server gave HTTP response to HTTPS client
```

**根因：**
Docker 默认使用 HTTPS 访问 registry。Harbor 配置了 HTTP 模式，需要在 Docker daemon 中声明为 insecure registry。

**解决：**
编辑 `/etc/docker/daemon.json`：
```json
{
  "insecure-registries": ["10.0.0.61"]
}
```
```bash
sudo systemctl restart docker
```

### 4.3 Harbor 镜像 push 成功但 K8s 拉取失败

这是本项目最常见的综合问题，参见上文「1.4 containerd 无法通过 hosts.toml 拉取 HTTP 镜像」。

---

## 五、部署后验证

### 5.1 ImagePullBackOff

**症状：** `kubectl get pods` 显示 `ImagePullBackOff`。

**诊断步骤：**
```bash
# 1. 看详细原因
kubectl describe pod <pod-name> -n bank-mall | tail -20

# 2. 看有哪些 imagePullSecrets
kubectl get secret -n bank-mall

# 3. 在节点上手动测试拉取
sudo crictl pull <image>
# 或
sudo ctr -n k8s.io images pull <image>
```

**常见原因：**
- 镜像名写错：`10.0.0.61/bank-mall/auth-service:1.0` vs `10.0.0.61/bank-mall/auth-service:1.0.0`
- containerd 无法 HTTP 拉取（见 1.4）
- imagePullSecrets 未创建或过期
- 网络不通：`ping 10.0.0.61` 验证

### 5.2 CrashLoopBackOff

**症状：** Pod 反复重启，`RESTARTS` 计数不断增加。

**诊断步骤：**
```bash
# 查看容器日志
kubectl logs <pod-name> -n bank-mall --previous

# 查看事件
kubectl describe pod <pod-name> -n bank-mall | grep -A 5 Events
```

**常见原因：**
- Spring Boot 启动报错（application.yml 配置不正确）
- 端口冲突
- 数据库连接失败（如果有外部依赖）
- 健康检查探针配置太激进（`initialDelaySeconds` 太短）
- OOMKilled（内存限制太小）
- Exit Code 137 = 被 SIGKILL（检查 liveness probe 和 OOM）

### 5.2.1 Spring Boot + JPA 探针配置陷阱

**现象：** auth-service 日志显示 HikariPool 连接成功、Hibernate 初始化正常，但 Pod 被 kubelet 杀掉（Exit Code 137）。

**根因：** Spring Boot + JPA + Hibernate 启动需要 80+ 秒，而 livenessProbe 的 `initialDelaySeconds + periodSeconds × failureThreshold` 小于此时间。

**示例计算：**
```
原配置：initialDelaySeconds=30, periodSeconds=10, failureThreshold=3
首次检测时间：30s
最多等待：30 + 10×3 = 60s → 60s 就被杀，但启动要 80s！
```

**修复：**
```yaml
livenessProbe:
  initialDelaySeconds: 120    # 足够让 Spring Boot 启动
  periodSeconds: 15
  timeoutSeconds: 5
  failureThreshold: 5
readinessProbe:
  initialDelaySeconds: 60     # 就绪检测可以稍早
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
resources:
  limits:
    memory: "1Gi"              # 512Mi 不够 JPA 应用
```

**经验公式：** 对于 Spring Boot + JPA 应用，`initialDelaySeconds` 至少设 90-120 秒。

### 5.2.2 数据库不应配 livenessProbe

**现象：** MySQL Pod 反复重启（CrashLoopBackOff），日志显示正常启动后立即被杀。

**根因：** 数据库（MySQL）的启动+初始化时间不可预测（首次建库需 90-120 秒）。liveness probe 失败 = 杀进程 → 数据库被杀 → 数据可能损坏。

**修复：** **移除 MySQL 的 livenessProbe**，只保留 readinessProbe：
```yaml
# 不配 livenessProbe（数据库不应被 K8s 随意杀）
readinessProbe:
  exec:
    command: [mysqladmin, ping, -h, localhost]
  initialDelaySeconds: 60
  periodSeconds: 15
  timeoutSeconds: 10
  failureThreshold: 3
resources:
  limits:
    memory: "2Gi"   # MySQL 需要 2Gi+
```

**教训：** livenessProbe 是"不健康就杀"的机制。对数据库来说，杀进程比让它自己恢复更危险。readinessProbe 足以保证"不健康时摘流量"。

### 5.2.3 characterEncoding=utf8mb4 不合法

**现象：** Spring Boot 启动报 `Invalid charset name: utf8mb4`

**根因：** `characterEncoding` 是 Java charset 名，只认 `UTF-8`，不认 MySQL 的 `utf8mb4`。两者是不同体系的命名。

**修复：** application.yml 中 `characterEncoding=utf8mb4` → `characterEncoding=UTF-8`

### 5.2.4 Docker build 使用缓存导致代码修改未生效

**现象：** 修改了源码，重建镜像后 Pod 仍然报旧错误。

**根因：** `docker build` 命中缓存层（`Using cache`），包括 `COPY src ./src` 和 `RUN mvn clean package`。Docker 认为文件没变化（可能是换行符差异）。

**修复：** 修改代码后重建镜像必须加 `--no-cache`：
```bash
docker build --no-cache -t 10.0.0.61/bank-mall/auth-service:1.0.0 .
docker push 10.0.0.61/bank-mall/auth-service:1.0.0
```

### 5.3 Pending

**症状：** Pod 长时间处于 `Pending`。

**诊断：**
```bash
kubectl describe pod <pod-name> -n bank-mall | grep -i "events\|warning\|failed"
```

**常见原因：**
- 资源不足（所有节点 CPU/内存不够）
- PVC 未绑定（如果有 StatefulSet）
- nodeSelector/taint 导致无节点可调度

---

## 六、快速诊断命令速查

```bash
# 集群整体状态
kubectl get nodes -o wide
kubectl get pods -A -o wide | grep -v Running

# 事件流
kubectl get events -A --sort-by='.lastTimestamp' | tail -20

# containerd 状态
sudo systemctl status containerd --no-pager -l
sudo journalctl -u containerd -n 50 --no-pager

# kubelet 状态
sudo systemctl status kubelet --no-pager -l
sudo journalctl -u kubelet -n 50 --no-pager

# 检查关键配置
sudo grep -E 'SystemdCgroup|sandbox_image' /etc/containerd/config.toml
sudo swapon --show
sudo cat /etc/fstab | grep swap

# 镜像验证
sudo ctr -n k8s.io images ls | grep bank-mall
kubectl get pods -n bank-mall -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\t"}{.status.containerStatuses[0].state}{"\n"}{end}'
```

---

## 七、containerd 镜像管理问题

### 7.1 ctr pull 只拉了 manifest（9.8KiB）

**症状：** Harbor 推送了第三方镜像（mysql/grafana/prometheus），worker 节点 `ctr pull` 只拉了 9.8KiB（manifest only），实际层数据未下载。Pod ImagePullBackOff。

**根因：** Harbor 推送第三方镜像时，可能只推送了 manifest 指针（指向 Docker Hub 层），而不是完整层数据。`ctr pull` 时只拉了 manifest，没有拉实际层。

**解决：**
```bash
# 方法 1：docker save + scp + ctr import（最可靠）
# Harbor 节点：
docker save 10.0.0.61/bank-mall/mysql:8.0 | gzip > /tmp/mysql.tar.gz
scp /tmp/mysql.tar.gz qian@10.0.0.41:/tmp/
# worker 节点：
gunzip -c /tmp/mysql.tar.gz | sudo ctr -n k8s.io images import -

# 方法 2：改用 Docker Hub 原始镜像名（如果 worker 有缓存）
# 在 Deployment 中：
image: mysql:8.0
imagePullPolicy: IfNotPresent
```

### 7.2 第三方镜像策略（国内 GFW 影响）

**策略：**
- K8s 组件镜像 → 阿里云 `registry.aliyuncs.com/google_containers`
- 业务镜像 → Harbor（需要 `ctr --plain-http` 预拉或 `docker save | ctr import`）
- 第三方镜像（mysql/grafana/prometheus）→ 优先用 Docker Hub 原始名 + `imagePullPolicy: IfNotPresent`，worker01 有缓存

---

> 本手册持续更新。每次遇到新问题并解决后，追加到对应分类。

---

## 八、Loki + Promtail 日志系统

> 完整复盘见 `docs/22-loki-promtail-postmortem.md`

### 8.1 Loki 查询无数据（Streams: 0, NO DATA）

**症状：** Promtail Running 无报错，但 Loki 查询返回 `"result": []`。

**诊断优先级（从快到慢）：**

**第一优先级：对比 Promtail metrics（排除管道丢弃）**
```bash
POD=$(kubectl get pods -n monitoring -l app=promtail -o wide | grep worker02 | awk '{print $1}')
kubectl port-forward -n monitoring $POD 9080:9080 &
curl -s http://localhost:9080/metrics | grep -E "read_lines_total|sent_entries_total"
```

判定：
- `read_lines > 0 && sent_entries = 0` → **管道丢弃**。删除 `pipeline_stages: - cri: {}`
- 两者均 >0 → 管道正常，查时间范围和查询语法
- 两者均 =0 → 查路径 glob 和权限

**第二优先级：验证 Loki 自身可达**
```bash
# port-forward 直连
kubectl port-forward -n monitoring deploy/loki 3101:3100 &
curl -X POST http://localhost:3101/loki/api/v1/push \
  -H "Content-Type: application/json" \
  -d '{"streams":[{"stream":{"test":"manual"},"values":[["'$(date +%s)'000000000","ok"]]}]}'
# 预期：204 No Content
```

**第三优先级：检查网络（ClusterIP vs Pod IP）**
- 从 Pod 内 `curl -X POST http://loki.monitoring.svc.cluster.local:3100/loki/api/v1/push` → 如返回空/EOF → kube-proxy 问题
- 从 Pod 内 `curl -X POST http://<Loki-Pod-IP>:3100/loki/api/v1/push` → 应返回 204
- 在 ConfigMap 中用 Pod IP 绕过 ClusterIP

**第四优先级：检查时间范围**
- `{namespace="bank-mall"}` 的时间范围必须包含日志时间戳
- 示例：日志时间戳是 `2026-05-25T17:39:57`，start 须在此之前

### 8.2 Promtail EOF 错误

**症状：** Promtail 日志反复输出：
```
error sending batch, will retry" status=-1 error="Post .../push: EOF"
```

**根因：** kube-proxy 对 ClusterIP POST 转发断开。

**解决：**
```bash
LOIP=$(kubectl get pod -n monitoring -l app=loki -o jsonpath='{.items[0].status.podIP}')
# 在 Promtail ConfigMap 中修改 clients[0].url:
# url: http://${LOIP}:3100/loki/api/v1/push
kubectl delete configmap promtail-config -n monitoring
kubectl create configmap promtail-config -n monitoring --from-file=promtail.yaml=<修改后的yaml>
kubectl rollout restart daemonset/promtail -n monitoring
```

### 8.3 Loki CrashLoopBackOff: /wal permission denied

**症状：** Loki 2.9.12 启动即崩溃，日志：`mkdir /wal: permission denied`

**根因：** Loki 默认 WAL 目录为 `/wal`（根文件系统），UID 10001 不可写。

**解决：** ConfigMap 中 `ingester.wal.dir: /loki/wal`（PVC 挂载点内）。

### 8.4 Promtail ConfigMap 修改不生效

**症状：** `kubectl patch configmap` 报错或无效果，Promtail 仍用旧配置。

**原因：** 多行 YAML 在 shell 嵌套引号中转义失败。

**解决：** 永远用 delete + create 方式改 ConfigMap：
```bash
kubectl delete configmap promtail-config -n monitoring
kubectl create configmap promtail-config -n monitoring --from-file=promtail.yaml=/path/to/file
kubectl rollout restart daemonset/promtail -n monitoring
```

### 8.5 快速验证命令

```bash
# 一步诊断 Loki 状态
kubectl get pods -n monitoring -l app=loki,app=promtail -o wide

# Loki 就绪检查
curl -s http://10.0.0.41:30310/ready

# Promtail 指标（推送确认）
kubectl exec -n monitoring deploy/loki -- echo "Loki ready" 2>/dev/null && echo "OK"
for p in $(kubectl get pods -n monitoring -l app=promtail -o name); do
  echo "=== $p ==="
  kubectl logs -n monitoring ${p#pod/} --tail=5 | grep -E "error|EOF|Starting|sent" | head -3
done

# 查询 bank-mall 日志
curl -s "http://10.0.0.41:30310/loki/api/v1/label/namespace/values" | python3 -m json.tool
```
