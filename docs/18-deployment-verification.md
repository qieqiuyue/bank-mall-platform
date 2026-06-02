# 全栈部署验证与排障报告

> 时间：2026年第3-4周 | 从"所有组件部署完毕"到"全部 Pod Running + Ingress 可访问"

---

## 一、最终状态

### 集群总览

| 组件 | Namespace | Pod | 状态 | 节点 |
|------|-----------|------|------|------|
| auth-service | bank-mall | auth-service-75757bcb5f-qxz8k | ✅ Running | k8s-worker01 |
| account-service | bank-mall | account-service-c94d58d4f-xvb2s | ✅ Running | k8s-worker01 |
| payment-service | bank-mall | payment-service-ffb7b5bb9-pt98c | ✅ Running | k8s-worker01 |
| notification-service | bank-mall | notification-service-64bc567d5c-xlkkn | ✅ Running | k8s-worker01 |
| mysql | bank-mall | mysql-658cdfc8c8-sfcvh | ✅ Running | k8s-worker01 |
| prometheus | monitoring | prometheus-859fbf6b44-4d222 | ✅ Running | k8s-worker01 |
| grafana | monitoring | grafana-fb8c7dc64-c4qbr | ✅ Running | k8s-worker01 |
| ingress-nginx | ingress-nginx | ingress-nginx-controller-9c89898b6-vt5qx | ✅ Running | k8s-worker01 |

### 接口验证

```bash
# 4 个服务通过 Ingress 全部可达
curl http://10.0.0.41:30080/auth/actuator/health
# → {"status":"UP","groups":["liveness","readiness"]}

curl http://10.0.0.41:30080/account/actuator/health
# → {"status":"UP","groups":["liveness","readiness"]}

curl http://10.0.0.41:30080/payment/actuator/health
# → {"status":"UP","groups":["liveness","readiness"]}

curl http://10.0.0.41:30080/notification/actuator/health
# → {"status":"UP","groups":["liveness","readiness"]}
```

### 监控访问

| 组件 | 访问方式 | URL |
|------|---------|-----|
| Grafana | NodePort | http://10.0.0.41:30030 (admin/<GRAFANA_PASSWORD>) |
| Prometheus | NodePort | http://10.0.0.41:30090 |

---

## 二、排障全记录

### 问题 1：auth-service 启动崩溃 — characterEncoding=utf8mb4

**现象：** auth-service Pod CrashLoopBackOff，日志报 `Invalid charset name: utf8mb4`

**根因：**

application.yml 中：
```yaml
url: jdbc:mysql://...?characterEncoding=utf8mb4
```

`characterEncoding` 是 Java charset 名，只认 `UTF-8`，不认 MySQL 的 `utf8mb4`。MySQL 的 charset 名和 Java 的 charset 名是两套体系。

**修复：** 将 `characterEncoding=utf8mb4` 改为 `characterEncoding=UTF-8`

**所在文件：** `bank-digital-platform/auth-service/src/main/resources/application.yml`

**教训：**
- MySQL charset `utf8mb4` ≠ Java charset `UTF-8`
- Spring Boot datasource URL 中的 `characterEncoding` 参数值必须是 Java charset 名

---

### 问题 2：Docker Build 使用缓存导致代码修改未生效

**现象：** `docker build -t ... auth-service/` 后推送到 Harbor，但 Pod 仍然报 utf8mb4 错误

**根因：** Docker 构建时所有层命中缓存（`Using cache`），包括 `COPY src ./src` 和 `RUN mvn clean package`。这说明 Docker 认为 `src/` 目录没有变化——实际上文件已在 Windows 编辑但 **Git 工作树的换行符差异**导致 Docker 判定缓存有效。

**修复：**
```bash
docker build --no-cache -t 10.0.0.61/bank-mall/auth-service:1.0.0 auth-service/
docker push 10.0.0.61/bank-mall/auth-service:1.0.0
```

**教训：**
- 修改了源码后重建镜像，**必须加 `--no-cache`**
- 或者至少修改 Dockerfile 让缓存失效（加 `ARG CACHEBUST=$(date)` 之类）

---

### 问题 3：Harbor 镜像拉取失败 — containerd HTTP 仓库兼容性

**现象：** Pod ImagePullBackOff，`kubectl describe pod` 显示 `failed to pull and unpack image`

**根因：** containerd 2.2.1 的 CRI 插件在拉取 HTTP 仓库镜像时，不会传递 `--plain-http` 参数。即使配置了 `hosts.toml`（`skip_verify = true`），CRI 路径也不生效。

**详细表现：**
- `ctr -n k8s.io images pull --plain-http` ✅ 成功
- kubelet 通过 CRI 拉取 ❌ 失败
- `hosts.toml` + `config.toml mirrors` 均不生效

**解决方案（分组件）：**

| 组件 | 解决方案 | 说明 |
|------|---------|------|
| auth-service | `ctr pull --plain-http` 预拉到 worker01 | 私有镜像只能手动预拉 |
| mysql:8.0 | 改用 `mysql:8.0`（Docker Hub）+ `imagePullPolicy: IfNotPresent` | worker01 有缓存 |
| grafana:10.4.0 | 同上，先预拉到 worker01 | worker01 有 Docker Hub 缓存 |
| prometheus:v2.53.0 | 同上 | worker01 有 Docker Hub 缓存 |

**最终镜像策略：**

```yaml
# 私有镜像（Harbor）——需要 ctr --plain-http 预拉
image: 10.0.0.61/bank-mall/auth-service:1.0.0
imagePullPolicy: IfNotPresent

# 第三方镜像（Docker Hub）——worker01 有缓存，直接用
image: mysql:8.0
imagePullPolicy: IfNotPresent
```

**Harbor 推送第三方镜像的坑：**
```bash
# Harbor 上 docker pull → docker tag → docker push 可以成功
# 但 worker 上 ctr pull --plain-http 只拉了 manifest（9.8KiB）
# 实际层数据未下来
# 解决：用 docker save + scp + ctr import 完整传输
docker save 10.0.0.61/bank-mall/mysql:8.0 | gzip > /tmp/mysql.tar.gz
scp /tmp/mysql.tar.gz qian@10.0.0.41:/tmp/
# worker01 上：
gunzip -c /tmp/mysql.tar.gz | sudo ctr -n k8s.io images import -
```

**教训：**
- containerd CRI 不支持 HTTP 仓库的 plain-http 模式，这是已知的限制
- 生产必须配置 Harbor HTTPS（自签证书 + CA 导入）
- `docker save | ctr import` 是离线/内网环境传输镜像的可靠方式
- Harbor 推送的第三方镜像可能缺少分发 manifest，导致 `ctr pull` 只拉了元数据

---

### 问题 4：MySQL CrashLoopBackOff — livenessProbe 太激进

**现象：** MySQL Pod 反复重启，日志显示"ready for connections"后几秒被杀

**根因：**
- MySQL 8.0 首次启动（尤其有 InitDB 时）需要 90-120 秒
- 原配置 `livenessProbe.initialDelaySeconds: 60` + `periodSeconds: 20` + `failureThreshold: 3`
- 即 60 + 20×3 = 120 秒后首次探针尝试，但实际上 MySQL 可能在 init 阶段就被杀
- 另外 MySQL 1Gi 内存限制在数据初始化阶段可能不够

**修复：**
```yaml
livenessProbe: null   # 完全移除 liveness probe（数据库不应被 K8s 随意杀）
readinessProbe:
  exec:
    command: [mysqladmin, ping, -h, localhost]
  initialDelaySeconds: 60
  periodSeconds: 15
  timeoutSeconds: 10
  failureThreshold: 3
resources:
  limits:
    memory: "2Gi"
```

**教训：**
- 数据库 Pod **不应配置 liveness probe**，或至少设置极宽的阈值
- liveness probe 是"不健康就杀"的机制，对数据库来说杀进程比让它自己恢复更危险
- readiness probe 足以保证"不健康时摘流量"
- MySQL 初始化 7 个数据库 + 创建用户需要额外时间

---

### 问题 5：auth-service CrashLoopBackOff — livenessProbe 太激进

**现象：** auth-service 启动日志显示 HikariPool 连接成功、Hibernate 初始化完成、Actuator 暴露成功，但 Pod 仍被 kubelet 杀掉

**根因：**
```
Exit Code: 137  (OOMKill 或被 kubelet 杀掉)
Liveness: http-get http://:8081/api/auth/health delay=30s timeout=3s period=10s #failure=3
```

Spring Boot + JPA + Hibernate 启动需要 ~80 秒：
- 30 秒延迟 + 10 秒周期 × 3 次失败 = 最多 60 秒后杀掉
- 但 auth-service 需要连接 MySQL、初始化连接池、建表，整个启动过程 80+ 秒

**修复：**
```yaml
livenessProbe:
  httpGet:
    path: /api/auth/health
    port: 8081
  initialDelaySeconds: 120   # 30 → 120
  periodSeconds: 15           # 10 → 15
  timeoutSeconds: 5           # 3 → 5
  failureThreshold: 5         # 3 → 5
readinessProbe:
  httpGet:
    path: /api/auth/health
    port: 8081
  initialDelaySeconds: 60     # 10 → 60
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
resources:
  limits:
    memory: "1Gi"              # 512Mi → 1Gi
```

**教训：**
- Spring Boot + JPA 应用的 `initialDelaySeconds` 至少设 90-120 秒
- `failureThreshold × periodSeconds` 的乘积必须大于启动时间
- Exit Code 137 = 被杀（SIGKILL），优先检查 liveness probe 和 OOM

---

### 问题 6：Grafana/Prometheus CrashLoopBackOff — worker02 Pod 网络 "invalid argument"

**现象：** Grafana 和 Prometheus Pod 在 worker02 上 CrashLoopBackOff

```
Readiness probe failed: Get "http://10.244.69.248:3000/api/health": 
dial tcp 10.244.69.248:3000: connect: invalid argument
```

**根因：** worker02 的 Pod 网络层有问题（`invalid argument` 不是常见的网络错误），可能是 Calico 节点状态异常。

**修复：** 强制调度到 worker01
```yaml
spec:
  template:
    spec:
      nodeName: k8s-worker01
```

**教训：**
- 学习环境中 nodeName 固定调度是最简单的排障手段
- `invalid argument` 在 Pod 网络上下文中通常意味着 Calico/cni0 接口异常
- 生产环境应排查 worker02 的 Calico 状态：`calicoctl node status`

---

### 问题 7：auth-service 连接 MySQL 失败 — MySQL 不稳定导致连锁崩溃

**现象：** auth-service 日志报 `Communications link failure` + `Connection refused`

**根因：** MySQL 的 liveness probe 太激进，反复杀掉 MySQL 进程。每次 MySQL 重启，auth-service 的 HikariPool 连接池全部断开，导致 Spring Boot 应用启动失败（JPA EntityManagerFactory 创建失败）。两者形成恶性循环：
1. MySQL 启动慢 → liveness probe 杀掉 MySQL
2. auth-service 连不上 MySQL → Spring Boot 启动失败
3. auth-service 被 liveness probe 杀掉 → 重启后再连 MySQL
4. MySQL 可能还没完全恢复 → 又杀 auth-service

**修复：**
1. 移除 MySQL 的 liveness probe（见问题 4）
2. 放宽 auth-service 的 liveness probe（见问题 5）
3. 确保 MySQL 先稳定 Running，再让 auth-service 重启

**教训：**
- 微服务启动顺序非常重要：**数据库必须先稳定**
- 应用应实现连接重试逻辑（Spring Boot 的 HikariPool 有重连，但启动阶段失败就直接退出）
- 探针配置导致的"乒乓"问题是 K8s 初学者最容易踩的坑

---

## 三、最终 Deployment 配置变更汇总

### MySQL

| 配置项 | 原值 | 新值 | 原因 |
|--------|------|------|------|
| image | `10.0.0.61/bank-mall/mysql:8.0` | `mysql:8.0` | worker01 有缓存，避免 Harbor 拉取问题 |
| imagePullPolicy | 未指定 | `IfNotPresent` | 使用本地缓存 |
| memory limit | 1Gi | 2Gi | MySQL 初始化需要更多内存 |
| livenessProbe | mysqladmin ping initDelay=60 | **移除** | 数据库不应被 liveness 杀 |
| readinessProbe | initDelay=30 period=10 | initDelay=60 period=15 timeout=10 | 给 MySQL 更多启动时间 |
| nodeName | 未指定 | k8s-worker01 | 避免 worker02 网络问题 |

### auth-service

| 配置项 | 原值 | 新值 | 原因 |
|--------|------|------|------|
| imagePullPolicy | Always | IfNotPresent | 避免 Harbor HTTP 拉取失败 |
| memory limit | 512Mi | 1Gi | Spring Boot + JPA 需要更多内存 |
| memory requests | 256Mi | 384Mi→256Mi | HPA 改用 CPU-only 指标后不再需要高 requests，改回 256Mi |
| livenessProbe initDelay | 30 | 120 | 应用启动需要 80+ 秒 |
| livenessProbe period | 10 | 15 | 减少探测频率 |
| livenessProbe timeout | 3 | 5 | 增加超时容忍 |
| livenessProbe failureThreshold | 3 | 5 | 给更多重试机会 |
| readinessProbe initDelay | 10 | 60 | 同上 |
| nodeName | 未指定→k8s-worker01→**移除** | 无 | HPA 需要自由调度到多节点 |

### Grafana

| 配置项 | 原值 | 新值 | 原因 |
|--------|------|------|------|
| image | `10.0.0.61/bank-mall/grafana:10.4.0` | `grafana/grafana:10.4.0` | worker01 有 Docker Hub 缓存 |
| imagePullPolicy | 未指定 | `IfNotPresent` | 使用本地缓存 |
| nodeName | 未指定 | k8s-worker01 | 避免 worker02 Pod 网络问题 |

### Prometheus

| 配置项 | 原值 | 新值 | 原因 |
|--------|------|------|------|
| image | `10.0.0.61/bank-mall/prometheus:v2.53.0` | `prom/prometheus:v2.53.0` | worker01 有 Docker Hub 缓存 |
| imagePullPolicy | 未指定 | `IfNotPresent` | 使用本地缓存 |
| nodeName | 未指定 | k8s-worker01 | 避免 worker02 Pod 网络问题 |

### Ingress Nginx Controller

| 配置项 | 原值 | 新值 | 原因 |
|--------|------|------|------|
| nodeName | 无 | k8s-worker01 | rollout restart 调度到 worker02 导致 NodePort 不可达 |
| externalTrafficPolicy | Local | 删除（默认 Cluster） | Local 导致没有 Ingress Pod 的节点 DROP NodePort 流量 |

---

## 四、镜像管理策略总结

### 当前策略

| 类型 | 镜像来源 | 分发方式 | 说明 |
|------|---------|---------|------|
| 业务镜像（auth-service 等） | Harbor (10.0.0.61) | `ctr --plain-http` 预拉 | 私有构建，必须推送 |
| 第三方镜像（MySQL/Grafana/Prometheus） | Docker Hub | 依赖 worker01 缓存 + `IfNotPresent` | 公共镜像，worker01 有缓存 |
| K8s 组件镜像（pause/Calico） | 阿里云 | `--image-repository` | 国内加速 |

### 预拉命令速查

```bash
# 业务镜像（Harbor）
sudo ctr -n k8s.io images pull --plain-http --user admin/<HARBOR_PASSWORD> 10.0.0.61/bank-mall/auth-service:1.0.0

# 第三方镜像（Harbor 推送后 ctr 拉不到层数据时）
# 在 Harbor 节点：
docker save 10.0.0.61/bank-mall/mysql:8.0 | gzip > /tmp/mysql.tar.gz
scp /tmp/mysql.tar.gz qian@10.0.0.41:/tmp/

# 在 worker 节点：
gunzip -c /tmp/mysql.tar.gz | sudo ctr -n k8s.io images import -
```

### 生产环境推荐

1. Harbor 配置 HTTPS（自签证书 + CA 导入所有节点）
2. 使用 `imagePullSecrets` 在 Deployment 中声明 Harbor 凭据
3. Harbor 配置 webhook 通知：镜像推送后自动触发部署
4. 第三方镜像统一推送到 Harbor，避免依赖 Docker Hub

---

## 五、Grafana/Prometheus NodePort 暴露

```bash
# Grafana
kubectl patch svc grafana -n monitoring -p '{"spec":{"type":"NodePort","ports":[{"port":3000,"nodePort":30030}]}}'

# Prometheus
kubectl patch svc prometheus -n monitoring -p '{"spec":{"type":"NodePort","ports":[{"port":9090,"nodePort":30090}]}}'
```

访问地址：
- Grafana: http://10.0.0.41:30030 (admin/<GRAFANA_PASSWORD>)
- Prometheus: http://10.0.0.41:30090

---

## 六、面试要点

| 面试问题 | 回答要点 |
|---------|---------|
| "你们怎么处理 Spring Boot 在 K8s 里的启动慢？" | Spring Boot + JPA 应用启动需要 80 秒，livenessProbe 的 initialDelaySeconds 必须设 120 秒以上，否则 kubelet 会杀掉还在启动的容器。我们踩过这个坑。 |
| "数据库在 K8s 里怎么配探针？" | 数据库不适合配 livenessProbe，因为 liveness 失败会杀进程，数据库被杀可能导致数据损坏。只用 readinessProbe 保证"不健康时摘流量"。MySQL 首次初始化需要 90-120 秒，readinessProbe 的 initialDelaySeconds 至少 60 秒。 |
| "containerd 拉取 HTTP 仓库镜像失败怎么解决？" | containerd 2.2.1 的 CRI 插件不支持 plain-http 拉取，hosts.toml 的 skip_verify 也不生效。学习环境用 `ctr --plain-http` 预拉镜像。生产环境必须配 HTTPS 证书。也可以用 `docker save | ctr import` 做离线传输。 |
| "Pod 网络出现 invalid argument 怎么排查？" | 这是 Calico/cni0 网络接口异常，通常出现在 worker 节点上。快速修复是 `nodeName` 固定到健康节点，根本解决需要排查 Calico 节点状态、重启 Calico DaemonSet。 |
| "怎么处理微服务和数据库的启动依赖？" | 不要靠 liveness probe 的延迟来解决，那是治标不治本。正确做法：1）数据库不配 liveness probe；2）应用做连接重试（Spring Boot 的 HikariPool 有重连但启动失败直接退出）；3）用 readinessProbe 标记就绪，流量等就绪才进来。 |

---

## 七、HPA 阶段追加问题

### 问题 14：worker02 NodePort 不可达 + readiness probe 报 `invalid argument`

**现象：** `curl http://10.0.0.42:30080/` 超时。Grafana/Prometheus 在 worker02 上 readiness probe 报 `dial tcp: connect: invalid argument`。

**根因：** Ingress Service 设了 `externalTrafficPolicy: Local`，kube-proxy 在没有 Ingress Pod 的节点上直接 DROP NodePort 流量。iptables 规则明确显示：
```
DROP  ingress-nginx/ingress-nginx-controller:http has no local endpoints  ... tcp dpt:30080
```

`externalTrafficPolicy: Local` 的含义：只有 Pod 所在节点才能通过 NodePort 访问，其他节点直接丢弃。适用于需要保留客户端 IP 的场景（如需要看到真实源 IP 的负载均衡器后端）。

**解决：** 去掉 `externalTrafficPolicy: Local`，使用默认的 `Cluster` 策略。kube-proxy 会在 worker02 上把流量转发到 worker01（Ingress Pod 所在节点）。

**考证：** 跨节点 Pod TCP 通信是正常的（auth-service 在 worker02 能连接 mysql 在 worker01）。问题只在于 NodePort 层面。

**现象：** `curl http://10.0.0.41:30080/` 超时，Ingress 无法访问。

**根因：** ci.sh 的 `rollout restart` 让 Ingress controller 重新调度到 worker02，worker02 的 Pod 网络有问题。

**解决：** ingress-nginx-controller 加 `nodeName: k8s-worker01`，确保跑在健康的节点上。

### 问题 9：auth-service 内存 81% 超过 HPA 阈值 80% 但不扩容

**现象：** HPA 显示 `memory: 81%/80%` 但 REPLICAS 始终为 1，`kubectl describe hpa` 显示 `DesiredWithinRange`。

**根因：** HPA 默认有 10% 容差（tolerance），实际触发线 = 80% × 1.1 = 88%。81% < 88%，不触发扩容。

**解决：**
- 内存阈值从 80% 降到 60%（触发线 66%）
- auth-service memory requests 从 256Mi 调到 384Mi（空闲 ~55%，不会误触发）

### 问题 10：Metrics Server 报 x509 证书错误

**现象：** Metrics Server Pod Running 但 `kubectl top` 报 `Metrics API not available`。

**日志：** `"x509: cannot validate certificate for 10.0.0.xx because it doesn't contain any IP SANs"`

**根因：** kubeadm 自签的 kubelet 证书不含 IP SANs。

**解决：** `kubectl patch deployment metrics-server -n kube-system --type=json -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'`

### 问题 11：auth-service Deployment 变更导致滚动更新卡在 ImagePullBackOff

**现象：** 修改 auth-service 的 memory requests（256Mi→384Mi）后，新 Pod 调度到 worker02 但 ImagePullBackOff，旧 Pod 等待新 Pod 就绪不释放。

**根因：** Deployment 滚动更新策略先建新 Pod 再删旧 Pod。新 Pod 在 worker02 拉不到 Harbor 镜像。

**解决：** 在所有 worker 节点预拉业务镜像后，再 apply Deployment 变更。

---

### 问题 9：auth-service 内存 81% 超过 HPA 阈值 80% 但不扩容

（已整合到 `docs/19-hpa.md` 踩坑记录 6.3、6.8、6.9）

### 问题 10：Metrics Server 报 x509 证书错误

（已整合到 `docs/19-hpa.md` 踩坑记录 6.2）

### 问题 11：auth-service Deployment 变更导致滚动更新卡在 ImagePullBackOff

（已整合到 `docs/19-hpa.md` 踩坑记录 6.5）

### 问题 12：HPA scaleDown 永远不缩容（Percent:10 取整为 0）

**现象：** 压测结束后 auth-service 3 副本一直不缩回 1，等了超过 5 分钟冷却期仍然 REPLICAS=3。

**根因：** `scaleDown` 策略只设了 `Percent: 10`，3 个副本的 10% = 0.3 → 取整为 0，HPA 每分钟缩容 0 个 Pod。

**解决：** 增加 `Pods: 1` 策略 + `selectPolicy: Min`，确保每分钟至少缩 1 个 Pod。

### 问题 13：JVM 内存不适合做 HPA 指标导致无法缩容

**现象：** 即使冷却期过后，auth-service 内存 51%（阈值 60%），HPA 算出 `desired = ceil(3 × 51/60) = 3`，不缩容。

**根因：** JVM 的内存使用特性——GC 后内存不释放回 OS，空闲时利用率也居高不下。auth-service 空闲内存 ~210Mi，3 个 Pod 总共 ~630Mi，HPA 按内存利用率算始终需要 3 个副本。

**解决：** HPA 只用 CPU 指标，去掉内存指标。

---

## 八、下一步

- [x] HPA 自动扩缩容（已完成 → `docs/19-hpa.md`）
- [ ] 排查 worker02 的 Pod 网络问题（Calico 节点状态）
- [ ] 将所有 Deployment 的 nodeName 改为 nodeAffinity（生产级调度）
- [ ] Harbor 配置 HTTPS（自签证书）
- [x] 安全加固 NetworkPolicy + PodSecurity（已完成 → `docs/20-security-hardening.md`）

---

> 本文档配合 `docs/14-troubleshooting-handbook.md`（故障排查）、`docs/13-design-decisions.md`（设计决策）一起阅读。