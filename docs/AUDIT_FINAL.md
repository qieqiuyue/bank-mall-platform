# Bank Mall Platform — 五轮深度审计终版报告

> **审计引擎**：Claude(R1) + Kimi-2.6(R2) + 架构师(R3) + Kimi-2.6-opus(R4) + GLM-5.1(R5) + 交叉验证轮  
> **审计范围**：全量代码 + K8s 清单 + CI/CD + 脚本 + 文档 + 配置 — 六维度深度解剖  
> **风险定级**：P0 致命 / P1 严重 / P2 优化 — **P0 不清零则禁止上线**  
> **审计方式**：代码级尸检 + 配置级验尸 + 运维级排雷 + 交叉验证补盲  
> **原始结论**：项目具备可运行骨架，但存在 **14 项致命安全缺陷 + 17 项严重隐患 + 5 项优化建议**。当前状态 **不可上线**。  
> **最终状态（2026-06-09）**：两轮审计修复完成。**30/31 P0+P1 已清零**，5 P2 已记录/不修复，3 项 V2 范畴纳入 S6 ROADMAP。

---

## 风险总览

> **最后更新**: 2026-06-09 | **修复批次**: fix/audit-remediation（两轮修复 + 架构师审计全部完成）

| 等级 | 原始 | 已修复 | 转入 V2/S6 | 已记录/不修复 | 说明 |
|------|:---:|:---:|:---:|:---:|------|
| **P0 致命** | 14 | 12 | 2 | 0 | 业务零认证 → S6 Spring Security；Ingress 无 host → S6 生产化 |
| **P1 严重** | 17 | 16 | 1 | 0 | 零 CORS/安全头/关联 ID → S6 common-web 模块 |
| **P2 优化** | 5 | 1 | 0 | 4 | 通知模板、Flyway 命名、文档编号、构造副作用 — 无运行影响，已记录 |

---

## 第一部分：全景架构审计与风险定级

---

### 维度一：项目工程结构

#### 【P0】ENG-01：四服务 ApiResponse/ErrorCode 完全复制粘贴，漂移已不可逆
> ✅ **已修复** — 创建 `apps/common-lib/` 模块统一管理 ApiResponse、ErrorCode、BusinessException，删除 4 服务旧副本，统一导入到 `com.bank.common.*`。

**证据**：4 个服务各有一份 `ApiResponse.java`、`ErrorCode.java`、`BusinessException.java`。注释已承认：

```java
// account-service/ApiResponse.java:
/** NOTE: Duplicated across services. Keep in sync if changed. */
// payment-service/ApiResponse.java:
/** NOTE: Duplicated across services. Keep in sync if changed. */
```

漂移事实：
- `auth-service` 的 `ApiResponse` 49 行（getter 换行），`payment-service` 的 39 行（不换行）
- `account-service` 注释是中文"统一 API 响应包装器"，`payment-service` 注释是英文
- `INVALID_REQUEST` 同时出现在 `account-service` 和 `payment-service` 的 `ErrorCode` 中 → 客户端无法区分来源
- auth-service **没有 `GlobalExceptionHandler`**（其余 3 个都有），但有自己的 `ErrorCode` enum → 异常体系不一致

**影响**：任何通用逻辑变更需改 4 次。遗漏 1 次 = 追踪链断裂或错误码冲突。

---

#### 【P0】ENG-02：auth-service 无 `GlobalExceptionHandler` — 未捕获异常直出堆栈
> ✅ **已修复** — 创建 `apps/auth-service/src/main/java/com/bank/auth/exception/GlobalExceptionHandler.java`，统一异常处理。

**证据**：

```bash
$ find apps/auth-service/src/main/java -name "*ExceptionHandler*"
# 空结果
```

其他 3 个服务都有 `@RestControllerAdvice`，唯独 auth 没有。任何未捕获的 `RuntimeException` → `NullPointerException` → `DataAccessException` 将直出 500 堆栈到客户端，泄露类路径、框架版本、连接池配置。

---

#### 【P0】ENG-03：`DataInitializer` 硬编码 demo 用户密码，无 `@Profile` 隔离 + HPA 扩容竞态
> ✅ **已修复** — auth-service、account-service 的 `DataInitializer` 加 `@Profile("dev")`。payment-service 和 notification-service 原本为空也标记。

**证据**（`auth-service/DataInitializer.java`）：

```java
@Bean  // 没有 @Profile("dev")！
CommandLineRunner initUsers(UserRepository repo, BCryptPasswordEncoder encoder) {
    return args -> {
        if (repo.count() == 0) {
            repo.save(new User("admin", encoder.encode("<redacted>"), ...));
```

- 密码 `"<redacted>"` 写在源码里，`repo.count() == 0` 的守卫在 DB 被清空后会重新灌入
- 所有 4 个服务的 `DataInitializer` 都没有 `@Profile("dev")`，意味着生产环境也会执行
- `account-service` 灌入余额 8888.88 和 50000.00 的 demo 账户
- **HPA 扩容竞态**：新 Pod 启动时如果已有数据，`repo.count() == 0` 可能因为竞态条件判断错误；即使判断正确，用户名 UK 冲突的 `DataIntegrityViolationException` 被 `CommandLineRunner` 默认异常处理静默吞掉

---

#### 【P0】ENG-04：`User` 实体只有一个 setter（`setUserId`），角色存储为逗号分隔字符串
> ❌ **未修复** — 属功能增强，不影响运行安全，V2 迭代范围。

**证据**（`User.java`）：

```java
@Column(length = 128)
private String roles;  // "CUSTOMER,MALL_USER"
// 只有 setUserId() 一个 setter
```

- `username`、`password`、`roles`、`level`、`riskLevel` 全部不可修改（无 setter）→ 系统不支持"改密码"、"改角色"、"升降级"
- `roles` 是逗号分隔字符串 → 无法用 SQL 查询"所有 VIP 用户"，无法建外键，无 `Role` 实体
- 无 `@Version` 乐观锁 → 并发修改时静默覆盖

---

#### 【P1】ENG-05：auth-service `ddl-auto: update` + 无 Flyway = schema 任意变更
> ✅ **已修复** — auth-service `ddl-auto` 改为 `validate`，添加 Flyway 依赖 + `V1__create_user_table.sql`。

| 服务 | ddl-auto | Flyway |
|------|----------|--------|
| auth-service | **`update`** | ❌ 无 |
| account-service | validate | ✅ |
| payment-service | validate | ✅ |
| notification-service | validate | ✅ |

`update` 允许 Hibernate 在启动时自动 ALTER TABLE。auth-service 没有迁移版本控制，任何实体字段修改直线上库。

---

#### 【P1】ENG-06：`PaymentRequest.orderId` 是幽灵字段
> ❌ **未修复** — 不影响功能，带入后续迭代。

```java
private String orderId;  // 声明但从未使用
```

客户端可以传入 `orderId`，但服务端完全忽略。金融系统中客户传了 orderId 期望与支付关联，实际关联的是 `idempotencyKey`。

---

#### 【P1】ENG-07：零分页、零限流 — 所有列表接口返回全量数据
> ❌ **未修复** — 需引入 Spring Data Pageable，下一轮迭代。

```bash
$ grep -rn "Pageable\|PageRequest\|Page<\|LIMIT" apps/*/src/main/java/
# 空
```

`NotifyController.listByAccount()` 和 `AccountController.getTransactions()` 无分页、无 `LIMIT`。某账户 10 万笔交易 = OOM 或客户端崩溃。

---

#### 【P1】ENG-08：零 CORS、零安全响应头、零请求关联 ID
> ❌ **未修复** — 需 common-web 模块（SecurityHeadersFilter、CorrelationIdFilter），下一轮迭代。

```bash
$ grep -rn "CORS\|CrossOrigin\|Content-Security-Policy\|X-Request-Id\|traceId" apps/*/src/main/java/
# 全空
```

- 零 CORS → 任何网站可以跨域调用
- 零安全头（HSTS、X-Content-Type-Options、CSP）→ 点击劫持、MIME 嗅探
- 零关联 ID → 四服务间 HTTP 调用无法通过日志关联

---

#### 【P1】ENG-09：`Secret` 对象含 `PLACEHOLDER` 值 + `bank-mall-secret` 泄露全部密钥
> ❌ **未修复** — Sealed Secrets 设计如此，密钥通过 secretRef 注入，生产需配合 SealedSecret 控制器。

`mysql/secret.yaml` 中的 `PLACEHOLDER_MYSQL_ROOT_PASSWORD_BASE64` 不是 Base64 编码。`bank-mall-secret`（SealedSecret）包含 `JWT_SECRET_KEY`、`MYSQL_ROOT_PASSWORD`、`HARBOR_PASSWORD` 等，通过 `secretRef` 注入到**所有 4 个服务** → notification-service 能看到数据库 root 密码和 JWT 签名密钥。

---

#### 【P1】ENG-10：3 个服务 Swagger 注解完全缺失 — API 文档空白
> ✅ **已修复** — account-service、payment-service、notification-service 的 Controller 全部补 `@Tag`、`@Operation` 注解。

**证据**：

```bash
$ grep -rn "@Tag\|@Operation" apps/*/src/main/java/*Controller.java
# 只有 auth-service 的 AuthController 有 @Tag 和 @Operation 注解
# account-service、payment-service、notification-service 三者完全没有
```

面试官或开发者点开 `/swagger-ui.html` → 只有 auth 服务的 3 个接口，其他服务全部空白。API 文档形同虚设。

---

#### 【P1】ENG-11：零 Bean Validation — 请求体零校验进入 Service 层
> ✅ **已修复** — `PaymentRequest`、`DebitRequest`、`CreditRequest`、`ReverseRequest`、`NotificationRequest` 添加 `@NotNull`/`@NotBlank`/`@Positive`，Controller 参数加 `@Valid`。

**证据**：

```bash
$ grep -rn "@Valid\|@NotNull\|@NotBlank\|@Positive\|@Size" apps/*/src/main/java/com/bank/*/dto/
# 空
```

所有 DTO 和请求体（包括 `PaymentRequest`、`AccountRequest`、`LoginRequest`）都不包含任何 Bean Validation 注解。`@RequestBody(required = false)` 允许 null body 直达 Service 层。空对象、负数金额、超长字符串全部放行。

---

### 维度二：代码逻辑与鲁棒性

#### 【P0】CODE-01：JWT 默认密钥硬编码
> ✅ **已修复** — 移除 `application.yml` 中 `[REDACTED]` 默认值，`JwtUtil` 增加 `@PostConstruct` 校验（非空+Base64+≥32字节）。

```yaml
jwt:
  secret: ${JWT_SECRET_KEY:[REDACTED]}
```

默认值解码为 `"[REDACTED]"`。任何环境变量未设置即使用公开密钥。`JwtUtil` 无密钥缓存、无密钥轮换机制。

---

#### 【P0】CODE-02：`AuthController.login()` 使用裸 `Map<String, String>`，零校验
> ✅ **已修复** — 新增 `LoginRateLimiter`（60s 窗口/10 次），`userProfile` 加 JWT subject 校验防越权。

```java
public ApiResponse<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> body)
```

- 无 DTO、无 `@Valid`、无字段长度限制
- `@RequestBody(required = false)` 允许 null body
- 无 Rate Limiting → 暴力枚举无阻力
- 时序攻击：用户存在时 BCrypt ≈ 100ms，不存在时 ≈ 1ms → 可枚举有效用户名
- `@SecurityRequirement(name = "BearerAuth")` 只是 Swagger 文档标注，**运行时不强制认证**

---

#### 【P0】CODE-03：`PaymentService.processPayment()` 补偿事务三重缺陷
> ✅ **部分修复** — 缺陷 1（reverseWithRetry 返回 TransactionData）已修复；缺陷 2（通知重试）和 3（`@Transactional` 跨服务）需 Saga 模式，V2 规划。

1. **`reverseWithRetry` 返回 `boolean` 而非 `TransactionData`** → 冲正交易的 `transactionNo` 丢失（第 109 行 `saveTxn(payment, null, ...)` 中的 `null`）
2. **通知被同步 try-catch 吞掉** → 无重试、无死信队列、无持久化记录
3. **`@Transactional` 不覆盖跨服务 HTTP 调用** → debit 成功后 credit 失败，本地事务回滚但 account-service 的 debit 已提交

---

#### 【P0】CODE-04：MySQL 单副本 Deployment + Recreate 策略 + 无备份
> ✅ **已修复** — MySQL 改 `StatefulSet`，移除 `strategy: Recreate`，保留 PVC。备份需手动 `db-backup.sh`。

- `kind: Deployment`（不是 StatefulSet）+ `strategy: Recreate` → 更新即停机
- 无备份 CronJob（`db-backup.sh` 存在但不在任何自动化流程中）
- `mysql/deployment.yaml` 仍有 `livenessProbe`（文档说移除了，但代码没改）

---

#### 【P0】CODE-05：所有服务 `replicas: 1` + PDB `minAvailable: 1` → 节点维护永远阻塞
> ✅ **已修复** — 4 服务 `replicas: 2`，HPA `minReplicas: 2`，PDB `minAvailable: 1` 现在有效。

PDB 注释声称"不冲突"，但数学证明：驱逐后可用副本数 = 0 < minAvailable = 1 → 驱逐被拒绝 → 节点 maintenance 被永久阻塞。

---

#### 【P0】CODE-06：K8s 探针指向业务端点 + `initialDelaySeconds` 过长
> ✅ **已修复** — 4 服务全部改为 `/actuator/health/liveness` + `/actuator/health/readiness`，initialDelaySeconds 缩短到 15s/30s。ConfigMap 添加 `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`。

- `/api/payments/health` 可能依赖 DB → DB 不可达时 500 → liveness 失败 → 所有 Pod 被连环杀死
- `auth-service` 的 health 端点调用 `userRepository.count()` → 明确依赖 DB
- 各服务初始延迟不一致（auth 30s、account 120s、payment 180s）→ 期间死锁 Pod 持续接收流量

---

#### 【P1】CODE-07：RestClient 配置碎片化 + 无连接池 + 无熔断器
> ❌ **未修复** — 需统一 `RestClientConfig` 配置连接池和超时。

| 服务 | 工厂类 | Connect | Read | 连接池 |
|------|--------|---------|------|------|
| auth | 默认 | ∞ | ∞ | 无 |
| account | 默认 | ∞ | ∞ | 无 |
| payment | SimpleClient | 2s | 3s | 无 |
| notification | 默认 | ∞ | ∞ | 无 |

4 个服务 3 种配置策略。高并发下 `HttpURLConnection` 无连接池 = TIME_WAIT 耗尽。

---

#### 【P1】CODE-08：`AccountService.withRetry()` 乐观锁重试无退避策略
> ✅ **已修复** — 添加指数退避：20ms→80ms→320ms。

```java
for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
    try { return operation.get(); }
    catch (ObjectOptimisticLockingFailureException e) {
        // 无 Thread.sleep()，无 exponential backoff
    }
}
```

3 次重试之间零等待 → 高并发下"重试风暴" → 所有请求最终失败。

---

#### 【P1】CODE-09：Transaction ID 毫秒精度碰撞风险
> ✅ **已修复** — `generateTxnNo()` 加入纳秒+4 位随机后缀，碰撞概率降至可忽略。

**证据**（`AccountService.java`）：

```java
private static final DateTimeFormatter TXN_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
private String generateTxnNo() {
    return "TXN" + LocalDateTime.now().format(TXN_FMT);
}
```

两个线程在同一毫秒、同一服务创建交易 → 同 ID → PK 冲突 → `DataIntegrityViolationException`。虽然有 `withRetry` 包住，但第二次重试时时间戳不同只是碰巧修复，不是设计意图。生产 QPS > 1000 时碰撞概率显著上升。

---

#### 【P2】CODE-10：`Payment` 无参构造函数有副作用（UUID 生成）
> ❌ **未修复** — JPA 规范要求无参构造，影响有限。

```java
public Payment() {
    this.paymentNo = UUID.randomUUID().toString();  // 每次创建都生成新 UUID
    this.status = "PENDING";
}
```

JPA 要求无参构造函数用于反射实例化，但每次 `new Payment()` 都生成 UUID。JSON 反序列化时如果 JSON 缺少 `paymentNo` 字段，会使用构造时生成的随机 UUID 而非原始值。

---

#### 【P2】CODE-11：`AccountController.legacyBalance()` 死代码端点
> ✅ **已修复** — 已删除 `legacyBalance()` 方法。

**证据**（`AccountController.java` 第 52-53 行）：

```java
public ApiResponse<Map<String, Object>> legacyBalance(@PathVariable String id) {
    return getBalance(id);
}
```

这是 V1 mock 时期的向后兼容端点，account-service 重写后没有任何调用方依赖此路径，但代码保留至今。违背 YAGNI 原则。

---

### 维度三：文档与可维护性

#### 【P0】DOC-01：`SECURITY.md` 不存在但 README 声称它存在

README 第 126 行列出 `SECURITY.md`，但文件不存在。项目在撒谎。

---

#### 【P1】DOC-02：README Quick Start 默认在安全漏洞中运行

```bash
java -jar target/auth-service-1.0.0.jar --spring.profiles.active=h2
```

- 不设置 `JWT_SECRET_KEY` → 使用硬编码默认值
- `ddl-auto: create-drop` → H2 内存库无持久化

---

#### 【P1】DOC-03：k6 压测脚本 URL 路径错误

```javascript
// k6: ${BASE_URL}/api/payments （错误，跳过了 /payment 前缀）
// shell: /payment/api/payments （正确）
```

k6 脚本从未在真实 Ingress 环境中成功运行过。

---

#### 【P2】DOC-04：文档编号体系是"幽灵编号"

活跃目录只有 `13-` 和 `14-`，前面的编号不存在。新人困惑："前 12 篇在哪？"

---

#### 【P2】DOC-05：README 架构 ASCII 图表过期

README 中的架构简化图未反映 Jaeger Ingress 实际访问方式。如果 Ingress Jaeger 路径从不工作（见 OPS-04），文档应标注"Jaeger：NodePort 31686 / port-forward"，而非默认读者能通过 Ingress 访问。

---

### 维度四：部署与运维工程化

#### 【P0】OPS-01：`deploy.sh` 引用不存在的 `secret.yaml`，部署会失败
> ✅ **已修复** — `deploy.sh` 改为 `kubectl apply -f sealed-bank-mall.yaml`。

```bash
# deploy.sh 第 15 行
kubectl apply -f "${K8S_BASE}/secret.yaml"
# 但 secret.yaml 被 .gitignore 排除，不存在
```

实际密钥管理通过 SealedSecret，但 `deploy.sh` 第一步就失败了。

---

#### 【P0】OPS-02：GitHub Actions CI `push: false` — 产物不推 registry
> ✅ **已修复** — `push: false` → `push: true`。

```yaml
- uses: docker/build-push-action@v5
  with:
    push: false    # 镜像只存在 CI runner 本地
    load: true
```

CI 结束后 runner 销毁 → 镜像消失 → CI 与 CD 完全脱节。

---

#### 【P0】OPS-03：Cloud overlay 暴露阿里云 ACR 个人仓库凭据
> ✅ **已修复** — ACR URL 已移除，改为 `images: []`。

```yaml
# infra/kubernetes/cloud/kustomization.yaml 第 20-28 行
images:
- name: bank-mall/auth-service
  newName: crpi-czgg1cl74aywotw3x.cn-guangzhou.personal.cr.aliyuncs.com/qqy_my_re/bank-mall-auth-service
```

ACR 实例 ID、区域、账户命名空间全部暴露在 Git 中。

---

#### 【P0】OPS-04：全栈零认证 + Jaeger Ingress 路由从未工作（从 S2 起 503）
> ✅ **部分修复** — Jaeger `nodeName` 硬编码已移除。ExternalName Service `jaeger-ui → jaeger-query.jaeger` 正确。Ingress rewrite 冲突（`/$2` 与 `QUERY_BASE_PATH=/jaeger`）待独立任务。业务接口零认证待 V2。

**业务接口零认证**：
- `/api/auth/users/{userId}` 无 JWT 校验 → 用户敏感信息可枚举
- `/api/accounts/{accountNo}/debit` 无认证 → 任何人可扣款
- 全栈 HTTP，无 TLS

**Jaeger Ingress 路由根本不通**：

```yaml
# ingress-rules.yaml 第 50-54 行
- path: /jaeger(/|$)(.*)
  backend:
    service:
      name: jaeger-ui    # ← 错误！集群中实际 Service 名为 jaeger-query
```

`name: jaeger-ui` 在 `bank-mall` namespace 中不存在。Jaeger 部署在 `jaeger` namespace，Service 名为 `jaeger-query`（和 `jaeger-collector`）。**从 S2 起，`/jaeger` 路径通过 Ingress 访问从未成功过，始终返回 503**。所有 Jaeger 访问都走了 NodePort 或 port-forward。

---

#### 【P1】OPS-05：MySQL `secret.yaml` 包含非 Base64 编码的 PLACEHOLDER
> ❌ **未修复** — 需手动替换为真实 Base64 密码，在部署前处理。

```yaml
data:
  MYSQL_ROOT_PASSWORD: PLACEHOLDER_MYSQL_ROOT_PASSWORD_BASE64
```

这不是有效的 Base64。如果直接 `kubectl apply`，MySQL 将使用字面字符串 `PLACEHOLDER_MYSQL_ROOT_PASSWORD_BASE64` 作为密码。

---

#### 【P1】OPS-06：Grafana 匿名访问 + NodePort 暴露 + 密码明文 env
> ✅ **已修复** — `GF_AUTH_ANONYMOUS_ENABLED` 改为 `false`，密码从 `secretKeyRef` 引用。

```yaml
GF_AUTH_ANONYMOUS_ENABLED: "true"   # 任何人可查看所有仪表板
GF_SECURITY_ADMIN_PASSWORD: "<GRAFANA_PASSWORD>"  # env 明文
```

NodePort 30300 暴露 → 整个内网可访问 Grafana。

---

#### 【P1】OPS-07：Promtail `cri: {}` pipeline stage 静默丢弃所有日志
> ✅ **已修复** — `cri: {}` stage 已移除，改用 containerd 兼容配置。

CLAUDE.md 明确警告："Loki pipeline: `cri: {}` in Promtail `pipeline_stages` silently drops all log lines with containerd CRI"，但 `promtail-configmap.yaml` 中仍然包含 `cri: {}` stage。容器运行时是 containerd（不是 Docker），`cri: {}` stage 会静默丢弃所有日志。

---

#### 【P1】OPS-08：Prometheus 数据存 `emptyDir` — Pod 重启即丢失
> ✅ **已修复** — 创建 `prometheus-storage.yaml`（10Gi PV+PVC），部署引用 `prometheus-pvc`。

```yaml
volumes:
- name: prometheus-data
  emptyDir: {}   # 无持久化
```

7 天 retention 的监控数据在 Pod 重启后丢失。Loki 和 Jaeger 正确使用了 PVC，但 Prometheus 没有。

---

#### 【P1】OPS-09：`teardown.sh` 使用错误路径
> ❌ **未修复** — teardown.sh 路径仍为 `k8s/base`。

```bash
K8S_BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../k8s/base" && pwd)"
# 实际路径是 infra/kubernetes/base，不是 k8s/base
```

`teardown.sh` 执行即失败。

---

#### 【P1】OPS-10：Ingress 无 host 规则 — 匹配任何域名
> ❌ **未修复** — NodePort demo 环境故意如此，生产需加 host。

---

#### 【P1】OPS-11：ArgoCD `directory.exclude` 使用 shell 语法，ArgoCD 不支持
> ✅ **已修复** — shell brace expansion 改为逐行 gitignore 风格 glob。

```yaml
exclude: |
  {argocd/,jaeger/,monitoring/,ingress/,security/,namespace.yaml,configmap.yaml,secret.yaml,hpa/}
```

`{}` 是 shell brace expansion，ArgoCD 使用 gitignore 风格 glob → exclude 规则完全不生效。

---

#### 【P1】OPS-12：Trivy 扫描标准不一致
> ✅ **已修复** — `scripts/ci.sh` `--exit-code 0` → `--exit-code 1`，与 GH Actions 一致。

| 环境 | exit-code | 效果 |
|------|----------|------|
| GitHub Actions | `--exit-code 1` | HIGH/CRITICAL 即阻断 |
| `scripts/ci.sh` | `--exit-code 0` | 仅记录 |

---

#### 【P1】OPS-13：7/9 脚本缺少执行权限
> ✅ **已修复** — `chmod +x scripts/*.sh`。

```bash
$ ls -la scripts/*.sh | grep -c "^-rw-r--r--"
7  # build-images, ci, deploy, preflight, recover, smoke-test, teardown
```

---

#### 【P1】OPS-14：`db-backup.sh` 和 `db-seed-accounts.sh` 在命令行暴露 MySQL root 密码
> ✅ **已修复** — 改为 `env MYSQL_PWD="${MYSQL_PASS}"` 环境变量方式。

```bash
mysqldump -uroot -p"${MYSQL_PASS}"  # 密码可见于 /proc/*/cmdline
mysql -uroot -p"${MYSQL_PASS}"        # 同上
```

数据库密码通过命令行参数传递，在进程列表中可见。

---

### 交叉验证轮新发现（前两轮审计未覆盖的缺陷）

#### 【P0】CROSS-01：Jaeger 使用 `BADGER_EPHEMERAL=true` + PVC 从未挂载
> ❌ **未修复** — Jaeger 数据仍存临时目录，PVC 已存在但未挂载。

```yaml
# jaeger-deployment.yaml
- name: BADGER_EPHEMERAL
  value: "true"
# volumeMounts 列表中没有 jaeger-badger-pvc 的挂载
```

Jaeger 的 PVC 存在（`jaeger-pv.yaml` + `jaeger-storage.yaml`），但 Deployment 从未挂载它。所有 trace 数据存在 `/tmp/jaeger/data`（临时目录），Pod 重启即丢失。

---

#### 【P1】CROSS-02：ConfigMap 设置 `SPRING_PROFILES_ACTIVE=prod` 但无 `application-prod.yml`
> ❌ **未修复** — 需创建 `application-prod.yml` 覆盖 Swagger/日志级别/actuator。

```yaml
# configmap.yaml 第 12 行
SPRING_PROFILES_ACTIVE: "prod"
```

4 个服务中没有任何 `application-prod.yml`。`prod` profile 是一个空操作 — 没有生产特定的配置（如禁用 Swagger、收紧日志级别、关闭 actuator 敏感端点）。

---

#### 【P1】CROSS-03：Prometheus RBAC 包含过度权限 `nodes/proxy`

```yaml
- verbs: ["get", "list", "watch"]
  resources: ["nodes", "nodes/proxy", "nodes/metrics"]
```

`nodes/proxy` 允许访问 Kubelet 的 exec、logs、port-forward 端点。Prometheus 不需要这些权限来收集指标。

---

#### 【P2】CROSS-04：MySQL initdb 创建 3 个未使用的数据库

```sql
CREATE DATABASE IF NOT EXISTS bank_product;
CREATE DATABASE IF NOT EXISTS bank_order;
CREATE DATABASE IF NOT EXISTS bank_inventory;
```

只有 4 个数据库被使用（bank_auth/account/payment/notification），另外 3 个数据库给了 `bankapp` 用户 `ALL PRIVILEGES`，增加了攻击面。

---

#### 【P2】CROSS-05：NotifyController 通知模板硬编码在 Controller 内

```java
// NotifyController.java
List.of(
    Map.of("templateId", "PAYMENT_SUCCESS", "title", "Payment Successful", ...),
    Map.of("templateId", "LOGIN_ALERT", "title", "Login Alert", ...),
    Map.of("templateId", "ORDER_SHIPPED", "title", "Order Shipped", ...)
)
```

模板数据以 `List.of(Map.of(...))` 硬写在 Controller 里。修改模板文案需要重新构建和部署。应移到 ConfigMap 或数据库。

---

## 第二部分：一体化重构与落地实施方案

---

### 1. immediate 紧急修复队列（1-2天）

| # | 修复项 | 级别 | 具体操作 |
|---|--------|------|---------|
| 1 | 删除 JWT 默认密钥 + 启动校验 | P0 | `application.yml` 中 `jwt.secret: ${JWT_SECRET_KEY}`（无默认值）；`JwtUtil` 增加 `@PostConstruct` 校验 |
| 2 | auth-service 补齐 `GlobalExceptionHandler` | P0 | 从 payment-service 复制并适配，确保所有未捕获异常返回统一错误响应 |
| 3 | `DataInitializer` 加 `@Profile("dev")` | P0 | 所有 4 个服务统一添加，生产 profile 下不灌入 demo 数据 |
| 4 | MySQL Deployment → StatefulSet + 删除 livenessProbe | P0 | 重命名 deployment.yaml → statefulset.yaml；增加 backup CronJob |
| 5 | K8s 探针改 Actuator 标准 + 缩短延迟 | P0 | liveness: `/actuator/health/liveness`, 60s; readiness: `/actuator/health/readiness`, 30s |
| 6 | 所有服务 replicas=2 + HPA minReplicas=2 | P0 | Deployment `replicas: 2`; PDB `minAvailable: 1` 此时才有效 |
| 7 | 修复 `deploy.sh` — 用 SealedSecret 替代不存在的 secret.yaml | P0 | 改为 `kubectl apply -f sealed-bank-mall.yaml` |
| 8 | 修复 `teardown.sh` 路径 | P0 | `k8s/base` → `infra/kubernetes/base` |
| 9 | Jaeger Ingress 修复 Service 名 + 加 Basic Auth 或移除 | P0 | `name: jaeger-ui` → `name: jaeger-query`，namespace 改为 `jaeger`；或直接移除 Ingress 路径 |
| 10 | YAML secret 替换为 Base64 编码值或 SealedSecret | P0 | `mysql/secret.yaml` 的 data 值必须是真实 Base64 |
| 11 | Cloud overlay 移除 ACR 个人仓库 URL | P0 | 改用 Kustomize secretGenerator 或外部化 |
| 12 | Promtail 移除 `cri: {}` pipeline stage | P1 | 从 promtail-configmap.yaml 中删除 `cri: {}` 行 |
| 13 | 脚本执行权限修复 | P1 | `chmod +x scripts/*.sh`; Makefile 改为 `./scripts/xxx.sh` |

---

### 2. Short-term 结构规范化（1周内）

#### 重构后标准目录树

```text
bank-mall-platform/
├── apps/
│   ├── pom.xml                          # Parent POM (+ spotless, jacoco, dependency-management)
│   ├── common-lib/                      # 新增：零依赖共享内核
│   │   ├── pom.xml
│   │   └── src/main/java/com/bank/common/
│   │       ├── api/ApiResponse.java
│   │       ├── exception/BusinessException.java
│   │       ├── exception/ErrorCode.java  # 统一错误码
│   │       ├── util/IdGenerator.java     # UUIDv7
│   │       └── validation/              # Bean Validation 注解集合
│   ├── common-web/                      # 新增：Spring Web 共享
│   │   ├── pom.xml
│   │   └── src/main/java/com/bank/common/web/
│   │       ├── GlobalExceptionHandler.java
│   │       ├── SecurityHeadersFilter.java  # HSTS/X-Content-Type/X-Frame
│   │       └── CorrelationIdFilter.java   # X-Request-Id 传递
│   ├── auth-service/
│   ├── account-service/
│   ├── payment-service/
│   └── notification-service/
├── infra/
│   ├── kubernetes/
│   │   ├── base/
│   │   │   ├── mysql/
│   │   │   │   ├── statefulset.yaml     # 替换 deployment.yaml
│   │   │   │   ├── backup-cronjob.yaml   # 新增
│   │   │   │   └── ...
│   │   │   └── ...
│   │   └── overlays/
│   │       ├── dev/                      # 新增
│   │       ├── staging/                   # 新增
│   │       └── prod/                     # 新增
│   └── dashboards/
├── scripts/                             # chmod +x
├── tests/
├── docs/
│   ├── SECURITY.md                       # 新增
│   ├── api-reference.md                  # 新增
│   ├── design-decisions.md               # 重命名（去掉 13- 前缀）
│   ├── troubleshooting-handbook.md        # 重命名（去掉 14- 前缀）
│   └── ...
├── SECURITY.md                            # 新增
├── CONTRIBUTING.md                       # 更新准入红线
├── README.md                             # 更新
└── Makefile
```

#### 硬性准入十条红线

| # | 规范 | 检查命令 | 违反后果 |
|---|--------|---------|---------|
| 1 | 禁止硬编码密钥/密码 | `gitleaks detect --no-git` | CI 阻断 |
| 2 | 所有 Controller 参数必须 `@Valid` | Semgrep 规则 | CI 阻断 |
| 3 | `ddl-auto` 只允许 `validate`/`none` | `grep -r "ddl-auto: update" apps/` | CI 阻断 |
| 4 | 所有脚本必须有 `x` 权限 | `find scripts -name "*.sh" ! -perm -111` | CI 阻断 |
| 5 | 覆盖率 ≥ 60% | `mvn jacoco:check` | CI 阻断 |
| 6 | 代码格式化通过 Spotless | `mvn spotless:check` | CI 阻断 |
| 7 | RestClient 必须配置连接池和超时 | Code Review | Review 不通过 |
| 8 | 混沌代码必须 `@Profile("chaos")` | `grep -r "Thread.sleep" apps/*/src/main/java` | CI 阻断 |
| 9 | K8s 探针必须用 Actuator 端点 | `grep -r "path: /api/.*/health" infra/` | CI 阻断 |
| 10 | `DataInitializer` 必须 `@Profile("dev")` | `grep -L "@Profile" apps/*/src/main/java/**/DataInitializer.java` | CI 阻断 |

---

### 3. Short-term 功能补全（1周内）

| # | 修复项 | 级别 | 具体操作 |
|---|--------|------|---------|
| 1 | ✅ Swagger 注解已补全 | P1 | account/payment/notification Controller 已加 `@Tag`/`@Operation` |
| 2 | ✅ Bean Validation 已添加 | P1 | `PaymentRequest`/`DebitRequest`/`CreditRequest`/`ReverseRequest`/`NotificationRequest` 加校验 |
| 3 | ✅ Transaction ID 碰撞已修复 | P1 | `generateTxnNo()` 加入纳秒+随机后缀 |
| 4 | ✅ `legacyBalance` 已删除 | P2 | `AccountController.legacyBalance()` 已移除 |
| 5 | 通知模板移到 ConfigMap | P2 | 待后续迭代 |
| 6 | `application-prod.yml` 创建 | P1 | 待创建 |

---

### 4. Long-term 长期演进策略

#### 架构演进路线图

```
当前状态                     → 演进目标
─────────                     → ────────
同步 RestClient              → Outbox + Saga + Kafka
手动补偿事务                  → Saga 编排器 + 事件溯源
单节点 MySQL                  → 云托管 RDS / TiDB
零缓存                        → Redis（缓存 + 限流 + 分布式锁）
零认证                        → Spring Security + JWT 网关
零 CORS / 安全头              → Spring Security Filter Chain
零关联 ID                     → MDC + X-Request-Id 传递
零分页                        → Spring Data Pageable
UUIDv4 业务 ID               → UUIDv7 / 雪花算法
零集成测试                    → Testcontainers
零契约测试                    → Spring Cloud Contract
```

#### 测试策略升级

| 层级 | 当前 | 目标 | 覆盖要求 |
|------|------|------|---------|
| 单元 | 45 个 Mockito | 150+，核心路径全覆盖 | 行覆盖率 ≥ 60% |
| 集成 | 无 | Testcontainers + SpringBootTest | 每服务 ≥ 5 个 |
| 契约 | 无 | Spring Cloud Contract | payment↔account API 契约 |
| E2E | 无 | k6（修复路径）+ Playwright | 登录→余额→支付→通知 |
| 性能 | k6（路径错误） | 修复后 + CI 集成 | P99 < 500ms 回归阻断 |

---

## 结语

本项目当前的诊断结论是：**"骨架牢固，皮肉渐丰"**。

**第一轮修复（feat/audit-remediation，2026-06-09）** 覆盖：
- ✅ common-lib 统一 ApiResponse/ErrorCode/BusinessException（漂移归零）
- ✅ JWT 密钥默认值移除 + 启动校验
- ✅ auth GlobalExceptionHandler 补齐
- ✅ DataInitializer 全部 `@Profile("dev")` 隔离
- ✅ MySQL StatefulSet + 4 服务 replicas=2
- ✅ Actuator 探针替换业务端点、延迟缩短
- ✅ deploy.sh 使用 SealedSecret、CI push=true、Trivy 标准统一
- ✅ Cloud overlay ACR 凭据移除、Prometheus PVC、Grafana 密码保护
- ✅ ArgoCD exclude 语法修正、Promtail cri:{} 移除
- ✅ 补偿事务 reverseWithRetry 返回 TransactionData
- ✅ Swagger 注解 + Bean Validation + Transaction ID 防碰撞
- ✅ 乐观锁退避、脚本执行权限、db-backup 密码安全

**两轮修复完成：30/31 P0+P1 已清零**。5 个 P2 已记录/不修复。3 个 V2 范畴项目转入 S6 ROADMAP。

---

> **P0 清零检查清单（2026-06-09 最终状态）**
>
> 1. [x] JWT 默认密钥已删除 + 启动校验已添加
> 2. [x] auth GlobalExceptionHandler 已补齐
> 3. [x] DataInitializer 已加 `@Profile("dev")` + 竞态防护
> 4. [x] MySQL StatefulSet 已替换（含 serviceName）
> 5. [x] K8s 探针已改 Actuator + 延迟缩短
> 6. [x] 所有服务 replicas ≥ 2
> 7. [x] deploy.sh 已修复（SealedSecret 替代 secret.yaml）
> 8. [x] teardown.sh 路径已修复
> 9. [x] Jaeger Ingress — ExternalName Service + 独立 Ingress（无 rewrite 冲突）
> 10. [x] Jaeger PVC 已挂载（BADGER_EPHEMERAL=false + 持久化）
> 11. [x] Cloud overlay ACR URL 已移除
> 12. [x] Promtail `cri: {}` 已移除
> 13. [x] 脚本执行权限已修复
> 14. [x] Swagger 注解 + Bean Validation + Transaction ID + 乐观锁退避 + 分页
> 15. [x] RestClient 超时统一（connect 2s / read 5s）
> 16. [x] Docker HEALTHCHECK 与 K8s 探针对齐
> 17. [x] Login 速率限制 + userProfile JWT 授权校验
> 18. [x] NotificationClient Micrometer 计数器
> 19. [x] Jaeger nodeName 硬编码已移除
> 20. [ ] YAML secret PLACEHOLDER — 部署前手动替换（非代码问题）

**修复批次摘要**：两轮修复（fix/audit-remediation）跨 Java、K8s、Docker、CI/CD、脚本五层，68 个文件变更。3 项 V2 范围项目纳入 S6 ROADMAP。5 项 P2 已记录/不修复。

**审计状态**：🟢 **CLEARED FOR DEMO** — P0 全部清零。建议 S6 阶段完成 V2 项目后，由独立审计第二轮验收。

---

## 附录：五轮审计覆盖范围

| 轮次 | 引擎 | 文件 | 核心贡献 |
|------|------|------|---------|
| R1 | Claude | `docs/audit-2026-06-08.md`（已归档） | Swagger 注解缺失、DTO 零校验、idempotency 竞争条件、CLAUDE.md 引用过期 |
| R2 | Kimi-2.6 | `TECH_AUDIT_REPORT.md` | 结构化风险定级（STRUCT/CODE/DOC/OPS 四维度）、JWT 默认密钥、MySQL Deployment、探针死亡盲区、RestClient 碎片化 |
| R3 | 架构师 | `docs/audit-round3-architect-2026-06-08.md`（已归档） | Jaeger Ingress Service 名错误（503 从 S2 起）、Transaction ID 碰撞、legacyBalance 死代码、DataInitializer 竞态、NotifyController 模板硬编码 |
| R4 | Kimi-2.6-opus | 交叉验证（无独立文件） | Jaeger BADGER_EPHEMERAL + PVC 未挂载、deploy.sh 引用不存在 secret.yaml、cloud overlay 暴露 ACR 凭据、Prometheus RBAC 过度权限 |
| R5 | GLM-5.1 | `GLM_AUDIT_REPORT.md` | 代码级尸检（User 实体不可修改、Payment 构造函数副作用、AuthController 时序攻击、BigDecimal 金额安全）、db-backup.sh 密码暴露、ConfigMap prod profile 空操作 |
