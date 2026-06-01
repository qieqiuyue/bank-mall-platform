# 6 阶段执行计划

> 总工时约 215h + 缓冲。策略：S0/S1/S2/S4 必须完美，S3/S5/S_DOC 及格就行。

---

## S0：平台抢救 + 前置验证（~15h）

| 任务 | 时间 | 产出 |
|------|------|------|
| 启动 4 台 VM，验证 K8s 集群节点状态 | 2h | `kubectl get nodes` 全部 Ready |
| 修复 Calico 挂起后遗症 | 2h | 所有 Pod Running |
| 验证 MySQL / Harbor / Ingress / Prometheus / Grafana / Loki | 3h | 全组件健康检查截图 |
| Spring Boot 3.2 升级验证 | 4h | auth-service 编译 + 启动正常 |
| 项目初始化 | 3h | README、Makefile、ROADMAP、CONTRIBUTING |
| 截图基准留存 | 1h | 证据链 |

**三个前置验证（不过后续全白做）**：
1. `curl https://github.com` → ArgoCD 用公网还是 Gitea
2. `curl ghcr.io` → 镜像推送用 ghcr.io 还是 ACR
3. Spring Boot 3.2 + RestClient 编译 + 启动

---

## S1：业务最小闭环（~47h，3 个 Checkpoint）

### CP1：account-service + auth-service JWT（18h）
- JPA 实体：Account + Transaction（`@Version` 乐观锁）
- Flyway 迁移：`bank_account` 库
- API：GET balance / debit / credit / reverse
- `@RestControllerAdvice` 统一异常处理
- auth-service：明文密码 → BCrypt，token → JWT 无状态

### CP2：payment-service + 补偿逻辑（19h）
- JPA 实体：Payment + PaymentTransaction
- RestClient 配置：连接 2s + 读取 3s
- 跨服务调用：payment → account
- 补偿：try-catch + reverse 冲正 + 3 次重试 + ERROR_MANUAL_REVIEW

### CP3：notification-service + 全链路验证（10h）
- JPA 实体：Notification，Flyway 迁移：`bank_notification`
- 全链路：`POST /api/payments` → 余额变化 → 流水落库 → 通知落库

---

## S2：平台能力矩阵（~56h）

| 能力域 | 任务 | 时间 |
|--------|------|------|
| GitOps | ArgoCD 部署 + Application CR + auto-sync | 12h |
| 可观测性 | Grafana 业务看板 + SLI/SLO + 告警 | 12h |
| 链路追踪 | Jaeger all-in-one + Badger + PVC + OTEL Agent | 6h |
| 凭证安全 | Sealed Secrets controller + kubeseal | 4h |
| 安全加固 | PDB + ResourceQuota + LimitRange | 4h |
| 工程基础 | Dockerfile 多阶段 + Maven 父 POM | 10h |
| 安全扫描 | NetworkPolicy + PodSecurity | 8h |

---

## S3：双平台 CI/CD（~29h）

| 层 | 任务 | 时间 |
|----|------|------|
| 公网 GitHub Actions | Gitleaks → Semgrep → mvn test → Trivy（hard gate）→ 飞书 | 10h |
| 内网 ci.sh | mvn package → docker build → push Harbor → Trivy（soft gate）→ kubectl set image | 8h |
| Gitleaks 阻断案例 | 故意提交假密码 → 被拦截 → 修复 → 截图 + 文档 | 6h |
| CI 集成验证 | 内网端到端部署 | 5h |

---

## S4：故障演练 + 压测（~30h）

| 场景 | 制造方式 | 排查手段 |
|------|---------|---------|
| JMeter 压测 | 并发 50/100/200 → `POST /api/payments` | Grafana + `kubectl top` |
| 故障 1：OOMKilled | account-service 设极低 memory limit | Loki + Prometheus |
| 故障 2：NetworkPolicy | 拒绝 payment→account ingress | `kubectl describe netpol` |
| 故障 3：慢调用 | account-service `Thread.sleep(2000)` | Jaeger UI |
| 复盘 ×3 | 现象→根因→修复→MTTR→预防 | 文档 |

---

## S5：润色包装（~26h）

| 任务 | 时间 |
|------|------|
| Swagger（springdoc-openapi） | 4h |
| Helm Chart（dev/staging/prod） | 4h |
| 高可用架构设计文档 | 4h |
| ROADMAP + README 终稿 | 5h |
| 面试材料（简历话术 + Q&A + 故障案例） | 5h |
| Redis 幂等设计文档 | 2h |
| Gitleaks 阻断案例文档 | 2h |

---

## S6：缓冲加分

| 优先级 | 项目 | 时间 |
|--------|------|------|
| 1 | Velero 备份恢复演示 | 4h |
| 2 | Argo Rollouts 灰度发布 | 6h |
| 3 | Kyverno 自定义策略 | 6h |

---

## 已知风险

| 风险 | 缓解 |
|------|------|
| S1 Java 踩坑远超预期 | 3 checkpoint 设计，先单服务再跨服务 |
| Spring Boot 3.2 breaking change | S0 用 auth-service 先验证 |
| ArgoCD 不可达公网 Git | S0 就测 curl github.com |
| 面试官质疑项目真实性 | VMware 截图 + IP 规划 + 排障记录 |

---

## 设计原则：有意的不完美

不是偷懒，是展示你理解"够用"和"生产级"的差距。在代码中主动标注：

- RestClient 配置类 Javadoc 注明选型理由和升级路径
- Dockerfile 注释当前 hostPath，生产应改为 StorageClass + PV
- K8s Deployment 注释当前单副本，生产最小 2 副本 + PDB
- ROADMAP.md 明确列出"不做"的项 + 理由

---

**来源**：技术文档 V1.0.0 | **最后更新**：2026-06-02
