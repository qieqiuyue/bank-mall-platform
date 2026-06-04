# Polish 清单

> 不改功能，不改面试叙事。顺手做，不阻塞当前阶段。

---

## S2 遗留

| # | 项 | 文件 | 修复方式 | 阶段 |
|---|------|------|------|:---:|
| 1 | HEALTHCHECK 路径不优雅 | `apps/account-service/Dockerfile` | `A1001/health` → `/actuator/health` | S3 |
| 2 | egress 多放了 16686 | `infra/kubernetes/base/security/allow-services-egress.yaml` | bank-mall 服务不需要连 Jaeger UI，删掉 16686 | S3 |
| 3 | NetworkPolicy 命名 | `allow-jaeger-ingress.yaml` | `allow-from-monitoring` → `allow-from-bank-mall-and-monitoring` 已修 | ✅ |

---

## S3 发现

| # | 项 | 文件 | 修复方式 | 阶段 |
|---|------|------|------|:---:|
| 4 | Mockito JDK 21 警告 | `apps/*/pom.xml` | 4 个服务 pom.xml 加 `maven-surefire-plugin` 的 `-XX:+EnableDynamicAgentLoading` | S5 |
| 5 | Q6 优先级标注矛盾 | `.opencode/plans/S3-cicd-implementation-plan.md` | 正文 P0 / 汇总表 P1 → 统一为 P0 | ✅ |
| 6 | 面试话术缺 CI/CD 体系化 Q&A | `docs/interview/` | 补 3-4 个 CI/CD 面试问题 | S5 |
| 7 | 面试话术未区分岗位类型 | `docs/interview/` | 区分通用后端 vs DevOps/SRE 回答口径 | S5 |

---

**规范**：不追求完美。每次发现有值得修但不急的东西，就加一行。
