# 面试讲解脚本

## 3 分钟版本（HR/初筛）

> 我参与了一个银行电子商城的云原生改造项目。项目背景是将传统的单体应用拆分为 Spring Boot 微服务，并基于 Kubernetes 进行容器化部署。
>
> 我主要负责的工作包括：
> 1. 设计并实现了 4 个微服务的容器化方案，使用 Dockerfile 多阶段构建，镜像体积从 500MB 优化到 180MB
> 2. 编写了 Kubernetes 的 Deployment、Service、ConfigMap、Secret 等 YAML 清单
> 3. 配置了 Harbor 私有镜像仓库，完成镜像的版本管理和安全推送
> 4. 为服务增加了健康检查探针和资源限制，提升了服务的稳定性
> 5. 设计并验证了 HPA 自动扩缩容和 Prometheus/Grafana 监控方案
>
> 技术栈包括：Spring Boot、Docker、Kubernetes、Harbor、containerd、Calico、Prometheus、Grafana
>
> 这个项目让我深入理解了云原生改造的核心链路，从代码到镜像到部署到监控的完整闭环。

---

## 5 分钟版本（技术面试）

### 开场（30 秒）

> 我想分享一个我参与的 Kubernetes 云原生改造项目。这个项目不是公司真实生产系统，而是我围绕银行电子商城业务场景，独立设计并实践的一套云原生部署方案。虽然规模不如生产环境复杂，但核心链路是完整的，而且我在实践中遇到了很多真实问题并逐一解决。

### 项目背景（1 分钟）

> 银行原有电子渠道系统采用传统部署方式，每次发布都是手工操作，版本不可控，扩容需要申请服务器，一次故障可能影响整个系统。我的目标是将其拆分为微服务，基于 Kubernetes 实现容器化部署，解决发布效率、弹性伸缩和故障隔离的问题。

### 技术架构（1 分钟）

> 技术选型上，后端使用 Spring Boot 3.1.3 + Java 17，容器运行时选择 containerd（因为 K8s 1.24+ 已移除 Dockershim），CNI 使用 Calico（支持 NetworkPolicy），镜像仓库使用 Harbor。
>
> 集群拓扑是 1 个 master + 2 个 worker，操作系统 Ubuntu 24.04，虚拟化使用 VMware NAT 网络。
>
> 当前 V1 版本有 4 个服务：auth-service（认证）、account-service（账户）、payment-service（支付）、notification-service（通知）。V2 会扩展商品、订单、库存服务。

### 我的职责（2 分钟）

> **镜像构建**：我编写了多阶段 Dockerfile，第一阶段用 maven 镜像编译，第二阶段用 eclipse-temurin 运行，这样运行时不包含编译工具，镜像从 500MB 降到 180MB。
>
> **K8s 部署**：我整理了统一的 K8s 清单，包含 Namespace 隔离、ConfigMap 统一配置、Secret 管理敏感信息。每个 Deployment 都配置了 readinessProbe 和 livenessProbe，以及 resources requests/limits。
>
> **配置管理**：所有服务的 application.yml 支持环境变量注入，比如 `${SERVER_PORT:8081}`，这样 ConfigMap 修改后只需要重启 Pod，不需要重新构建镜像。
>
> **踩坑解决**：部署过程中我遇到了几个典型问题。第一个是境内环境访问 registry.k8s.io 被干扰，我用阿里云镜像站 `registry.aliyuncs.com/google_containers` 解决了。第二个是 containerd 的 sandbox_image 配置会被 kubeadm reset 重置，每次重置后需要重新修改。第三个是 Harbor 使用 HTTP，containerd 默认走 HTTPS，我通过在每个 worker 节点预拉镜像到 k8s.io namespace 解决了。

### 总结（30 秒）

> 这个项目让我理解了云原生改造不只是把服务放进容器，而是要考虑配置管理、健康检查、弹性伸缩、监控告警、日志采集和安全边界。目前 V1 核心链路、Ingress、HPA、Prometheus/Grafana、Loki/Promtail 和 Grafana Alerting 已经跑通；高可用集群、Redis 和链路追踪是后续生产化方向。

---

## 10 分钟版本（深度技术面试）

### 开场（1 分钟）

> 我独立完成了一个银行电子商城的云原生改造项目，从微服务设计到 K8s 部署到监控方案，做了完整的实践。项目背景是模拟企业真实场景，不是玩具 demo。我会用这个项目来展示我对云原生技术的理解深度。

### 架构设计（2 分钟）

**展示架构图（手机/Pad 或口述）**

> 整体架构分为四层：用户接入层、微服务层、K8s 编排层、基础设施层。
>
> 用户通过 Ingress Nginx 统一入口访问，Ingress 基于域名和路径路由到不同服务。
>
> 微服务层目前 4 个 Spring Boot 服务，每个服务都有独立的 Deployment 和 Service。Service 使用 ClusterIP 对内暴露，只有 Ingress 对外暴露。
>
> K8s 层我重点做了几件事：Namespace 隔离不同环境、ConfigMap 解耦配置、Secret 管理敏感信息、HPA 自动扩缩容、PodDisruptionBudget 保证可用性。
>
> 基础设施层包括 Harbor 镜像仓库、containerd 运行时、Calico 网络、Prometheus/Grafana 监控。

### 详细技术决策（3 分钟）

**Q：为什么用 Calico 而不是 Flannel？**

> 两个原因。第一，Calico 支持 NetworkPolicy，可以限制服务间通信，这在银行场景很重要——比如支付服务只能被订单服务访问，不能被直接访问。第二，Calico 使用 BGP 路由而不是 overlay 网络，性能更好，更接近生产环境选择。

**Q：ConfigMap 和 Secret 怎么设计？**

> 我设计了一个统一的 `bank-mall-config` ConfigMap，包含所有服务共享的配置：日志级别、时区、服务间调用地址。每个 Deployment 通过 `envFrom` 引用，这样修改配置只需要改一次 ConfigMap，所有服务自动生效。
>
> Secret 单独管理敏感信息，包括 JWT 密钥、数据库密码、Harbor 凭据。所有值都是 Base64 编码，且设置了文件权限限制。

**Q：探针怎么设计的？**

> livenessProbe 用 HTTP GET 检查 `/api/<service>/health`，initialDelaySeconds 30 秒，给 Spring Boot 足够启动时间。readinessProbe 同样路径，initialDelaySeconds 10 秒，更早开始检查。
>
> 设计理由：Spring Boot 冷启动约 28 秒，如果 livenessProbe 设置太短会误杀。readinessProbe 更早启动是为了快速识别不健康实例并从 Service 移除。

**Q：资源限制怎么设计的？**

> requests 100m CPU / 256Mi 内存，limits 500m CPU / 512Mi 内存。这个值是我通过压测得出的：auth-service 平均 CPU 50m、内存 200Mi，预留 2 倍 buffer。
>
> 没有 limits 会导致 noisy neighbor 问题，一个服务耗尽节点资源会影响其他服务。

### 故障处理（2 分钟）

**Q：部署过程中遇到的最大问题？**

> 三个典型问题：
>
> **问题 1**：kubeadm init 拉取核心镜像超时。境内访问 registry.k8s.io 被干扰，TLS handshake 失败。解决：使用阿里云镜像站 `registry.aliyuncs.com/google_containers`。
>
> **问题 2**：containerd 的 sandbox_image 配置被重置。kubeadm reset 会重新生成 containerd 默认配置，把 sandbox_image 改回 `registry.k8s.io/pause:3.10.1`，导致 kubelet 无法创建 Pod sandbox。解决：每次 reset 后重新修改 sandbox_image 为阿里云镜像。
>
> **问题 3**：Harbor HTTP 仓库拉取失败。containerd 默认走 HTTPS，连接 Harbor 的 443 端口被拒绝。我尝试了 hosts.toml 和 config.toml 多种配置方式都没生效，最终在每个 worker 节点手动用 `ctr pull --plain-http` 预拉镜像到 k8s.io namespace。
>
> 生产环境会配置 Harbor HTTPS（自签证书 + CA 导入到 containerd），或者使用 Docker pull secret。

### 生产化思考（2 分钟）

**Q：生产环境还需要补什么？**

> 从五个维度：
>
> **高可用规划**：V1 是 1 master + 2 worker 实验集群；生产方案是 3 master + HAProxy + Keepalived，etcd 定期快照备份
> **安全**：NetworkPolicy 限制服务间通信、RBAC 权限最小化、Pod Security Standards
> **可观测性**：Prometheus + Grafana 监控、Grafana Alerting、Loki/Promtail 日志采集；Jaeger 链路追踪是 V2 规划
> **存储**：对接 Ceph/Longhorn，实现有状态服务的数据持久化
> **CI/CD**：ArgoCD GitOps 流水线，实现代码提交到生产部署的自动化

**Q：如果面试官质疑"这不是真实生产项目"？**

> 我会坦诚说明这是围绕真实业务场景独立设计的实战项目。虽然不是公司生产系统，但我亲手解决了 kubeadm 镜像拉取、containerd 配置重置、HTTP 仓库认证等真实问题。这些踩坑经验在真实工作中同样会遇到。而且我清楚知道生产环境还需要补什么，这是我从"学习 demo"到"生产化改造"的真实思考过程。

---

## 快速回答卡片（面试现场速查）

| 问题 | 30 秒速答 |
|------|----------|
| 项目做什么？ | 银行电子商城微服务容器化 + K8s 部署改造 |
| 技术栈？ | Spring Boot + Docker + K8s + Harbor + Calico |
| 集群拓扑？ | 1 master + 2 worker + 1 Harbor，Ubuntu 24.04 |
| 镜像怎么构建？ | 多阶段 Dockerfile，maven 编译 → temurin 运行，180MB |
| K8s 清单有什么？ | Deployment + Service + ConfigMap + Secret + HPA |
| 探针怎么配？ | liveness 30s 延迟，readiness 10s 延迟，/api/<service>/health |
| 资源限制？ | requests 100m/256Mi，limits 500m/512Mi |
| 怎么解决镜像拉取失败？ | 阿里云镜像站 + 手动 ctr pull --plain-http |
| 生产还缺什么？ | 多 master 高可用、生产级存储、Redis、OpenTelemetry/Jaeger、AlertManager HA、GitOps |
| 项目亮点？ | 不是简单跑通，而是补齐了 ConfigMap、探针、资源限制、Ingress、HPA、NetworkPolicy/PSA、监控、日志和 Grafana 告警 |

---

## 简历对应话术

**简历描述**：
> 参考银行电子商城业务场景，搭建了一套 Kubernetes 云原生部署实战环境，完成 Spring Boot 微服务容器化、K8s 部署、配置管理、健康检查、弹性伸缩和监控告警方案设计。

**面试展开**：
- "参考银行电子商城业务场景" → 我不是瞎编，有业务背景
- "搭建了一套 K8s 云原生部署实战环境" → 我能动手，不是只看文档
- "完成 Spring Boot 微服务容器化" → 我有编码能力
- "K8s 部署" → 我能写 YAML，能调参数
- "配置管理" → 我懂 ConfigMap/Secret 的设计思想
- "健康检查" → 我懂探针的设计和调优
- "弹性伸缩" → 我懂 HPA 的设计（100 并发冷启动死亡螺旋 + 503 复盘）
- "监控告警" → 我懂 Prometheus/Grafana 的原理和配置

---

## 故障案例话术（3 个，追加到 5/10 分钟脚本）

### 案例 1：NetworkPolicy 误配 — 全部 Pod Running 但业务不通

"有一次压测，payment 全失败但所有 Pod 的状态都是 Running。payment 日志显示 `Connect timed out` 到 account-service:8082——这说明 account Pod 在运行但不可达。第二件事我确认 account Pod 是 1/1 Running，但它的业务日志没有任何来自 payment 的请求——流量根本没到。这是典型的网络层拦截。第三件事 `kubectl describe netpol -n bank-mall`——发现入站白名单只有 auth-service 和 notification-service，payment-service 被漏了。根因确认：运维在 apply YAML 时少写了一行 `app: payment-service`。恢复就是一行 `kubectl apply -f original.yaml`，然后压测恢复到 161/161 全过。这个案例说明 deny-all 白名单模型下，故障不是 Pod 挂了这种一眼能看出的问题，而是网络规则层面的隐性问题。"

### 案例 2：HPA 冷启动死亡螺旋 — 100 并发 93% 503

"100 并发压测时成功率只有 6.3%。503 占了 93%。这不是业务代码有问题——是 K8s 层面的容量问题。HPA 检测到 CPU 飙升触发扩容，但新 Pod 启动需要 60 秒（JPA + Flyway + Hibernate）。这 60 秒里流量全打在老 Pod 上，老 Pod CPU 打满 → 重启 → 更少健康后端 → 更多 503 → 恶性循环。200 并发反而成功率 84%——因为 JIT 在前一轮压测中预热了，热点代码编译成 native code 后每个请求处理速度快了一个数量级。这个案例说明两个事：第一，HPA 扩容的冷启动窗口是最危险的时候——生产环境必须 min=2 副本加 PDB；第二，Java 服务的 JIT 预热不能忽视——200 并发比 100 并发成功率高 13 倍，完全是预热导致的。"

### 案例 3：Jaeger 慢调用 trace — P99 飙升到 5000ms

"我们有一个端到端 trace 验证——冷启动 account-service 然后立即打流量。在 Jaeger UI 里选 payment-service，按 Duration 降序排列，最慢的一条 trace 总耗时 5300ms 但实际业务逻辑只跑了 50ms。展开 span tree 发现 `AccountClient.debit` 这个 span 占了 5000ms——点进去是 account-service 冷启动时的 JPA 初始化。分布式追踪的价值就在这里：不看代码、不看日志，直接定位到瓶颈所在的微服务和具体方法。V2 计划用 `@Profile('chaos')` 注入延迟来做更可控的慢调用演示。"

---

## 设计决策段（10 分钟脚本补充）

### 为什么选 compensation 而不是 Seata？

"Seata AT 模式要对数据库加 `undo_log` 表，全局事务锁由 TC 协调——架构上更重，运维需要额外维护 TC Server 的高可用。我的支付链路是 account debit + account credit + notification——扣除款没有库存扣减那种强一致性要求。补偿逻辑就是 try-catch + 3 次 reverse 重试，如果 reverse 也失败就标记 `ERROR_MANUAL_REVIEW` 等人工处理。这更契合银行对账模型——每天日终清算时会发现 '有扣款无入账' 然后冲正。这是业务现实，不是技术缺陷。"

### 为什么 status 用 VARCHAR 而不是 ENUM？

"MySQL ENUM 加新状态需要 ALTER TABLE——DDL 操作在有 1000 万行支付记录的表上可能锁几分钟。VARCHAR 加新状态就是一个代码 commit，零 DDL。这也是为什么 `fail_reason` 字段是 VARCHAR 而不是固定错误码——生产环境这种灵活性比一点存储开销重要得多。"

### 为什么没有 Redis / Spring Cloud Gateway / 前端？

"Redis 缓存的设计文档已经有了（`redis-idempotency-design.md`），但没有落地——因为平台工程这个叙事不依赖缓存层。支付幂等用 DB UNIQUE 约束就够了，不需要分布式锁。Gateway——Ingress Nginx 做了路由 rewrite，4 个服务的流量管理用不上 Spring Cloud Gateway 的过滤器链。前端——这个项目是平台工程 / SRE 方向的，核心价值在 K8s 运维和 CI/CD 而非 UI。面试时我会直接说：V1 有意识的不做这些，ROADMAP 里列出了原因和未来规划。这比硬塞一个 bootstrap 页面诚实得多。"

**每个关键词都可以展开 1-2 分钟的技术细节。**
