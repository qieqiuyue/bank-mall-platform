# Polish 清单

> 不改功能，不改面试叙事。顺手做，不阻塞当前阶段。

---

## S2 遗留

| # | 项 | 文件 | 修复方式 | 阶段 |
|---|------|------|------|:---:|
| 1 | HEALTHCHECK 路径不优雅 | `apps/account-service/Dockerfile` | `A1001/health` → `/actuator/health` | S5 |
| 2 | egress 多放了 16686 | `infra/kubernetes/base/security/allow-services-egress.yaml` | bank-mall 服务不需要连 Jaeger UI，删掉 16686 | S5 |
| 3 | NetworkPolicy 命名 | `allow-jaeger-ingress.yaml` | `allow-from-monitoring` → `allow-from-bank-mall-and-monitoring` 已修 | ✅ |

---

## S3 发现

| # | 项 | 文件 | 修复方式 | 阶段 |
|---|------|------|------|:---:|
| 4 | Mockito JDK 21 警告 | `apps/*/pom.xml` | 4 个服务 pom.xml 加 `maven-surefire-plugin` 的 `-XX:+EnableDynamicAgentLoading` | S5 |
| 5 | Q6 优先级标注矛盾 | `.opencode/plans/S3-cicd-implementation-plan.md` | 正文 P0 / 汇总表 P1 → 统一为 P0 | ✅ |
| 6 | 面试话术缺 CI/CD 体系化 Q&A | `docs/interview/` | 补 3-4 个 CI/CD 面试问题 | S5 |
| 7 | 面试话术未区分岗位类型 | `docs/interview/` | 区分通用后端 vs DevOps/SRE 回答口径 | S5 |
| 8 | 云迁移计划 K8s 版本写 1.30 | `.opencode/plans/cloud-migration-plan.md` | 1.30 → 1.36.1（本地集群实际版本） | ✅ |

---

## S4 Day 1 发现

| # | 项 | 文件 | 修复方式 | 阶段 |
|---|------|------|------|:---:|
| 9 | payment-load.sh 默认 URL 缺 Ingress 前缀 | `tests/payment-load.sh` | `api/payments` → `payment/api/payments` | S4 |
| 10 | 压测脚本 HTTP 解析三次迭代 | `tests/payment-load.sh` | grep -oP→sed，trap→注释，uuid→RANDOM | S4 |
| 11 | A1001 是唯一压测账户 | DB | 需补 9 个测试账户，每账户初始余额 100000 | S4 |
| 12 | master01 同步问题 | master01 | 落后 79 个提交、脚本 URL 本地陈旧、git pull 偶尔 GFW 阻断 | S4 |
| 13 | Jaeger NodePort 31686 跨节点不通 | `jaeger-service.yaml` | Pod 在 worker02 则只有 worker02 的 NodePort 通，master01/worker01 不行 | S5 |

**规范**：不追求完美。每次发现有值得修但不急的东西，就加一行。
