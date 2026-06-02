# CI/CD + MySQL + Prometheus 组合拳部署文档

> 时间：2026年第2-3周 | 目标：从"学习 demo"升级到"可演示的云原生项目"

---

## 一、背景与动机

### 之前的状态

- 4 个 Spring Boot 服务全部使用 mock 数据，重启即丢失
- 没有监控，看不到 CPU/内存/JVM/接口 QPS
- 部署全靠手工：Maven → Docker build → push → kubectl apply，每一步人工执行
- 面试时能说"代码跑通了"，但经不起追问

### 现在的状态

| 能力 | 之前的回答 | 现在的回答 |
|------|----------|-----------|
| 数据持久化 | "用 mock 数据演示" | "MySQL StatefulSet，auth-service 已接入 JPA，3 个种子用户" |
| 监控 | "还没加" | "Prometheus + Grafana，8 面板 Dashboard，CPU/Memory/JVM GC/HTTP QPS/p99 延迟" |
| 部署方式 | "手动执行命令" | "`bash scripts/ci.sh` 一键流水线，GitHub Actions 自动构建+测试" |

---

## 二、架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                        CI/CD Pipeline                            │
│                                                                  │
│  GitHub Actions (.github/workflows/ci.yml)                      │
│  ├── build-and-test: Maven compile + test (4 服务并行)           │
│  ├── docker-build: Docker build (4 服务并行)                     │
│  └── deploy: 提醒内网运行 scripts/ci.sh                          │
│                                                                  │
│  本地流水线 (scripts/ci.sh, 在 Harbor 节点执行)                  │
│  Stage 1 → Maven Build (4 服务)                                  │
│  Stage 2 → Docker Build & Push to Harbor                         │
│  Stage 3 → K8s Apply (namespace → secrets → MySQL → apps)       │
│  Stage 4 → Deploy Apps + Restart                                 │
│  Stage 5 → Deploy Monitoring + Smoke Test                       │
└─────────────────────────────────────────────────────────────────┘

┌───────────────────────┐    ┌──────────────────────────────────┐
│    MySQL 8.0          │    │    Prometheus + Grafana           │
│    (bank-mall ns)      │    │    (monitoring ns)                │
│                        │    │                                   │
│  ┌──────────────────┐ │    │  Prometheus :30090                │
│  │ bank_auth        │ │    │  ├── spring-boot job (4 targets)  │
│  │ bank_account     │ │    │  └── RBAC: ClusterRole for SD     │
│  │ bank_payment     │ │    │                                   │
│  │ bank_notification│ │    │  Grafana :30300                   │
│  │ bank_product     │ │    │  ├── Datasource → Prometheus      │
│  │ bank_order       │ │    │  └── Dashboard: 8 panels          │
│  │ bank_inventory   │ │    │                                   │
│  └──────────────────┘ │    └──────────────────────────────────┘
│                        │
│  PV: /data/mysql (10Gi)│
│  PVC → hostPath        │
│  User: bankapp         │
│  InitDB: 7 databases   │
└───────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│    4 个 Spring Boot 服务 (bank-mall ns)                           │
│                                                                   │
│  auth-service :8081     account-service :8082                     │
│  ├── JPA + MySQL 接入了  ├── Actuator + Micrometer                │
│  ├── 3 种子用户          ├── /actuator/prometheus                 │
│  └── /actuator/prometheus└── prometheus.io annotations             │
│                                                                   │
│  payment-service :8083   notification-service :8084               │
│  ├── Actuator + Micrometer├── Actuator + Micrometer                │
│  └── prometheus.io annotations└── prometheus.io annotations        │
└──────────────────────────────────────────────────────────────────┘
```

---

## 三、文件清单

### 新增文件

| # | 文件 | 所属模块 | 说明 |
|---|------|---------|------|
| 1 | `k8s/base/mysql/storage.yaml` | MySQL | PV (hostPath 10Gi) + PVC |
| 2 | `k8s/base/mysql/secret.yaml` | MySQL | 凭据 (base64): root/bankapp |
| 3 | `k8s/base/mysql/initdb-configmap.yaml` | MySQL | 启动时创建 7 个数据库 + 用户授权 |
| 4 | `k8s/base/mysql/deployment.yaml` | MySQL | MySQL 8.0 + 探针 + 资源限制 |
| 5 | `k8s/base/mysql/service.yaml` | MySQL | ClusterIP :3306 |
| 6 | `k8s/base/monitoring/namespace.yaml` | 监控 | `monitoring` 命名空间 |
| 7 | `k8s/base/monitoring/prometheus-rbac.yaml` | 监控 | SA + ClusterRole (pod SD) + RoleBinding |
| 8 | `k8s/base/monitoring/prometheus-configmap.yaml` | 监控 | scrape_configs: spring-boot-static + K8s SD |
| 9 | `k8s/base/monitoring/prometheus-deployment.yaml` | 监控 | Prometheus v2.53.0 |
| 10 | `k8s/base/monitoring/prometheus-service.yaml` | 监控 | NodePort :30090 |
| 11 | `k8s/base/monitoring/grafana-configmap.yaml` | 监控 | Datasource + Dashboard (8 panels) |
| 12 | `k8s/base/monitoring/grafana-deployment.yaml` | 监控 | Grafana 10.4.0 |
| 13 | `k8s/base/monitoring/grafana-service.yaml` | 监控 | NodePort :30300 |
| 14 | `scripts/ci.sh` | CI/CD | 5 阶段全流水线 |
| 15 | `.github/workflows/ci.yml` | CI/CD | GitHub Actions 自动构建+测试 |

### 新增 Java 源文件

| # | 文件 | 说明 |
|---|------|------|
| 16 | `auth-service/.../entity/User.java` | JPA Entity，映射 `users` 表 |
| 17 | `auth-service/.../repository/UserRepository.java` | JPA Repository，findByUsername/findByUserId |
| 18 | `auth-service/.../config/DataInitializer.java` | 启动时预置 3 个种子用户 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `auth-service/pom.xml` | 加 JPA + MySQL + Actuator + Micrometer |
| `auth-service/application.yml` | 加 datasource + JPA + management 配置 |
| `auth-service/.../AuthController.java` | 从 mock 改为 JPA 查询 + 内存 token |
| `account-service/pom.xml` | 加 Actuator + Micrometer |
| `payment-service/pom.xml` | 加 Actuator + Micrometer |
| `notification-service/pom.xml` | 加 Actuator + Micrometer |
| `account/payment/notification-service/application.yml` | 加 management 端点配置 |
| `k8s/base/configmap.yaml` | 加 DB_HOST/DB_PORT/DB_NAME_* |
| `k8s/base/secret.yaml` | 加 DB_USERNAME/DB_PASSWORD/MYSQL_* |
| `k8s/base/*/deployment.yaml` (4个) | 加 `prometheus.io/*` annotations |
| `scripts/deploy.sh` | 加 MySQL [1/8] + 监控 [8/8] 步骤 |

---

## 四、文件同步

> 你在 Windows 上编辑了代码和 YAML，需要先把它们传到 Linux VM 上才能执行。
> 三种方式任选其一，详见 `docs/17-file-transfer.md`。

### 方式一：Git（推荐）

```bash
# 在 Harbor 节点执行（构建+推送）
cd ~
git clone https://github.com/<你的用户名>/bank-mall-cloudnative.git
cd bank-mall-cloudnative
```

### 方式二：scp

```powershell
# 在 Windows PowerShell 执行
# 传整个项目到 Harbor 节点
scp -r C:\LearningResources\k8s项目\bank-mall-cloudnative user@10.0.0.61:~/

# 或只传修改过的文件到 master 节点
scp C:\LearningResources\k8s项目\bank-mall-cloudnative\k8s\base\mysql\* user@10.0.0.31:~/bank-mall-cloudnative/k8s/base/mysql/
scp C:\LearningResources\k8s项目\bank-mall-cloudnative\k8s\base\monitoring\* user@10.0.0.31:~/bank-mall-cloudnative/k8s/base/monitoring/
scp C:\LearningResources\k8s项目\bank-mall-cloudnative\scripts\ci.sh user@10.0.0.61:~/bank-mall-cloudnative/scripts/
```

### 方式三：VMware 共享文件夹

```bash
# 在 Harbor 或 master 节点直接操作
cd /mnt/hgfs/k8s项目/bank-mall-cloudnative
```

> ⚠️ **重要：** 从 Windows 传上去的 `.sh` 脚本需要转换换行符：
> ```bash
> sudo apt install -y dos2unix
> dos2unix ~/bank-mall-cloudnative/scripts/*.sh
> chmod +x ~/bank-mall-cloudnative/scripts/*.sh
> ```

---

## 五、部署步骤

### 5.1 前置条件

```bash
# 在 worker 节点创建 MySQL 数据目录
sudo mkdir -p /data/mysql
sudo chown 999:999 /data/mysql    # MySQL 容器 UID

# Docker 登录 Harbor
docker login 10.0.0.61 -u admin -p <HARBOR_PASSWORD>
```

### 5.2 方式一：全流水线（推荐）

```bash
# 在 Harbor 节点执行（需同时有 docker、maven、kubectl）
cd ~/bank-mall-cloudnative
bash scripts/ci.sh
```

流水线输出示例：
```
===[ Stage 1/5: Maven Build ]===
[PASS] auth-service built
[PASS] account-service built
...
===[ Stage 5/5: Deploy Monitoring ]===
...
Smoke test: auth-service health...
Forwarding from 127.0.0.1:18081 -> 8081
Forwarding from [::1]:18081 -> 8081
Handling connection for 18081
[PASS] auth-service is healthy

===[ Pipeline Complete in 81s ]===

  Prometheus:  http://<node-ip>:30090
  Grafana:     http://<node-ip>:30300
  Dashboard:   Bank Mall - Service Overview
```

### 5.3 方式二：分步部署

```bash
# Step 1: 构建并推送镜像（在 Harbor 节点）
REGISTRY=10.0.0.61 NAMESPACE=bank-mall VERSION=1.0.0 PUSH=true bash scripts/build-images.sh

# 或逐个构建：
cd ~/bank-mall-cloudnative/bank-digital-platform
for svc in auth-service account-service payment-service notification-service; do
  cd ${svc}
  docker build -t 10.0.0.61/bank-mall/${svc}:1.0.0 .
  docker push 10.0.0.61/bank-mall/${svc}:1.0.0
  cd ..
done

# Step 2: 手动预拉镜像到 worker（Harbor HTTP 模式需此步骤）
# 在每台 worker 节点（worker01 + worker02）上逐条执行：
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> \
  10.0.0.61/bank-mall/auth-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> \
  10.0.0.61/bank-mall/account-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> \
  10.0.0.61/bank-mall/payment-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> \
  10.0.0.61/bank-mall/notification-service:1.0.0

# 第三方镜像（MySQL/Grafana/Prometheus）用 Docker Hub 原始名 + IfNotPresent，无需预拉
# 但如果节点上没有这些镜像，需要先 docker pull 再 ctr import：
# MySQL 示例（在 worker 节点执行）：
docker pull mysql:8.0
docker save mysql:8.0 | gzip > /tmp/mysql.tar.gz
sudo ctr -n k8s.io images import /tmp/mysql.tar.gz
# Grafana 示例：
docker pull grafana/grafana:10.4.0
docker save grafana/grafana:10.4.0 | gzip > /tmp/grafana.tar.gz
sudo ctr -n k8s.io images import /tmp/grafana.tar.gz
# Prometheus 示例：
docker pull prom/prometheus:v2.53.0
docker save prom/prometheus:v2.53.0 | gzip > /tmp/prometheus.tar.gz
sudo ctr -n k8s.io images import /tmp/prometheus.tar.gz

# Step 3: 部署（在 master 节点）
cd ~/bank-mall-cloudnative
bash scripts/deploy.sh
```

### 5.4 部署顺序说明

`deploy.sh` 的 apply 顺序是精心设计的：

```
[0/8] namespace        ← 必须先存在
[1/8] MySQL secrets    ← Deployment 启动前需要
[2/8] MySQL storage + deployment + service
      ↓ wait MySQL Ready
[3/8] ConfigMap        ← 应用依赖
[4/8] auth-service     ← 依赖 MySQL
[5/8] account-service
[6/8] payment-service
[7/8] notification-service
[8/8] monitoring       ← Prometheus 需要抓取应用 metrics
      kubectl create namespace monitoring  ← 先创建 namespace 避免字母序竞争
```

---

## 六、验证

### 6.1 MySQL

```bash
kubectl exec -n bank-mall deploy/mysql -- mysql -u bankapp -p<DB_PASSWORD> \
  -e "SHOW DATABASES;" 2>/dev/null
# bank_auth, bank_account, bank_payment, bank_notification,
# bank_product, bank_order, bank_inventory

kubectl exec -n bank-mall deploy/mysql -- mysql -u bankapp -p<DB_PASSWORD> \
  -e "SELECT username, user_id, level FROM bank_auth.users;"
# admin | U1001 | GOLD
# vip01 | U1002 | PLATINUM
# tester| U1003 | SILVER
```

### 6.2 Prometheus

```bash
# 访问 Prometheus
curl http://10.0.0.31:30090/api/v1/targets | python3 -m json.tool | grep -A2 '"job"'

# 验证 Spring Boot metrics
curl http://10.0.0.31:30090/api/v1/query?query=up | python3 -m json.tool
```

### 6.3 Grafana Dashboard

```bash
# 浏览器访问
http://10.0.0.31:30300

# 默认账号: admin / <GRAFANA_PASSWORD>
# Dashboard: Bank Mall - Service Overview
```

Dashboard 面板说明：

| 面板 | 指标 | 面试价值 |
|------|------|---------|
| Pod CPU Usage | `rate(system_cpu_usage)` | 能看到各服务 CPU 使用趋势 |
| Pod Memory Usage | `jvm_memory_used_bytes` | JVM heap/non-heap 分区域显示 |
| JVM GC Pause Time | `rate(jvm_gc_pause_seconds_sum)` | 能判断 GC 是否正常 |
| HTTP Request Rate | `rate(http_server_requests_seconds_count)` | 能看到每个接口的 QPS |
| HTTP Response Time (p99) | `histogram_quantile(0.99, ...)` | 能看到 99 分位延迟 |
| Service Up/Down | `up` | 服务存活状态 |
| JVM Thread Count | `jvm_threads_live_threads` | 线程数趋势 |
| Pod Count | `count(up)` | Running 服务总数 |

### 6.4 CI/CD 验证

```bash
# 本地流水线
bash scripts/ci.sh
# 期望: 5 个 Stage 全部 PASS，最后打印访问入口

# GitHub Actions
# 推送到 GitHub → Actions tab → Bank Mall CI/CD workflow 自动触发
# build-and-test: 4 个 job 并行，各自 mvn compile + test
# docker-build: 4 个 job 并行构建镜像
```

---

## 七、关键技术决策

### 7.1 为什么用 Deployment 而不是 StatefulSet 部署 MySQL？

| 方案 | 优点 | 缺点 |
|------|------|------|
| Deployment + PVC | 简单，不需要 StorageClass | 不能按序扩缩容，Pod 名不固定 |
| StatefulSet + volumeClaimTemplates | 生产标准，Pod 名固定 | 需要 StorageClass 动态 PV 创建 |

**选择 Deployment 的原因：** 学习环境只有 hostPath，没有 StorageClass。用 StatefulSet 需要额外安装 local-path-provisioner。Deployment 更直观，面试时能解释"学习环境用 Deployment，生产用 StatefulSet"反而加分。

### 7.2 为什么 Prometheus 用静态配置 + K8s SD 两套？

```yaml
# job 1: spring-boot (K8s Service Discovery)
# 自动发现带有 prometheus.io/scrape: "true" annotation 的 Pod

# job 2: spring-boot-static (静态 targets)
# 手动列出 4 个 Service DNS，作为 fallback
```

**原因：**
- K8s SD 需要 RBAC（ClusterRole + ClusterRoleBinding），如果 RBAC 没配好会静默失败
- 静态 target 不依赖 RBAC，作为保险手段
- 两套同时运行会产生重复数据，但在学习环境可接受（Prometheus 自动去重）

### 7.3 为什么 GitHub Actions 不能完成完整 CD？

```
GitHub Actions Runner (云端)
         │
         ╳  防火墙 / NAT
         │
  10.0.0.61 (Harbor)  ← 内网 IP，公网不可达
  10.0.0.31 (K8s API) ← 内网 IP，公网不可达
```

**解决方案分层：**

| 方案 | 适用场景 |
|------|---------|
| `bash scripts/ci.sh` 在内网节点手动运行 | 当前学习环境 |
| GitHub Actions + self-hosted runner (VMware 内部) | 小团队内网 CI |
| Harbor 暴露公网 + kubectl 通过 VPN 访问 | 企业混合云 |
| 全部上云（ACK/EKS + ACR/ECR） | 生产环境 |

### 7.4 Spring Boot 应用如何暴露 Prometheus metrics？

三步走：
1. **pom.xml** 加 `micrometer-registry-prometheus`
2. **application.yml** 加 `management.endpoints.web.exposure.include: prometheus`
3. **Deployment** 加 pod annotations：
   ```yaml
   annotations:
     prometheus.io/scrape: "true"
     prometheus.io/path: /actuator/prometheus
     prometheus.io/port: "8081"
   ```

验证：
```bash
kubectl exec -n bank-mall deploy/auth-service -- \
  curl -s http://localhost:8081/actuator/prometheus | head -20
```

---

## 八、踩坑记录

### 8.1 DB 凭据不一致

**现象：** auth-service 启动时报 `Access denied for user 'bank_user'@'...'`

**根因：** `bank-mall-secret` 中 `DB_USERNAME` 是 `bank_user`，`mysql-secret` 和 `initdb-configmap` 创建的用户是 `bankapp`。

**解决：** 审计后统一为 `bankapp` / `<DB_PASSWORD>`，并改为 base64 编码存储。

### 8.2 Prometheus K8s 服务发现静默失败

**现象：** Prometheus Targets 页面中 `spring-boot` job 为 0/0 targets。

**根因：** Prometheus 使用 `kubernetes_sd_configs` 需要跨命名空间访问 K8s API，`serviceAccountName: default` 没有所需 RBAC。

**解决：** 新增 `prometheus-rbac.yaml`（ServiceAccount + ClusterRole + ClusterRoleBinding）。

### 8.3 CI/CD 脚本 namespace 顺序

**现象：** `ci.sh` 在干净集群上运行时 MySQL apply 失败——namespace `bank-mall` 不存在。

**根因：** 原始脚本在 Stage 3 先 apply MySQL，Stage 4 才创建 namespace。

**解决：** 审计后将 namespace/configmap/secret 创建移到 MySQL 之前。

### 8.4 ci.yml 部署步骤缺少闭合引号

**现象：** GitHub Actions deploy job 日志报 `syntax error`。

**根因：** 多行字符串中第 92 行 `echo "... Argo)` 缺少闭合双引号。

**解决：** 补上闭合引号。

### 8.5 AuthController null 安全问题

**现象：** 登录时偶尔 500 错误，`NullPointerException`。

**根因：** `body.get("username")` 返回 null 时传给 `findByUsername(null)` 触发 JPA 异常；`Map.of()` 不接受 null 值。

**解决：** 改用 `body.getOrDefault("username", "")` + `LinkedHashMap` 构建响应。

### 8.6 characterEncoding=utf8mb4 不合法

**现象：** auth-service CrashLoopBackOff，日志报 `Invalid charset name: utf8mb4`

**根因：** Spring Boot datasource URL 中的 `characterEncoding` 参数值必须是 Java charset 名（`UTF-8`），不是 MySQL charset 名（`utf8mb4`）。

**解决：** `characterEncoding=utf8mb4` → `characterEncoding=UTF-8`

### 8.7 MySQL livenessProbe 太激进

**现象：** MySQL Pod 反复 CrashLoopBackOff，日志显示"ready for connections"后被杀。

**根因：** MySQL 首次启动（尤其有 InitDB 创建 7 个数据库时）需要 90-120 秒，但 livenessProbe 在 60 秒后开始检测，3 次失败就杀掉进程。

**解决：** 完全移除 MySQL 的 livenessProbe（数据库不应被 liveness 杀），readinessProbe 的 initialDelaySeconds 设为 60 秒。同时内存限制从 1Gi 提高到 2Gi。

### 8.8 auth-service livenessProbe 太激进

**现象：** auth-service 日志显示 HikariPool 连接成功、Hibernate 初始化正常，但 Pod Exit Code 137（被 kubelet 杀掉）。

**根因：** Spring Boot + JPA 启动需要 80+ 秒，但 livenessProbe 的 initialDelaySeconds=30 + periodSeconds=10 × failureThreshold=3 = 最多 60 秒就被杀。

**解决：** initialDelaySeconds 从 30 调到 120，failureThreshold 从 3 调到 5，内存限制从 512Mi 调到 1Gi。

### 8.9 containerd 无法拉取 Harbor HTTP 镜像

**现象：** Pod ImagePullBackOff，`ctr --plain-http` 可以拉取但 kubelet 通过 CRI 拉取失败。

**根因：** containerd 2.2.1 的 CRI 插件不支持 plain-http 拉取，hosts.toml 配置也不生效。

**解决：**
- 业务镜像：`ctr -n k8s.io images pull --plain-http` 预拉到 worker 节点
- 第三方镜像：改用 Docker Hub 原始名（mysql:8.0, grafana/grafana:10.4.0, prom/prometheus:v2.53.0）+ `imagePullPolicy: IfNotPresent`
- 完整传输：`docker save | gzip > file` + `scp` + `gunzip -c | ctr import -`

### 8.10 Docker build 使用缓存导致代码修改未生效

**现象：** 修改了 application.yml（utf8mb4 → UTF-8），重建镜像后 Pod 仍然报旧错误。

**根因：** `docker build` 所有层命中缓存（`Using cache`），Docker 认为源码目录没有变化。

**解决：** `docker build --no-cache -t ...`

### 8.11 Grafana/Prometheus worker02 Pod 网络 "invalid argument"

**现象：** Grafana 在 worker02 上 Readiness probe 报 `dial tcp: connect: invalid argument`。

**根因：** worker02 的 Calico/cni0 网络接口异常，导致 Pod 网络层随机失败。

**解决：** 监控组件强制调度到 worker01（`nodeName: k8s-worker01`）

---

## 九、审计记录

本次组合拳部署后经过三方审计，发现并修复了以下关键问题：

| 严重级别 | 数量 | 典型问题 |
|---------|------|---------|
| CRITICAL | 2 | DB 凭据不一致、ci.sh namespace 排序 |
| HIGH | 5 | ci.yml 缺引号、AuthController null 安全、Prometheus 缺 RBAC、密码明文输出 |
| MEDIUM | 8 | useSSL=false、ddl-auto: update、floating image tag、emptyDir for data |

完整审计报告见 `docs/13-design-decisions.md` 和 `.opencode/audit/`。

---

## 十、面试要点

| 面试问题 | 回答要点 |
|---------|---------|
| "你们的数据库怎么部署的？" | MySQL Deployment + PV/PVC hostPath。学习环境用 Deployment，生产会升级为 StatefulSet + StorageClass。通过 InitDB ConfigMap 自动建库建用户。 |
| "监控怎么做的？" | Prometheus + Grafana。4 个 Spring Boot 服务通过 Actuator + Micrometer 暴漏 /actuator/prometheus。Prometheus 通过 Pod annotation 自动发现 + 静态 target 双保险抓取。Grafana 预置了 8 面板 Dashboard。 |
| "怎么部署新版本？" | 在 Harbor 节点执行 `bash scripts/ci.sh` 一键完成 Maven 构建 → Docker 镜像 → push Harbor → kubectl apply。GitHub Actions 负责 CI（自动构建+测试），CD 由于内网限制由内网脚本实现。 |
| "Prometheus 为什么不直接用 Helm？" | 学习阶段手动写 YAML 能深入理解每个组件的作用。面试时可以讲"我手动配过，知道 Helm chart 里面每个资源是干嘛的"。 |
| "MySQL 数据持久化怎么保证？" | hostPath PV 绑定到 worker 节点本地路径 `/data/mysql`。PVC 用 label selector 精确绑定该 PV。生产建议用 StorageClass 动态创建 + 定期备份。 |
| "你们的 CI/CD 和监控的关联？" | 每次部署后 `ci.sh` 的 smoke test 会调用 health endpoint。Prometheus 监控到异常（服务 down）会触发 Grafana 告警（规划中）。形成"部署 → 验证 → 监控"闭环。 |

---

## 十一、下一步

- [x] Ingress Nginx（第3周已完成 → `docs/15-ingress-deployment.md`）
- [x] HPA 自动扩缩容（已完成 → `docs/19-hpa.md`）
- [ ] 安全加固（RBAC + NetworkPolicy + PodSecurity）
- [ ] 日志聚合（Loki + Promtail）
- [ ] V2 业务服务（product/order/inventory/gateway）

---

> 本文档配合 `docs/13-design-decisions.md`（设计决策）、`docs/14-troubleshooting-handbook.md`（故障排查）、`docs/15-ingress-deployment.md`（Ingress 部署）、`docs/17-file-transfer.md`（文件同步）一起阅读。
