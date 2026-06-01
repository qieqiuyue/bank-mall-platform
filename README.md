# Bank Mall Cloud-Native Platform

> 某城商行电子商城云原生平台 — 独立设计并交付的完整平台工程项目。
> **当前状态：S0 初始化中** | 架构已定稿，按 6 阶段执行计划增量交付。

![Java](https://img.shields.io/badge/Java_17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.2-6DB33F?logo=spring&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes_v1.36-326CE5?logo=kubernetes&logoColor=white)
![ArgoCD](https://img.shields.io/badge/ArgoCD-EF7B4D?logo=argo&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-F46800?logo=grafana&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-3DA639)

![S0](https://img.shields.io/badge/Phase-S0_Init-important)
![S1](https://img.shields.io/badge/Phase-S1_Business-inactive)
![S2](https://img.shields.io/badge/Phase-S2_Platform-inactive)
![S3](https://img.shields.io/badge/Phase-S3_CICD-inactive)
![S4](https://img.shields.io/badge/Phase-S4_Chaos-inactive)
![S5](https://img.shields.io/badge/Phase-S5_Polish-inactive)

---

## 项目定位

以银行电子商城为业务背景，从裸金属服务器搭建 Kubernetes 集群开始，完成微服务容器化、CI/CD 流水线、GitOps 声明式部署、全栈可观测性体系（指标 / 日志 / 链路追踪）、安全策略加固，并开发核心微服务验证平台承载能力。

**定位**：平台工程 / SRE — Java 代码是验证平台能力的手段，不是叙事主体。

本仓库按 `docs/execution-plan.md` 中定义的 6 阶段计划（S0-S5）增量构建。

## 业务背景

某城商行电子渠道系统包含用户认证、账户管理、支付处理和消息通知等核心能力。本项目将系统拆分为 4 个 Spring Boot 微服务，基于 Kubernetes 进行容器化部署改造，补齐镜像管理、弹性伸缩、监控告警、日志采集、链路追踪和安全加固能力。

## 技术栈

| 领域 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 17 LTS | 稳定优先 |
| 框架 | Spring Boot 3.2+ | RestClient 作为同步 HTTP 客户端 |
| 网关 | Ingress Nginx | K8s 原生，DaemonSet + hostNetwork |
| 服务发现 | K8s CoreDNS + Service | 无需额外注册中心 |
| 容器运行时 | containerd + Calico | CNI |
| 镜像仓库 | Harbor | 私有仓库 |
| CI/CD | GitHub Actions + 内网 ci.sh | 双平台分层验证 |
| GitOps | ArgoCD | auto-sync + selfHeal + prune |
| Secret 管理 | Sealed Secrets | GitOps 原生加密 |
| 链路追踪 | Jaeger all-in-one + Badger + PVC | 零外部依赖 |
| 指标监控 | Prometheus + Micrometer + Grafana | 基础设施 + 业务指标 |
| 日志聚合 | Loki + Promtail | 轻量级日志方案 |
| 安全扫描 | Semgrep + Trivy + Gitleaks | SAST + 镜像 + 密钥三重门禁 |
| 通知 | 飞书 Bot | CI/CD 状态 + 告警推送 |
| 数据库 | MySQL 8.0 | 每服务独立数据库 |
| 分布式事务 | 补偿逻辑 + 最终一致性 | 冲正 + 重试 + 人工兜底 |

## 微服务架构

| 服务 | 端口 | 职责 | 关键接口 |
|------|------|------|---------|
| **auth-service** | 8081 | 用户认证与授权 | `POST /login` `POST /validate` |
| **account-service** | 8082 | 账户管理与交易流水 | `GET /{id}/balance` `POST /{id}/debit` `POST /{id}/credit` |
| **payment-service** | 8083 | 支付处理与补偿 | `POST /payments` `GET /payments/{id}` |
| **notification-service** | 8084 | 通知记录 | `POST /notifications` |

调用链路：`Ingress → payment-service → account-service (debit/credit) → notification-service`

## 集群拓扑

| 节点 | 角色 | IP | OS | 规格 |
|------|------|-----|-----|------|
| k8s-master01 | Control Plane | 10.0.0.31 | Ubuntu 24.04 | 4C/8G |
| k8s-worker01 | Worker | 10.0.0.41 | Ubuntu 24.04 | 4C/8G |
| k8s-worker02 | Worker | 10.0.0.42 | Ubuntu 24.04 | 4C/8G |
| harbor01 | Harbor Registry | 10.0.0.61 | Ubuntu 24.04 | 2C/4G |

> ⚠️ 实验环境（VMware NAT）。网络约束：中国内地 GFW，K8s 组件用阿里云镜像源。

## 项目结构

```text
bank-mall-platform/
├── bank-digital-platform/         # Spring Boot 微服务源码
│   ├── pom.xml                    #   父 POM
│   ├── auth-service/              #   端口 8081
│   ├── account-service/           #   端口 8082
│   ├── payment-service/           #   端口 8083
│   └── notification-service/      #   端口 8084
├── k8s/                           # Kubernetes 清单
│   └── base/
│       ├── mysql/                 #   StatefulSet + PV/PVC
│       ├── *-service/             #   4 服务 Deployment + Service + HPA
│       ├── ingress/               #   Ingress Nginx
│       ├── monitoring/            #   Prometheus / Grafana / Loki
│       ├── security/              #   NetworkPolicy + PodSecurity
│       └── sealed-secrets/        #   加密 Secret
├── helm/                          # Helm Charts
├── scripts/                       # 运维脚本
├── .github/workflows/             # GitHub Actions CI
├── docs/                          # 技术文档
├── Makefile
├── ROADMAP.md
├── CONTRIBUTING.md
└── LICENSE
```

## 执行阶段

| 阶段 | 焦点 | 状态 | 关键产出 |
|------|------|------|---------|
| **S0** | 平台抢救与验证 | 🔵 进行中 | 集群恢复、SB 3.2 验证、工程基础 |
| **S1** | 业务最小闭环 | ⚪ 规划中 | auth JWT、account/payment/notification 服务 |
| **S2** | 平台能力矩阵 | ⚪ 规划中 | ArgoCD、Jaeger、Grafana、Sealed Secrets |
| **S3** | 双平台 CI/CD | ⚪ 规划中 | GitHub Actions + 内网 ci.sh |
| **S4** | 故障演练与压测 | ⚪ 规划中 | 3 次故障复盘、JMeter 报告 |
| **S5** | 润色与包装 | ⚪ 规划中 | Swagger、Helm、面试材料 |

详见 `docs/execution-plan.md`。

## Git 提交约定

| 前缀 | 用途 | 前缀 | 用途 |
|------|------|------|------|
| `[INIT]` | 项目初始化 | `[OBS]` | 可观测性 |
| `[FEAT]` | 新功能 | `[TRACE]` | 链路追踪 |
| `[FIX]` | Bug 修复 | `[SEC]` | 安全加固 |
| `[TEST]` | 测试 | `[DEPLOY]` | 部署 |
| `[CI]` | CI/CD | `[CHAOS]` | 故障演练 |
| `[API]` | API 文档 | `[PERF]` | 性能测试 |
| `[DOC]` | 文档 | `[MIGRATE]` | 代码迁移 |

## 快速开始

```bash
# 当前状态（S0 初始化中）
# 完整启动需要：4 台 Ubuntu 24.04 VM + Kubernetes v1.36 集群 + Harbor

# S0 完成后可用：
make help          # 查看所有可用目标
make preflight     # 部署前验证
make smoke-test    # 烟雾测试
make ci            # 一键内网 CI/CD
```

## 文档索引

| 文档 | 内容 |
|------|------|
| `docs/execution-plan.md` | S0-S6 完整执行计划 |
| `docs/04-architecture.md` | 架构图与组件说明 |
| `ROADMAP.md` | 能力状态、V2 规划、技术取舍 |
| `CONTRIBUTING.md` | 提交约定、分支策略、PR 流程 |

---

**独立完成** | [GitHub](https://github.com/qieqiuyue/bank-mall-platform) | 实验环境，非生产系统
