# AGENTS.md

## 项目概览

银行商城微服务平台（教学/演示 + 面试素材）：4 个 Java 21 微服务 + common-lib 共享内核，MySQL 8 一服务一库（Flyway 迁移），K8s（Kustomize base 为部署事实来源）+ ArgoCD + 内网 Harbor(10.0.0.61) 部署，可观测性 Prometheus/Grafana/Loki/Tempo。

- `apps/` — auth(8081)/account(8082)/payment(8083)/notification(8084) + common-lib（`ApiResponse`/`BusinessException`/`ErrorCode`，零 Spring 依赖）
- `infra/kubernetes/base/` — Kustomize 部署清单（服务/MySQL/Ingress/security/监控/tempo/hpa）；`infra/helm/` 仅为演示骨架
- `scripts/` — 12 个运维脚本（deploy/preflight/post-boot/recover/ci 等），全部从本机驱动 VM 集群
- `tests/` — k6 压测 + `payment-load.sh`（`tests/jmeter/` 已删除）
- `docs/` — 审计报告（TECH/GLM/AUDIT_FINAL）、设计决策、复盘、面试材料

## 常用命令

- 构建: `make build`（⚠️ 直接 `mvn clean package` 依赖本地 .m2 已有 common-lib；全新环境先 `mvn install -pl common-lib -am`）
- 测试: `make test`（4 服务 JUnit 单测，无集成测试）
- Lint: `make lint`（semgrep + gitleaks）
- 部署: `make preflight deploy smoke-test verify`（依赖本机网络 + SSH 到 4 台 VM）
- CI: GitHub Actions（gitleaks→semgrep→test→build+trivy hard gate，仅 main）；内网 `make ci` 在 harbor01 上跑，是唯一实际推送镜像的链路

## 架构要点

- 服务间全部同步 REST（RestClient，无 MQ）；payment 编排 debit→credit→通知，失败走补偿冲正（3 次指数退避）
- 幂等靠 DB 唯一约束 `uk_idempotency` + account 侧 `@Version` 乐观锁重试；payment 幂等 key 前缀 `debit-`/`credit-`/`reverse-`
- 密钥全走环境变量 + K8s SealedSecret；JWT 密钥启动强校验 ≥256bit

## Spring Boot 4.0.6 破坏性变更表（改依赖前必读）

本项目用 **Spring Boot 4.0.6**（Spring Framework 7.0），**不是 SB 3.x**。

| SB 3.x 写法 | SB 4.0.6 替代 | 影响范围 |
|------------|-------------|---------|
| `RestTemplate` / `WebClient.block()` | `RestClient`（同步，`RestClient.builder()` 手动创建） | payment → account HTTP 调用 |
| `@MockBean` / `@WebMvcTest` | 已移除，改用 `MockMvcBuilders.standaloneSetup()` + `Mockito.mock()` | 所有单元测试 |
| `RestClientCustomizer` | 已移除，改用 `RestClient.Builder` 直接创建 Bean | auth-service `RestClientConfig.java` |
| spring-security 全家桶 | 只引 `spring-security-crypto`（`BCryptPasswordEncoder`） | auth-service |
| springdoc-openapi v2.x | **必须用 v3.0.0** | 4 服务 Swagger UI |
| `@MockitoBean` | SB 3.4+ 引入但 4.0 已改，直接用 `Mockito.mock()` | 测试 |

**结论**：任何导入 `org.springframework.boot.web.client` 或 spring-security 全家桶的操作需先对照此表。

## Monorepo 边界（改 common-lib 必读）

- `apps/` 下 5 个 Maven 模块：`common-lib` + 4 服务；common-lib 是零 Spring 依赖共享内核
- **改 `common-lib` 必须 4 处同步**（漏一个就爆炸）：
  1. 父 POM `apps/pom.xml` 的 `<modules>` 列表
  2. 依赖服务 POM 加 `<dependency>com.bank:common-lib`
  3. 每个 Dockerfile：builder sed 掉父 POM `<modules>` 块 → `mvn install -N` 父 → `mvn install` common-lib → `mvn package` 服务
  4. CI workflow 构建顺序：`mvn install -pl common-lib -am -DskipTests` 必须先跑
- Dockerfile 模板：builder `maven:3.9-eclipse-temurin-21-alpine`；runtime `eclipse-temurin:21-alpine` + 非 root `appuser` + `HEALTHCHECK /actuator/health/liveness`

## K8s / GitOps

- `infra/kubernetes/base/kustomization.yaml` 只管核心资源，**显式排除**：`sealed-bank-mall.yaml`、`mysql/secret.yaml`、`monitoring/`、`tempo/`、`security/`、`hpa/`、`argocd/`（各自由 deploy.sh step 或 ArgoCD Application 单独 apply）
- 3 个 ArgoCD Application CR 分拆管理：`bank-mall-apps`（服务+MySQL）、`bank-mall-monitoring`（监控）、`bank-mall-infra`（ingress/security/hpa/configmap/secret）
- **ArgoCD selfHeal 3 分钟内回滚 `kubectl set/edit`** —— 线上改动必须走 git commit & push
- `infra/helm/bank-mall/` **NOT FOR DEPLOYMENT** —— 仅骨架，Kustomize base 是唯一权威
- MySQL 是 StatefulSet（`nodeName: k8s-worker01` + hostPath PV `/data/mysql`）；集群非 HA：1 master + 2 workers + 1 harbor，MySQL/Ingress/Loki/Prometheus 全单点
- 8 个 SealedSecret key：`DB_PASSWORD`/`DB_USERNAME`/`HARBOR_PASSWORD`/`HARBOR_USERNAME`/`JWT_SECRET_KEY`/`MYSQL_PASSWORD`/`MYSQL_ROOT_PASSWORD`/`MYSQL_USER`

## CI（两条并行 live）

- **Path 1 — GitHub Actions**（`ci.yml`）：gitleaks→semgrep→test→build+trivy hard gate（仅 main，`push:false` 不推镜像）
- **Path 2 — Internal harbor01**（`scripts/ci.sh`）：真实 CD 路径，Maven test(默认跳过)→package→build+push Harbor→Trivy soft gate→git push 触发 ArgoCD→verify。**唯一实际推送镜像的链路**
- 注意两路径 gate 强度不一致（GH 硬 vs ci.sh 软），改动时保持对齐

## 测试怪癖

- `MockMvcBuilders.standaloneSetup()` + `Mockito.mock()`（SB 4.0.6 已移除 `@MockBean`/`@WebMvcTest`）
- `maven-surefire-plugin` 需 `-XX:+EnableDynamicAgentLoading` 适配 JDK 21 的 Mockito（父 POM 已配）
- `tests/k6/payment-load.js` 路径已修复（`/payment/api/payments`，PR #48）；压测可用 k6 或零依赖的 `tests/payment-load.sh`
- 单测 49 个，分布在 8 个测试类（controller/service/util）

## 安全工具链

- `make lint` = `semgrep --config=auto apps/` + `gitleaks detect --no-git`
- pre-commit 单 hook：gitleaks v8.30.1
- `.gitleaks.toml`：allowlist 排除 `docs/.*` / `sealed-*.yaml` / `*.md`（避免 SealedSecret 加密数据误报）
- 机密约定：`secret.yaml` 永不入 git；真机密通过 SealedSecret 解密注入

## 已知问题（改动前必读）

- ~~**P0 冲正 bug**~~ **已修复**（`c1be4e9`，PR #47）：`reverseWithRetry` 曾把 `"debit-"+idempotencyKey` 当 `originalTransactionNo` 传给 account 侧 → 冲正必失败；现传 `debitResp.getTransactionNo()`，测试契约已重写
- **P0 零鉴权（仍未修）**：account/payment/notification 无任何 JWT/Filter 校验，debit/credit/payments 接口任何人可调且 Ingress 公网暴露（无 host/TLS）；MySQL 连接 `useSSL=false`
- ~~**P0 部署断裂**~~ **已修复**（`08b06a4`，PR #50）：deploy.sh 对 gitignored `mysql/secret.yaml` 缺失降级为 warn、MySQL 改读 SealedSecret `bank-mall-secret`、initdb 删字面占位符；Tempo 部署入口补全（deploy.sh step `kubectl apply tempo/` + ArgoCD infra include tempo/ + `tempo/namespace.yaml`）
- **测试资产**：k6 路径已修复（PR #48 加 `/payment` 前缀，2026-09-05 实测确认）；`tests/jmeter/` 已删除，ROADMAP 的 JMeter 声明已校正为 payment-load.sh

## Git 工作区约定

- 本目录是**唯一**开发工作区；WSL `/home/shelton/projects/bank-mall-platform` 是**只读备份**（2026-08-21 归档，资产已迁移），禁止在其开发提交
- `CLAUDE.md`/`MEMORY.md`/`TECH_AUDIT_REPORT.md`/`GLM_AUDIT_REPORT.md`/`.opencode/`/`.claude/` 被 .gitignore 忽略，只存在于本机，clone 不带
- 审计文档：`docs/AUDIT_FINAL.md`（REDACT 后已入库）；`docs/audit-private/`（TECH/GLM 报告 + gitignore 说明，gitignored 保持私有）
- 优化路线图：见 `docs/optimization-roadmap.md`（P0/P1/P2 全量清单，修复后回填状态）

## 协作约定

- **默认多 agent 协同**：涉及 3+ 个独立文件或跨项目操作时，自动并行派 agent
- **记住用户的明确拒绝**：用户说"不接受 X 方案"后，不再重复提议
- **犯错后停下来**：连续 2 次同类型错误 → 停止执行，分析根因再继续
- **步骤标注依赖和备选**：每个操作步骤标注"若失败，备选方案是什么"
- **版本审计**：每个 Phase 收尾时检查依赖 EOL 状态（参照 `docs/tech-stack-audit.md` 模板）
- **事故复盘**：重大故障后写复盘报告，提取可复用技能/规则，写入本文件
- **前端 Bug 诊断**：任何 UI "无数据" → 先开 F12 Network Tab 确认 API 请求 URL 正确，再动后端
- **网络排障守则**：绝不 `iptables -F` 在运行中的 K8s 节点上
- **探针配置**：Spring Boot + OTEL 启动慢 → 用 `startupProbe`（30×10s=300s 窗口），不用 `initialDelaySeconds`

## 常见故障速查

### VM 冷启动
- Harbor: `systemctl restart docker && iptables -P FORWARD ACCEPT`
- Calico: `kubectl delete pod -n kube-system -l k8s-app=calico-node`
- kube-proxy: `kubectl delete pod -n kube-system -l k8s-app=kube-proxy`

### 镜像 tag 不匹配
- `build-images.sh` 默认用 `git describe --always`，deployment YAML 用 `2.0.0`
- 修复：构建后手动 `docker tag` 或统一版本源

### 探针太短
- 修复后 Bank-mall Pod 用 startupProbe (30×10s=300s)
- Jaeger/Tempo 用 livenessProbe initialDelaySeconds=300s

### 前端 UI Bug（Jaeger 1.60）
- Jaeger 1.60 JS 把搜索请求拼错 URL 路径 → 已迁移至 Grafana Tempo
- 通用方法：F12 Network Tab → 查实际 API 请求 → 对比文档中的正确 API URL
