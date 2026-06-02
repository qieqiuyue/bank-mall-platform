# Bank Mall Cloud-Native Platform

> 某城商行电子商城云原生平台 — 独立设计并交付的完整平台工程项目。
> **当前状态：S1 CP1 完成** | 账户服务重写 + 认证服务 JWT 改造已部署上线。

![Java](https://img.shields.io/badge/Java_21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.0.6-6DB33F?logo=spring&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes_v1.36-326CE5?logo=kubernetes&logoColor=white)
![ArgoCD](https://img.shields.io/badge/ArgoCD-EF7B4D?logo=argo&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?logo=prometheus&logoColor=white)
![Grafana](https://img.shields.io/badge/Grafana-F46800?logo=grafana&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-3DA639)

![S0](https://img.shields.io/badge/Phase-S0_Complete-success)
![S1](https://img.shields.io/badge/Phase-S1_CP1_Done-success)
![S2](https://img.shields.io/badge/Phase-S2_Platform-inactive)
![S3](https://img.shields.io/badge/Phase-S3_CICD-inactive)
![S4](https://img.shields.io/badge/Phase-S4_Chaos-inactive)
![S5](https://img.shields.io/badge/Phase-S5_Polish-inactive)

---

## 项目定位

以银行电子商城为业务背景，从裸金属服务器搭建 Kubernetes 集群开始，完成微服务容器化、CI/CD 流水线、GitOps 声明式部署、全栈可观测性体系（指标 / 日志 / 链路追踪）、安全策略加固，并开发核心微服务验证平台承载能力。

**定位**：平台工程 / SRE — Java 代码是验证平台能力的手段，不是叙事主体。

## 技术栈

| 领域 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 21 LTS | LTS 支持到 2031 |
| 框架 | Spring Boot 4.0.6 | Spring Framework 7.0，RestClient 为同步 HTTP 客户端 |
| 网关 | Ingress Nginx | K8s 原生，DaemonSet + hostNetwork |
| 服务发现 | K8s CoreDNS + Service | 无需额外注册中心 |
| 容器运行时 | containerd + Calico | CNI |
| 镜像仓库 | Harbor | 私有仓库，HTTP plain-http |
| CI/CD | GitHub Actions + 内网 ci.sh | 双平台分层验证 |
| GitOps | ArgoCD | S2 部署 |
| Secret 管理 | Sealed Secrets | S2 部署 |
| 链路追踪 | Jaeger all-in-one + Badger + PVC | S2 部署 |
| 指标监控 | Prometheus + Micrometer + Grafana | 基础设施 + 业务指标 |
| 日志聚合 | Loki + Promtail | 轻量级日志方案 |
| 安全扫描 | Semgrep + Trivy + Gitleaks | S3 集成 |
| 通知 | 飞书 Bot | S3 集成 |
| 数据库 | MySQL 8.0 | 每服务独立数据库 |
| 分布式事务 | 补偿逻辑 + 最终一致性 | S1 CP2 实现 |

## 微服务架构

| 服务 | 端口 | 状态 | 关键接口 |
|------|------|:---:|---------|
| **auth-service** | 8081 | ✅ SB 4.0.6, BCrypt + JWT | `POST /login` `POST /validate` `GET /health` |
| **account-service** | 8082 | ✅ JPA + Flyway, 乐观锁 + 幂等 | `GET /{id}/balance` `POST /{id}/debit` `POST /{id}/credit` `POST /{id}/reverse` |
| **payment-service** | 8083 | ⚪ V1 mock | `POST /payments` (S1 CP2 重写) |
| **notification-service** | 8084 | ⚪ V1 mock | `POST /notifications` (S1 CP3 重写) |

调用链路：`Ingress → payment-service → account-service (debit/credit) → notification-service`

## 集群拓扑

| 节点 | 角色 | IP | OS | 规格 |
|------|------|-----|-----|------|
| k8s-master01 | Control Plane | 10.0.0.31 | Ubuntu 24.04 | 2C/4G |
| k8s-worker01 | Worker | 10.0.0.41 | Ubuntu 24.04 | 2C/5G |
| k8s-worker02 | Worker | 10.0.0.42 | Ubuntu 24.04 | 2C/5G |
| harbor01 | Harbor Registry | 10.0.0.61 | Ubuntu 24.04 | 2C/6G |

> ⚠️ 实验环境（VMware NAT 10.0.0.0/24）。网络约束：中国内地 GFW，K8s 组件用阿里云镜像源，Docker Hub / ghcr.io 不可达（ghcr.io 用 ghcr.nju.edu.cn 镜像）。

## 项目结构

```text
bank-mall-platform/
├── apps/                           ← 业务应用源码
│   ├── auth-service/               ← SB 4.0.6 + JDK 21, BCrypt + JWT ✅
│   ├── account-service/            ← JPA + Flyway + 乐观锁 + 幂等 ✅
│   ├── payment-service/            ← S1 CP2 重写
│   └── notification-service/       ← S1 CP3 重写
├── libs/common/                    ← 共享库（S2 填充）
├── infra/                          ← 基础设施即代码
│   ├── kubernetes/base/            ← K8s 清单（Deployment/Service/Ingress/监控/安全/HPA）
│   ├── kubernetes/argocd/          ← ArgoCD Application（S2）
│   ├── helm/                       ← Helm Charts（S5）
│   └── dashboards/                 ← Grafana Dashboard JSON（S2）
├── pipelines/                      ← CI/CD 工具配置（S3）
├── .github/workflows/              ← GitHub Actions CI
├── scripts/                        ← 运维脚本（build-images / deploy / smoke-test / ci）
├── docs/                           ← 30+ 份技术文档
├── sql/initdb/                     ← MySQL 建库脚本
├── Makefile
├── ROADMAP.md
├── CONTRIBUTING.md
└── LICENSE
```

## 执行阶段

| 阶段 | 焦点 | 状态 | 关键产出 |
|------|------|:---:|---------|
| **S0** | 平台抢救与前置验证 | ✅ 完成 | 集群恢复、SB 4.0.6 验证、项目 C 初始化、Makefile |
| **S1 CP1** | 账户 + 认证服务 | ✅ 完成 | account-service JPA 重写、auth-service BCrypt+JWT 改造，26 测试，K8s 部署 |
| **S1 CP2** | 支付 + 跨服务调用 | 🔵 下一步 | payment-service、RestClient 调用 account、补偿逻辑 |
| **S1 CP3** | 通知 + 全链路 | ⚪ 规划中 | notification-service、端到端验证 |
| **S2** | 平台能力矩阵 | ⚪ 规划中 | ArgoCD、Jaeger、Grafana、Sealed Secrets |
| **S3** | 双平台 CI/CD | ⚪ 规划中 | GitHub Actions + 内网 ci.sh |
| **S4** | 故障演练与压测 | ⚪ 规划中 | 3 次故障复盘、JMeter 报告 |
| **S5** | 润色与包装 | ⚪ 规划中 | Swagger、Helm、面试材料 |

## S0 前置验证（2026-06-02）

| # | 验证项 | 结果 | 影响 |
|---|--------|:---:|------|
| 1 | `curl https://github.com`（4 VM） | ✅ | ArgoCD 走公网 Git |
| 2 | `curl ghcr.io`（4 VM） | ❌ | 用 `ghcr.nju.edu.cn` 替代 |
| 3 | SB 4.0.6 + RestClient 编译 + auth-service 启动 | ✅ | 3.1.3 → 4.0.6，JDK 17 → 21，H2 profile 绕过 MySQL |

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
# 构建单个服务
cd apps/auth-service
mvn clean package -DskipTests

# H2 内存库快速验证（不依赖 MySQL）
java -jar target/auth-service-1.0.0.jar --spring.profiles.active=h2

# 运行测试
mvn test

# 一键操作
make help          # 查看所有可用目标
make build         # 构建所有服务
make test          # 运行所有测试
make smoke-test    # 烟雾测试
make ci            # 一键内网 CI/CD
```

## 文档索引

| 文档 | 内容 |
|------|------|
| `docs/execution-plan.md` | S0-S6 完整执行计划 |
| `docs/execution-record.md` | 项目演进实录（决策偏离、踩坑、面试素材） |
| `docs/04-build-and-verify.md` | 构建与验证命令 |
| `docs/10-actual-progress.md` | 实际操作记录（14 个阶段的完整踩坑） |
| `docs/26-final-verification-checklist.md` | 最终验收清单 |
| `ROADMAP.md` | 能力状态、V2 规划、技术取舍 |
| `CONTRIBUTING.md` | 提交约定、分支策略、PR 流程 |

---

**独立完成** | [GitHub](https://github.com/qieqiuyue/bank-mall-platform) | 实验环境，非生产系统
