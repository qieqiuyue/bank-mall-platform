# 项目演进日志

> 一页时间线。详细决策与踩坑见 `docs/13-design-decisions.md` 和 `docs/14-troubleshooting-handbook.md`。Git 历史保留全部细节。

---

## S0：平台抢救 + 前置验证（2026-06-02 · ~6h）✅

- 启动 4 台 VMware VM，修复 Calico 挂起后遗症
- Spring Boot 3.1.3 → 4.0.6 升级，JDK 17 → 21，RestClient 替代 RestTemplate
- `@MockBean` 移除——改用 `MockMvcBuilders.standaloneSetup()` + `Mockito.mock()`
- GFW 验证：`curl github.com` ✅ `curl ghcr.io` ❌ → 镜像用 NJU 代理 / Harbor

---

## S1：业务最小闭环（2026-06-02~03 · ~17h）✅

**CP1**（6h）：account-service 重写（JPA + Flyway + @Version 乐观锁），auth-service JWT 改造（BCrypt + jjwt 0.12.6）
**CP2**（5h）：payment-service（RestClient + 补偿事务 3 次重试 + idempotency_key UNIQUE）
**CP3**（6h）：notification-service + 全链路验证 + 2.0.0 镜像部署

关键决策：ApiResponse 独立复制（每服务持一份，加 `// NOTE: Duplicated. Keep in sync.`）；status 用 VARCHAR 非 ENUM。

部署踩坑：NetworkPolicy 白名单遗漏（`allow-mysql` 只放行 auth）、liveness probe 30s 不够（SB 4.0.6 + JPA 启动需 60-80s）、Flyway K8s 不触发（临时 `ddl-auto: update`）。

---

## S2：平台能力矩阵（2026-06-04 · ~56h）✅

ArgoCD 3 Application CR + GitOps · Jaeger all-in-one 1.60 + OTEL agent initContainer · Prometheus + Grafana dashboard + 3 alert rules · Loki + Promtail · Sealed Secrets（8 凭证加密）· NetworkPolicy deny-all + 白名单 · PodSecurity baseline · PDB ×4 + LimitRange + ResourceQuota · Maven 父 POM + Dockerfile 多阶段构建

---

## S3：双平台 CI/CD（2026-06-04~07 · ~29h）✅

- GitHub Actions 5-job pipeline：gitleaks → semgrep+test → build+trivy(hard gate) → feishu
- harbor01 `scripts/ci.sh` 端到端验证（211s，4/4 zero HIGH/CRITICAL，NJU 镜像 17s 拉 DB）
- Trivy snap 沙箱兼容：`docker save` → temp file（`/tmp` → `$HOME`）；GFW 适配 6 版迭代
- Gitleaks 双层防护（pre-commit hook + CI gate）+ 阻断案例文档
- Semgrep SAST、Feishu 通知

---

## S4：故障演练 + 压测（2026-06-07~08 · ~30h）✅

**100/200 并发压测**：HPA 冷启动死亡螺旋（100 并发 6.3% 成功率，503 占 93%）→ JIT 预热后 200 并发 83.6%

**场景 2 NetworkPolicy 误配**：故障注入 → `kubectl describe netpol` 排障 → MTTR 5min

**场景 3 Jaeger trace 验证**：5 服务 OTEL 注入在线，emptydir 修复 3 次崩溃

**删除场景 1 OOMKilled**：SB 4.0.6 启动最小内存 ~320Mi，4 轮尝试全部失败 → V2 混沌工程规划

---

## S5：润色包装（2026-06-08 · ~30h）✅

Swagger springdoc v3.0.0（SB 4.0.6 兼容）· Helm Chart 骨架 · HA 架构设计（Keepalived 脑裂防护 + etcd 备份）· README 重写（价值陈述 + 数据栏 + 中英双语）· 面试材料更新（6 CI/CD Q&A + 3 fault cases + 设计决策）· 文档大扫除（50→13 篇活跃）

---

## S6：加分项（时间允许）⚪

Velero 备份恢复 · Argo Rollouts 灰度发布 · Kyverno 自定义策略

---

## 关键数据

| 指标 | 数值 |
|------|------|
| 微服务 | 4 |
| 单元测试 | 41 |
| 技术文档（活跃） | 13 |
| K8s 资源 | 16 |
| CI/CD 全流程 | 211s |
| VM 节点 | 4 |
| 运维 Skill | 9 |
| 故障演练场景 | 2/3 |

---

**最后更新**：2026-06-08
