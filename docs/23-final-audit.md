# 23 - 项目最终审计报告 (Pre-Interview Audit)

> 审计日期：2026-05-26 | 修正日期：2026-05-26 (v2)
> 审计范围：bank-mall-cloudnative 全链（代码、配置、文档、技能、面试话术）
> 审计方法：模拟面试官压力测试 + 静态代码审计 + 容灾恢复验证
> **v2 修正：** 经二次审计发现 6 处技术细节错误 + 3 个遗漏维度，已全部修正。修正清单见文末。

> **当前状态修订（2026-05-29）：** 本文保留当时的压力测试记录，但 V1 已选择 Grafana Unified Alerting 作为告警实现路径，不再把“未部署 AlertManager”视为 V1 致命漏洞。生产或多集群场景再引入 AlertManager HA。Redis、OpenTelemetry/Jaeger、多 master HA 均为 V2 规划，不应表述为已落地。

---

## 第一部分：面试压力测试（模拟追问）

### 卖点 A：V1 可观测性体系（Prometheus + Grafana + Loki + Grafana Alerting）

| 轮次 | 🔴🟡🟢 | 问题 | 你的可回答性 |
|------|-------|------|-------------|
| 基础 | 🟢 | "为什么用 Loki 而不用 ELK？" | Loki 不索引日志正文只索引标签 → 存储降 80%+。Grafana 原生集成。interview-guide 里有话术 |
| 深入 | 🟡 | "Promtail 的 cri 管道为什么静默丢日志？" | 核心话术：**"CRI 日志行的纳秒时间戳格式（`2026-05-25T17:39:57.126+08:00`）与 Promtail 默认 cri 解析器预期的微秒格式不兼容，正则匹配失败，行被静默丢弃。我通过对比 `promtail_read_lines_total=368` 和 `promtail_sent_entries_total=0` 定位的。"** |
| 极限 | 🟢 | "如果 Loki 挂了，Promtail 会怎样？" | Promtail 缓冲重试 + positions 防丢。宕机超文件轮转周期则数据丢失 |
| **杀手** | **🟡** | "告警的完整链路是什么？AlertManager 挂了怎么办？" | V1 使用 Grafana Unified Alerting：Prometheus 指标 → Grafana 规则评估 → Contact Point webhook（学习占位）。AlertManager HA 是生产/多集群方案，不宣称已落地。 |

### 卖点 B：实验集群安全加固（NetworkPolicy + PSA + 非根容器）

| 轮次 | 🔴🟡🟢 | 问题 | 你的可回答性 |
|------|-------|------|-------------|
| 基础 | 🟢 | "为什么用 deny-all 模型？" | 可答。白名单比黑名单安全——默认拒绝、显式允许 |
| 深入 | 🟡 | "auth-service 访问 MySQL 的 NetworkPolicy 怎么写的？" | **当前 Bug：** `allow-mysql.yaml` Ingress 只允许 `app: auth-service`，但 ConfigMap 中 account/payment/notification 都配了独立 DB。两种解释任选：(a) 修复规则改用 `podSelector: {}`；(b) 话术："V1 只有 auth-service 直连 MySQL，其他服务通过 auth-service HTTP API 操作数据" |
| 极限 | 🔴 | "Calico iptables 被清空，NetworkPolicy 生效吗？怎么排查？" | 核心话术：**"Calico 的 Felix 组件负责将 NetworkPolicy 翻译成 iptables 规则。Felix 异常 = 规则不生效。排查用 `iptables-save | grep cali | wc -l` 和 `calicoctl node status`。"** |
| **杀手** | **🔴** | "MySQL init container 权限。Secret base64 算安全吗？" | **主动承认：** "Base64 是编码不是加密。学习环境简化处理。生产环境用 External Secrets Operator 或 Sealed Secrets。" 你的 `bank-digital-platform/auth-service/k8s/deployment.yaml` 不含 namespace、资源限制、探针——这是半成品残留 |

### 卖点 C：真实环境约束解决能力（GFW + Harbor HTTP + containerd）

| 轮次 | 🔴🟡🟢 | 问题 | 你的可回答性 |
|------|-------|------|-------------|
| 基础 | 🟢 | "containerd 怎么配 Harbor HTTP？" | config.toml registry.mirrors + insecure_skip_verify |
| 深入 | 🔴 | "为什么 containerd 不像 Docker 有 insecure-registries？" | 核心话术：**"Docker CLI → Docker daemon 是控制面链路；Kubelet → CRI gRPC → containerd 是 CRI 标准链路。CRI 接口无环境变量传递，必须静态配置。dockershim 在 K8s v1.24 被正式移除（v1.20 开始弃用），containerd 自 1.1 起原生支持 CRI，无需 shim。这是架构差异，不是功能缺失。"** |
| 极限 | 🔴 | "迁移 HTTPS Harbor 要改什么？证书分发？" | Harbor 端配 TLS。所有节点 `/etc/containerd/certs.d/` 放 CA。去除非安全的 skip_verify |
| **杀手** | 🔴 | "VMware NAT 下 Pod 跨节点 MTU 问题？" | 核心话术：**"Calico IPIP 封装增加 20 字节外层 IP 头。原始 MTU 1500 → Pod 侧 IP 层可用 1480 → TCP MSS 1440。ping 测试用 `ping -M do -s 1452 <pod-ip>`（1452 + 8 ICMP + 20 IP-inner + 20 IP-outer = 1500）。我的小集群未触发此问题，但生产环境会通过 Calico Felix 配置 MTU=1480。"** |

### 压力测试统计

| 卖点 | 🟢 | 🟡 | 🔴 | 最大暴露 |
|------|----|----|-----|---------|
| 可观测性 | 2 | 2 | 0 | **V1 用 Grafana Alerting；AlertManager HA 为 V2** |
| 安全加固 | 2 | 0 | 2 | **base64 Secret 在 Git + NetworkPolicy Bug** |
| GFW 约束 | 1 | 0 | 3 | **CRI 架构 / MTU / HTTPS 均为概念级回答** |

🔴 总数：**6/12** 轮次。超过 3 个阈值。

---

## 第二部分：知识盲区扫描

| 模块 | 🟢🟡🔴 | 自测问题 | 最小学习路径（耗时） |
|------|--------|---------|-------------------|
| K8s 调度 | 🔴 | Scheduling Framework 的 Filter（过滤）vs Score（打分）vs Reserve（预留）阶段？CycleState 是什么？ | 读 K8s Scheduling Framework 页（v1.19+），20min。注意旧文档的 predicates/priorities 术语已废弃 |
| Calico 网络 | 🔴 | BGP(不封装路由) vs IPIP(隧道封装) 区别？开销？ | 读 Calico 官方 networking 页，15min |
| PV ReclaimPolicy | 🟢 | Delete(自动清理) vs Retain(手动保留) 区别？ | 已理解，不用学 |
| Ingress rewrite | 🟡 | Controller(Nginx Pod) ≠ Resource(YAML 路由规则)。rewrite 用 annotation | 看 ingress-nginx rewrite 示例，10min |
| HPA 冷却 | 🟢 | scaleDown stabilizationWindow=300s，策略 Pods=1/60s，selectPolicy:Min | 已在 YAML 中，不用学 |
| **Prometheus WAL** | 🔴 | WAL（Write-Ahead Log）是 TSDB 的预写日志。新样本先写 WAL 再内存索引，崩溃后从 WAL 重放恢复。每 2h WAL segment 切换为 block 压缩。与 Loki ingester WAL 同一设计模式 | 读 Prometheus storage 页，15min |
| **Loki compactor** | 🔴 | compactor 做了什么？ | 读 Loki architecture 页，15min。**注意：** compactor 只做 index chunk 合并和排序（提升查询性能），不做日志去重（Loki 是 append-only 存储）。2.9.x 支持 boltdb-shipper 和 tsdb 两种索引引擎，本项目因从 3.0 降级至 2.9.12 故沿用 boltdb-shipper+schema v11 |
| NetworkPolicy 层级 | 🟢 | Pod 级规则 → Calico Felix ↣ iptables。DNS Egress 是 kube-dns Pod 的出站规则 | 已理解，不用学 |
| **CRI vs Docker shim** | 🔴 | CRI=K8s 定义的容器运行时 gRPC 接口标准。Docker 通过 dockershim 适配 CRI，dockershim 在 **v1.24 被正式移除**（v1.20 弃用）。containerd 自 1.1 起原生实现 CRI API，无需 shim 层。这也是 K8s v1.24+ 只能用 containerd/CRI-O 的原因 | 读 containerd CRI 文档，20min |
| **Harbor GC** | 🔴 | 镜像删除是 soft delete（标记），非实时释放。需手动触发 GC 扫描 blob 是否仍被 manifest 引用，无引用的 blob 才被物理删除。v2.10+ 支持在线 GC（无需停服） | 读 Harbor GC 文档，10min |

🔴 知识盲区：**6/10**。判定：**可带盲区面试，每个盲区一句话回答即可过关。面试官不期望学习项目深入源码级。**

---

## 第三部分：技术债务清点

### 序 1：致命

**Secret.yaml 含明文密码提交到 Git**

`HARBOR_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`DB_PASSWORD`、`JWT_SECRET_KEY` 以 base64 存储在 repo。任何面试官 `echo xxx | base64 -d` 即可看到明文。

> 修复：0.5h。方案 A) 加文件头注释声明学习用途；方案 B) git rm --cached + .gitignore + secret.yaml.example 占位模板。

### 序 2：高

**NetworkPolicy allow-mysql 只允许 auth-service 访问 MySQL**

`allow-mysql.yaml` Ingress matchLabels 仅为 `app: auth-service`。但 ConfigMap 中 account、payment、notification 均有独立 DB_NAME 配置。规则与配置矛盾。

> 修复：0.5h。要么改规则用 `matchExpressions` 覆盖所有服务，要么准备话术："其他服务通过 auth-service HTTP API 做数据操作，不直连 MySQL。"

### 序 3：高

**AlertManager 未部署 / V1 使用 Grafana Alerting**

旧审计曾把“没有 AlertManager”视为致命项。当前口径调整为：V1 单集群采用 Grafana Unified Alerting，webhook 是学习环境占位；生产或多 Prometheus 实例场景再接 AlertManager HA 做告警分组、静默、去重和通知路由。

> 边界：不声称已具备完整生产级 AlertManager 通知链路。

### 序 4：中

**OpenTelemetry/Jaeger 尚未部署**

当前 `k8s/base/configmap.yaml` 已移除 `JAEGER_ENDPOINT`，避免出现未部署组件的死引用。链路追踪保留为 V2 规划。

> 边界：文档和面试材料中只能说“设计/规划链路追踪方案”，不能说已接入 Jaeger。

### 序 5：中

**遗留 YAML：`bank-digital-platform/*/k8s/` 为半成品**

无 namespace、无探针、无 resources、用 NodePort。与 `k8s/base/` 正式版本矛盾。

> 修复：0.5h。在每个旧目录下加 LEGACY_README.md。

### 序 6：中

**Prometheus static_configs 与 SD 冗余**

`spring-boot-static` job 使用硬编码 DNS，端口映射 `replacement: '${1}:${1}'` 的语法可疑（端口加两次？）。

> 修复：评估后决定。SD 正常工作时 static 冗余但不影响功能。SD 失效时 static 是兜底。

### 序 7：低

**Dockerfile 无 `.dockerignore`**

构建上下文含 `target/`、`.git/` 等大目录，build 慢、镜像可能含多余文件。

> 修复：可忽略。面试官通常不查 Dockerfile 细节。

### 序 8：低

**Promtail ConfigMap 源文件与实际运行不一致**

`k8s/base/monitoring/promtail-configmap.yaml` 中 `url` 仍为 ClusterIP，集群中已手动改为 Pod IP。源文件落后于部署。

> 修复：可忽略。但面试展示时应该指出："实验环境中由于 kube-proxy POST 转发问题，临时用 Pod IP 直连——ConfigMap 在集群中手动修改。"

---

## 第四部分：容灾恢复能力验证

| 故障场景 | 🟢🟡🔴 | 应对流程 | 暴露点 |
|----------|--------|---------|--------|
| worker 宕机 | 🟢 | Pod 自动重调度 | 无 PDB，单副本场景下临时不可用但不丢数据 |
| MySQL CrashLoopBackOff | 🟡 | `kubectl describe` + `kubectl logs --previous` 查 OOM/权限/配置 | 不会 `mysqlcheck` 验证数据完整性 |
| **Loki 磁盘写满** | **🔴** | 不知道 `retention_period` 配置项。compactor 满磁盘无法工作。Loki 拒绝新写入 | 记：`limits_config.retention_period: 168h` + 症状 `no space left on device` |
| **Calico 异常** | **🔴** | 不知道 `calicoctl` CLI。网络故障无法系统诊断 | 记三条：`calicoctl node status`、`kubectl get ippool`、`iptables-save | grep cali` |
| Harbor 不可用 | 🟢 | 现有镜像不受影响。新部署 ImagePullBackOff → `ctr images import` 手工导入 | 答得不错 |
| **etcd 损坏** | **🔴** | 单 master 无备份。etcd 损坏 = 集群全损 | 承认限制。话术："实验环境单 master 无 etcd 备份是已知约束。生产用 3 etcd 节点 + 定时 snapshot。" |

🔴 容灾盲区：**3/6**。故障处理自评 4.5 分实际约 **3.0 分**。

---

## 第五部分：最终裁决

### 项目状态：还需要 6-7 小时（修正自原 4 小时估算）

原估算过于乐观。修正后：

| # | 任务 | 耗时 | 类别 | 优先级 |
|---|------|------|------|--------|
| 1 | Secret 从 git 移除 / 加声明注释 | 0.5h | 致命 | **必须** |
| 2 | Grafana Alerting 口径收口 + 告警规则说明 + 触发验证话术 | 1.0h | 对齐 V1 告警实现 | **必须** |
| 3 | 截图（6张核心 + 标注说明） | 1.0h | 证据 | **必须** |
| 4 | NetworkPolicy 修复或话术准备 | 1.0h | Bug | **应该** |
| 5 | 复习 6 条 🔴 盲区话术 | 1.0h | 每个 10-15min | **应该** |
| 6 | 遗留 YAML 加 LEGACY 标记 | 0.5h | 消除混淆 | 可选 |
| **合计** | | **6.5h** | | |

**如果只能分配 3 小时：** Secret 占位说明 + Grafana Alerting 口径 + 截图（砍掉其他 3 项，但面试中需要承认 NetPol 的限制）

### 面试最大 3 个风险

1. **"Secret 里密码是 base64——这叫安全？"** → 主动承认 + Sealed Secrets 方案
2. **"告警怎么通知你的？"** → V1 用 Grafana Contact Point webhook 占位，生产/多集群引入 AlertManager HA
3. **"MySQL 只允许 auth——account 数据怎么存的？"** → 修复 NetPol 或准备话术

### 修完后，面试通过概率

| 岗位 | 概率 |
|------|------|
| 初级 DevOps / K8s 运维 | **85%** |
| 中级 DevOps / SRE | **60%** （缺 CI/CD、IaC） |
| 后端开发 + K8s 部署 | **80%** |
| 架构师 | **30%** （缺 HA、多租户、容量规划） |

### ROI 最高单一知识点

**Grafana Alerting 与 AlertManager 的边界。** 理由：V1 要讲清楚 Grafana 规则评估和 Contact Point；生产/多集群再讲 AlertManager 的分组、静默、去重和 HA gossip。

### 一句话结论

**你的项目比 80% 的学习项目完整，但有 3 条致命漏洞：Secret 在 Git、无告警链路、NetworkPolicy Bug。花 6 小时修掉，然后可以自信停下来。不再添加新功能——已是深度加固阶段。**

---

## 第六部分：补充维度（二次审计发现）

以下三个维度在初版审计中遗漏，现补充。

### 补充 1：简历描述可信度压力测试

第二轮评估中给出的简历描述，逐句验证：

| 简历语句 | 能自圆其说？ | 风险 |
|----------|-------------|------|
| "HPA 自动扩缩容（CPU 70% 阈值）" | 🟡 | HPA YAML 存在、metrics-server 运行，但 **从未压测过**。面试官问"触发过扩缩容吗"——你只能答"配置测过" |
| "4 个核心业务模块" | 🟢 | 微服务架构清晰，但数据是 Mock 的——面试时主动说明 |
| "22 份技术文档" | 🟢 | 确实存在，但有几份是"边做边记"而不是"整理后发布的"。面试时挑 21-logging-loki.md 和 22-loki-promtail-postmortem.md 作为代表作展示 |
| "NetworkPolicy 白名单 + PSA baseline" | 🟡 | 部署了但 NetPol 有个 bug（只允许 auth 连 MySQL），被发现会减分 |
| "Grafana 监控 + Loki 日志聚合" | 🟡 | Grafana 无 Screenshot、无告警规则，展示靠口述 |

**建议：** 面试时只展示 3 个你最熟悉的模块（推荐：Loki 排障 + HPA 配置 + NetworkPolicy 设计），其余模块作为"已知但不是我主导的"淡化处理。

### 补充 2：面试 Q&A 深度审计

抽查 interview-guide.md 中的 3 个 Q&A：

| Q&A | 深度 | 问题 |
|-----|------|------|
| "为什么用 ConfigMap/Secret？" | 🟢 背诵级 | 话术正确，但如果能加一句"ConfigMap 更新不会自动重启 Pod，我用的是 Reloader 或手动 rollout"会提升到理解级 |
| "为什么 resources requests/limits？" | 🟢 背诵级 | 话术正确。面试官追问"你 requests=100m 是怎么算出来的，还是猜的？"——你需诚实回答"学习环境中经验值，生产需要压测确定" |
| "如果 Pod 崩溃怎么办？" | 🟡 背诵级 | 回答正确但太短。加一个真实经历："在我们项目中，auth-service 曾因为 initialDelaySeconds 太短被 livenessProbe 误杀，后来调整到 120s 解决。这个教训我写了文档。"——这样从"背诵"升级到"理解" |

### 补充 3：30 分钟面试时间线规划

| 时间 | 阶段 | 你的策略 |
|------|------|---------|
| 0-2min | 项目概述 | 一句话："基于 Spring Boot 4 微服务的 K8s 云原生部署实践，涵盖从代码到集群的全链路" |
| 2-5min | 架构介绍 | 口头画架构图：Ingress → Services → 4 微服务 + MySQL → Prometheus/Grafana/Loki，边说边说明 Redis 与链路追踪是 V2 规划 |
| 5-10min | **引导面试官** | 主动抛出你最擅长的：**"这个项目最难忘的是 Loki 日志管道的排障——`cri: {}` 静默丢弃了所有日志行但没有任何报错，我花了一下午才定位到"**。面试官一定会追问这个问题——你已经准备好了完整话术 |
| 10-20min | 面试官追问 | 90% 概率追问：1) 你那个 Loki 问题具体怎么查出来的？2) 你们的安全怎么做的？3) 有没做过压测？——前两个你已经有话术，第 3 个诚实说"学习环境，未做" |
| 20-25min | 场景题 | 面试官可能会问："如果线上 MySQL Pod CrashLoopBackOff，你怎么排查？"——你的答案应包含：describe Pod → logs --previous → check PV/PVC → check resource limits → 如果数据坏了，PV 有 Retain 策略不丢数据 |
| 25-30min | 反问 | 问 1-2 个有质量的问题：**"你们团队 K8s 集群的 NetworkPolicy 是 deny-all 模型还是 allow-all？为什么选这种？"** 或 **"你们对 etcd 备份的频率和策略是什么？"**——这表明你理解 K8s 运维的核心痛点 |

### 本次审计修正清单

| 修正项 | 严重度 | 来源 |
|--------|--------|------|
| Scheduler 术语：predicates/priorities → Filter/Score | 🔴 | 技术过时，会被资深面试官识破 |
| Loki compactor：移除"去重"，纠正 boltdb-shipper 废弃时间 | 🔴 | 技术错误 |
| Calico MTU：1460/1472 → 1480/1440/1452 | 🟡 | 数值偏差，追问可能暴露 |
| Docker shim：补 v1.24 移除时间线 | 🟡 | 信息不完整 |
| Harbor GC：补 soft delete + blob 引用 + 在线 GC | 🟡 | 信息不完整 |
| Prometheus WAL：补 WAL segment 切换机制 | 🟡 | 描述不够精确 |
| 时间估算：4h → 6.5h | 🟡 | 估算乐观 |
| 补 3 个遗漏维度 | 🟡 | 结构性缺失 |
