# Bank Mall Cloud-Native Platform

某城商行电子商城云原生平台 — 独立设计并交付的完整平台工程项目。

## 项目概述

以一个银行电子商城的真实业务场景为载体，从裸金属服务器搭建 Kubernetes 集群开始，完成微服务容器化、CI/CD 自动化流水线、GitOps 声明式部署、全栈可观测性体系（指标 / 日志 / 链路追踪）、安全策略加固，并开发核心微服务验证平台业务承载能力。

**定位**：平台工程 / SRE，非单纯的业务后端开发。

## 业务背景

某城商行电子渠道系统包含用户认证、账户管理、支付处理和消息通知等核心能力。传统部署方式依赖手工发布，存在版本不可控、扩容不灵活、故障影响范围大、日志和监控分散等问题。

本项目以该场景为背景，将系统拆分为多个 Spring Boot 微服务，基于 Kubernetes 进行容器化部署改造，逐步补齐镜像管理、服务发布、配置管理、弹性伸缩、监控告警、日志采集和安全加固能力。

## 技术栈

`Spring Boot 3.1` `Java 17` `Docker` `Kubernetes v1.36` `kubeadm` `containerd` `Calico` `Harbor` `Ingress Nginx` `Helm` `ArgoCD` `GitHub Actions` `GitLab CI` `Trivy` `Gitleaks` `Sealed Secrets` `Prometheus` `Grafana` `Loki` `Promtail` `Jaeger` `Velero` `MySQL`

## 平台能力总览

| 能力域 | 核心组件 | 说明 |
|--------|---------|------|
| **容器编排** | Kubernetes v1.36 (kubeadm) | 1 control plane + 2 worker，containerd 运行时，Calico CNI |
| **镜像管理** | Harbor | 私有镜像仓库，多阶段 Docker 构建，非 root 运行 |
| **流量管理** | Ingress Nginx | 统一入口，路径路由，DaemonSet + hostNetwork |
| **弹性伸缩** | HPA | CPU 70% 触发，min=1 max=3，scaleDown 冷却 5min |
| **CI/CD** | GitHub Actions + GitLab CI | 双平台分层验证：公网代码门禁 + 内网镜像安全交付 |
| **GitOps** | ArgoCD | 声明式部署，自动同步，selfHeal + prune |
| **应用配置** | Helm | dev / staging / prod 三环境 values 覆盖 |
| **安全扫描** | Trivy + Gitleaks | 镜像漏洞扫描 (hard gate) + 密钥泄露检测 (pre-commit + CI) |
| **凭证管理** | Sealed Secrets | GitOps 友好的加密 Secret，Git 中零明文 |
| **网络策略** | NetworkPolicy | deny-all + 白名单规则，最小权限通信 |
| **Pod 安全** | PodSecurity (baseline) | 禁止特权容器、hostPath 限制 |
| **监控告警** | Prometheus + Grafana | 基础设施指标 + 业务指标 (QPS/成功率/P99) + SLI/SLO |
| **日志聚合** | Loki + Promtail | 3 个命名空间日志采集，LogQL 查询 |
| **链路追踪** | Jaeger + OpenTelemetry | 跨服务调用链可视化，瓶颈定位 |
| **备份恢复** | Velero | MySQL 备份 + 删库恢复演练 |
| **通知** | 飞书 Bot | 交互卡片通知，CI/CD 状态 + 告警推送 |

## 微服务架构

| 服务 | 端口 | 职责 | 关键接口 |
|------|------|------|---------|
| **auth-service** | 8081 | 用户认证与授权 | `POST /login` `POST /validate` `GET /users/{id}` |
| **account-service** | 8082 | 账户管理与流水记录 | `GET /{id}/balance` `POST /{id}/debit` `POST /{id}/credit` |
| **payment-service** | 8083 | 支付处理与状态流转 | `POST /payments` `GET /payments/{id}` |
| **notification-service** | 8084 | 通知记录与发送 | `POST /notifications` `GET /notifications/templates` |

调用链路：`Ingress → payment-service → account-service (扣款/入账) → notification-service (通知)`

## 集群拓扑

| 节点 | 角色 | OS | 规格 |
|------|------|-----|------|
| k8s-master01 | Control Plane | Ubuntu 24.04 | 4C/8G |
| k8s-worker01 | Worker | Ubuntu 24.04 | 4C/8G |
| k8s-worker02 | Worker | Ubuntu 24.04 | 4C/8G |
| harbor01 | Harbor Registry | Ubuntu 24.04 | 2C/4G |

## 快速开始

```bash
# 本地开发（仅需 Docker）
docker-compose up -d    # 启动 MySQL

# 构建并推送镜像到 Harbor
bash scripts/build-images.sh

# 部署到 K8s
bash scripts/deploy.sh

# 一键 CI/CD（lint → test → build → scan → push → deploy → verify）
bash scripts/ci.sh
```

## 文档索引

| # | 文档 | 内容 |
|---|------|------|
| 00 | 项目概述 | 项目定位、业务背景、架构总览 |
| 01 | 快速开始 | 最小化环境搭建，5 分钟跑通 |
| 02 | 环境拓扑与规划 | 集群拓扑、网络规划、资源分配 |
| 03 | 集群搭建手册 | kubeadm + containerd + Calico 完整步骤 |
| 04 | 微服务开发指南 | 业务设计、数据模型、API 清单 |
| 05 | 容器化与镜像管理 | Dockerfile 多阶段构建 + Harbor |
| 06 | K8s 部署详解 | Deployment/Service/ConfigMap/Ingress/HPA |
| 07 | CI/CD 流水线 | GitHub Actions + 内网 CI + Trivy + Gitleaks |
| 08 | GitOps 与 Helm | ArgoCD + Helm 多环境管理 |
| 09 | 可观测性体系 | Prometheus + Grafana + Loki + Jaeger |
| 10 | 安全策略 | NetworkPolicy + PodSecurity + Sealed Secrets |
| 11 | 排障手册 | 常见问题 + 踩坑记录 + 诊断命令 |
| 12 | 设计决策日志 | 技术选型理由 + 架构权衡 |
| 13 | 故障演练复盘 | OOMKilled / NetworkPolicy / 慢调用 3 场景 |

## 项目状态

**当前阶段**：V1 基线 — 4 个微服务可运行 + K8s 全栈部署 + CI/CD 双平台闭环 + 全栈可观测 + 3 轮故障演练

后续规划（V2）：多 master 高可用集群、Argo Rollouts 灰度发布、Kyverno 自定义策略、OpenTelemetry 全量集成。

## 设计原则

- **平台交付视角**：关注“这个平台能承载什么业务”而非“这个业务怎么实现”
- **面试可追问**：每个技术决策背后都有明确的选型理由和权衡分析
- **诚实边界**：明确标注实验环境与生产方案的差异，不包装成真实生产系统
- **代码即文档**：配置文件注释充分，关键设计决策写入设计决策日志

---

**独立完成** | [GitHub](https://github.com/qianqiuyue/bank-mall-platform)
