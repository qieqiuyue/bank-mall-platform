> **⚠️ ARCHIVED** — 本文档为 V1 单控制面实验阶段简历描述，已过期。
> Live 版本 [`resume-final.md`](./resume-final.md) 反映 5 轮审计整改 + S6 生产化补齐 + V2 规划中（多 master HA / DR / 灰度 / 策略即代码 / 云迁移均为设计文档，代码待实现）的最终状态。
>
> ---

# 简历项目描述（V1 archived）

## 项目名称

某银行电子商城云原生改造与 Kubernetes 实验集群部署实践

## 一句话总结

参考银行电子商城业务场景，基于 Spring Boot 微服务和 Kubernetes 搭建云原生部署实战环境，完成微服务容器化、K8s 部署、配置管理、健康检查、弹性伸缩和监控告警方案设计。

## 技术栈

Spring Boot、Docker、Kubernetes、containerd、Harbor、Ingress Nginx、ConfigMap、Secret、HPA、NetworkPolicy、PodSecurity、Prometheus、Grafana、Loki、Promtail、Jaeger、ArgoCD、Helm、Swagger/OpenAPI、GitHub Actions、Sealed Secrets、Linux、Shell

## 项目背景

某银行原有电子渠道系统包含用户认证、账户查询、商城商品、订单支付、交易通知等业务能力。传统部署方式依赖手工发布，存在版本不可控、扩容不灵活、故障影响范围大、日志和监控分散等问题。

本项目以该业务场景为背景，将系统拆分为多个 Spring Boot 微服务，并基于 Kubernetes 进行容器化部署改造。项目重点不是简单把服务跑起来，而是围绕企业实际交付中常见的问题，逐步补齐镜像管理、服务发布、配置管理、弹性伸缩、监控告警、日志采集和安全加固能力。V1 是单控制面实验集群；高可用集群、链路追踪和更多业务服务属于后续规划。

## 项目职责/亮点（已落地）

- 设计银行电子商城微服务拆分方案，梳理认证、账户、支付、通知、商品、订单、库存等模块边界。
- 编写 Dockerfile 完成 Spring Boot 服务镜像构建，并接入 Harbor 私有镜像仓库管理流程。
- 编写 Kubernetes Deployment 和 Service 清单，实现多服务容器化部署与集群内服务发现。
- 使用 ConfigMap 和 Secret 管理应用配置与敏感信息，降低配置与镜像耦合。
- 为服务增加 readinessProbe、livenessProbe 和资源限制，提升服务发布和运行稳定性。
- 设计 Ingress 统一入口方案，对外暴露业务服务访问路径。
- 设计 HPA 自动扩缩容方案，根据 CPU/内存指标提升服务弹性。
- 落地 Prometheus + Grafana 监控、Grafana Alerting 告警规则和 Loki/Promtail 日志采集，覆盖服务运行状态与日志排查。
- 梳理 Kubernetes 高可用集群拓扑，理解多 master、etcd、高可用负载均衡和故障恢复机制，作为 V2 生产化方案储备。

## 项目职责/亮点（设计中）

- 生产环境补齐 Harbor 镜像安全扫描、镜像签名和更严格的凭据管理。
- 生产环境使用 nodeAffinity、StorageClass 和多副本替代实验环境中的固定 `nodeName` 与 hostPath。
- 落地分布式链路追踪方案（OpenTelemetry + Jaeger），定位跨服务调用延迟。
- 规划 Redis 缓存与 AlertManager HA，分别用于热点数据和多集群告警治理。
- 规划 CI/CD 流水线，实现代码提交到生产部署的自动化。

## 技术架构

```
用户 → Ingress Nginx → auth-service / account-service / payment-service / notification-service
                                          ↓
                                    Kubernetes Deployment + Service + ConfigMap + Secret
                                          ↓
                              Harbor (镜像仓库) + Calico (CNI) + containerd (运行时)
```

## 环境说明

项目代码和文档在个人工作站维护，实际项目经验口径以 Linux 节点和 Kubernetes 集群为准：在 Linux 构建节点完成 Maven 打包、Docker 镜像构建和 Harbor 推送，在 Kubernetes 控制节点完成 YAML 部署和资源排查。

## 面试表达边界

**推荐说法：**

> 参考银行电子商城业务场景，搭建了一套 Kubernetes 云原生部署实战环境，完成 Spring Boot 微服务容器化、K8s 部署、配置管理、健康检查、弹性伸缩和监控告警方案设计。

**避免说法：**

> 我独立负责了某银行生产系统上线。

**更稳妥的说法：**

> 模拟企业真实场景 / 学习实战项目 / 完成核心链路并设计生产化改造方案。

## 可量化的成果

- 4 个 Spring Boot 微服务全部容器化并部署到 Kubernetes
- 镜像构建时间从 5 分钟优化到 2 分钟（多阶段构建）
- 服务启动时间从 45 秒优化到 28 秒（JVM 参数调优 + 探针设计）
- 配置文件与镜像解耦，环境切换零代码改动
- 故障恢复时间从手动 10 分钟缩短到自动 30 秒（探针自动重启）

## 项目规模

- 4 个 Spring Boot 微服务，41 个单元测试
- 28 份技术文档 + 3 份故障复盘
- 4 台 VMware VM（1 master + 2 worker + 1 harbor）
- 500+ 配置项（K8s manifests + CI/CD pipelines + monitoring dashboards）
- 200+ 次故障排障经验（GFW/containerd/Calico/Loki/Jaeger）

> 以上数据来自 `project-resume.md`（已合并），保留在本文档中。

## 关键词（用于简历搜索优化）

微服务、Spring Boot、Docker、Kubernetes、K8s、云原生、DevOps、容器化、Harbor、Ingress、ConfigMap、Secret、HPA、Prometheus、Grafana、Loki、Promtail、NetworkPolicy、PodSecurity、Linux
