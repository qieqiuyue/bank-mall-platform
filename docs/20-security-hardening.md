# 安全加固部署文档

> 时间：2026年第4周 | 目标：为 K8s 集群添加网络隔离和 Pod 安全策略

---

## 一、为什么需要安全加固？

### 面试怎么问

| 面试问题 | 没有加固的回答 | 有加固的回答 |
|---------|--------------|------------|
| "Pod 之间能互相访问吗？" | "能，默认都是通的" | "默认拒绝，只放行明确允许的流量。NetworkPolicy 白名单模型" |
| "容器以什么用户运行？" | "root，默认就是 root" | "每个容器都设了 runAsUser，Spring Boot 用 UID 1000，MySQL 用 UID 999，全部 drop ALL capabilities" |
| "怎么防止特权容器？" | "没想过" | "Namespace 加了 PSA 标签，enforce: baseline 阻止特权容器，audit/warn: restricted 记录违规" |

### 安全模型

```
                    ┌──────────────────────────────────────────────┐
                    │              Internet / Client                │
                    └──────────────────┬───────────────────────────┘
                                       │ NodePort 30080
                    ┌──────────────────▼───────────────────────────┐
                    │        Ingress Nginx (ingress-nginx ns)       │
                    │        只允许 → bank-mall 服务端口              │
                    └──────────────────┬───────────────────────────┘
                                       │
            ┌──────────────────────────┼──────────────────────────┐
            │                          │                          │
    ┌───────▼──────┐         ┌────────▼────────┐        ┌───────▼──────┐
    │ auth-service  │ ──────→ │     mysql       │        │  其他服务     │
    │ :8081         │ :3306   │ :3306           │        │ :8082-8084   │
    └──────────────┘         └─────────────────┘        └──────────────┘
            │                          ▲
            │ Egress: mysql:3306       │ Ingress: auth→mysql
            └──────────────────────────┘

    ┌──────────────────────────────────────────────────┐
    │                   Egress: DNS                     │
    │   所有 Pod → kube-dns:53 (UDP/TCP)              │
    └──────────────────────────────────────────────────┘

    ┌──────────────────────────────────────────────────┐
    │              Monitoring → Metrics                 │
    │   monitoring ns → bank-mall:8081-8084            │
    └──────────────────────────────────────────────────┘
```

---

## 二、NetworkPolicy 详解

### 2.1 默认拒绝（Zero Trust）

```yaml
# deny-all.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: bank-mall-deny-all
  namespace: bank-mall
spec:
  podSelector: {}          # 选择所有 Pod
  policyTypes:
  - Ingress               # 拒绝所有入站
  - Egress                # 拒绝所有出站
```

**关键概念：** `podSelector: {}` 表示选择命名空间内所有 Pod。`policyTypes: [Ingress, Egress]` 表示默认拒绝所有入站和出站流量。任何未明确允许的流量都被阻断。

### 2.2 允许 Ingress → 服务

```yaml
# allow-ingress.yaml
ingress:
- from:
  - namespaceSelector:
      matchLabels:
        name: ingress-nginx    # 只允许 ingress-nginx 命名空间
  ports:
  - port: 8081               # auth-service
  - port: 8082               # account-service
  - port: 8083               # payment-service
  - port: 8084               # notification-service
```

**注意：** `namespaceSelector` 通过标签选择命名空间，所以 ingress-nginx 命名空间必须有 `name: ingress-nginx` 标签。

### 2.3 允许 auth-service → MySQL

```yaml
# allow-mysql.yaml
spec:
  podSelector:
    matchLabels:
      app: mysql              # 目标是 mysql Pod
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: auth-service    # 只允许 auth-service
    ports:
    - port: 3306              # MySQL 端口
```

**设计决策：** 只有 auth-service 需要连 MySQL（JPA），其他 3 个服务用 mock 数据，不需要连数据库。

### 2.4 允许 DNS 解析

```yaml
# allow-dns.yaml
egress:
- to:
  - namespaceSelector: {}
    podSelector:
      matchLabels:
        k8s-app: kube-dns     # CoreDNS
  ports:
  - port: 53
    protocol: UDP
  - port: 53
    protocol: TCP
```

**为什么需要这条？** 默认拒绝所有 Egress 后，Pod 无法做 DNS 解析（服务发现依赖 DNS），所有服务名查询都会失败。必须放行 kube-dns。

### 2.5 允许服务 → MySQL（Egress）

```yaml
# allow-services-egress.yaml
egress:
- to:
  - podSelector:
      matchLabels:
        app: mysql
  ports:
  - port: 3306
```

### 2.6 允许监控 → Metrics

```yaml
# allow-monitoring.yaml
ingress:
- from:
  - namespaceSelector:
      matchLabels:
        name: monitoring
  ports:
  - port: 8081
  - port: 8082
  - port: 8083
  - port: 8084
```

---

## 三、PodSecurity 详解

### 3.1 Pod Security Standards

| 级别 | 说明 | 适合场景 |
|------|------|---------|
| privileged | 不限制 | 系统组件、需要特权的 Pod |
| baseline | 禁止明显危险的配置 | 大多数应用 |
| restricted | 最严格 | 安全要求高的应用 |

### 3.2 Namespace PSA Labels

```yaml
# namespace-psa.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: bank-mall
  labels:
    pod-security.kubernetes.io/enforce: baseline   # 违规 Pod 直接拒绝
    pod-security.kubernetes.io/audit: restricted    # 违规记录审计日志
    pod-security.kubernetes.io/warn: restricted     # kubectl apply 时警告
```

**为什么 enforce 用 baseline 而不是 restricted？**

MySQL 容器的 entrypoint 需要 `chown` 操作，`restricted` 级别会阻止。`baseline` 允许 `fsGroup` 和 `runAsUser`，但阻止特权容器和 hostPath 挂载等危险配置。

### 3.3 容器 securityContext

#### Spring Boot 服务（UID 1000）

```yaml
spec:
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
    seccompProfile:
      type: RuntimeDefault
  containers:
  - name: auth-service
    securityContext:
      allowPrivilegeEscalation: false
      capabilities:
        drop:
        - ALL
```

**为什么用 UID 1000？** Spring Boot 是普通 Java 应用，不需要 root。UID 1000 是常见的非特权用户 ID。

#### MySQL（UID 999）

```yaml
spec:
  securityContext:
    fsGroup: 999           # 文件系统组，确保数据目录可写
    seccompProfile:
      type: RuntimeDefault
  containers:
  - name: mysql
    securityContext:
      runAsNonRoot: true
      runAsUser: 999       # mysql 用户的 UID
      runAsGroup: 999
      allowPrivilegeEscalation: false
      capabilities:
        drop:
        - ALL
```

**为什么 MySQL 不在 pod 级别设 `runAsNonRoot`？** MySQL 的 entrypoint 脚本需要先以 root 运行 `chown` 数据目录，然后切换到 mysql 用户。如果 pod 级别设 `runAsNonRoot`，init container（如果有）无法运行。我们只设容器级别的 `runAsNonRoot`。

**为什么用 `fsGroup: 999`？** `fsGroup` 确保挂载的存储卷文件属于 GID 999，这样 mysql 用户（UID 999, GID 999）可以读写 `/var/lib/mysql`。

#### Grafana（UID 472）

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 472      # grafana 官方镜像的 UID
  runAsGroup: 472
  fsGroup: 472
  seccompProfile:
    type: RuntimeDefault
```

#### Prometheus（UID 65534）

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 65534    # nobody 用户
  runAsGroup: 65534
  fsGroup: 65534
  seccompProfile:
    type: RuntimeDefault
```

---

## 四、文件清单

### 新增文件

| # | 文件 | 说明 |
|---|------|------|
| 1 | `k8s/base/security/deny-all.yaml` | 默认拒绝所有入站+出站流量 |
| 2 | `k8s/base/security/allow-ingress.yaml` | 允许 ingress-nginx → 服务端口 |
| 3 | `k8s/base/security/allow-mysql.yaml` | 允许 auth-service → mysql:3306 |
| 4 | `k8s/base/security/allow-monitoring.yaml` | 允许 Prometheus → metrics 端口 |
| 5 | `k8s/base/security/allow-services-egress.yaml` | 允许服务 → mysql:3306 出站 |
| 6 | `k8s/base/security/allow-dns.yaml` | 允许所有 Pod → kube-dns:53 |
| 7 | `k8s/base/security/namespace-psa.yaml` | bank-mall namespace PSA 标签 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `k8s/base/{auth,account,payment,notification}-service/deployment.yaml` | 加 securityContext (UID 1000, drop ALL) |
| `k8s/base/mysql/deployment.yaml` | 加 securityContext (fsGroup 999 + container UID 999) |
| `k8s/base/monitoring/grafana-deployment.yaml` | 加 securityContext (UID 472) |
| `k8s/base/monitoring/prometheus-deployment.yaml` | 加 securityContext (UID 65534) |
| `k8s/base/monitoring/namespace.yaml` | 加 `name: monitoring` 标签 |
| `k8s/base/ingress/controller-rbac.yaml` | 加 `name: ingress-nginx` 标签 |
| `scripts/deploy.sh` | 步骤从 10 → 12，加 PSA labels 和 NetworkPolicy |

---

## 五、部署步骤

### 5.1 部署 PodSecurity Labels

```bash
# 更新 namespace（加 PSA 标签）
kubectl apply -f k8s/base/security/namespace-psa.yaml

# 给其他命名空间加 NetworkPolicy 需要的标签
kubectl label namespace ingress-nginx name=ingress-nginx --overwrite
kubectl label namespace monitoring name=monitoring --overwrite
```

### 5.2 部署 NetworkPolicy

```bash
kubectl apply -f k8s/base/security/

# 验证
kubectl get networkpolicy -n bank-mall
```

### 5.3 更新 Deployment（securityContext）

```bash
kubectl apply -f k8s/base/mysql/deployment.yaml
kubectl apply -f k8s/base/auth-service/deployment.yaml
kubectl apply -f k8s/base/account-service/deployment.yaml
kubectl apply -f k8s/base/payment-service/deployment.yaml
kubectl apply -f k8s/base/notification-service/deployment.yaml
kubectl apply -f k8s/base/monitoring/
```

### 5.4 验证

```bash
# 所有 Pod 应该 Running
kubectl get pods -n bank-mall -o wide
kubectl get pods -n monitoring -o wide

# 服务健康检查
curl -s http://10.0.0.41:30080/auth/actuator/health
curl -s http://10.0.0.41:30080/account/actuator/health

# 登录测试（验证 auth-service 能连 MySQL）
curl -s -X POST http://10.0.0.41:30080/auth/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'

# PSA 警告检查
kubectl apply -f k8s/base/mysql/deployment.yaml --dry-run=server
# 如果有警告，会显示 PodSecurity 违规信息
```

---

## 六、踩坑记录

### 6.1 deny-all 导致服务不通

**现象：** `kubectl apply -f deny-all.yaml` 后服务全部不通。

**根因：** deny-all 拒绝所有流量，但没有部署相应的 allow 规则。

**解决：** 确保 `kubectl apply -f k8s/base/security/` 一次性部署所有 NetworkPolicy，或按顺序先部署 allow 规则再部署 deny-all。

### 6.2 NetworkPolicy 不生效

**现象：** 加了 NetworkPolicy 但 Pod 还能互相访问。

**根因：** NetworkPolicy 需要 CNI 插件支持。Calico 支持，但 flannel 不支持。如果 CNI 不支持，NetworkPolicy 相当于不存在。

**验证：** 我们用 Calico，支持 NetworkPolicy：
```bash
kubectl get pods -n kube-system | grep calico
# calico-node-xxx 应该在 Running
```

### 6.3 namespaceSelector 不匹配

**现象：** ingress-nginx 到 bank-mall 的流量被拒绝。

**根因：** ingress-nginx 命名空间没有 `name: ingress-nginx` 标签，`namespaceSelector` 匹配不上。

**解决：**
```bash
kubectl label namespace ingress-nginx name=ingress-nginx --overwrite
```

### 6.4 MySQL Pod 无法启动（runAsNonRoot + entrypoint）

**现象：** MySQL Pod CrashLoopBackOff，日志显示权限错误。

**根因：** MySQL 官方镜像的 entrypoint 需要先以 root 运行 `chown` 数据目录。Pod 级别设 `runAsNonRoot: true` 会阻止 entrypoint 运行。

**解决：** Pod 级别只设 `fsGroup: 999`（确保存储卷可写），容器级别设 `runAsNonRoot: true + runAsUser: 999`（运行时以 mysql 用户运行）。

### 6.5 DNS 解析失败

**现象：** 服务之间 `svc.cluster.local` 无法解析。

**根因：** deny-all 阻断了 Egress，包括 DNS 查询（UDP/TCP 53）。

**解决：** 添加 `allow-dns-egress.yaml`，放行所有 Pod → kube-dns:53。

---

## 七、面试要点

| 面试问题 | 回答要点 |
|---------|---------|
| "K8s 默认网络策略是什么？" | 默认允许所有 Pod 间通信（同命名空间可互通，跨命名空间也可互通）。必须显式设置 NetworkPolicy 才能限制 |
| "NetworkPolicy 的工作原理？" | 基于标签选择器匹配 Pod，支持 Ingress（入站）和 Egress（出站）规则。需要 CNI 插件支持（Calico 支持，flannel 不支持） |
| "白名单模型怎么实现？" | 先部署 deny-all（拒绝所有），再逐条添加 allow 规则。比黑名单更安全——只放行已知合规的流量 |
| "Pod Security Standards 有哪几个级别？" | privileged（不限制）、baseline（禁止危险配置如特权容器）、restricted（最严格，要求 non-root、drop capabilities 等） |
| "为什么 enforce 用 baseline 而不是 restricted？" | MySQL 的 entrypoint 需要 chown 操作，restricted 会阻止。baseline 足以阻止特权容器和 hostPath 挂载等 |
| "securityContext 的 pod 级别和容器级别有什么区别？" | pod 级别是默认值，对所有容器生效；容器级别可以覆盖 pod 级别的设置。对 MySQL 这种 entrypoint 需要 root 的，pod 级别不设 runAsNonRoot，容器级别设 |
| "runAsUser 和 fsGroup 的区别？" | runAsUser 是进程运行的身份；fsGroup 是挂载卷的文件系统组，确保 Pod 可以读写挂载的存储 |
| "capabilities: drop ALL 有什么用？" | 移除所有 Linux capabilities（如 NET_ADMIN、SYS_PTRACE 等），只保留进程运行必需的最小权限 |

---

> 本文档配合 `docs/14-troubleshooting-handbook.md`（故障排查）、`docs/19-hpa.md`（HPA）一起阅读。