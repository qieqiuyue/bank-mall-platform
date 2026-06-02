# 简历项目描述草稿

## 项目名称

某银行电子商城云原生改造与 Kubernetes 实验集群部署实践

## 项目描述

参考银行电子商城业务场景，基于 Spring Boot 微服务和 Kubernetes 搭建云原生部署实战环境。项目包含用户认证、账户查询、支付转账、通知服务等基础模块，并规划扩展商品、订单、库存等电子商城核心服务。通过 Docker 完成服务镜像构建，使用 Kubernetes Deployment、Service、ConfigMap、Secret、健康检查和资源限制完成服务部署与治理，并落地 Harbor 私有镜像仓库、Ingress 统一入口、HPA 自动扩缩容、Prometheus/Grafana 监控、Loki/Promtail 日志采集和 Grafana Alerting。多 master 高可用、Redis、OpenTelemetry/Jaeger 和 AlertManager HA 属于 V2 规划。

## 技术栈

Spring Boot、Docker、Kubernetes、containerd、Harbor、Ingress Nginx、ConfigMap、Secret、HPA、NetworkPolicy、PodSecurity、Prometheus、Grafana、Loki、Promtail、Linux、Shell

## 环境说明

项目代码和文档可以在个人工作站维护，实际项目经验口径以 Linux 节点和 Kubernetes 集群为准：在 Linux 构建节点完成 Maven 打包、Docker 镜像构建和 Harbor 推送，在 Kubernetes 控制节点完成 YAML 部署和资源排查。

## 项目职责/亮点

- 设计银行电子商城微服务拆分方案，梳理认证、账户、支付、通知、商品、订单、库存等模块边界。
- 编写 Dockerfile 完成 Spring Boot 服务镜像构建，并规划 Harbor 私有镜像仓库管理流程。
- 编写 Kubernetes Deployment 和 Service 清单，实现多服务容器化部署与集群内服务发现。
- 使用 ConfigMap 和 Secret 管理应用配置与敏感信息，降低配置与镜像耦合。
- 为服务增加 readinessProbe、livenessProbe 和资源限制，提升服务发布和运行稳定性。
- 设计 Ingress 统一入口方案，对外暴露业务服务访问路径。
- 设计 HPA 自动扩缩容方案，根据 CPU/内存指标提升服务弹性。
- 落地 Prometheus + Grafana 监控、Grafana Alerting 告警规则和 Loki/Promtail 日志采集，覆盖服务运行状态与日志排查。
- 梳理 Kubernetes 高可用集群拓扑，理解多 master、etcd、高可用负载均衡和故障恢复机制，作为 V2 生产化方案储备。

## 面试表达提示

这个项目要突出“我做过核心链路，并理解生产化改造点”：

- 已落地：微服务代码、Dockerfile、K8s Deployment/Service、ConfigMap/Secret、探针、资源限制、Ingress、HPA、监控、日志、Grafana 告警规则。
- 规划中：Redis、OpenTelemetry/Jaeger、AlertManager HA、多 master 高可用和 product/order/inventory 服务。
- 能讲清楚：V1 做到了什么、实验环境哪里简化、生产化改造需要怎么补。
