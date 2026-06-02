# 构建与验证

## 适用范围

这个项目是一个 Kubernetes 实战项目。Windows 可以作为编辑工作区使用，但真正的打包、镜像推送和部署流程应在 Linux 构建节点、Kubernetes 控制节点或 CI Runner 上完成。

推荐执行环境：

| 环境 | 用途 |
| --- | --- |
| Windows/macOS 工作站 | 编辑代码、写文档、管理 Git |
| Linux 构建节点 | 执行 Maven、构建 Docker 镜像、推送镜像到 Harbor |
| Kubernetes 控制节点 | 执行 `kubectl apply`、检查工作负载、排查集群资源 |
| Kubernetes 工作节点 | 运行业务 Pod 和平台组件 |

## Linux 前置条件

- JDK 21
- Maven 3.9+
- Docker 或兼容的镜像构建工具
- 可访问 Harbor 或其他镜像仓库
- 已配置好可访问 Kubernetes 集群的 `kubectl`

## 技术栈版本（2026-06 更新）

| 组件 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.0.6 | 由 3.1.3 升级，Spring Framework 7.0.7 |
| JDK | 21 | 由 17 升级 |
| Hibernate ORM | 7.2.12.Final | Boot 4.0 托管版本 |
| Tomcat | 11.0.21 | Jakarta EE 11 |
| RestClient | Spring Framework 7.0 | 替代 RestTemplate，从 Framework 6.1 引入 |

## 构建 JAR

在 Linux 构建节点上进入各服务目录执行：

```bash
cd ~/bank-mall-cloudnative/bank-digital-platform/auth-service
mvn clean package -DskipTests
```

同样的方法适用于：

- `auth-service`
- `account-service`
- `payment-service`
- `notification-service`

当前代码级验证结果（2026-06-02）：

| 服务 | Maven 打包结果 | Spring Boot | JDK |
|------|---------------|-------------|-----|
| auth-service | ✅ 通过 | 4.0.6 | 21 |
| account-service | 通过 | 3.1.3 | 17 |
| payment-service | 通过 | 3.1.3 | 17 |
| notification-service | 通过 | 3.1.3 | 17 |

> **注意**：auth-service 已升级到 Spring Boot 4.0.6 + JDK 21 + RestClient，其他 3 个服务仍为 Spring Boot 3.1.3 + JDK 17，后续统一升级。

### H2 内存库快速验证（auth-service）

不依赖 MySQL 即可启动验证：

```bash
mvn clean package -DskipTests
java -jar target/auth-service-1.0.0.jar --spring.profiles.active=h2
```

看到 `Started AuthApplication in X.XXX seconds` 即为通过。

## 构建镜像

使用 Linux 脚本：

```bash
cd ~/bank-mall-cloudnative
chmod +x scripts/build-images.sh
REGISTRY=10.0.0.61 NAMESPACE=bank-mall VERSION=1.0.0 ./scripts/build-images.sh
```

推送镜像到 Harbor：

```bash
REGISTRY=10.0.0.61 NAMESPACE=bank-mall VERSION=1.0.0 PUSH=true ./scripts/build-images.sh
```

生成的镜像名：

| 服务 | 镜像 |
| --- | --- |
| auth-service | `10.0.0.61/bank-mall/auth-service:1.0.0` |
| account-service | `10.0.0.61/bank-mall/account-service:1.0.0` |
| payment-service | `10.0.0.61/bank-mall/payment-service:1.0.0` |
| notification-service | `10.0.0.61/bank-mall/notification-service:1.0.0` |

## Kubernetes NodePort 验证

将 `NODE_IP` 替换为 Kubernetes 工作节点 IP。

```bash
curl -X POST http://NODE_IP:30081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'

curl http://NODE_IP:30082/api/accounts/A1001/balance

curl -X POST http://NODE_IP:30083/api/payments \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORDER1001","payerAccount":"A1001","amount":299.00,"currency":"CNY"}'

curl -X POST http://NODE_IP:30084/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"channel":"SMS","receiver":"13800000000","template":"PAYMENT_SUCCESS"}'
```
