# ROADMAP

> 按 `docs/execution-plan.md` 中定义的 6 阶段计划增量交付。
> ✅ = 已完成 / 🔵 = 进行中 / ⚪ = 规划中

---

## V1 — 当前版本

### S0：平台抢救与验证 🔵 进行中

- [x] 项目脚手架：README、.gitignore、.dockerignore、Makefile、ROADMAP、CONTRIBUTING
- [ ] K8s 集群恢复与健康检查
- [ ] Spring Boot 3.2 升级验证
- [ ] 网络连通性验证
- [ ] Harbor 镜像仓库恢复
- [ ] `scripts/preflight.sh`
- [ ] `.github/workflows/ci.yml`

### S1：业务最小闭环 ⚪ 规划中

- [ ] auth-service JWT + BCrypt
- [ ] account-service JPA + Flyway + 交易流水
- [ ] payment-service RestClient + 补偿逻辑
- [ ] notification-service 通知记录
- [ ] 统一 ApiResponse + 全局异常处理
- [ ] MySQL 4 库初始化
- [ ] build-images.sh / deploy.sh / smoke-test.sh

### S2：平台能力矩阵 ⚪ 规划中

- [ ] ArgoCD + auto-sync + selfHeal
- [ ] Jaeger all-in-one + Badger + PVC
- [ ] Prometheus + Grafana 仪表盘 + 告警
- [ ] Loki + Promtail 日志聚合
- [ ] Sealed Secrets
- [ ] NetworkPolicy + PodSecurity
- [ ] HPA

### S3：双平台 CI/CD ⚪ 规划中

- [ ] GitHub Actions：Gitleaks + Semgrep + mvn test + Trivy
- [ ] `scripts/ci.sh` 内网一键交付
- [ ] 飞书 Bot 通知

### S4：故障演练与压测 ⚪ 规划中

- [ ] 故障 1：OOMKilled
- [ ] 故障 2：NetworkPolicy 误配
- [ ] 故障 3：Jaeger 慢调用定位
- [ ] JMeter 压测报告
- [ ] 3 份复盘文档

### S5：润色与包装 ⚪ 规划中

- [ ] Swagger/OpenAPI
- [ ] Helm Charts（dev/staging/prod）
- [ ] 面试材料

### S6：缓冲加分 ⚪ 规划中

- [ ] Velero 备份恢复演示
- [ ] Argo Rollouts 灰度发布
- [ ] Kyverno 自定义策略

---

## V2 — 生产化规划

| 能力 | 说明 |
|------|------|
| 多 master HA | keepalived + etcd 备份 |
| Argo Rollouts | 金丝雀发布 |
| Kyverno | 策略引擎 |
| Velero | 定时备份 + 灾难恢复 |
| Redis | 热点缓存、分布式锁 |

---

## 明确排除

| 排除项 | 理由 |
|--------|------|
| Spring Cloud Gateway | Ingress Nginx 已够，K8s Service + CoreDNS 就是服务发现 |
| Redis 缓存 | 平台工程叙事不需要；设计文档有对比方案 |
| 前端页面 | 定位为平台工程师/SRE |
| 多 master HA | 实验集群无法重建；设计文档已覆盖 |
| SonarQube | Semgrep 覆盖相同场景 |
| Seata 分布式事务 | 补偿逻辑 + 日终对账更贴近真实支付系统 |

---

**最后更新**：2026-06-02 | S0 初始化中
