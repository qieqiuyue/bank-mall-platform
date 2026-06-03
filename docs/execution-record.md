# 项目演进实录

> 只记录关键决策偏离、踩坑、时间实际消耗、面试故事素材。
> 一个阶段一小节，严格筛选，不堆日志。
> 计划书看 `技术文档V1.0.0-银行商城云原生平台.md`，变更精度看 `git log`。

---

## S0：平台抢救 + 前置验证

**日期**：2026-06-02  
**计划时间**：15h → **实际**：约 6h  
**状态**：✅ 完成

### 关键决策偏离

| # | 计划 | 实际 | 原因 |
|---|------|------|------|
| 1 | Spring Boot 3.2+ | **4.0.6** | 调研发现 3.5.x OSS 支持将于 2026-06-30 终止（只剩 1 个月），4.0.6 是当前最新 GA（2026-04-23 发布），直接上新版本。Spring Framework 由 6.1 升级至 7.0 |
| 2 | Java 17 | **21** | Spring Boot 4.0 推荐 JDK 21，LTS 支持到 2031 |
| 3 | RestClientCustomizer 配置 | **RestClient.Builder 注入** | `RestClientCustomizer` 在 Spring Boot 4.0 被移除（模块化重构），改用 `RestClient.Builder` 直接创建 Bean |

### 踩坑记录

#### 坑 1：Docker Hub 无法拉取

- **现象**：`docker build` 拉取 `eclipse-temurin:21-alpine` 超时，`Head "https://registry-1.docker.io/...": EOF`
- **根因**：中国大陆 GFW，`ghcr.io` 和 `docker.io` 均被阻断
- **解决**：改为直接在各 VM 上安装 JDK 21 + Maven，用阿里云 Maven 镜像编译，绕过 Docker 构建
- **面试话术**："国内环境拉 Docker Hub 镜像不稳定，我选择直接在构建节点上装 JDK 21 + Maven 编译 jar，再拷贝到 Docker 运行镜像。生产环境建议用 Harbor 缓存或配置 registry mirror"

#### 坑 2：H2 dialect 覆盖不彻底

- **现象**：`java -jar --spring.profiles.active=h2` 启动后报 `Syntax error in SQL statement "...engine=InnoDB"`
- **根因**：`application-h2.yml` 只配了 `spring.jpa.database-platform`，但 `application.yml` 里的 `spring.jpa.properties.hibernate.dialect: org.hibernate.dialect.MySQLDialect` 优先级更高，Hibernate 仍按 MySQL 语法生成 DDL（`engine=InnoDB` + `auto_increment`）
- **解决**：`application-h2.yml` 中同时覆盖 `spring.jpa.properties.hibernate.dialect: org.hibernate.dialect.H2Dialect`
- **教训**：Spring Boot 配置优先级：profile-specific 的 `spring.jpa.properties` 可以覆盖默认 `application.yml` 的同名属性，但 `spring.jpa.database-platform` 不一定改 dialect

### S0 三条前置验证结果

| # | 验证项 | 结果 | 后续影响 |
|---|--------|:---:|---------|
| 1 | `curl https://github.com` 4 台 VM | ✅ | ArgoCD 走公网 Git（决策 3 公网方案成立） |
| 2 | `curl ghcr.io` 4 台 VM | ❌ | 镜像推送用 `ghcr.nju.edu.cn` 替代 `ghcr.io`；内网 Harbor 不受影响 |
| 3 | Spring Boot + RestClient 编译 + auth-service 启动 | ✅ | 升级至 4.0.6，RestClient Bean 注入成功，health 接口返回正常 |

### 面试故事素材

**“为什么要从 SB 3.1 直跳 4.0.6？”**
> 原项目用的是 Spring Boot 3.1，那个版本 2024 年 5 月就 EOL 了。我研究了一下生态现状：3.5.x 的 OSS 社区支持到 2026 年 6 月 30 日，只剩一个月。4.0.6 是 2026 年 4 月刚发的 GA，底层 Spring Framework 7.0。Java 也同步从 17 升到 21。升级过程很顺利——就改了个 RestClient 配置类，因为 Boot 4.0 移除了 RestClientCustomizer 接口，改成了 Builder 注入模式。

**“升级过程有没有出问题？”**
> 有一个小坑。RestClientCustomizer 在 Boot 4.0 被移除了，编译直接报 `package org.springframework.boot.web.client does not exist`。查了一下是 Spring Boot 4.0 模块化重构把 web.client 包拆了。改成直接注入 RestClient.Builder 创建 Bean 就好了——两行代码的事。

---

## S1 CP1：account-service 新写 + auth-service JWT 改造

**日期**：2026-06-02  
**计划时间**：18h → **实际**：约 6h  
**状态**：✅ 代码完成，测试通过，待 K8s 部署验证

### 产出

| 服务 | 新建 | 修改 | 测试 | 结果 |
|------|:---:|:---:|:---:|:---:|
| account-service | 23 文件 | 0 | 15 测试 | ✅ BUILD SUCCESS, 0 failures |
| auth-service | 2 文件 | 4 文件 | 11 测试 | ✅ BUILD SUCCESS, 0 failures |

### 踩坑

#### 坑 1：SB 4.0 测试注解变更
- **现象**：`@MockBean` 和 `@WebMvcTest` 编译失败，`package org.springframework.boot.test.mock.bean does not exist`
- **根因**：`@MockBean` 在 SB 3.4+ 重命名为 `@MockitoBean`，但 SB 4.0 将其移除得更彻底；`spring-boot-starter-test` 不再自动引入 `spring-boot-test-autoconfigure`
- **解决**：改用 standalone `MockMvcBuilders.standaloneSetup()` + 普通 `Mockito.mock()`，零 SB 注解依赖，只依赖 `spring-boot-starter-test`

#### 坑 2：reverse 事务类型错误
- **现象**：`reverse_success` 测试失败，期望 `REVERSAL` 实际返回 `CREDIT`
- **根因**：`reverse()` 方法直接复用 `doCredit()`/`doDebit()`，创建的事务类型是 CREDIT/DEBIT
- **解决**：新增 `doCreditWithType()`/`doDebitWithType()` 方法，reverse 调用时传入 `TransactionType.REVERSAL`

#### 坑 3：XML 标签重复
- `</dependency>` 编辑时意外重复，导致 POM 不可解析。手动删除修复。

### 关键决策

- **ApiResponse 独立复制**：两个服务各持一份 ApiResponse.java，加 `// NOTE: Duplicated. Keep in sync.` 注释。避免 Maven 多模块重构。
- **Account 主键用 String accountNo**：来自核心银行系统的自然键，不用自增 ID。
- **乐观锁手动 3 次重试**：不引入 spring-retry，减少 Java 依赖。
- **Flyway 管理 schema**：`ddl-auto: validate`，JPA 不自动建表。
- **BCrypt 独立使用**：`spring-security-crypto` 只引入 BCryptPasswordEncoder，不引入全部 Spring Security。

### 面试故事素材

**“account-service 从 mock 重写为真实 JPA 服务”**
> 原来的 account-service 是完全 mock 的——所有数据硬编码，没有数据库，没有 JPA。我在 S1 把它重写了：Account + Transaction 两个 JPA 实体，Flyway 管理建表迁移，@Version 乐观锁防止并发扣款超扣，idempotency_key UNIQUE 约束防止重复处理。幂等检查和乐观锁重试都是手工实现的，没有引入 spring-retry——因为就 3 次重试，手工写比引入一个依赖更简单。

**“auth-service 明文密码 → BCrypt + JWT”**
> 原来的 auth-service 用明文存密码、用 ConcurrentHashMap 在内存里管 token。我做了两个改造：密码用 BCrypt 编码——只引入了 spring-security-crypto 这一个模块，不引入整个 Spring Security 框架。token 从 `"token-" + UUID` 改为 JJWT 签发的无状态 JWT，包含 userId/username/roles/expiration。这样 auth-service 变成无状态服务，多副本天然负载均衡，不需要 Redis 做 session 共享。

### 部署验证

**日期**：2026-06-02 深夜~2026-06-03 凌晨  
**结果**：✅ 两个服务成功部署，全链路验证通过

| 步骤 | 操作 | 结果 |
|------|------|:---:|
| Docker 构建 | harbor01 上 `docker build` + `docker push` 两个 2.0.0 镜像 | ✅ |
| Worker 预拉 | worker01/02 上 `ctr images pull --plain-http` | ✅ |
| JWT Secret | master01 上 `kubectl patch secret` 只更新 JWT_KEY 字段 | ✅ |
| NetworkPolicy | `allow-mysql.yaml` 新增 account/payment/notification 白名单 | ✅ |
| 滚动部署 | `kubectl set image` + `rollout restart` | ✅ |
| 数据修复 | `DELETE FROM bank_auth.users` 清旧明文密码，DataInitializer 重 seed | ✅ |
| 接口验证 | curl account balance + debit + auth login + validate + health | ✅ 6/6 |

### 部署踩坑

#### 坑 4：RestClient.Builder 自动注入失败
- **现象**：auth-service 启动报 `No qualifying bean of type 'org.springframework.web.client.RestClient$Builder'`
- **根因**：S0 写 RestClientConfig 时用了 `RestClient restClient(RestClient.Builder builder)` 依赖注入，在 SB 4.0 中 `RestClient.Builder` 不会自动注册为 Bean（与 SB 3.x 行为不同）
- **解决**：改为 `RestClient.builder()` 手动创建，不依赖注入。两个服务都修了。
- **教训**：SB 4.0 模块化后，很多自动配置的 Bean 消失了。带 `@Bean` 方法的参数注入不一定可靠。

#### 坑 5：NetworkPolicy 白名单遗漏
- **现象**：account-service Pod 不断重启，日志 `SocketTimeoutException: Connect timed out` 连 MySQL
- **根因**：`allow-mysql.yaml` 只放行了 `app: auth-service` 访问 MySQL 3306 端口，其他 3 个服务被 deny-all 策略拦截。旧集群只有 auth-service 真用了 MySQL，其他都是 mock。
- **解决**：修改 NetworkPolicy，增加 `app: account-service`、`app: payment-service`、`app: notification-service` 三条 podSelector。
- **面试话术**："之前只有 auth-service 用了 MySQL，其他服务是 mock。S1 把 account-service 重写后它需要自己的数据库，但 NetworkPolicy 忘了加白名单。这种问题在生产环境很常见——加一个新服务或者给旧服务新开一个数据库依赖，防火墙规则要同步更新。我用 `kubectl describe netpol` 定位到 deny-all 拦截了 3306 端口的出站流量，三分钟就修好了。"

#### 坑 6：Flyway 不执行 + Hibernate validate 阻塞
- **现象**：Pod `CrashLoopBackOff`，日志 `Schema validation: missing table [accounts]`
- **根因**：`ddl-auto: validate` 在 Flyway 之前校验表结构，而 bank_account 库是空的（表还没建）。Flyway 本应在 Hibernate 之前运行，但在本次部署中未触发（日志中无 Flyway 输出，待深入排查）。
- **解决**：先 `kubectl set env SPRING_JPA_HIBERNATE_DDL_AUTO=update` 让 Hibernate 直接建表，后续再切回 validate。不是最优方案，但保证了进度。
- **TODO**：排查 Flyway 为何在 K8s prod 环境下不触发（本地 H2 测试正常），可能和 `baseline-on-migrate` 或 MySQL 用户权限有关。

#### 坑 7：liveness probe 过短
- **现象**：Pod `Running` 但 `RESTARTS` 不断增加。新 account-service 启动需 ~60s（JPA 初始化），探活 30s 就杀 Pod。
- **解决**：`kubectl patch deployment account-service -- livenessProbe.initialDelaySeconds=120`
- **教训**：带 Flyway/JPA 的服务首次启动时间远超静态服务。探活配置应按最慢启动路径算，不能沿用 mock 服务的参数。

#### 坑 8：auth-service 旧数据不兼容 BCrypt
- **现象**：JWT 登录返回 `null`，验证 `AUTH_FAILED`
- **根因**：S0 时数据库里有 3 个用户的明文密码（`123456`），新代码用 `BCryptPasswordEncoder.matches()` 去匹配明文 → 永远失败。JWT 签发路径依赖 BCrypt 验证通过。
- **解决**：`DELETE FROM bank_auth.users` 清空，重启后 DataInitializer 用 BCrypt 重新 seed。
- **面试话术**："密码哈希算法升级是典型的破坏性变更。旧数据不兼容新算法，必须迁移。生产环境通常用两阶段迁移：先双写（同时支持旧哈希和新哈希验证），再批量重哈希，最后下线旧验证逻辑。我这里因为是实验环境直接清数据重建了——但如果面试官问，我会说清楚生产方案。"

### H2 本地验证（补充）
- ✅ `mvn test`：account-service 15 测试 + auth-service 11 测试，全部通过
- ✅ `java -jar --spring.profiles.active=h2`：两个服务本地启动正常，curl 验证通过

---

## S1 CP2：payment-service 新写 + 跨服务调用 + 补偿兜底

**日期**：2026-06-03 凌晨  
**计划时间**：19h → **实际**：约 5h  
**状态**：✅ 完成

### 产出

| 维度 | 数值 |
|------|------|
| 新建文件 | 22 |
| 修改文件 | 1 (deployment.yaml) |
| 测试 | 9 测试（6 service + 3 controller） |
| 新增表 | payments + payment_transactions |
| 新增 K8s 组件 | payment-service 2.0.0 |

### 关键设计

| 决策 | 选择 | 理由 |
|------|------|------|
| HTTP 超时 | connect 2s, read 3s | 支付链路不阻塞过久，避免线程池耗尽 |
| 结算账户 | `MALL-SETTLEMENT` 硬编码（默认值） | 平台工程演示，不走配置中心 |
| reverse 重试 | 手动 3 次循环，1s 间隔 | 不用 spring-retry，最少依赖 |
| db 幂等 | `payments.idempotency_key UNIQUE` | 与 account-service 的 DB UNIQUE 方案一致 |
| 故障留痕 | `fail_reason` 记录 credit 原始错误 + reverse 3 次失败 | 对账溯源完整信息 |

### 踩坑

#### 坑 1：PaymentTransactionRepository 漏文件
- **现象**：`mvn compile` 报 `cannot find symbol: class PaymentTransactionRepository`
- **根因**：写入文件时被权限系统拦截，创建了其他文件但漏了该 Repository
- **解决**：补写 + 提交推送。**教训**：写完文件后 `find src -name "*.java" | wc -l` 确认数量

#### 坑 2：NetworkPolicy Egress 拦截跨服务 HTTP
- **现象**：payment-service 日志 `Connect timed out` 调用 account-service:8082
- **根因**：`deny-all` 策略默认拒绝所有出站流量。`allow-services-egress` 只放了 `app: mysql` 的 3306 端口，没有放行服务间 HTTP（8081-8084）
- **解决**：新增 `allow-services-ingress` + 修改 `allow-services-egress` 增加 4 个服务端口
- **教训**：每当有新服务加入跨服务调用链时，NetworkPolicy 的 ingress/egress 双向都要更新

#### 坑 3：liveness probe 30s 不够
- **现象**：Pod 启动 ~68s（JPA + Flyway），30s 探活开始杀 Pod
- **解决**：`kubectl patch` 改到 120s + `kubectl apply deployment.yaml`
- **复用 CP1 经验**：直接设置 initialDelaySeconds=120

#### 坑 4：MALL-SETTLEMENT 账户不存在
- **现象**：ERROR_MANUAL_REVIEW，`Account not found: MALL-SETTLEMENT`
- **根因**：account-service DataInitializer 只 seed 了 A1001 + A1002，没有结算账户
- **解决**：`INSERT INTO bank_account.accounts` 手动插入，同时修改 DataInitializer 增加第三条 seed

#### 坑 5：deployment.yaml 的 image tag 生效需要 rollout restart
- **现象**：`kubectl apply -f` 更新了 yaml，但 Pod 仍是 1.0.0 镜像
- **根因**：Deployment 只追踪 `.spec.template` 的变化触发滚动更新。`kubectl apply` 更新后需手动 `kubectl set image` 或 `kubectl rollout restart`
- **解决**：组合 `kubectl set image` + `kubectl rollout restart`

### 验证结果

```bash
# 全链路成功
curl -X POST .../payment/api/payments -d '{"amount":50,..."idempotencyKey":"CP2-SUCCESS-001"}'
→ status: COMPLETED, paymentNo: ce5bfd62-...

# 余额正确
A1001: 8888.88 → 8389.88 (-100, CP2-SETTLE-001)
MALL-SETTLEMENT: 0 → 150 (+100 + +50)

# 补偿验证
无 MALL-SETTLEMENT 时 → status: ERROR_MANUAL_REVIEW
fail_reason: "Debit succeeded but credit+reverse both failed..."
```

### 面试素材

**"你怎么测试补偿逻辑？"**
> 我故意不创建结算账户 MALL-SETTLEMENT，发了一笔支付。扣款成功了但入账失败，reverse 被自动触发并执行了 3 次重试。最终 payment 状态变成 ERROR_MANUAL_REVIEW，fail_reason 里记录了 credit 的错误信息和 reverse 的 3 次重试结果。然后我手动插入结算账户，再发一笔，支付链路完全正常——扣款→入账→流水全部一致。

**"跨服务调用怎么处理超时和失败？"**
> RestClient 设了连接 2 秒、读取 3 秒的超时。如果 account-service 不可达，AccountClient 会抛 BusinessException(ACCOUNT_SERVICE_UNAVAILABLE)，PaymentService 捕获后走补偿分支——已扣款则冲正，未扣款则直接标记 FAILED。这比用 WebClient.block() 更干净——同步调用不需要引入 Reactor 栈。

---

## 分支策略（2026-06-03 切换）

S0-S1 期间使用 `feat/s0-cluster-verification` 承载所有前期工作。

自 S1 CP2 完成后切换为新命名规则：
- **已完成分支**：`feat/s0-cluster-verification` → PR 合并 → main → **删除**
- **未来分支**：从 main 拉 `feat/<feature-name>`、`fix/<description>`
- **当前 S1 CP3 下一步**：`git checkout -b feat/notification-service`（从 main）
- VM 上：`git checkout main && git pull` 永远用最新

**实际操作**：
1. GitHub 上把 `feat/s0-cluster-verification` 通过 PR 合并到 main
2. WSL2：`git checkout main && git pull && git branch -d feat/s0-cluster-verification`
3. WSL2：`git checkout -b feat/notification-service`（CP3 开发）
4. VM：`git checkout main && git pull origin main`（永远拉 main 部署）

---

## S2 Day 1：Jaeger + ArgoCD + Sealed Secrets + 安全加固

**日期**：2026-06-03  
**计划时间**：56h（全 S2）→ **Day 1 实际**：~10h  
**状态**：PDB/Quota/Limits ✅ | deploy.sh fix ✅ | Jaeger ✅ | ArgoCD ✅ | Sealed Secrets ✅

### 产出

| 模块 | 文件 | 关键决策 |
|------|------|---------|
| PDB | 4 服务 minAvailable=1 | 防 kubectl drain 杀光 |
| ResourceQuota | bank-mall cpu:8 mem:16Gi | namespace 级资源隔离 |
| LimitRange | 默认 100m/256Mi | 容器必须声明 resources |
| deploy.sh | `k8s/base` → `infra/kubernetes/base` | 路径 Bug 修了 |
| Jaeger | all-in-one + PVC + Badger + OTEL agent | 零代码注入，initContainer 下载 |
| ArgoCD | CRD + 3 Application CRs + auto-sync | GitOps 集群改 Go push |
| Sealed Secrets | controller + kubeseal + 加密 | 零明文 secret 存 Git |
| NetworkPolicy | Jaeger ingress/egress + port 16686 | 每次加服务必更新 |

### 踩坑（按严重程度）

#### 🔴 P0：ArgoCD selfHeal 覆盖手动部署
- **现象**：ArgoCD sync 后 account-service 被回滚到 SB 3.1.3 + JDK 17 旧镜像
- **根因**：Git 里 deployment.yaml 的 image tag 是 `1.0.0`，集群里是 `kubectl set image` 改的 `2.0.0`。ArgoCD selfHeal 把集群拉回 Git 状态
- **解决**：更新 Git 中 4 个 deployment.yaml 的 image tag 为 `2.0.0`，Git 成为唯一真相源
- **教训**：**GitOps 启用后，任何 kubectl 直接改集群都会被覆盖。** 所有变更必须走 Git → ArgoCD

#### 🔴 P1：GitHub 文件下载域名被墙
- **现象**：`curl https://github.com/.../kubeseal-0.27.3-linux-amd64.tar.gz` 在 VM 和 WSL2 都超时
- **根因**：GitHub API 通（`api.github.com`），但文件下载走 `objects.githubusercontent.com`，该域名被 GFW 阻
- **解决**：Windows 浏览器下载 → scp 到 VM
- **长期方案**：建一个内部静态文件服务（Harbor 或 Nginx），缓存常用二进制

#### 🟡 P2：Jaeger Ingress SPA 路径冲突
- **现象**：浏览器访问 `/jaeger/` HTML 正常，但页面全白。JS module 加载报 MIME type error
- **根因**：Ingress rewrite `/$2` 把 `/jaeger/static/foo.js` 重写为 `/static/foo.js`，Jaeger 返回 HTML
- **临时方案**：port-forward 替代 Ingress，后续需单独 issue 跟踪

#### 🟡 P3：containerd 镜像拉取不稳定
- **现象**：worker01 拉 quay.io 卡住，worker02 正常；worker02 拉 docker.io 不通
- **根因**：GFW 对不同节点的 TCP 干扰不同
- **解决**：Harbor 中转镜像 → worker 从 Harbor 拉

#### 🟢 P4：kubectl port-forward 端口残留
- **现象**：`kubectl port-forward` 被 kill 后端口不释放
- **解决**：`pkill -9 -f "port-forward"` 全清

### 工作流改进建议

| 问题 | 现状 | 改进 |
|------|------|------|
| 二进制下载 | curl GitHub → 超时 | Windows 浏览器下 → scp |
| 镜像拉取 | 每节点分别试 | Harbor 统一中转 |
| NetworkPolicy | 反复漏端口 | 加新服务模板 checklist |
| 多行粘贴 | heredoc 失败 | 推 GitHub 再 pull 或单行 echo |
| Git push | 只能在 WSL2 | 在 Windows 上统一操作 |
| ArgoCD 部署 | CLI bug + UI 不稳定 | kubectl 直接操作可行，别信 UI |

### S2 Day 2：Micrometer + Grafana + OTEL Agent + Dockerfile + Maven

**日期**：2026-06-04

#### Micrometer 指标 ✅
- 4 Metrics 类：PaymentMetrics, AccountMetrics, AuthMetrics, NotificationMetrics
- H2 本地验证通过：`payment_requests_total{status="FAILED"} 1.0`
- 指标自动暴露到 `/actuator/prometheus`（micrometer-registry-prometheus 已存在）
- 9 tests 全部通过，无回归

#### Grafana 业务看板 ✅（代码层面）
- `infra/dashboards/bank-mall-business.json`：支付 QPS、成功率、P99、账户操作、登录、通知
- `infra/dashboards/bank-mall-sli-slo.json`：可用性 SLI、P99 延迟、错误率、支付成功率
- ConfigMap 更新待部署阶段

#### OTEL Agent 注入 ✅（Agent 加载完成，Collector 连通性待修复）

**已验证**：
- `JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar` ✅ JVM 正确加载
- `opentelemetry-javaagent version: 2.28.1` ✅ Agent 启动
- `OTEL_SERVICE_NAME=payment-service` ✅ 服务标识
- emptyDir + initContainer（Harbor otel-agent-init 镜像）解决 PodSecurity hostPath 限制

**已知问题**：
- Jaeger collector 端口（4317 gRPC / 4318 HTTP）从 bank-mall Pod 不可达，同节点 Pod IP 直连也超时
- Jaeger pod localhost:4318 返回 404（端口监听正常），排除应用层问题
- NetworkPolicy 双向白名单均已配置（bank-mall→jaeger egress + jaeger←bank-mall ingress）
- 根因推测：Calico iptables 规则或 Jaeger pod 网络接口绑定，非 OTEL 配置问题
- 后续：单独 issue 跟踪，可能需 `calicoctl` 排查或 Jaeger pod 重建

**临时方案**：OTEL agent 加载成功即是里程碑——agent 注入、JVM 参数、initContainer、PodSecurity 合规全部打通。Collector 连通性是网络层隔离问题。

#### Dockerfile non-root + HEALTHCHECK ✅（2026-06-04 完成）
- 4 个 Dockerfile 全部多阶段：`maven:3.9-eclipse-temurin-21-alpine` → `eclipse-temurin:21-alpine`
- `RUN addgroup -S appgroup && adduser -S appuser -G appgroup` + `USER appuser`
- `HEALTHCHECK` 用 wget 探活，与 K8s liveness probe 互补

#### Maven 父 POM ✅（2026-06-04 完成）
- `apps/pom.xml`：SB 4.0.6 + JDK 21 + jjwt 0.12.6 统一版本管理
- 4 个子模块：auth-service, account-service, payment-service, notification-service

### S2 Day 3：全链路调试 + 3 个 Fix（2026-06-04）

**耗时**：~8h 集群调试

#### 修复 1：Jaeger collector 连通性 ✅

**症状**：payment Pod → Jaeger `wget` 超时，OTEL `Failed to export spans`

**排查过程**（5 层弯路，详见 `docs/14-troubleshooting-handbook.md` §六）：
1. 怀疑 Calico IPIP 跨节点 → 迁同节点仍超时，排除
2. 怀疑多 egress policy 聚合 → 合并后仍超时
3. 怀疑 kube-proxy Service → Jaeger 连自己 ClusterIP 也超时（干扰信号）
4. 怀疑 egress deny-all → 删后仍超时
5. **怀疑 jaeger ingress 策略 → 删后通了！apply 回去不通**

**根因**：jaeger ingress NetworkPolicy 用 `namespaceSelector: {name: bank-mall}` 匹配来源，但 `bank-mall` namespace 缺少 `name=bank-mall` 标签。K8s `kubernetes.io/metadata.name` 不会模糊匹配 `matchLabels` 的 `name` key。

**修复**：
```bash
kubectl label ns bank-mall name=bank-mall
```
同步到 Git：`infra/kubernetes/base/namespace.yaml` 加 `name: bank-mall` label。

**结论**：这条策略从项目 A 时代起就无效，S2 第一次有跨 ns 流量才暴露。

#### 修复 2：PSA restricted warning ✅

4 个 deployment 的主容器和 otel-agent-init 补容器级 `securityContext`（`allowPrivilegeEscalation: false` + `capabilities.drop: [ALL]`）。

#### 修复 3：Jaeger runAsNonRoot 回退 ✅

Jaeger Go 二进制以 root 运行，PVC 已有 root 数据。撤 `runAsNonRoot`/`runAsUser`，保留 `fsGroup: 1000` + 容器级 securityContext。

#### Liveness probe 120→180

OTEL agent 加载增加约 30s 启动时间，慢节点总启动超 120s。payment/account/notification 三个服务的 `livenessProbe.initialDelaySeconds` 改为 180。

### S2 最终状态

| Phase | 状态 |
|-------|:---:|
| Dockerfile non-root + HEALTHCHECK | ✅ |
| Maven 父 POM | ✅ |
| Jaeger collector 连通性 | ✅ namespace label fix |
| Grafana ConfigMap 更新 | ✅ `infra/dashboards/` 已嵌入 |
| PSA restricted warning | ✅ 零 warning |
| liveness OTEL 延迟 | ✅ 120→180 |
| NetworkPolicy 跨 ns | ✅ jaeger ingress port 16686 |

**S2 整体交付**：6 个能力域全部完成，交付率 93%（Semgrep/Gitleaks 属 S3）。GitHub `feat/s2-platform-matrix` 分支，commit `57c6041`。

### 面试素材

**"你怎么处理国内开源的网络问题？"**
> 两种策略。镜像走 Harbor 中转——harbor01 上的 Docker 配了 NJU 镜像加速，拉下来后推到本地 Harbor，worker 节点从 Harbor 拉。二进制文件走 Windows 下载再 scp 进内网。ArgoCD 和 Jaeger 的 quay.io 镜像都是这么部署的。生产环境方案是阿里云 ACR 的海外同步功能。

**"Sealed Secrets 的原理？"**
> kubeseal 用 controller 的公钥加密 K8s Secret → 生成 SealedSecret CR → 存 Git → ArgoCD 同步到集群 → controller 用私钥解密创建真正的 Secret。加密后的内容在 Git 里就是一堆 base64，无法反推明文。密钥轮换时 controller 自动生成新密钥对，旧的 SealedSecret 仍然可解密。比 External Secrets + Vault 少一层外部依赖。
