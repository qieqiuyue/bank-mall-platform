# 生产上线自检清单

> **用途**: 每次部署前逐项勾选，确保已修复的审计发现不会退化。
> **来源**: 两轮深度审计（五轮交叉验证 + 主审架构师灾难预演）
> **最后更新**: 2026-06-09

---

## 一、部署前检查（13 项）

### 密钥与安全

- [ ] **JWT_SECRET_KEY** 已设置且非默认值（`openssl rand -base64 32` 生成）
- [ ] **Grafana 密码** 已通过 SealedSecret 注入（非明文 env）
- [ ] **所有 SealedSecret** 已解密并 apply（`kubectl get sealedsecret -n bank-mall`）
- [ ] **mysql/secret.yaml** 中无 `PLACEHOLDER` 占位符

### 服务与副本

- [ ] **所有服务 replicas ≥ 2**（auth / account / payment / notification）
- [ ] **HPA minReplicas ≥ 2**（4 个 HPA 均已配置）
- [ ] **PDB minAvailable: 1** 目标 Pod 数 ≥ 2，驱逐保护生效

### 探针与健康检查

- [ ] **K8s livenessProbe** 指向 `/actuator/health/liveness`（非 `/api/*/health`）
- [ ] **K8s readinessProbe** 指向 `/actuator/health/readiness`
- [ ] **Docker HEALTHCHECK** 与 K8s 探针对齐（`/actuator/health/liveness`）
- [ ] **ConfigMap** 包含 `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"`

### 存储与数据

- [ ] **Prometheus PVC** 已创建并绑定（`kubectl get pvc -n monitoring prometheus-pvc`）
- [ ] **MySQL PVC** 已创建并绑定（`kubectl get pvc -n bank-mall mysql-pvc`）

---

## 二、部署后验证（8 项）

### 服务状态

- [ ] `kubectl get pods -n bank-mall` — 全部 Running，无 CrashLoopBackOff
- [ ] `bash scripts/smoke-test.sh` — 4 项全部 PASS
- [ ] `kubectl get hpa -n bank-mall` — 4 个 HPA 状态正常

### 可观测性

- [ ] **Prometheus targets** 全部 UP（`http://<node>:30090/targets`）
- [ ] **Grafana** 可登录、仪表板有数据（`http://<node>:30300`）
- [ ] **Jaeger UI** 可访问（`http://<node>:31686`，或 Ingress 路径 `/jaeger`）
  - ⚠️ Jaeger Ingress 修复：`/jaeger` 路径使用独立 Ingress 资源（无 rewrite-target），可经 Ingress 访问。

### 安全

- [ ] **Grafana 匿名访问已关闭**（`GF_AUTH_ANONYMOUS_ENABLED: "false"`）

---

## 三、开发和代码审查红线

### 提交前

- [ ] `gitleaks detect --no-git` — 无密钥泄露
- [ ] 所有 `DataInitializer` Bean 带 `@Profile("dev")`（`grep -L '@Profile' apps/*/src/main/java/**/DataInitializer.java`）
- [ ] 所有 Controller `@RequestBody` 参数加 `@Valid`（`grep -rn 'public.*@RequestBody' apps/*/src/main/java | grep -v '@Valid'`）
- [ ] RestClient 配置了 connect/read 超时（`grep -rn 'RestClient.builder()' apps/*/src/main/java`）

---

## 四、已知不阻塞项（注意即可）

| 项目 | 影响 | 说明 |
|------|------|------|
| 业务接口无运行时认证 | 中 | V2 Spring Security 规划中 |
| Ingress 无 host 规则 | 低 | NodePort demo 环境 |
