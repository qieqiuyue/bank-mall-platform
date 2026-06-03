# 项目设计决策与踩坑记录

> 本文档从 codex 原始对话中提取核心设计思路、技术选型和问题解决经验，作为项目的“技术决策日志”。

---

## 一、原始案例分析与项目定位

### 1.1 两个原始案例的核心差异

本项目参考了两个真实运维面试记录，取其精华、避其陷阱：

**案例 1：某银行 K8s 部署**

- 背景：银行内部测试环境，直接在生产网络 IP 段操作（`10.203.x.x`），属于真实企业环境。
- 涉及技术：`Docker`、`Kubernetes`、`Harbor`、`Vue.js`、`Spring Boot`、`Nacos`、`Sentinel`、`ELK`、`Jenkins`、`SkyWalking`、`MinIO`、`XXL-Job`、`Grafana`、`Prometheus`。
- 正面价值：涉及私有镜像仓库、微服务治理、可观测性，技术栈较完整。
- 反面教训：候选人把生产环境的具体 IP、项目名称、甚至管理员真实手机号都写在简历里。这属于严重信息泄露，面试官一问就露馅，项目可信度归零。

**案例 2：商城/ERP 系统运维**

- 背景：中小企业 ERP 系统，多节点服务器，手动 Docker 部署。
- 涉及技术：`Docker Swarm`、`Nginx`、`MySQL`、`Redis`、`Nacos`、`ELK`、`NFS`、`WebSocket`。
- 正面价值：有明确的集群节点数、服务拆分、版本迭代描述。
- 反面教训：候选人技术深度不足——Nginx 是运维做配置但不懂原理；Redis 缓存命中率只回答“50%”，被面试官直接判定为“监控上看的但不知道怎么优化”；故障处理只讲表象不讲根因；面试官评价是“60-70分”的水平。

### 1.2 我们的项目定位：60-70分可运行 + 90分项目理解

核心策略：**用一个“可以跑起来、可以演示”的骨架，承载“可以经得起追问”的深度理解。**

| 维度 | 案例 1 问题 | 案例 2 问题 | 我们的策略 |
|------|------------|------------|-----------|
| 信息泄露 | 真实 IP、项目名称、手机号 | 相对模糊 | 完全虚构环境，所有 IP、名称、域名均为示例 |
| 技术栈堆砌 | 列了 20 项，深度不足 | 列了 10 项，深度更不足 | 精选核心组件，每个都能讲出原理和配置 |
| 部署描述 | “离线安装 Docker”——太笼统 | “Docker Swarm 集群”——缺少细节 | 每一步都有手册、命令和验证标准 |
| 故障处理 | 只说“挂起虚拟机” | 只说“重启容器” | 记录真实踩坑过程和根因分析 |
| 简历表达 | 真实到露馅 | 模糊到经不起问 | “模拟企业真实场景”——可演示、可追问 |

**关键原则：**

> 宁可让项目看起来是一个“精心设计的实战演练”，也不要包装成“我独立负责了某银行生产系统”。
> 前者加分，后者一旦被追问就崩盘。

---

## 二、技术选型决策

### 2.1 容器运行时：containerd（不是 Docker）

**决策：** 使用 `containerd` 作为 K8s 的容器运行时，不安装 Docker CE。

**理由：**
- Kubernetes 1.24+ 已移除对 Docker 的直接支持，`dockershim` 被废弃。
- `containerd` 是 K8s 官方推荐运行时，更轻量、更原生。
- 学习 `ctr` / `crictl` 命令也是云原生运维的必备技能。

**踩坑记录：**
- `containerd 2.2.1` 在 Ubuntu 24.04 上，`hosts.toml` 和 `registry.mirrors` 配置均**无法**成功将 Harbor 的 HTTPS 重定向为 HTTP。
- 唯一可行的 worker 节点拉取方式：`ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> <image>`
- 这意味着 `imagePullSecrets` 在 Deployment 中仍然需要，但 worker 节点的 containerd 无法自动使用 Secret 进行 plain-HTTP 拉取，需要手动导入或换方案。

### 2.2 CNI：Calico（不是 Flannel）

**决策：** 使用 Calico 作为 CNI 网络插件。

**理由：**
- 更贴近生产环境，支持 NetworkPolicy。
- 方便后续讲解网络安全策略。

**踩坑记录：**
- Calico 默认 IPPool 使用 `192.168.0.0/16`，与 `kubeadm init --pod-network-cidr=10.244.0.0/16` 冲突。
- **解决方案：** 部署 Calico 后，必须 patch IPPool：
  ```bash
  kubectl patch ippool default-ipv4-ippool -p '{"spec":{"cidr":"10.244.0.0/16"}}' --type=merge
  ```

### 2.3 K8s 版本：v1.36.1（不降级）

**决策：** 坚持使用 `v1.36.1`，即使网上教程多用 `v1.28/v1.30`。

**理由：**
- 了解最新版本的安装方式（`pkgs.k8s.io` 源）比跟着旧教程更有价值。
- 旧源 `apt.kubernetes.io` 已废弃冻结，必须学会用新源。

**踩坑记录：**
- `kubeadm reset` 会**重置 containerd 配置**：`SystemdCgroup` 变回 `false`，`sandbox` 变回 `registry.k8s.io/pause:3.10.1`。
- **解决方案：** `kubeadm reset` 后必须重新修改 `/etc/containerd/config.toml` 并重启 containerd。

### 2.4 Harbor：HTTP 模式（非 HTTPS）

**决策：** Harbor 使用 HTTP 部署，不用 HTTPS 证书。

**理由：**
- 学习环境简化配置，避免自签名证书带来的 trust 问题。
- 真实企业生产环境中，Harbor 通常有正式的 TLS 证书或放在 LB 后面。

**踩坑记录：**
- Harbor HTTP 模式下，`docker login` 需要在 Docker daemon 的 `insecure-registries` 中配置。
- K8s worker 节点通过 containerd 拉取时，`hosts.toml` 的 `skip_verify = true` 在 containerd 2.2.1 中未生效（可能和配置路径有关）。
- **解决方案：** 临时采用手动 `ctr pull` 到 worker 节点，或在 Harbor 前置 Nginx/LB 做 TLS termination。

### 2.5 节点网络：VMware NAT（非桥接）

**决策：** 使用 VMware NAT 网络模式。

**理由：**
- 宿主机（Windows）可以稳定访问虚拟机，不受外部网络环境变化影响。
- 虚拟机之间二层互通，满足 K8s 节点通信需求。
- 如果 NAT 子网不同，只需要替换 IP 前缀即可，不影响整体架构。

**IP 规划演进：**

| 阶段 | master | worker01 | worker02 | Harbor |
|------|--------|----------|----------|--------|
| 初始规划 | 192.168.40.180 | 192.168.40.181 | 192.168.40.182 | 192.168.40.62 |
| 实际环境 | 10.0.0.31 | 10.0.0.41 | 10.0.0.42 | 10.0.0.61 |

**教训：** 文档中保留 `192.168.40.x` 作为“示例网段说明”，所有操作手册统一使用实际 `10.0.0.x`。

---

## 三、关键踩坑与解决方案

### 3.1 Worker 加入集群失败：kubelet 证书冲突

**现象：**
```
[kubelet-check] The HTTP call ... failed with error: Get ... connection refused
```

**根因：** 之前失败的操作在 `/etc/kubernetes/kubelet.conf` 留下了过期的 kubelet 客户端证书。

**解决：**
```bash
sudo rm -f /etc/kubernetes/kubelet.conf
sudo kubeadm reset
# 重新执行 kubeadm init 或 join
```

### 3.2 Swap 重启后自动开启

**现象：** 虚拟机重启后，`kubectl get nodes` 显示 `NotReady`。

**根因：** Ubuntu 默认在 `/etc/fstab` 中配置了 swap 分区， reboot 后 swap 重新启用，导致 kubelet 无法启动。

**解决（永久）：**
```bash
sudo sed -i '/swap/s/^/#/' /etc/fstab
sudo swapoff -a
```

### 3.3 containerd 配置被 kubeadm reset 覆盖

**现象：** `kubeadm reset` 后，containerd 日志报错 `failed to create shim task: OCI runtime create failed`，sandbox 镜像找不到。

**根因：** `kubeadm reset` 似乎会触发 containerd 默认配置的重写（或者某些发行版脚本会恢复默认配置）。

**解决：** `kubeadm reset` 后，重新执行：
```bash
sudo containerd config default | sudo tee /etc/containerd/config.toml
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
sudo sed -i 's|sandbox_image = "registry.k8s.io/pause:.*"|sandbox_image = "registry.aliyuncs.com/google_containers/pause:3.10"|' /etc/containerd/config.toml
sudo systemctl restart containerd
```

### 3.4 国内镜像拉取失败（GFW）

**现象：** `kubeadm init` 时 `registry.k8s.io` 无法访问，拉取 pause/coredns 等镜像超时。

**解决：** 使用阿里云镜像仓库 `registry.aliyuncs.com/google_containers`：
```bash
# kubeadm init 时指定镜像仓库
sudo kubeadm init --image-repository=registry.aliyuncs.com/google_containers
```

---

## 四、YAML 清单设计决策

### 4.1 统一使用 `k8s/base` 目录结构

```
k8s/
├── base/
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── secret.yaml
│   ├── auth-service/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── account-service/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   └── ...
```

**理由：** 便于使用 Kustomize 或 Helm 做环境区分（dev/staging/prod）。

### 4.2 每个 Deployment 包含：探针 + 资源限制 + imagePullSecrets

```yaml
resources:
  requests:
    memory: "256Mi"
    cpu: "200m"
  limits:
    memory: "512Mi"
    cpu: "500m"
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 30
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 60
```

**理由：**
- `resources` 是 HPA 的前提条件（必须设置 requests）。
- `readinessProbe` 避免流量打到未就绪 Pod。
- `livenessProbe` 在服务死锁时自动重启。

### 4.3 Service 类型选择

| 服务 | 类型 | 理由 |
|------|------|------|
| 内部微服务 | ClusterIP | 仅内部调用，不暴露 |
| 外部验证/演示 | NodePort | 学习阶段简单直接 |
| 生产入口 | Ingress | V2 阶段统一入口 |

---

## 五、Spring Boot 应用改造决策

### 5.1 使用 `application.yml` 支持环境变量注入

```yaml
server:
  port: ${SERVER_PORT:8080}
spring:
  application:
    name: ${SPRING_APP_NAME:auth-service}
```

**理由：** 配合 K8s ConfigMap/Secret，实现配置与镜像解耦。

### 5.2 引入 Spring Boot Actuator

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**理由：** 提供 `/actuator/health/readiness` 和 `/actuator/health/liveness`，直接作为 K8s 探针端点。

---

## 六、面试表达策略

### 6.1 推荐话术

> “参考银行电子商城业务场景，搭建了一套 Kubernetes 云原生部署实战环境，完成 Spring Boot 微服务容器化、K8s 部署、配置管理、健康检查、弹性伸缩和监控告警方案设计。”

### 6.2 不推荐话术

> “独立负责某银行生产系统上线。”（一旦追问网络拓扑、灾备方案、变更流程、监控指标，极易露馅。）

### 6.3 应对追问的核心准备

如果被问到“你们生产环境用了几台机器？”，准备两套答案：

1. **学习环境（诚实版）：**
   - 1 master + 2 worker + 1 Harbor
   - 资源：4C/8G 每台
   - 网络：VMware NAT
   - 目的：学习验证

2. **设计方案（能力版）：**
   - 3 master（高可用）+ 2 worker + 2 LB + 1 Harbor + 1 Storage
   - etcd：stacked 或 external
   - 网络：Calico + NetworkPolicy
   - 存储：NFS 入门，后续 Longhorn/Ceph
   - 监控：Prometheus + Grafana + Loki

**原则：** 诚实描述做了什么，但用设计方案展示你理解生产级应该怎么搞。

---

## 七、文档体系决策

### 7.1 编号规则

| 编号 | 内容 | 语言 |
|------|------|------|
| 00 | 实验环境说明 | 中文 |
| 01 | 项目定位 | 中文 |
| 02 | 实施路线 | 中文 |
| 03 | API 清单 | 中文 |
| 04 | 构建与验证 | 中文 |
| 05 | 网络拓扑 | 中文 |
| 06 | K8s 集群规划 | 中文 |
| 07 | Ubuntu 虚拟机初始化 | 中文 |
| 08 | containerd 安装 | 中文 |
| 09 | K8s 组件安装 | 中文 |
| 10 | 实际部署操作记录 | 中文 |
| 11 | 部署教程 | 中文 |
| 12 | 架构图（HTML） | 中文 |
| 13 | 设计决策（本文档） | 中文 |

### 7.2 脚本目录

```
scripts/
├── build-images.sh    # 构建并推送镜像到 Harbor
├── deploy.sh          # 一键部署到 K8s
└── teardown.sh        # 一键清理
```

### 7.3 面试材料目录

```
docs/interview/
├── resume-description.md   # 简历项目描述
├── interview-qa.md         # 15 道面试 Q&A
└── interview-script.md       # 3/5/10 分钟版本 + 速查卡
```

---

## 八、下阶段待决策事项

| 事项 | 候选方案 | 状态 |
|------|----------|:---:|
| Ingress Controller | Nginx Ingress（已选） | ✅ 已决策 |
| HPA 指标源 | CPU（已选）+ 业务指标（Micrometer 已接入） | ✅ 已决策 |
| 监控栈 | Prometheus + Grafana（已部署） | ✅ 已决策 |
| 日志方案 | Loki + Promtail（已部署） | ✅ 已决策 |
| V2 数据存储 | MySQL in K8s（当前方案），生产建议外部 | ⚠️ 待评估 |
| Gateway | Ingress Nginx（4 服务不需要额外 Gateway） | ✅ 已决策 |
| 链路追踪 | Jaeger all-in-one + OTEL Java Agent（2026-06-04 上线） | ✅ 已决策 |
| Secret 管理 | Sealed Secrets（2026-06-04 上线） | ✅ 已决策 |
| Redis | 设计文档已写，当前不实现 | 🔵 V2 规划 |

---

## 九、S2 关键决策：OTEL Agent 注入方式（2026-06-04）

### 背景

4 个 Java 微服务需要向 Jaeger 上报 trace，OpenTelemetry Java Agent 是最佳方案（JVM `-javaagent` 参数注入，零代码侵入）。

### 三个候选方案及其迭代

| 版本 | 方案 | 实现 | 问题 |
|:---:|------|------|------|
| V1 | hostPath | Deployment 挂载 `/opt/otel/opentelemetry-javaagent.jar` | PSA `baseline` 标签不允许 hostPath 挂载，Pod 无法启动 |
| V2 | initContainer 从 GitHub 下载 | initContainer `wget` 下载 jar 到 emptyDir | VM 网络 `objects.githubusercontent.com` 被 GFW 阻断，curl 超时 |
| V3 | Harbor 镜像中转 | initContainer 镜像打包 agent jar，`cp` 到 emptyDir，主容器 `-javaagent` 加载 | ✅ 无 hostPath 依赖、无外网依赖、PSA 合规 |

### 最终方案

```
initContainers:
- name: otel-agent-init
  image: 10.0.0.61/bank-mall/otel-agent-init:latest
  command: [sh, -c, cp /otel/opentelemetry-javaagent.jar /mnt/otel/]

containers:
- name: payment-service
  env:
  - name: JAVA_TOOL_OPTIONS
    value: -javaagent:/otel/opentelemetry-javaagent.jar
  - name: OTEL_SERVICE_NAME
    value: payment-service
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    valueFrom: configMapKeyRef  # jaeger-collector.jaeger:4317
```

### 面试话术

> “OTEL agent 注入经历了三次迭代。第一版 hostPath 被 PodSecurity 阻断；第二版 initContainer 从 GitHub 下载被网络墙阻断；最终版把 agent jar 打进 Harbor 镜像，initContainer 从内网 Harbor 拉取后拷贝到 emptyDir。三种约束——安全策略、网络策略、零代码侵入——全部满足。这个过程本身就是平台工程能力的体现。”

### 教训

- K8s PodSecurity `restricted` 不允许 hostPath。initContainer + emptyDir 是生产合规方案。
- initContainer 不适合运行时下载（网络不稳定、增加启动延迟）。Harbor 中转是内网环境的正确做法。
- OTEL agent 以 gRPC 协议发送到 Jaeger 可能遇到兼容性问题。当前使用 http/protobuf + 端口 4318。

---

> 本文档持续更新。每次重大技术决策或踩坑解决后，追加到对应章节。
