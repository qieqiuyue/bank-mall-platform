# Security Policy

## 报告漏洞

请通过 **GitHub Issues** 报告。

## 安全实践

| 措施 | 说明 |
|------|------|
| **Sealed Secrets** | 集群内公钥加密 → 密文存 Git → Controller 解密。Git 中零明文。 |
| **Gitleaks** | pre-commit + CI 双层门禁 |
| **Trivy** | 镜像漏洞扫描，HIGH/CRITICAL 阻断 |
| **NetworkPolicy** | deny-all + 白名单最小权限 |
| **PodSecurity** | baseline enforced |
| **BCrypt** | 密码哈希存储 |
| **JWT** | 无状态认证 |

## 生产化差距

当前为实验环境，以下为生产必备但在本项目中仅做设计覆盖：

- Secret 自动轮换（External Secrets + Vault）
- RBAC 最小权限
- 审计日志
- 镜像签名（Cosign）

---

**最后更新**：2026-06-02 | S0 初始化中
