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
