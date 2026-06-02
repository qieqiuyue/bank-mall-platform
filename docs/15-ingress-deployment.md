# Ingress Nginx 统一入口部署文档

> 时间：2026年第3周 | 目标：替代 port-forward，建立统一的 HTTP 入口

---

## 一、背景与动机

### 之前的状态

4 个 Spring Boot 服务在 K8s 中运行，Service 类型均为 `ClusterIP`（仅集群内部可达）。测试接口只能用 `kubectl port-forward` 逐个转发：

```bash
kubectl port-forward -n bank-mall svc/auth-service 18081:8081 &
kubectl port-forward -n bank-mall svc/account-service 18082:8082 &
# ... 每加一个服务就要一个终端
```

**问题：** 面试官问"你们怎么暴露服务的？"——不能说 port-forward。

### 现在的状态

一台 Ingress Nginx Controller 监听 NodePort `30080`，根据 URL 前缀自动路由到对应后端服务：

```
curl http://10.0.0.31:30080/auth/api/auth/login → auth-service:8081
curl http://10.0.0.31:30080/account/api/accounts/A1001/balance → account-service:8082
curl http://10.0.0.31:30080/payment/api/payment/pay → payment-service:8083
curl http://10.0.0.31:30080/notification/api/notifications/send → notification-service:8084
```

---

## 二、架构

```
                         10.0.0.31:30080 (NodePort)
                                │
                                ▼
┌──────────────────────────────────────────────────────────┐
│  Ingress Nginx Controller                                │
│  (ingress-nginx namespace, Deployment 1 replica)         │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Ingress Rules (bank-mall-ingress)                 │  │
│  │                                                    │  │
│  │  /auth(/|$)(.*)          → auth-service:8081       │  │
│  │  /account(/|$)(.*)       → account-service:8082    │  │
│  │  /payment(/|$)(.*)       → payment-service:8083    │  │
│  │  /notification(/|$)(.*)  → notification-service:8084│  │
│  │                                                    │  │
│  │  rewrite-target: /$2  (strip prefix before proxy)  │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ConfigMap:                                              │
│    proxy-body-size: 10m                                  │
│    proxy-read-timeout: 60s                               │
│    allow-snippet-annotations: false                      │
└──────────────────────────────────────────────────────────┘
```

### URL 转换规则

Ingress 使用 `rewrite-target: /$2` 配合正则路径：

| 请求 URL | 正则匹配 | 转发给后端 |
|----------|---------|-----------|
| `/auth/api/auth/login` | `$1=""` `$2="api/auth/login"` | `GET /api/auth/login` |
| `/auth/` | `$1="/"` `$2=""` | `GET /` |
| `/account/api/accounts/A1001/balance` | `$1=""` `$2="api/accounts/A1001/balance"` | `GET /api/accounts/A1001/balance` |

**正则解释：** `(/|$)(.*)` 分为两个捕获组：
- `$1` = `/` 或 行尾 `$`
- `$2` = 剩余路径

`rewrite-target: /$2` 丢弃前缀，只保留 `$2`。

---

## 三、文件清单

### 新增文件

| # | 文件 | 说明 | 关键配置 |
|---|------|------|---------|
| 1 | `k8s/base/ingress/controller-rbac.yaml` | Namespace + SA + ClusterRole + Role + ClusterRoleBinding + RoleBinding | leases 含 `list` verb（leader election 必需） |
| 2 | `k8s/base/ingress/controller-configmap.yaml` | Ingress Nginx 运行时配置 | `proxy-body-size: 10m`、`proxy-read-timeout: 60`、`allow-snippet-annotations: false` |
| 3 | `k8s/base/ingress/controller-deploy.yaml` | Deployment v1.10.1 | 阿里云镜像、`NET_BIND_SERVICE` capability、`seccompProfile`、`runAsNonRoot: true`、512Mi memory |
| 4 | `k8s/base/ingress/controller-service.yaml` | NodePort Service | 30080 (HTTP)、30443 (HTTPS)、`externalTrafficPolicy: Local` |
| 5 | `k8s/base/ingress/ingressclass.yaml` | IngressClass `nginx` | `controller: k8s.io/ingress-nginx` |
| 6 | `k8s/base/ingress/ingress-rules.yaml` | 路由规则 | 4 条 `path` + `rewrite-target` 注解 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `scripts/deploy.sh` | 追加 `[9/9]` Ingress 步骤，显式按顺序 apply 6 个文件 |
| `docs/12-architecture-diagram.html` | Ingress 从"规划"→"已完成"（绿色），用户层说明更新 |
| `docs/02-roadmap.md` | 阶段 7 标记 Ingress ✅ |

---

## 四、文件同步

> 你在 Windows 上编辑了 Ingress 的 YAML 文件，需要先把它们传到 Linux VM 上才能执行。
> 三种方式任选其一，详见 `docs/17-file-transfer.md`。

### 方式一：Git（推荐）

```bash
# 在 master 节点执行
cd ~
git clone https://github.com/<你的用户名>/bank-mall-cloudnative.git
cd bank-mall-cloudnative
```

### 方式二：scp

```powershell
# 在 Windows PowerShell 执行
scp -r C:\LearningResources\k8s项目\bank-mall-cloudnative\k8s\base\ingress user@10.0.0.31:~/bank-mall-cloudnative/k8s/base/
scp C:\LearningResources\k8s项目\bank-mall-cloudnative\scripts\deploy.sh user@10.0.0.31:~/bank-mall-cloudnative/scripts/
```

### 方式三：VMware 共享文件夹

```bash
# 在 master 节点直接操作
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

- K8s 集群正常运行（`kubectl get nodes` 全部 Ready）
- `bank-mall` namespace 中有 4 个服务 Running
- 注意：Ingress Nginx 镜像来源 `registry.aliyuncs.com/google_containers`，需要网络可访问（或提前手动 pull）

### 5.2 一键部署

```bash
# 在 master 节点执行
cd ~/bank-mall-cloudnative
bash scripts/deploy.sh
```

### 5.3 手动分步部署

```bash
# Step 1: 创建命名空间
kubectl create namespace ingress-nginx

# Step 2: RBAC（必须在 Deployment 之前）
kubectl apply -f k8s/base/ingress/controller-rbac.yaml

# Step 3: ConfigMap
kubectl apply -f k8s/base/ingress/controller-configmap.yaml

# Step 4: Deployment
kubectl apply -f k8s/base/ingress/controller-deploy.yaml

# Step 5: Service
kubectl apply -f k8s/base/ingress/controller-service.yaml

# Step 6: IngressClass
kubectl apply -f k8s/base/ingress/ingressclass.yaml

# Step 7: 路由规则
kubectl apply -f k8s/base/ingress/ingress-rules.yaml

# Step 8: 等待就绪
kubectl wait --for=condition=ready pod \
  -l app.kubernetes.io/component=ingress \
  -n ingress-nginx --timeout=180s
```

**重要的顺序：** RBAC → ConfigMap → Deployment → Service → IngressClass → Ingress Rules。如果 Deployment 在 RBAC 之前 apply，Pod 会因为 ServiceAccount 不存在而无法启动。

---

## 六、验证

### 6.1 确认 Controller 运行

```bash
kubectl get pods -n ingress-nginx
# NAME                                       READY   STATUS
# ingress-nginx-controller-xxxxx             1/1     Running

kubectl get svc -n ingress-nginx
# NAME                       TYPE       PORT(S)
# ingress-nginx-controller   NodePort   80:30080/TCP,443:30443/TCP
```

### 6.2 测试路由

```bash
# 从任何一个 K8s 节点执行

# 1. auth-service 健康检查
curl http://10.0.0.31:30080/auth/api/auth/health
# → {"status":"UP","service":"auth-service","users":3}

# 2. 登录获取 Token
curl -s -X POST http://10.0.0.31:30080/auth/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
# → {"code":"SUCCESS","token":"token-...","userId":"U1001",...}

# 3. 账户余额查询
curl http://10.0.0.31:30080/account/api/accounts/A1001/balance
# → {"availableBalance":8888.88,"currency":"CNY",...}

# 4. 通知发送
curl -s -X POST http://10.0.0.31:30080/notification/api/notifications/send \
  -H "Content-Type: application/json" \
  -d '{"userId":"U1001","message":"Test via Ingress"}'
```

### 6.3 完整登录+查询流程（一键脚本）

```bash
#!/bin/bash
NODE="10.0.0.31"
PORT="30080"

echo "=== Login ==="
TOKEN=$(curl -s -X POST http://${NODE}:${PORT}/auth/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "Token: ${TOKEN}"

echo ""
echo "=== Balance ==="
curl -s http://${NODE}:${PORT}/account/api/accounts/A1001/balance | python3 -m json.tool

echo ""
echo "=== Validate Token ==="
curl -s -X POST http://${NODE}:${PORT}/auth/api/auth/validate \
  -H "Authorization: Bearer ${TOKEN}" | python3 -m json.tool
```

---

## 七、配置说明

### 7.1 Ingress 规则详解

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  annotations:
    # rewrite-target: /$2  将 /xxx/api/... 重写为 /api/...
    # $1 = 路径中的 (/|$) ，$2 = 路径中的 (.*)
    nginx.ingress.kubernetes.io/rewrite-target: /$2
    # use-regex 启用正则路径匹配
    nginx.ingress.kubernetes.io/use-regex: "true"
spec:
  ingressClassName: nginx  # 关联到 IngressClass
  rules:
  - http:
      paths:
      - path: /auth(/|$)(.*)    # 匹配 /auth, /auth/, /auth/api/...
        pathType: ImplementationSpecific
        backend:
          service:
            name: auth-service
            port:
              number: 8081
```

### 7.2 Controller 关键配置

| 配置 | 值 | 说明 |
|------|-----|------|
| 镜像 | `registry.aliyuncs.com/google_containers/nginx-ingress-controller:v1.10.1` | 阿里云备用源 |
| Memory | requests 128Mi / limits 512Mi | 生产建议 512Mi+ |
| NET_BIND_SERVICE | ✅ | 非 root 运行必需 |
| runAsNonRoot | ✅ | UID 101 |
| seccompProfile | RuntimeDefault | 限制系统调用 |
| externalTrafficPolicy | Local | 保留客户端源 IP |
| terminationGracePeriodSeconds | 300 | 从容关闭，耗尽存量连接 |
| proxy-body-size | 10m | 请求体大小限制 |

### 7.3 RBAC 权限清单

| 作用域 | 资源 | 操作 |
|--------|------|------|
| ClusterRole | configmaps, endpoints, nodes, pods, secrets, namespaces, services, ingresses, ingressclasses, endpointslices, leases | get, list, watch (部分 update) |
| Role (ingress-nginx) | configmaps, pods, secrets, endpoints | get, list, watch |
| Role (ingress-nginx) | configmaps: ingress-nginx-controller | get, update, create |
| Role (ingress-nginx) | events | create, patch |
| Role (ingress-nginx) | leases | get, list, create, update |

---

## 八、踩坑记录

### 8.1 NET_BIND_SERVICE 缺失

**现象：** Pod 日志报 `bind() to 0.0.0.0:80 failed (13: Permission denied)`

**根因：** `runAsNonRoot: true` + `capabilities.drop: [ALL]` 导致 nginx 进程没有绑定特权端口的能力。

**解决：** `capabilities.add: [NET_BIND_SERVICE]`

### 8.2 leases 缺 list verb

**现象：** Controller 日志反复出现 leader election 错误，Pod 可能 crash-loop。

**根因：** leader election 需要 `list` leases 来枚举已有租约，但 Role 只给了 `[get, create, update]`。

**解决：** Role 中 leases verbs 改为 `[get, list, create, update]`，ClusterRole 也加 `[list, watch]`。

### 8.3 部署顺序导致的启动失败

**现象：** `kubectl apply -f ingress/` 按字母序先 apply Deployment，后 apply RBAC，Pod 因 ServiceAccount 不存在而失败。

**解决：** `deploy.sh` 中改为显式逐文件 apply：RBAC → ConfigMap → Deployment → Service → IngressClass → Rules。

### 8.4 阿里云镜像 vs 官方镜像

**现象：** `registry.k8s.io` 国内拉镜像超时。

**解决：** 使用阿里云镜像 `registry.aliyuncs.com/google_containers/nginx-ingress-controller:v1.10.1`，评论中有切换说明。

---

## 九、本周学习要点

| 知识点 | 掌握程度 | 面试可能怎么问 |
|--------|---------|--------------|
| Ingress 工作原理 | 能画图讲 | "Ingress 和 Service 的区别？" |
| rewrite-target 正则 | 能解释 `$1` `$2` | "这个正则表达式是什么意思？" |
| IngressClass | 知道 K8s 1.18+ 必需 | "为什么要有 IngressClass？" |
| RBAC 最小权限 | ClusterRole vs Role 区别 | "这个 SA 需要哪些权限？为什么？" |
| NET_BIND_SERVICE | 知道非 root 绑定特权端口 | "容器以非 root 运行，nginx 怎么绑定 80 端口？" |
| externalTrafficPolicy | Local vs Cluster 区别 | "客户端源 IP 怎么保留？" |
| 部署顺序 | RBAC → ConfigMap → Deployment | "为什么这个顺序？反过来会怎样？" |

---

## 十、与监控体系的集成

Ingress Controller 的 `/metrics` 端点可以被 Prometheus 抓取，但尚未配置（下一步规划）。

```bash
# 验证 metrics 端点
kubectl exec -n ingress-nginx deploy/ingress-nginx-controller -- \
  curl -s http://localhost:10254/metrics | head
```

---

## 十一、下一步

- [ ] 接入 HPA 自动扩缩容（本周下一个任务）
- [ ] 接入 TLS（cert-manager + Let's Encrypt 或自签证书）
- [ ] 配置限流（rate limiting per user/ip）
- [ ] 配置 CORS（跨域访问控制）

---

> 本文档配合 `docs/02-roadmap.md` 阶段 7、`docs/12-architecture-diagram.html` 统一入口层、`docs/17-file-transfer.md` 文件同步指南 一起阅读。
