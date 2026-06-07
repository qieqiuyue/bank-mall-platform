# 全景评估报告 —— Bank Mall Cloud-Native Platform（终版）

> **审计引擎**：Claude Opus 4.8（R1+R3）+ Kimi 2.6（R2+R4）+ GLM-5.1（R5）
> **审计日期**：2026-06-08 · **五轮交叉验证**
> **方法论**：逐文件、逐行、逐配置项解剖式扫描。拒绝任何"能跑就行"的妥协。基准为企业级 Java/K8s 项目标准（非 OWASP ASVS/CIS——本项目为实验集群，非生产环境）。

---

## 一、合并发现（终版目录）

| 编号 | 严重度 | 来源 | 维度 | 问题 | 首次提出 |
|------|:---:|------|------|------|------|
| F01 | 🔴 | 五轮 | 安全 | JWT_SECRET 硬编码默认值 `ZGV2LXNlY3JldC1rZXktY2hhbmdlLWluLXByb2R1Y3Rpb24tMzJjaGFycw==` → Base64 解码为 `dev-secret-key-change-in-production-32chars`。Spring `${VAR:default}` 语义：环境变量未注入 = 默认值生效 | Kimi R2 |
| F02 | 🔴 | GLM | 安全 | DataInitializer 硬编码密码 `"123456"`、`"vip123"`，无 `@Profile` 隔离。攻击者看源码即可登录 | GLM-5.1 ENG-002 |
| F03 | 🔴 | Kimi R3 | 安全 | auth-service 无 `GlobalExceptionHandler`——account/payment/notification 都有，唯独 auth 没有。任何未捕获 RuntimeException → 500 堆栈泄露到客户端 | Kimi R3 STRUCT-001 |
| F04 | 🔴 | Kimi R3 | 安全 | `AccountClient.callAccount()` 中 `catch (BusinessException e) { throw e; }` 为无用代码；`catch (Exception e)` 将超时/反序列化失败全部伪装成 `ACCOUNT_SERVICE_UNAVAILABLE` | Kimi R3 CODE-003 |
| F05 | 🔴 | GLM | 代码 | `Payment` 实体无参构造函数有副作用：`new Payment()` 自动 `UUID.randomUUID()` + `status="PENDING"`。JPA 每次反射实例化都调此构造函数 | GLM-5.1 ENG-006 |
| F06 | 🔴 | GLM | 代码 | User 实体只有一个 setter（`setUserId`）——username、password、roles 创建后不可变。系统没有"修改密码"能力 | GLM-5.1 ENG-003 |
| F07 | 🔴 | GLM | 代码 | User.roles 存储为逗号分隔字符串 `"CUSTOMER,MALL_USER"`——无法 SQL 查询"所有 VIP 用户"，无法外键约束 | GLM-5.1 ENG-003 |
| F08 | 🔴 | GLM | 代码 | `@SecurityRequirement(name = "BearerAuth")` 只是 OpenAPI 文档标注，非运行时强制。任何人可 curl `/api/auth/users/U1001` 无 Token 获取用户敏感信息 | GLM-5.1 ENG-004 |
| F09 | 🔴 | GLM | 运维 | auth-service 的 `/health` 调用 `userRepository.count()`——DB 挂了 → probe 失败 → kubelet kill → 但 DB 挂了 auth 也起不来 → 级联死锁 | GLM-5.1 OPS-001 |
| F10 | 🔴 | Claude R3 | infra | Ingress Jaeger 路由指向不存在的 Service：`name: jaeger-ui` → 应为 `name: jaeger-query`。所有通过 Ingress 的 Jaeger 访问从 S2 起就是 503 | Claude R3 R3-1 |
| F11 | 🔴 | 两轮 | Swagger | account/payment/notification Controller 无 `@Tag/@Operation` 注解。Swagger UI 打开是空白页 | Claude R1 #1 |
| F12 | 🔴 | Claude R1 | API | auth-service `ApiResponse.java` 缺少 `// NOTE: Duplicated. Keep in sync.` 注释 | Claude R1 #2 |
| F13 | 🔴 | Claude R1 | 文档 | CLAUDE.md 包含已删除文件的引用 | Claude R1 #6 |
| F14 | 🟡 | GLM | API | `PaymentRequest.orderId` 是幽灵字段——声明了 getter/setter 但 Service 层从不引用。客户端传了等于白传，欺骗性 API 契约 | GLM-5.1 ENG-005 |
| F15 | 🟡 | GLM | 代码 | 所有列表接口零分页——`NotifyController.listByAccount()` + `AccountController.getTransactions()` 返回全量数据，10万条 = OOM | GLM-5.1 ENG-007 |
| F16 | 🟡 | Claude R3 | 代码 | Transaction ID 生成用 `LocalDateTime.now()` 毫秒精度——两个线程同一毫秒 = PK 冲突 | Claude R3 R3-2 |
| F17 | 🟡 | 两轮 | DTO | 零 Bean Validation（`@NotNull`/`@NotBlank`/`@Positive`）在所有 Request DTO 上 | Claude R1 #3 |
| F18 | 🟡 | Kimi R3 | 代码 | PaymentService.reverse `saveTxn(payment, null, ...)`——冲正交易传 null 作为 transactionNo，无法追溯 | Kimi R3 CODE-004 |
| F19 | 🟡 | Kimi R2 | 代码 | RestClient 无连接池——`SimpleClientHttpRequestFactory` 单连接，高并发端口耗尽 | Kimi R2 ARCH-003 |
| F20 | 🟡 | Kimi R2 | 代码 | MySQL `useSSL=false` + `allowPublicKeyRetrieval=true` 硬编码在 4 个 application.yml | Kimi R2 SEC-004 |
| F21 | 🟡 | GLM | 代码 | DataInitializer 在所有 Spring Profile 下执行（无 `@Profile("dev")` 隔离） | GLM-5.1 ENG-002 |
| F22 | 🟡 | Kimi R2 | 代码 | `Thread.sleep(debitDelayMs)` 混沌代码在生产代码中，无 `@Profile("chaos")` 隔离 | Kimi R2 ARCH-006 |
| F23 | 🟡 | Claude R1 | 代码 | AccountClient + NotificationClient 无独立测试 | Claude R1 #4 |
| F24 | 🟡 | Claude R3 | 代码 | AccountController `legacyBalance` 死代码——V1 mock 遗留 | Claude R3 R3-3 |
| F25 | 🟡 | GLM | 代码 | `NotificationService` 默认内容策略是字面字符串 `"Notification content"`——测试数据当生产内容 | GLM-5.1 CODE-009 |
| F26 | 🟡 | GLM | 运维 | 零 CORS 配置、零安全响应头（HSTS/X-Content-Type-Options/CSP）、零请求关联 ID | GLM-5.1 CODE-008 |
| F27 | 🟢 | Claude R1 | infra | 4 个 settings.xml 完全相同（指向 `repo.maven.apache.org`） | Claude R1 #10 |
| F28 | 🟢 | 两轮 | 测试 | 无 repository 集成测试（`@DataJpaTest`） | Claude R1 #5 |
| F29 | 🟢 | Claude R3 | 代码 | `NotifyController.templates()` 硬编码 3 条通知模板 | Claude R3 R3-7 |
| F30 | 🟢 | Kimi R2 | 代码 | auth-service `ddl-auto: update`（其他 3 服务为 `validate`） | Kimi R2 ARCH-001 |

---

## 二、GLM-5.1 独占发现详解

### F02 — DataInitializer 硬编码密码（P0）

**证据**（`auth-service/DataInitializer.java` 第 17-23 行）：

```java
repo.save(new User("admin", encoder.encode("123456"), "U1001", ...));
repo.save(new User("vip01", encoder.encode("vip123"), "U1002", ...));
repo.save(new User("tester", encoder.encode("test123"), "U1003", ...));
```

**致命点**：
1. 这个 `CommandLineRunner` 没有 `@Profile("dev")` 或 `@Profile("test")`。`@Configuration` 意味着它在所有 Profile 下都执行
2. `if (repo.count() == 0)` 看起来安全，但如果生产库被清空重启 = 自动灌入 demo 数据
3. 如果有人手动删了 admin 用户，下次重启自动重建——密码是源码明文的 `"123456"`
4. `account-service/DataInitializer` 同理：灌入 8888.88/50000.00 余额 demo 账户

**攻击路径**：攻击者看 GitHub 源码 → 得知 `admin:123456` → 登录生产。即使用户改了密码，`repo.count() == 0` 条件一旦满足就自动重建。

### F05 — Payment 构造函数的 UUID 副作用（P0）

**证据**（`Payment.java` 第 46-49 行）：

```java
public Payment() {
    this.paymentNo = UUID.randomUUID().toString();
    this.status = "PENDING";
}
```

**致命点**：
- JPA 要求无参构造函数用于反射实例化。但这个构造函数有副作用：每次创建对象都生成一个新 UUID
- 如果从 JSON 反序列化 Payment 对象（如 Kafka 消息），Jackson 先调用无参构造函数生成 UUID，再覆盖字段
- 如果反序列化框架不覆盖 `paymentNo`（如 JSON 缺少该字段），支付记录会用随机 UUID
- `@PrePersist` 在 `onCreate()` 中又设置了 `createdAt`/`updatedAt`，与构造函数副作用叠加

### F06 — User 实体只有一个 setter（P0）

**证据**（`User.java`）：

```java
// Setter 只有一个！
public void setUserId(String userId) { this.userId = userId; }
```

`username`、`password`、`roles`、`level`、`riskLevel` 等所有业务字段创建后不可变。系统没有"修改密码"、"修改角色"、"升/降级"能力。User 实体也没有 `@Version` 乐观锁（Account 实体有）。

### F07 — User.roles 逗号分隔反模式（P0）

```java
@Column(length = 128)
private String roles;  // "CUSTOMER,MALL_USER"
```

无法 SQL 查询"所有 VIP 用户"（`LIKE '%VIP%'` 可能误匹配），无法外键约束。角色管理完全是字符串游戏。

### F08 — `@SecurityRequirement` 无运行时强制（P0）

`AuthController.userProfile()` 上有 `@SecurityRequirement(name = "BearerAuth")`，但这只是 OpenAPI 文档标注——运行时没有任何 Filter/Interceptor 检查 Token。任何人可以 curl `/api/auth/users/U1001` 获取敏感信息：

```bash
curl http://10.0.0.41:30080/auth/api/auth/users/U1001
# → {"code":"SUCCESS","data":{"userId":"U1001","name":"Demo Bank Customer","level":"GOLD","riskLevel":"LOW"}}
```

### F09 — Liveness Probe 的 DB 依赖级联崩溃（P0）

```java
@GetMapping("/health")
public ApiResponse<Map<String, Object>> health() {
    return ApiResponse.success("auth-service is healthy", Map.of(
        "status", "UP",
        "service", "auth-service",
        "users", userRepository.count()  // ← DB！
    ));
}
```

如果 MySQL 不可达 → `DataAccessException` → auth-service 无 GlobalExceptionHandler → 500 → liveness probe 失败 → kubelet kill Pod → 但 DB 挂了 auth 也起不来 → **死锁循环**。

### F14 — PaymentRequest.orderId 幽灵字段（P1）

```java
// PaymentRequest.java
private String orderId;  // 声明 + getter/setter

// PaymentService.java — processPayment() 中没有任何地方引用 orderId
```

客户端可以传入 `orderId`，服务端完全忽略。金融系统中客户期望 orderId 与支付关联——实际关联的是 idempotencyKey。欺骗性 API 契约。

---

## 三、排除的发现及理由

| 发现 | 排除理由 |
|------|---------|
| "必须引入 Spring Security" | 项目有意识选择：BCrypt + JWT 独立引入，CONTRIBUTING 已说明 |
| "零 Rate Limiting" | V1 实验集群，V2 规划（已写入 ROADMAP） |
| "全栈 HTTP 无 TLS = 重大安全" | 集群跑在 VMware NAT 内网，外部不可达 |
| "MySQL 必须用 StatefulSet" | 实验集群 hostPath PV，Deployment vs StatefulSet 无差异 |
| "CI push: false = 镜像不可用" | 设计如此——GitHub Actions 做质量门，harbor01 ci.sh 做构建推送，双通路 |
| "缺少 SECURITY.md" | 已故意删除（2026-06-08）——内容过时+重复 |
| "文档编号 13/14 混乱" | 故意的——这两份是高频参考，编号表示原序列位置 |
| Kafka + Istio + Redis + Saga + mTLS + cert-manager | 企业级 50 人团队技术栈，单人项目不可行 |
| "脚本缺少执行权限" | `make ci` 用 `bash scripts/ci.sh` 显式调用——make + shell 标准做法 |

---

## 四、修复计划

### 阶段一：立即（P0，< 4h）

| # | 项 | 方法 |
|---|------|------|
| F01 | JWT_SECRET 默认值 | 改为 `${JWT_SECRET_KEY:}` 无默认值，未注入则启动失败 |
| F02 | DataInitializer 加 `@Profile("dev")` | `@Configuration @Profile("dev")` 或 `@ConditionalOnExpression` |
| F03 | auth-service 补齐 GlobalExceptionHandler | 从 account-service 复制模板，改包名 |
| F04 | AccountClient 异常处理重写 | 删 `catch (BusinessException e) { throw e; }`，区分超时/反序列化/业务错误 |
| F05 | Payment 构造函数副作用移除 | `paymentNo` 改为 `@PrePersist` 中生成，status 默认值用字段初始化 |
| F06-F07 | User 实体加 setter + @Version | 添加 `setUsername/setPassword/setRoles`；添加 `@Version` |
| F08 | userProfile 加 Token 校验 | Controller 中手动 `jwtUtil.validateToken(authorization)` |
| F09 | Health endpoint 移除 DB 依赖 | 删除 `userRepository.count()`，只返回 `status: UP` |
| F10 | Jaeger Ingress 修正 | `name: jaeger-ui` → `name: jaeger-query` |
| F11 | Swagger 注解补全 3 服务 | 参照 auth-service 模板 |
| F13 | CLAUDE.md 清除删除文件引用 | 删 `SECURITY.md` `pipelines/` `sql/` `libs/` `tests/k6/` 行 |

### 阶段二：本周（P1，< 10h）

| # | 项 |
|---|------|
| F14 | PaymentRequest.orderId 标注 `@Deprecated` + 注释 "Not used. Retained for API compatibility." |
| F15 | 所有 list 接口加 `@PageableDefault` + `Pageable` 参数 |
| F16 | Transaction ID 加纳秒精度 + UUID fallback |
| F17 | DTO Bean Validation |
| F18 | reverse saveTxn 传有效 transactionNo |
| F19 | RestClient 连接池化 + add request/response logging interceptor |
| F20 | MySQL SSL 参数加注释 `# mitm-safe: internal NAT network` |
| F21 | DataInitializer 加 `@Profile("dev")`（同 F02） |
| F22 | `Thread.sleep` → `@Profile("chaos")` 隔离 |
| F25 | `NotificationService` 默认内容 null-safety + `@NotBlank` |
| F26 | 安全响应头 + 请求关联 ID（`X-Request-Id` filter） |

### 阶段三：长期

| # | 项 |
|---|------|
| F23 | AccountClient/NotificationClient 测试 |
| F24 | 删 `legacyBalance` 死代码 |
| F27 | settings.xml 去重 |
| F28 | Repository 集成测试 |
| F29 | 通知模板 → ConfigMap |
| F30 | ddl-auto 统一为 validate |

---

**总评**：五轮审计从 Claude R1 的 11 条收敛到终版 30 条。最后一轮 GLM-5.1 贡献了最有价值的 6 条专属发现（F02/F05/F06/F07/F08/F09），集中在实体设计、构造函数副作用和安全运行时缺失——这些是纯架构师视角才能抓到的深层问题。按阶段一实施后，P0 清零。按阶段二实施后，达到生产级最低门槛。
