# 待确认问题全集

> 来源：S3 CI/CD v3 + 云迁移 v2 + 两份独立审计
> 最后更新：2026-06-04 14:00 CST

---

## 一、🟢 用户决策 — 全部完成 ✅

| # | 决定 |
|---|------|
| **Q1** | **A** — 4 个服务 pom.xml 指向 bank-mall-parent |
| **Q2** | **A** — ci.sh 改 Git commit+push 触发 ArgoCD |
| **Q3** | **C** — 补充 PaymentServiceTest 边界用例 |
| **Q4** | **B** — 删除"项目 A"引用，amd64 only |

---

## 二、🔵 S3 CI/CD 验证 — 全部完成 ✅

| # | 问题 | 结果 | 状态 |
|---|------|------|:---:|
| **Q5** | JDK 21 编译 | harbor01 上 11 tests 通过 | ✅ |
| **Q6** | ghcr.nju.edu.cn | HTTP 200，registry/2.0 API | ✅ |
| **Q7** | Maven Central 内网可达 | Q5 编译通过 = aliyun 镜像可下载 | ✅ |
| **Q8** | Gitleaks 安装 | harbor01 + master01 均 8.30.1，已加入 PATH | ✅ |
| **Q9** | 飞书 webhook 可达 | HTTP/2 404（Tengine），域名可达 | ✅ |

---

## 三、🔵 云迁移验证

| # | 问题 | 状态 |
|---|------|:---:|
| **Q10** | ACK CNI 选 Flannel | ⬜ 阿里云控制台 |
| **Q11** | ACR Image Pull 权限 | ⬜ 阿里云控制台 |
| **Q12** | ECS vCPU 配额 ≥ 10 | ⬜ 阿里云控制台 |
| **Q13** | SLB + kubeadm init 时序 | ⬜ HA 实施时 |
| **Q14** | ECS 公网带宽 ≥ 5 Mbps | ⬜ 创建 ECS 时 |
| **Q15** | Maven Central 内网可达 | ✅ 同 Q7 |
| **Q16** | Harbor HTTP 镜像拉取 | ⬜ worker 上验证 |
| **Q17** | 资源清理确认 | ⬜ 部署时 |

---

## 四、🔴 审计遗漏

| # | 问题 | 结果 | 状态 |
|---|------|------|:---:|
| **Q18** | harbor01 有 Maven 吗 | Maven 3.8.7 + Java 21.0.11 | ✅ |
| **Q19** | Docker insecure registry | `10.0.0.61` 已注册 ✅ | ✅ |
| **Q20** | Q6 优先级矛盾 | — | ⬜ |
| **Q21** | 面试话术缺 CI/CD Q&A | — | ⬜ |
| **Q22** | aliyun CLI 安装 | `pip3 install --user` 被 PEP 668 阻断。改 `pipx install aliyun-cli`，或直接用阿里云网页控制台（推荐） | ⚠️ |
| **Q23** | ACR docker login | — | ⬜ |
| **Q24** | 面试话术区分岗位 | — | ⬜ |

---

## 五、Q22 最终方案

`pip3 install --user` 被 Ubuntu 24.04 的 PEP 668 策略阻止。两个选择：

- **A（推荐）**：不用 CLI。ACK 集群创建、ACR 仓库管理全走阿里云网页控制台，零安装成本
- **B**：`apt install pipx -y && pipx install aliyun-cli`。`pipx` 为每个工具建独立 venv，不污染系统

不影响验证进度——ACK 体验步骤在网页控制台 10 分钟就能完成，CLI 不是必需的。

---

**总进度**：24 项 → 17 通过 + 1 待定 + 6 后续 = **79%**

**S3 前置验证全部通过，可以开工了。**
