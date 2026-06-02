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

### 待完成
- [ ] H2 启动 + curl 全端点验证
- [ ] Docker 构建 + 推 Harbor
- [ ] K8s 更新 JWT Secret + 滚动部署 auth/account 两个服务
- [ ] Ingress 全链路验证

---
