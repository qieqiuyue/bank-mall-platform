# 面试 Q&A 话术

## 基础问题

### Q1：这个项目是做什么的？

**回答框架：**

这是一个银行电子商城的云原生改造项目。原始系统采用传统部署方式，存在版本不可控、扩容不灵活、故障影响范围大等问题。我将系统拆分为多个 Spring Boot 微服务，基于 Kubernetes 进行容器化部署，完成了镜像管理、服务发布、配置管理、弹性伸缩和可观测性方案设计。

**关键数字：** 4 个微服务、3 节点 K8s 集群、1 个 Harbor 镜像仓库。

### Q2：为什么选择 Kubernetes？不用 Docker Compose 或虚拟机？

**回答要点：**

1. **服务发现**：K8s Service 自动实现服务间通信，不需要手动配置 IP
2. **弹性伸缩**：HPA 根据负载自动扩缩容，Docker Compose 做不到
3. **自愈能力**：Pod 挂了自动重启/重新调度，虚拟机需要人工介入
4. **配置管理**：ConfigMap/Secret 解耦配置，Compose 需要改文件重启
5. **资源隔离**：Namespace + 资源限制防止服务互相影响

> 面试加分：我先用 Docker Compose 做过原型验证，但发现它无法解决生产环境的调度、故障恢复和水平扩展问题，所以切换到 K8s。

### Q3：微服务是怎么拆分的？拆分的依据是什么？

**回答：**

按业务边界拆分，遵循单一职责原则：
- `auth-service`：用户认证，独立是因为认证逻辑变化少但调用量大
- `account-service`：账户查询，涉及敏感数据，需要独立部署和权限控制
- `payment-service`：支付模拟服务，生产场景需要高可用和严格的数据一致性；V1 主要用于演示接口与部署治理
- `notification-service`：通知发送，属于异步任务，可独立扩展

V2 还会增加 product/order/inventory，形成完整的电商链路。

### Q3.1：你这次 V1 收口优化做了什么？

**回答：**

我没有继续堆 product/order/inventory 这类 V2 服务，而是先做 V1 可信度收口：

1. 统一 4 个服务的接口响应结构：`code/message/data/timestamp`
2. 统一最小错误码：`SUCCESS`、`BAD_REQUEST`、`AUTH_FAILED`、`NOT_FOUND`
3. 统一 health 接口，不再有的返回字符串、有的返回 JSON
4. 新增 `scripts/smoke-test.sh`，用一条命令验证 Ingress 到 4 个服务的最小闭环

这类优化更接近真实团队里的 V1 收尾：功能不一定继续扩，但接口契约、验证方式和展示证据必须稳定。

### Q3.2：为什么统一响应格式比继续加新服务更重要？

**回答：**

因为当前项目的主要问题不是服务数量不够，而是工程可信度需要收口。统一响应格式以后，前端、测试脚本、日志排查和面试展示都有稳定契约；smoke test 则证明请求确实经过 Ingress 到达服务并返回成功。继续加 mock 服务只能扩大功能面，但不能证明已有 V1 是稳定可交付的。

### Q4：Dockerfile 是怎么设计的？为什么用多阶段构建？

**回答：**

```dockerfile
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY settings.xml /root/.m2/settings.xml
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

多阶段构建的好处：
1. 第一阶段包含 Maven 工具链（约 500MB），第二阶段只有 JRE（约 180MB）
2. 减少镜像体积，降低存储和传输成本
3. 减少攻击面，运行时不包含编译工具
4. 分层缓存：pom.xml 不变时可以直接复用依赖层

### Q5：为什么用 Harbor 而不是 Docker Hub？

**回答：**
1. **安全性**：银行场景要求镜像不出内网，Harbor 可部署在私有网络
2. **权限控制**：Harbor 支持项目级权限、镜像扫描、签名验证
3. **性能**：内网拉取速度远快于公网 Docker Hub
4. **合规**：企业审计需要记录镜像来源和版本

### Q6：Kubernetes 集群是怎么搭建的？

**回答框架：**

1. 使用 kubeadm 初始化控制平面（1 个 master）
2. 使用 Calico CNI 安装网络插件
3. worker 节点通过 kubeadm join 加入集群
4. 安装 Harbor 作为私有镜像仓库
5. 所有节点使用 containerd 作为容器运行时

**拓扑：** 1 master (10.0.0.31) + 2 worker (10.0.0.41, 10.0.0.42) + 1 Harbor (10.0.0.61)

### Q7：kubeadm init 时遇到的最大问题是什么？

**回答：**

境内环境访问 `registry.k8s.io` 被干扰，导致 `kubeadm init` 拉取核心镜像（kube-apiserver、etcd 等）时 TLS handshake timeout。

**解决方案：** 使用阿里云镜像站 `registry.aliyuncs.com/google_containers` 作为镜像仓库：
```bash
kubeadm init \
  --apiserver-advertise-address=10.0.0.31 \
  --pod-network-cidr=10.244.0.0/16 \
  --image-repository=registry.aliyuncs.com/google_containers
```

### Q8：容器运行时为什么选 containerd 而不是 Docker？

**回答：**
1. Kubernetes 1.24+ 已移除 Dockershim，containerd 是官方推荐运行时
2. containerd 更轻量，启动更快，资源占用更少
3. containerd 直接支持 CRI，无需额外适配层
4. Docker 包含太多非运行时组件（dockerd、docker-cli、docker-compose），不符合云原生精简理念

### Q9：ConfigMap 和 Secret 是怎么用的？

**回答：**

**ConfigMap** `bank-mall-config`：
- `LOG_LEVEL`：控制日志级别
- `SPRING_PROFILES_ACTIVE`：激活 prod 环境
- `SERVER_TOMCAT_THREADS_MAX`：线程池配置
- `AUTH_SERVICE_URL`、`ACCOUNT_SERVICE_URL` 等：服务间调用地址
- `TZ`：时区配置

**Secret** `bank-mall-secret`：
- `JWT_SECRET_KEY`：JWT 签名密钥
- `DB_USERNAME`、`DB_PASSWORD`：数据库凭据
- `HARBOR_USERNAME`、`HARBOR_PASSWORD`：镜像仓库凭据

**注入方式**：Deployment 中通过 `envFrom` 引用，Spring Boot 的 `application.yml` 通过 `${ENV_VAR:default}` 语法读取环境变量。

### Q10：readinessProbe 和 livenessProbe 有什么区别？

**回答：**

| 探针 | 作用 | 失败后果 | 我的配置 |
|------|------|---------|---------|
| livenessProbe | 检查容器是否存活 | 杀死容器重新启动 | initialDelaySeconds: 30, period: 10s |
| readinessProbe | 检查容器是否准备好接收流量 | 从 Service Endpoints 移除 | initialDelaySeconds: 10, period: 5s |

**设计理由**：
- livenessProbe 给 Spring Boot 足够启动时间（30s），避免误杀
- readinessProbe 更早开始检查（10s），快速将不健康实例移出负载均衡
- 两者都使用 `/api/<service>/health` 端点，保持一致性

### Q11：resources requests/limits 是怎么设置的？

**回答：**

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "256Mi"
  limits:
    cpu: "500m"
    memory: "512Mi"
```

**设计理由**：
- requests：Scheduler 用此值分配节点，保证节点资源不超额预订
- limits：防止单个 Pod 耗尽节点资源（noisy neighbor 问题）
- 值根据服务实际负载测试得出：auth-service 平均 CPU 50m、内存 200Mi，预留 2 倍 buffer

### Q12：HPA 是怎么设计的？

**回答（设计阶段）：**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: auth-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: auth-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
```

**设计要点**：
- CPU 阈值 70%：避免频繁扩缩容（阈值太低会导致震荡）
- scaleDown 冷却 5 分钟：防止流量短暂下降后立即缩容
- minReplicas=2：保证基础可用性，即使流量低谷也有冗余

### Q13：服务间是怎么通信的？

**回答：**

集群内通过 Kubernetes Service DNS 通信：
- `auth-service:8081` → `auth-service` Service 的 ClusterIP
- 不需要知道 Pod 的具体 IP，Service 自动负载均衡到后端 Pod
- 服务 URL 存储在 ConfigMap 中，通过环境变量注入到每个 Pod

**实际调用链路示例**：
```
用户 → Ingress → gateway-service → order-service → payment-service → account-service
                                        ↓
                                   notification-service
```

### Q14：如果 Pod 一直 CrashLoopBackOff，怎么排查？

**回答框架：**

1. `kubectl describe pod <pod-name>` 看 Events（OOMKilled? ImagePullBackOff?）
2. `kubectl logs <pod-name>` 看应用日志（NullPointerException? 配置错误?）
3. `kubectl get events --sort-by='.lastTimestamp'` 看集群事件
4. 如果是 OOMKilled：增加 memory limits
5. 如果是 LivenessProbe 失败：延长 initialDelaySeconds 或检查 health 端点
6. 如果是 ImagePullBackOff：检查镜像名、标签、仓库可访问性

**我的实际踩坑**：
- Harbor 使用 HTTP，containerd 默认走 HTTPS → 443 连接拒绝
- 解决：在 worker 节点手动 `ctr pull --plain-http` 预拉镜像到 k8s.io namespace
- 生产环境会配置 Harbor HTTPS（自签证书 + CA 导入到 containerd）

### Q15：生产环境还需要补什么？

**回答：**

1. **高可用集群规划**：V1 是 1 master + 2 worker 实验集群；生产方案是 3 master + HAProxy + Keepalived（API Server VIP）
2. **持久化存储**：对接 NFS/Ceph/Longhorn，Pod 重建后数据不丢失
3. **安全加固**：NetworkPolicy 限制服务间通信、RBAC 权限最小化、Pod Security Standards
4. **CI/CD**：GitLab CI / Jenkins / ArgoCD 实现自动化构建和发布
5. **可观测性**：V1 已落地 Prometheus + Grafana 监控、Grafana Alerting、Loki/Promtail 日志采集；Jaeger 链路追踪是 V2 规划
6. **灾难恢复**：etcd 定期快照备份、Velero 集群备份、跨可用区部署

## 进阶追问

### Q：为什么 Spring Boot 启动要 28 秒？怎么优化？

**回答：**
当前 28 秒主要是：
1. JVM 类加载和 Spring 上下文初始化（约 20s）
2. Tomcat 启动和 Servlet 初始化（约 8s）

**优化方案：**
1. 使用 Spring Boot AOT + GraalVM Native Image（启动时间 < 1s，但镜像构建复杂）
2. 使用 Spring Boot 4.x 的 CRaC（Coordinated Restore at Checkpoint）特性
3. 精简依赖：移除未使用的 Starter，减少 Bean 扫描范围
4. 调整 JVM 参数：`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`

### Q：如果节点故障，Pod 怎么恢复？

**回答：**
1. K8s Controller 检测到 Pod 失联（NodeNotReady 超时 5 分钟）
2. 将故障节点上的 Pod 标记为 Terminating
3. 在健康节点上重新调度并创建新 Pod
4. Deployment 的 replicas 保证最终状态一致

**优化**：使用 PodDisruptionBudget 保证最小可用副本数，防止同时重启过多 Pod。

### Q：镜像版本怎么管理？回滚怎么做？

**回答：**
1. 使用语义化版本：`bank-mall/auth-service:1.0.0`
2. Git tag 与镜像 tag 保持一致
3. 回滚：`kubectl rollout undo deployment/auth-service` 或指定 revision
4. 生产环境保留最近 10 个版本镜像，旧版本自动清理

### Q：Ingress 和 NodePort 有什么区别？

**回答：**

| 特性 | NodePort | Ingress |
|------|---------|---------|
| 暴露方式 | 每个节点开放固定端口 | 统一入口，基于域名/路径路由 |
| 端口范围 | 30000-32767 | 80/443 |
| SSL/TLS | 不支持 | 支持 |
| 负载均衡 | 简单轮询 | 支持会话保持、权重、重写规则 |
| 适用场景 | 测试/开发 | 生产 |

**我的设计**：测试阶段用 port-forward，生产用 Ingress + 域名 `api.bank-mall.local`。

### Q16：为什么 MySQL 的 NetworkPolicy 只允许 auth-service？

**回答：**

这是 V1 架构设计决策：auth-service 作为数据网关，其他服务（account、payment、notification）通过 auth-service 的 HTTP API 获取用户数据，而非直连 MySQL。这遵循"数据库即服务"模式——一个 MySQL 实例，但逻辑上分离的数据库。

**为什么 ConfigMap 里有其他服务的 DB 配置？**
DB_NAME_ACCOUNT、DB_NAME_PAYMENT、DB_NAME_NOTIFICATION 是为 V2 预留的。当 product/order/inventory 服务部署时，每个服务会有自己的 schema。Egress NetworkPolicy 已经允许所有服务访问 MySQL，所以 V2 只需要更新 ingress 规则。

**追问："但 egress 允许所有服务访问 MySQL？"**
是的，这是有意为之：
1. 为 V2 设计——egress 规则已预置
2. 仅 egress 不授予访问权限——MySQL 上的 ingress 规则才是真正的守门人
3. 纵深防御：即使 Pod 尝试连接，ingress 规则也会阻止

**追问："V2 怎么修复这个问题？"**
将 ingress 从 `matchLabels: app: auth-service` 改为 `matchExpressions`，使用 `operator: In` 覆盖所有服务标签，或使用通用标签 `app.kubernetes.io/part-of: bank-mall`。

### Q17：告警是怎么设计的？

**回答：**

我使用 Grafana Unified Alerting（Grafana 10.x 内置）作为告警评估引擎。告警规则通过 ConfigMap 以代码方式 provisioned，挂载到 Grafana 的 `/etc/grafana/provisioning/alerting/` 目录。配置了 3 条规则：

| 规则 | 严重度 | PromQL | 持续时间 |
|------|--------|--------|----------|
| Service Down | critical | `up{job=~"bank-mall/.*"} == 0` | 1m |
| High CPU | warning | `rate(process_cpu_seconds_total[5m]) > 0.8` | 5m |
| High JVM Heap | warning | `heap_used / heap_max > 0.85` | 5m |

**通知链路：** Grafana 评估规则 → 触发告警 → 路由到 Contact Point（学习环境 webhook 占位）→ 生产环境路由到 PagerDuty/Slack/邮件，或在多 Prometheus 场景接 AlertManager。

**为什么选 Grafana Alerting 而不是 AlertManager？**
1. 零额外组件（Grafana 已部署）
2. 统一 UI：Dashboard + Alert 在同一界面
3. 相同 PromQL 查询语言，学习成本低
4. Provisioning as Code（GitOps 友好）

**追问："AlertManager 呢？"**
AlertManager 是传统 Prometheus 告警栈，擅长多 Prometheus 实例间的告警去重、分组和静默。单集群场景下 Grafana Unified Alerting 覆盖相同用例且运维成本更低。生产多 Prometheus 实例环境会部署 AlertManager，利用其 HA gossip 协议和 inhibition 规则。

## 进阶追问（基础架构深度）

以下 6 个问题是面试官可能追问的架构级盲区。每道题准备一句话回答 + 扩展知识，面试官不期望学习项目答到源码级。

### Q18：K8s 调度器是怎么工作的？

**一句话回答：** Scheduling Framework 是 v1.19 引入的插件化架构，取代了旧的 predicates/priorities 硬编码模型。核心扩展点：Filter（过滤）→ Score（打分）→ Reserve（预留资源）。

**扩展知识：** CycleState 是每次调度周期内的键值存储，Reserve 阶段写入数据，PreBind 阶段读取。这种设计让插件之间无需全局状态即可传递决策。注意旧文档中 `predicates/priorities` 术语已被 Filter/Score 取代。

> 推荐拓展：[K8s Scheduling Framework](https://kubernetes.io/docs/concepts/scheduling-eviction/scheduling-framework/)（20min）

### Q19：Calico BGP 和 IPIP 隧道有什么区别？

**一句话回答：** BGP 纯路由无封装（零开销，需路由器支持），IPIP 把原始包封装在外层 IP 头中（20 字节开销）。Calico 默认跨子网用 IPIP，同子网用 BGP。

**扩展知识：** IPIP 的 20 字节开销导致 Pod MTU 从 1500 降到 1480。所以 ping 测试时用 `ping -M do -s 1452 <pod-ip>` 是正确的 MTU 验证值（1452 + 8 ICMP + 20 内层 IP + 20 外层 IP = 1500）。生产裸金属环境切换 BGP 可消除这 20 字节开销。

> 推荐拓展：[Calico Networking](https://docs.tigera.io/calico/latest/networking/)（15min）

### Q20：Prometheus WAL 是什么？

**一句话回答：** WAL（Write-Ahead Log）是 Prometheus TSDB 的持久化机制。新采集的样本先写 WAL 磁盘，再暂存内存；崩溃后从 WAL 重放恢复，防丢 2 小时内数据。

**扩展知识：** 每 2 小时 WAL segment 被截断压缩为 TSDB block（chunks + index + meta.json）。超过 `retention.time` 的 block 被自动清理。Loki 的 ingester WAL 用了相同设计模式——理解一个，就理解两个。

> 推荐拓展：[Prometheus Storage](https://prometheus.io/docs/prometheus/latest/storage/)（15min）

### Q21：Loki compactor 做什么？

**一句话回答：** compactor 合并排序 boltdb-shipper 或 TSDB 的索引 chunk 以提升查询性能。它不去重日志——Loki 是 append-only 存储。

**扩展知识：** 我集群用 Loki 2.9.12 + boltdb-shipper + schema v11（从 3.0 降级）。compactor 在微服务模式是独立组件，单二进制模式是内部协程。关键配置：`compaction_interval: 10m`。

> 推荐拓展：[Loki Architecture](https://grafana.com/docs/loki/latest/architecture/)（15min）

### Q22：CRI 是什么？为什么不能用 Docker 了？

**一句话回答：** CRI 是 Kubelet 和容器运行时之间的 gRPC 接口标准。Docker 需要 `dockershim` 适配 CRI，K8s v1.20 弃用、v1.24 移除。containerd 和 CRI-O 原生实现 CRI，无需 shim。

**扩展知识：** 这就是为什么 K8s v1.24+ 不能用 Docker 做运行时——Kubelet 说 CRI，Docker 说 Docker API。我集群用 containerd v2.2.1（SystemdCgroup=true）。实际操作：worker 上不能用 `docker ps`，用 `sudo ctr -n k8s.io containers ls` 或 `crictl ps`。

> 推荐拓展：[containerd CRI](https://github.com/containerd/containerd/blob/main/docs/cri/architecture.md)（20min）

### Q23：Harbor 删镜像为什么空间没释放？

**一句话回答：** Harbor 镜像删除是 soft delete——只删 manifest 引用，不删实际数据。需手动触发 GC 扫描 blob，确认无 manifest 引用后再物理删除。

**扩展知识：** 我集群 Harbor v2.10.3 支持在线 GC（无需停服）。GC 分两步：mark 阶段识别未引用 blob → sweep 阶段物理删除。v2.9 以前的版本需切只读模式。

---

### Q24：你们为什么不用 Jenkins 而用 GitHub Actions + ArgoCD？

**一句话回答：** Jenkins 是 VM 时代的瑞士军刀——什么都能做，但插件管理、JVM 资源占用、Pipeline DSL 维护成本对 5 人团队不划算。GitHub Actions 零运维、matrix 并行开箱即用，ArgoCD 专注 Git 同步，两者各做一件事。

**扩展知识：** Jenkins 需要一个 Master 节点常驻运行（1Gi+ 内存），而 GitHub Actions runner 按需提供。我在 harbor01 上跑了 `scripts/ci.sh` 作为内网构建——这部分用 Jenkins 也无所谓，但公网质量门用 GitHub Actions 省了一台 Jenkins VM。

### Q25：GitHub Actions 的 matrix 策略解决了什么问题？

**一句话回答：** 4 个微服务并行 build+test，不用串行等。

**扩展知识：** `ci.yml` 的 Job 3（test）和 Job 4（build-and-scan）都用 `strategy.matrix.service: [auth, account, payment, notification]`，5 分钟跑完 4 个服务的测试和镜像构建，串行要 20 分钟。矩阵策略的好处是加新服务只需在数组里加一行——不需要改 workflow 逻辑。

### Q26：Trivy 扫描在哪个环节做？为什么要分硬门和软门？

**一句话回答：** GitHub Actions 上做硬门（HIGH/CRITICAL → 流水线失败，PR 不让合），harbor01 上做软门（记录结果不阻塞）。

**扩展知识：** 内网 `scripts/ci.sh` 的 Trivy 门是软门——因为 GFW 阻断 `ghcr.io`，DB 下载不稳定。公网 GitHub Actions 的 Trivy 走 `aquasecurity/trivy-action@master`，不受 GFW 影响，所以设 `exit-code 1`。另外，内网阶段 4 用 NJU 镜像（`ghcr.nju.edu.cn`）加速 DB 下载——12 秒拉完 95MiB，直连 ghcr.io 要 3 小时。

### Q27：ArgoCD 为什么用 Application CR 而不是 Helm？

**一句话回答：** 我的 4 个服务是 raw YAML 部署的，不是 Helm chart。Application CR 直接指向 `infra/kubernetes/base/` 目录，不经过 Helm template 渲染。

**扩展知识：** V1 用 Kustomize 作为部署方式（`kustomization.yaml` 管理 16 个资源），ArgoCD Application 直接 watch 这个目录。S5 新增了 Helm Chart 骨架作为能力演示，但 `README.md` 首行写了："NOT FOR DEPLOYMENT. Kustomize is the source of truth for V1." V2 如果 Helm 成熟了，可以切到 Helm Application CR。

### Q28：containerd 镜像拉不通时怎么排查？

**一句话回答：** containerd v2.x 配置路径和 v1.x 完全不同——`hosts.toml` 替代了 `config.toml` 的 `plugins."io.containerd.grpc.v1.cri"`。错用一个路径，所有 `ctr pull` 都会静默失败。

**扩展知识：** 踩过坑：containerd 2.2.1 用 `certs.d/<registry>/hosts.toml`，不是旧版 `config.toml`。Harbor HTTP-only（无 TLS）必须配 `plain-http=true`，否则 containerd 拒绝拉取。排查三步：`systemctl status containerd` → `journalctl -u containerd --since 5m` → 检查 `/etc/containerd/certs.d/10.0.0.61/hosts.toml`。

### Q29：Kustomize vs Helm，你们为什么先有 Kustomize 再加 Helm？

**一句话回答：** Kustomize 是做加法的——在 base YAML 上 patch 差异。Helm 是做模板的——把整个 YAML 参数化。Kustomize 更适合"一套 base + 少量 overlay 变化"的场景，Helm 更适合"多租户/多环境差异化较大"的场景。

**扩展知识：** V1 只有 2 个 overlay（VMware base + ACK cloud），Kustomize 的 `patchesStrategicMerge` 足够了。Helm 的价值在于 `values-dev/staging/prod.yaml` 三级覆盖——但 V1 没有 staging，dev 就是 VMware 集群。V2 如果加了阿里云 ACK staging + 生产 ACK，Helm 的 values 分层才真正发挥作用。

> 推荐拓展：[Harbor GC](https://goharbor.io/docs/2.10.0/administration/garbage-collection/)（10min）
