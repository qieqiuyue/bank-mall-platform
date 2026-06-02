# 实施路线

## 阶段 0：资料整理与项目定名

状态：已完成

- 创建独立项目目录 `bank-mall-cloudnative`。
- 解压原始 `bank-digital-platform` 微服务代码。
- 明确项目包装方向：某银行电子商城云原生改造与 Kubernetes 实验集群部署实践。
- 建立文档区、脚本区、K8s 清单区和面试材料区。
- 根据当前电脑配置规划 1 master + 2 worker + 1 Harbor 的虚拟机实验拓扑。

## 阶段 1：基础服务可运行

状态：已完成

目标：跑通 4 个基础服务。

- 已检查并修复源码乱码。
- 已统一 Dockerfile 命名和构建方式。
- 已增强 4 个基础服务的业务接口。
- 已补充 API 清单和构建验证文档。
- 在 Linux 构建节点或容器构建环境中执行 Maven 打包。
- 编写基础验证命令。

交付物：

- 服务接口清单。
- Linux 构建命令。
- 容器镜像构建与 K8s 访问验证记录。

## 阶段 2：Kubernetes 基础部署

目标：让服务可以在 K8s 中稳定运行和访问。

- 准备 Linux 虚拟机实验环境。
- 安装 containerd、kubeadm、kubelet、kubectl。
- 初始化 Kubernetes 控制节点并加入 worker 节点。
- 安装 CNI 网络插件。
- 部署 Harbor 并创建 `bank-mall` 镜像项目。
- 整理 Deployment 和 Service。
- 增加 Namespace。
- 增加 ConfigMap。
- 增加 Secret。
- 增加 readinessProbe 和 livenessProbe。
- 增加 resources requests/limits。
- 通过 NodePort 或 Ingress 验证访问。

交付物：

- `k8s/base` 清单。
- 部署文档。
- curl 验证命令。

## 阶段 3：业务增强（数据持久化）

状态：V1 已完成（auth-service 接入 MySQL；account/payment/notification 保留 mock，数据库配置为 V2 预留）

目标：从 mock 数据演进为真实 MySQL 持久化。

- 部署 MySQL 8.0（Deployment + PV + PVC）。
- 创建 7 个数据库（auth/account/payment/notification/product/order/inventory）。
- auth-service 接入 JPA + MySQL，启动时自动建表并预置种子数据。
- 其他 3 个服务保留 mock 模式，MySQL 连接信息已就绪。

交付物：

- `k8s/base/mysql/` 存储 + 部署 + 服务 + InitDB 一览。
- auth-service JPA Entity / Repository / DataInitializer。
- 数据库连接配置（ConfigMap + Secret）。

## 阶段 4：监控体系搭建

状态：已完成

目标：搭建 Prometheus + Grafana 监控栈。

- 部署 Prometheus v2.53.0（静态 + K8s 服务发现）。
- 部署 Grafana 10.4.0（预置 datasource + 8-panel Dashboard）。
- 所有 4 个服务添加 Actuator + Micrometer Prometheus registry。
- Pod annotations 实现 Prometheus 自动发现抓取。
- Dashboard 面板：CPU / Memory / JVM GC / HTTP QPS / p99 延迟 / 服务存活。

交付物：

- `k8s/base/monitoring/` 全套清单。
- Grafana Dashboard JSON（Bank Mall - Service Overview）。
- 访问入口：Prometheus :30090，Grafana :30300。

## 阶段 5：CI/CD 流水线

状态：已完成

目标：从手工部署演进为自动化流水线。

- `scripts/ci.sh`：完整流水线（Maven → Docker Build → Push → K8s Apply → Verify）。
- `.github/workflows/ci.yml`：GitHub Actions 自动构建 + 测试（内网限制，推送部署由本地脚本完成）。
- `scripts/deploy.sh` 已更新支持 MySQL + 监控一键部署。

交付物：

- ci.sh 流水线脚本。
- GitHub Actions 工作流。
- 更新后的 deploy.sh。

## 阶段 6：业务增强（V2 新服务）

状态：规划中

目标：从“银行接口 demo”增强为“银行电子商城”。

- 增加 product-service。
- 增加 order-service。
- 增加 inventory-service。
- 梳理下单调用链：登录 -> 商品 -> 下单 -> 支付 -> 扣库存 -> 通知。
- 可选接入 MySQL 保存商品、订单和库存。

交付物：

- 新服务源码。
- 新服务镜像构建文件。
- 下单链路说明。

## 阶段 7：云原生能力增强

状态：V1 已完成（Ingress / HPA / Prometheus / Grafana / Loki / Promtail / Grafana Alerting），规划中（链路追踪 / AlertManager HA）

目标：体现实际工作中的 K8s 项目经验。

- ✅ 接入 Harbor 镜像仓库（阶段 3 已完成）。
- ✅ Ingress 统一入口（NodePort :30080，path rewrite 路由规则）。
- 增加 HPA 自动扩缩容（依赖 Prometheus metrics）。 ✅ 已完成 → `docs/19-hpa.md`
- ✅ Prometheus/Grafana 监控方案（阶段 4 已完成）。
- ✅ 增加日志采集方案（Loki + Promtail）。
- 增加链路追踪方案（OpenTelemetry + Jaeger）→ V2 规划，当前未部署。
- ✅ 故障排查手册（14-troubleshooting-handbook.md）。
- ✅ 架构图 + 监控说明 + 面试讲解稿。

交付物：

- 架构图（交互式 HTML）。
- 监控仪表盘。
- 故障排查手册。
- 面试讲解稿。

## 阶段 8：高可用集群方案

目标：能讲清楚 K8s 高可用部署方案；V1 只落地 1 master + 2 worker 实验集群，V2 再实践多 master。

- 设计 3 master + 2 worker + LB 的高可用拓扑。
- 梳理 kubeadm 高可用部署步骤。
- 说明 stacked etcd 和 external etcd 的差异。
- 设计 API Server VIP。
- 设计 etcd 备份恢复流程。
- 做节点故障场景说明。

交付物：

- 高可用部署文档。
- 故障演练记录。
- 面试追问题库。
