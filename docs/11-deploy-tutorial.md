# Bank Mall 服务部署教程

## 前置条件检查

| 组件 | 节点 | 状态 | 地址 |
|------|------|------|------|
| K8s 控制平面 | k8s-master01 | Ready | 10.0.0.31 |
| K8s 工作节点 | k8s-worker01 | Ready | 10.0.0.41 |
| K8s 工作节点 | k8s-worker02 | Ready | 10.0.0.42 |
| Harbor 镜像仓库 | k8s-harbor01 | Running | 10.0.0.61:80 |

验证命令：

```bash
# 在 master 上执行
kubectl get nodes
# 预期：3 个节点全部 Ready

# 在 harbor 上执行
docker ps | grep harbor
# 预期：看到 harbor-core, harbor-portal, nginx 等容器运行中
```

---

## 步骤 1：Harbor 创建项目

浏览器访问 `http://10.0.0.61`，用 `admin / <HARBOR_PASSWORD>` 登录。

点击 **"新建项目"**：
- 项目名称：`bank-mall`
- 访问级别：`公开`

或者命令行：

```bash
# 在 harbor 节点上
curl -u admin:<HARBOR_PASSWORD> -X POST http://10.0.0.61/api/v2.0/projects \
  -H "Content-Type: application/json" \
  -d '{"project_name":"bank-mall","public":true}'
```

---

## 步骤 2：配置 containerd 允许 Harbor HTTP 访问

由于 Harbor 使用 HTTP（非 HTTPS），所有 K8s 节点需要配置 insecure registry。

**在 k8s-master01, k8s-worker01, k8s-worker02 上分别执行：**

```bash
sudo mkdir -p /etc/containerd/certs.d/10.0.0.61
cat <<EOF | sudo tee /etc/containerd/certs.d/10.0.0.61/hosts.toml
server = "http://10.0.0.61"

[host."http://10.0.0.61"]
  capabilities = ["pull", "resolve"]
  skip_verify = true
EOF
sudo systemctl restart containerd
```

验证（拉取一个测试镜像）：

```bash
# 任意 K8s 节点上
sudo ctr -n k8s.io images pull --plain-http 10.0.0.61/bank-mall/pause:3.10.2
```

> 这一步暂时会失败（仓库里还没镜像），只是确认配置格式。真正验证等步骤 4。

---

## 步骤 3：构建并推送镜像到 Harbor

**在 Linux 构建节点上执行**（需要有 Docker 和 Maven，且能从 `bank-digital-platform/` 源码构建）。

> 注意：如果 Windows 是唯一可用环境，建议把源码传到 Harbor 节点或任一 K8s 节点，安装 JDK 17 + Maven + Docker 后执行。

```bash
cd ~/bank-mall-cloudnative

# 构建 + 推送
REGISTRY=10.0.0.61 NAMESPACE=bank-mall VERSION=1.0.0 PUSH=true bash scripts/build-images.sh
```

推送成功后查看：

```bash
curl -u admin:<HARBOR_PASSWORD> http://10.0.0.61/api/v2.0/projects/bank-mall/repositories
```

---

## 步骤 4：部署到 Kubernetes

**在 k8s-master01 上执行：**

```bash
cd ~/bank-mall-cloudnative
bash scripts/deploy.sh
```

部署过程会依次创建：Namespace → ConfigMap → Secret → Deployment → Service

---

## 步骤 5：验证部署

```bash
# 查看所有 Pod
kubectl get pods -n bank-mall -o wide

# 查看所有 Service
kubectl get svc -n bank-mall

# 查看 ConfigMap 是否注入成功
kubectl get configmap bank-mall-config -n bank-mall -o yaml

# 查看 Secret
kubectl get secret bank-mall-secret -n bank-mall
```

预期输出（Pod 全部 Running）：

```
NAME                                    READY   STATUS    RESTARTS   AGE
account-service-xxxxxxxxxx-xxxxx        1/1     Running   0          1m
auth-service-xxxxxxxxxx-xxxxx           1/1     Running   0          1m
notification-service-xxxxxxxxxx-xxxxx   1/1     Running   0          1m
payment-service-xxxxxxxxxx-xxxxx        1/1     Running   0          1m
```

---

## 步骤 6：功能验证

### 方法 A：Port-forward（推荐，无需 NodePort）

```bash
# 在 master 上端口转发
kubectl port-forward -n bank-mall svc/auth-service 8081:8081 &
kubectl port-forward -n bank-mall svc/account-service 8082:8082 &
kubectl port-forward -n bank-mall svc/payment-service 8083:8083 &
kubectl port-forward -n bank-mall svc/notification-service 8084:8084 &

# 测试认证
curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' | python3 -m json.tool

# 测试账户余额
curl -s http://localhost:8082/api/accounts/A1001/balance | python3 -m json.tool

# 测试支付
curl -s -X POST http://localhost:8083/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORDER1001","amount":299.00,"currency":"CNY"}' | python3 -m json.tool

# 测试通知
curl -s -X POST http://localhost:8084/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"channel":"SMS","receiver":"13800000000","template":"PAYMENT_SUCCESS"}' | python3 -m json.tool
```

### 方法 B：集群内部访问

```bash
kubectl run curl-test --image=curlimages/curl -n bank-mall --rm -it --restart=Never -- \
  curl -s http://auth-service:8081/api/auth/health
```

---

## 步骤 7：健康检查验证

```bash
# 检查探针状态
kubectl describe pod -n bank-mall | grep -A5 "Liveness\|Readiness"

# 查看事件（排查问题用）
kubectl get events -n bank-mall --sort-by='.lastTimestamp'
```

---

## 常见问题

### Pod 一直 ImagePullBackOff

```bash
kubectl describe pod -n bank-mall <pod-name> | grep -A5 Events
```

可能原因：Harbor 地址不对、containerd 没配 hosts.toml、镜像版本号不匹配。

### 修复：创建 Harbor pull secret

```bash
kubectl create secret docker-registry harbor-pull \
  --docker-server=10.0.0.61 \
  --docker-username=admin \
  --docker-password=<HARBOR_PASSWORD> \
  -n bank-mall
```

然后在 deployment 的 `spec.template.spec` 中添加：

```yaml
imagePullSecrets:
- name: harbor-pull
```

### Pod Running 但探针失败

```bash
kubectl logs -n bank-mall <pod-name>
kubectl exec -n bank-mall <pod-name> -- curl -s localhost:<port>/api/<service>/health
```

---

## 快速部署检查清单

| 检查项 | 命令 | 预期 |
|--------|------|------|
| 节点状态 | `kubectl get nodes` | 3 Ready |
| Pod 状态 | `kubectl get pods -n bank-mall` | 4 Running |
| Service | `kubectl get svc -n bank-mall` | 4 个 ClusterIP |
| ConfigMap | `kubectl get cm -n bank-mall` | bank-mall-config |
| Secret | `kubectl get secret -n bank-mall` | bank-mall-secret |
| 认证接口 | `port-forward` + `curl /api/auth/login` | 返回 token |
| 账户接口 | `port-forward` + `curl /api/accounts/A1001/balance` | 返回余额 |

---

## 卸载

```bash
cd ~/bank-mall-cloudnative
bash scripts/teardown.sh
```