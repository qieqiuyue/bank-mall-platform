# 优化路线图 (Optimization Roadmap)

> 合并来源：历史安全审计（`AUDIT_FINAL.md`，2026-06-09）、WSL 会话分析（2026-08-21）、Windows 会话分析（2026-08-21）。
> 状态标注：`[待修]` 未处理；`[部分修复]` 有缓解；`[已修复]` 已解决。
> **代码 Bug 修复清单（含验收点）：见 [bug-fix-todo.md](bug-fix-todo.md)** — 本文档侧重架构/性能/配置债，bug 清单侧重可复现错误行为。

## 结论先行

三份来源对项目的**核心问题判断高度一致**：最大的风险不是单点代码缺陷，而是 (1) 业务接口零鉴权、(2) 部署链路断裂（`deploy.sh` 无法跑通）、(3) 支付冲正逻辑 bug 且被测试固化。这三项构成 P0。其余为版本/配置/运维类 P1-P2 债。

---

## P0 — 立即修（会导致线上故障或安全事件）

| # | 问题 | 位置 | 修复建议 | 来源 |
|---|------|------|----------|------|
| P0-1 | **支付冲正永远失败**：`reverseWithRetry` 把 `"debit-"+idempotencyKey` 当 `originalTransactionNo` 传给 account 侧，但 account 按交易号主键 `TXN...` 查找，永远查不到 → 扣款成功+入账失败的支付全部落入 `ERROR_MANUAL_REVIEW` | `apps/payment-service/.../service/PaymentService.java:146`；`AccountClient.java:57-64` | 把 debit 响应里的真实 `transactionNo`（`debitResp.getTransactionNo()`）传给 `reverseWithRetry`，不要拼接幂等 key。**同步重写 `PaymentServiceTest.java:82` 的 mock**（当前测试固化了错误行为） | 历史审计 CODE-04 / 两轮分析 |
| P0-2 | **失败支付仍发成功通知**：通知调用不检查 status，`FAILED`/`ERROR_MANUAL_REVIEW` 的支付也发 `PAYMENT_SUCCESS` 模板 | `PaymentService.java:127-133` | 按支付状态选择模板：仅 `COMPLETED` 发成功通知，失败状态发失败通知或跳过 | 本会话独有发现 |
| P0-3 | **部署断裂**：`deploy.sh:16` 引用 `mysql/secret.yaml`（被 .gitignore 忽略，干净 clone 上不存在 → `set -e` 下脚本必死）；`mysql/initdb-configmap.yaml:21` 密码是字面 `<DB_PASSWORD>` | `scripts/deploy.sh:16`、`infra/kubernetes/base/mysql/initdb-configmap.yaml:21` | ① 生成真实 secret 或改 `kubectl create secret` 注入；② 替换 initdb 占位符；③ 把 secret 收编进 SealedSecret | 历史审计 OPS-01 / 两轮分析 |
| P0-4 | **SECURITY.md 缺失**：README.md:143 仓库结构图引用它，文件不存在 | 根目录 | 创建 `SECURITY.md`（安全策略、报告漏洞渠道），同步 README | 历史审计 DOC-01 / WSL 分析 |

---

## P1 — 近期（1-2 周内）

| # | 问题 | 位置 | 修复建议 | 来源 |
|---|------|------|----------|------|
| P1-1 | **业务接口零鉴权**：account/payment/notification 无任何 JWT/Filter 校验，debit/credit/payments 接口公网可达（Ingress 全暴露、无 host/TLS） | 三个服务 Controller、`infra/kubernetes/base/ingress/ingress-rules.yaml` | 引入 Spring Security 或 JWT Filter + `@PreAuthorize`；服务间调用加服务身份令牌（`X-Service-Token`）；Ingress 加 host 规则和 TLS | 历史审计 ENG-04 / 两轮分析 |
| P1-2 | **镜像 tag 三套断裂**：GH Actions 用 `github.sha`，ci.sh 用 `git describe`，deployment YAML 手写 `2.0.0` → ArgoCD 拉取的 tag 与 CI 产物对不上 | `ci.yml`、`scripts/ci.sh`、`infra/kubernetes/base/*/deployment.yaml` | 统一单一版本源（建议 `git describe`），由 CI 写入 deployment 清单（sed/yml 替换），消除手改 | 两轮分析 |
| P1-3 | **登录限流形同虚设**：`request.getRemoteAddr()` 在 Ingress 后全是 Ingress Pod IP（所有用户共享一个限流 key）；内存态多副本独立；`store` 无主动清理（被刷 IP 内存膨胀） | `apps/auth-service/.../LoginRateLimiter.java` | 改用 `X-Forwarded-For` + 可信代理白名单；加账号级失败计数与锁定；定时清理过期窗口；或用 Redis 集中限流 | 本会话独有发现 / WSL 分析 |
| P1-4 | **Tempo 三处部署入口全漏**：`base/tempo/` 清单存在，但 deploy.sh、base/kustomization、ArgoCD exclude 三处都未包含 → 追踪链路实际部署不上 | `scripts/deploy.sh`、`infra/kubernetes/base/kustomization.yaml`、`bank-mall-apps.yaml:16` | 把 `tempo/` 纳入任一部署入口（建议 deploy.sh 加一步 apply），修正 Ingress ExternalName 端口 16686→3200 | WSL 分析 / 本会话 |
| P1-5 | **Jaeger 迁移残留**：verify.sh/ci.sh 仍查 `jaeger` namespace 和 `/jaeger/` 路径；`allow-services-ingress-minus-payment.yaml` 演练文件残留 | `scripts/verify.sh:94-106`、`scripts/ci.sh:171-172`、`infra/kubernetes/base/security/` | 清理为 Tempo 引用；删除 minus-payment 演练文件；`infra/dashboards/*.json` 孤儿文件挂载或删除 | WSL 分析 |
| P1-6 | ~~测试资产损坏~~ **已解决（2026-09-05 复核）**：k6 路径已修（PR #48 加 `/payment` 前缀，实测 `payment-load.js:47` 正确）；jmeter 已删除、ROADMAP 声明已校正 | — | 无剩余动作 | WSL 分析 |

---

## P2 — 规划（V2/S6）

| # | 问题 | 位置 | 修复建议 | 来源 |
|---|------|------|----------|------|
| P2-1 | **结算账户单行热点**：所有支付 credit 到同一 `MALL-SETTLEMENT` 账户，乐观锁冲突率随并发陡增，是确定的扩展瓶颈 | `PaymentService.java:26` | 结算账户分片（N 个结算户按 hash 路由）或引入批量对账 | 本会话独有发现 |
| P2-2 | **JVM 无 -Xmx + 容器 limit 不一致**：Dockerfile `java -jar` 无内存参数；account/payment/notification limit 512Mi 而 auth 1Gi；OTEL agent 再占几十 MB → OOMKilled 风险 | `apps/*/Dockerfile`、`infra/kubernetes/base/*/deployment.yaml` | Dockerfile 加 `-Xmx` + `-XX:MaxRAMPercentage`；统一 limit 语义；HPA 加内存指标 | 两轮分析 |
| P2-3 | **promtail 读 docker 路径但集群是 containerd**：~~`/var/lib/docker/containers` 在 containerd 集群下无日志~~（核实：config 实际已用 `/var/log/pods`，残余是 docker 死挂载 + cri stage 被删） | `infra/kubernetes/base/monitoring/promtail-daemonset.yaml` | ✅ **已修复**（第三批 PR）：删除 docker 死挂载、恢复 `cri: {}` stage 解析 containerd 日志格式 | WSL 分析 |
| P2-4 | **Grafana 告警失效**：告警 webhook 指向 `localhost:9999` 无服务监听；`GRAFANA_ADMIN_PASSWORD` key 不在 SealedSecret 且 SealedSecret 在 `bank-mall` ns、Grafana 在 `monitoring` ns（跨 ns 不可见）→ 回落默认 admin/admin | `grafana-configmap.yaml:40-51`、`grafana-deployment.yaml:38-43` | ✅ **已修复**（第二批 PR）：webhook 改明确占位；新增 `monitoring` ns 的 `grafana-secret` SealedSecret 模板（需集群 kubeseal） | WSL 分析 |
| P2-5 | **HPA 虚设**：~~`maxReplicas: 3` 在只有 2 个可调度 worker 的集群基本无意义；仅 CPU 指标~~ | `infra/kubernetes/base/hpa/*.yaml` | ✅ **已修复**（第三批 PR）：min1/max2 对齐 2-worker、加内存指标（CPU 70% / 内存 80%） | WSL 分析 |
| P2-6 | **补偿重试异步化**：同步 HTTP 补偿最多阻塞 ~1s，故障注入下线程池易打满；崩溃后"卡死 PENDING 且幂等 key 被占"无恢复机制 | `PaymentService.java` | 引入 outbox 表 + 定时 reconciliation job 扫 PENDING | 本会话独有发现 |
| P2-7 | **MySQL 单点无备份验证**：单副本 StatefulSet + hostPath，`db-backup.sh` dump 到 `/tmp` 不落持久存储、无恢复演练 | `mysql/deployment.yaml`、`scripts/db-backup.sh` | 备份落对象存储 + cron + 定期恢复演练；MySQL 上 PDB | WSL 分析 |
| P2-8 | **监控组件版本陈旧**：Loki 2.9.12(EOL)、Grafana 10.4.0、Prometheus 2.53.0 均落后 1-2 个主版本 | `infra/kubernetes/base/monitoring/*` | ✅ **已修复**（第三批 PR）：Loki 3.4.2 + promtail 3.4.2、Grafana 11.5.2、Prometheus v3.2.1；Loki schema 同步改 TSDB v13（Loki 3 移除 boltdb-shipper） | WSL 分析 |
| P2-9 | **Notification DTO/实体命名不一致**（从 bug 清单降级）：`NotificationRequest`/`Response` 用 `channel`/`template`，实体用 `type`/`title`，请求-响应自洽但 DB 语义易困惑 | `NotificationRequest.java`、`NotificationResponse.java`、`Notification.java` | 统一命名（如实体加 `channel`/`template` 字段或 DTO 对齐），**保持 API 响应字段名不变** | 本会话复查 |

---

## 已修复（供追溯，勿重复处理）

| 项 | 修复 commit |
|----|------------|
| Trivy 9 个 HIGH CVE（jackson-core/databind、micrometer、spring-data-commons、spring-framework 5 个 BOM 升级） | `d5f90d7`（PR #46） |
| JWT 默认密钥删除 + `@PostConstruct` 强校验 | 历史审计整改 |
| auth-service 补 GlobalExceptionHandler | 历史审计整改 |
| DataInitializer 加 `@Profile("dev")` | 历史审计整改 |
| MySQL 改 StatefulSet + replicas=2 + HPA | 历史审计整改 |
| 探针改 Actuator /health/liveness | 历史审计整改 |
| common-lib 抽取 | 历史审计整改 |
| Tempo PVC 属主权限 initContainer | `84f4a82`（PR #46） |
| 支付冲正传错交易号（P0-1）+ 失败支付发成功通知（P0-2）+ 幂等 race 直出 500（P1-1） | `c1be4e9`（PR #47） |
| Tempo 部署入口（A-2）+ Tempo Ingress rewrite-target（A-1）+ deploy.sh MySQL secrets（A-6）+ ci.sh tag 回写（A-5） | PR #50 |
| Grafana 告警 webhook + admin 密码跨 ns（A-4）+ 反亲和/PDB（A-8） | PR #51 |
| promtail 死挂载删除 + cri stage 恢复（A-3）+ HPA 对齐 2-worker 加内存指标（A-7）+ 监控版本升级 Loki 3.4/Grafana 11.5/Prom 3.2 + Loki TSDB schema（A-9） | 第三批 PR |

---

## 建议执行顺序

1. **P0-1 冲正 bug**（含测试重写）→ 单个 PR
2. **P0-2 失败通知** → 可并入 P0-1 同一 PR（同文件 PaymentService）
3. **P0-3 部署断裂** → 单独 PR（涉及脚本 + 清单）
4. **P0-4 SECURITY.md** → 文档 PR，可随时做
5. P1 按依赖顺序：P1-1 鉴权（最大）→ P1-2 tag 统一 → P1-4 Tempo 部署 → 其余
6. P2 进入 V2/S6 规划池

> 每次修复完成后回填本表状态列。
