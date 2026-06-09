# 项目学习路径

> 适合：面试准备 / 新人接手 / 快速回忆

---

## 新手入门（30 分钟）

按顺序读：

| 顺序 | 文档 | 回答什么问题 |
|:---:|------|-------------|
| 1 | `README.md` | 这项目是什么、技术栈是什么 |
| 2 | `ROADMAP.md` | 项目分了几个阶段、每阶段做了什么 |
| 3 | `docs/13-design-decisions.md` | 为什么选 containerd 不选 Docker、为什么用 Calico 不用 Flannel、RestClient 超时策略怎么定的 |
| 4 | `CONTRIBUTING.md` | 怎么提代码、commit 格式、分支命名、代码审查红线 |

读完这四篇，你能回答面试官："项目架构是怎么设计的，有什么关键技术决策。"

---

## 动手跑起来（30 分钟）

```bash
# 1. 集群在哪
ssh root@10.0.0.31   # master01
ssh root@10.0.0.61   # harbor01（构建节点）

# 2. 看集群状态
kubectl get nodes
kubectl get pods -n bank-mall
kubectl get pods -n jaeger
kubectl get pods -n monitoring
kubectl get hpa -n bank-mall

# 3. 端到端验证
cd ~/bank-mall-platform
bash scripts/smoke-test.sh    # 四链冒烟
bash scripts/verify.sh        # 全量验证（审计修复 30 项）
```

| 服务 | 入口 | 被测接口 |
|------|------|---------|
| Auth | `http://10.0.0.41:30080/auth/api/auth/login` | POST |
| Account | `http://10.0.0.41:30080/account/api/accounts/A1001/balance` | GET |
| Payment | `http://10.0.0.41:30080/payment/api/payments` | POST |
| Notification | `http://10.0.0.41:30080/notification/api/notifications?accountNo=A1001` | GET |

---

## 理解架构（1 小时）

按领域深入：

| 领域 | 对应文件/文档 |
|------|-------------|
| **Java 微服务** | 4 个 `apps/*/src/main/java/com/bank/<service>/` |
| **跨服务通信** | `payment-service/.../client/AccountClient.java` + `PaymentService.java`（补偿事务） |
| **共享代码** | `apps/common-lib/`（ApiResponse + ErrorCode + BusinessException） |
| **K8s 部署** | `infra/kubernetes/base/` — 每服务一个目录、MySQL、Ingress、监控、安全 |
| **CI/CD** | `.github/workflows/ci.yml`（5 job） + `scripts/ci.sh`（harbor01 一键） |
| **可观测性** | Prometheus `:30090`、Grafana `:30300`、Loki `:30310` |
| **网络策略** | `infra/kubernetes/base/security/` — deny-all → whitelist 13 条规则 |

---

## 面试准备（1 小时）

| 主题 | 对应材料 |
|------|---------|
| 架构决策 | `docs/13-design-decisions.md` |
| 踩坑记录 | `docs/14-troubleshooting-handbook.md` |
| 面试 Q&A | `docs/interview/interview-qa.md`（29 题） |
| 三分钟版本 | `docs/interview/interview-script.md` |
| 审计成果 | `docs/production-readiness-checklist.md`（部署前 21 项检查） |

面试话术骨架：

> "我在 4 台 VMware 虚拟机上搭建了一套银行电商云原生平台，包含 4 个 Spring Boot 微服务、MySQL 数据库、Jenkins-less CI/CD 流水线、全链路可观测性（Prometheus + Grafana + Loki + Jaeger）、NetworkPolicy 零信任网络模型和 ArgoCD GitOps 交付。项目经过两轮深度审计——五轮交叉验证 + 主审架构师灾难预演——修复了 30 项安全和工程缺陷。"

---

## 日常运维（速查）

| 操作 | 命令 |
|------|------|
| 全量验证 | `bash scripts/verify.sh` |
| 冒烟测试 | `bash scripts/smoke-test.sh` |
| 构建推送 | harbor01: `make push` |
| 集群部署 | master01: `kubectl apply -f infra/kubernetes/base/` |
| 回滚快照 | `kubectl get all -n bank-mall -o yaml > /tmp/backup-$(date +%Y%m%d-%H%M).yaml` |
| 查 HPA | `kubectl get hpa -n bank-mall` |
| 查日志 | `kubectl logs -n bank-mall deploy/<service>` |
| 数据库备份 | master01: `bash scripts/db-backup.sh` |

---

## 已知坑（避免反复踩）

| 坑 | 现象 | 修复 |
|----|------|------|
| Calico VM 重启断裂 | Jaeger 跨节点超时 | `kubectl delete pod -n kube-system -l k8s-app=calico-node` |
| Harbor HTTP | `ctr pull` 失败 | 必须加 `--plain-http` |
| `docker system prune -a -f` | 镜像被清 | 推完再 prune |
| Maven `settings.xml` 假阿里云 | GFW 阻断 | URL 是 `maven.aliyun.com/repository/public`，不是 `repo.maven.apache.org` |
| Jaeger Ingress 502 | rewrite 冲突 | 已修：独立 Ingress 资源无 rewrite-target |
| 加新 Maven 模块 | CI 挂 | 见 CLAUDE.md Known Pitfalls #9 |

---

**最后更新**: 2026-06-09 | 审计修复完成后
