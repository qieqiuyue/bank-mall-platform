# 技术栈审计报告

**日期**: 2026-07-07  
**项目**: 企业安全防护 + bank-mall-platform  
**上下文**: 2026 年中国中级 DevOps/SRE 面试市场

---

## Project 1: 企业安全防护

| 技术 | 版本 | 状态 | 建议 |
|------|------|------|------|
| **Tengine** | 源码编译 | **DEPRECATED** — 最后 release 2023，社区停滞 | 换标准 Nginx 或准备面试理由 |
| **阿里云 CDN** | SaaS | ✅ OK | 补充 Cloudflare 作对比 |
| **雷池 WAF** | 社区版 | ✅ OK | 准备 vs ModSecurity 的对比答案 |
| **阿里云 OSS** | SaaS | ✅ OK | — |
| **Docker/docker-compose** | Compose Spec | ✅ OK | — |
| **Node.js** | 22 | 🟡 **OUTDATED** — 最新 LTS 已升级 | 升到 24 LTS |
| **VitePress** | 1.x | ✅ OK | — |
| **GitHub Actions** | ubuntu-latest | ✅ OK | — |

### Project 1 缺失项

- Terraform / IaC — 全手动开通 ECS
- Ansible — 纯 shell 脚本

---

## Project 2: bank-mall-platform

### 🔴 EOL / 严重过期

| 技术 | 当前版本 | 状态 | EOL 日期 | 替代方案 |
|------|---------|------|----------|---------|
| **Jaeger** | 1.60 | **EOL** | 2025-12-31 | Grafana Tempo（全 Grafana LGTM 栈） |
| **Loki** | 2.9.12 | **EOL** — 落后 1 个主版本 | Loki 3.x 已发布 | 升到 Loki 3.x |
| **Grafana** | 10.4.0 | **OUTDATED** — 落后 2 个主版本 | 10.x 已不再更新 | 升到 11.x/12.x |
| **Prometheus** | 2.53.0 | **OUTDATED** — 落后 1 个主版本 | 3.x 已于 2025 发布 | 升到 3.x |
| **MySQL** | 8.0 | **APPROACHING EOL** | 标准支持 2026-04 终止 | 升到 8.4 LTS (2032) |

### 🟡 已弃用 / 非主流

| 技术 | 状态 | 面试影响 |
|------|------|---------|
| **VMware** | 仅 homelab — 面试官期望云平台经验 | HIGH — "熟悉至少一种公有云"是基础要求 |
| **OTEL Agent `latest` tag** | K8s 面试中的反模式 — 不可重现 | MEDIUM |
| **Harbor HTTP 模式** | 仅实验环境 — 需说明生产会走 HTTPS | LOW |
| **Ingress Nginx** | v1.10.1 — 落后当前版本 | LOW |

### 🟢 过于新（面试需要准备好理由）

| 技术 | 版本 | 风险 | 建议 |
|------|------|------|------|
| **K8s** | 1.36.1 | 国内企业多在 1.28-1.31 | 强调"了解旧版能力，主动研究新版" |
| **Spring Boot** | 4.0.6 | 多数企业在 3.x | 强调"为学习 Jakarta EE 11 和 Spring Framework 7.0" |
| **containerd** | 2.2.1 | 配置方式与 1.x 不同 | 强调"踩坑就是学习价值" |

### ✅ OK

| 技术 | 版本 | 备注 |
|------|------|------|
| Java | 21 LTS | 当前活跃 LTS |
| jjwt | 0.12.6 | 最新 stable |
| ArgoCD | CNCF 毕业项目 | 3 Application CR，配置正确 |
| Prometheus | 可观测核心 | scrape 配置正确 |
| Trivy/Semgrep/Gitleaks | 活跃维护 | 三轴安全扫描 |
| Kustomize/Helm | — | 两种 IaC 工具都有经验 |

### 🔵 主流缺失（2026 DevOps JD 高频要求）

| 技术 | 出现频率 | 项目状态 |
|------|---------|---------|
| **Terraform / IaC** | 60%+ | ❌ 完全缺失 |
| **Ansible** | 50%+ | ❌ 纯 shell 脚本 |
| **Redis** | Top 3 中间件 | ❌ 只有设计文档 |
| **Jenkins** | 大量中国企业 | ❌ 用 GHA 替代 |
| **Kafka / RocketMQ** | 80% 分布式事务面试 | ❌ 缺失 |
| **Nacos / Sentinel / SkyWalking** | 阿里系 JD 高频 | ❌ 缺失 |
| **Service Mesh (Istio)** | 中高级 JD 加分 | ❌ 缺失 |
| **云平台** (ACK/TKE) | 基础要求 | ❌ 只有设计文档 |

---

## Top 修复优先级

| # | 严重度 | 操作 | 工作量 |
|---|--------|------|--------|
| 1 | **CRITICAL** | **Jaeger 1.60 → Grafana Tempo** | HIGH |
| 2 | **HIGH** | **Loki 2.9 → 3.x** + Grafana 10.4 → 11.x + Prometheus 2.53 → 3.x | MEDIUM |
| 3 | **HIGH** | **MySQL 8.0 → 8.4 LTS** | MEDIUM |
| 4 | **HIGH** | **OTEL Agent 固定版本**（去掉 `latest` tag） | LOW |
| 5 | **HIGH** | **加 Terraform** IaC 示例（供腾讯云资源） | MEDIUM |
| 6 | **MEDIUM** | **Tengine → Nginx**（或准备面试理由） | MEDIUM |
| 7 | **MEDIUM** | **加 Redis**（至少落地 V2 设计） | MEDIUM |
| 8 | **MEDIUM** | **加 Ansible playbook**（K8s 集群初始化） | MEDIUM |

---

## 面试话术要点

- Jaeger 1.60 EOL：承认已知，已在迁移到 Grafana Tempo
- K8s 1.36 + SB 4.0.6："学习型项目主动跟进新技术，理解企业稳定性需求"
- 缺 Terraform："Week 3 计划补，正在做腾讯云 module"
- 缺 Redis："设计文档已完成，DB UNIQUE 幂等验证可行，Redis 是 V2 优化项"
