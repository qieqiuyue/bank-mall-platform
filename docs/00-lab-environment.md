# 实验环境说明

## 原则

这个项目应该被表述为一个基于 Linux 和 Kubernetes 的实战项目，而不是 Windows 本地部署项目。

Windows 只作为可选的工作站，用于编辑代码和整理文档。真正的项目经验应围绕 Linux 节点、Harbor 镜像仓库、容器镜像和 Kubernetes 工作负载来描述。

## 推荐实验拓扑

入门阶段拓扑：

| 节点 | 示例 IP | 角色 | 建议资源 |
| --- | --- | --- | --- |
| k8s-master01 | 10.0.0.31 | Kubernetes 控制平面 | 2 CPU / 4 GB RAM |
| k8s-worker01 | 10.0.0.41 | 业务工作节点 | 2 CPU / 4 GB RAM |
| k8s-worker02 | 10.0.0.42 | 业务工作节点 | 2 CPU / 4 GB RAM |
| harbor01 | 10.0.0.61 | Harbor 镜像仓库 | 2 CPU / 4 GB RAM |

用于面试表达的高可用拓扑：

| 节点类型 | 数量 | 组件 |
| --- | --- | --- |
| 负载均衡节点 | 2 | HAProxy + Keepalived，提供 API Server VIP |
| 控制平面节点 | 3 | kube-apiserver、scheduler、controller-manager、stacked etcd |
| 工作节点 | 2-3 | 业务 Pod、Ingress Controller、监控组件 |
| Harbor 节点 | 1 | 私有镜像仓库 |
| 存储节点 | 1+ | 入门可用 NFS，进阶可用 Longhorn/Ceph |

## 执行边界

| 操作 | 正确执行位置 |
| --- | --- |
| 编辑代码和文档 | 工作站 |
| 执行 Maven 打包 | Linux 构建节点或 CI Runner |
| 构建和推送镜像 | Linux 构建节点或 CI Runner |
| 应用 Kubernetes YAML | Kubernetes 控制节点 |
| 验证 Service/Ingress 访问 | Linux 节点或可访问集群的工作站 |
| 排查 Pod、Service、Event | Kubernetes 控制节点 |

## 面试表达口径

推荐说法：

> 我使用 Windows 工作站进行代码编辑和文档整理，实际的镜像构建、镜像推送和 Kubernetes 部署流程都是围绕 Linux 节点和 Kubernetes 集群设计与实践的。

避免说法：

> 我在 Windows 本地把 Kubernetes 项目部署起来了。
