# Scripts

运维和 CI/CD 脚本，在目标节点直接执行或通过 Makefile 间接调用。

## 脚本清单

| 脚本 | 用途 | 执行环境 | Make Target |
|------|------|---------|-------------|
| `ci.sh` | 一键 CI/CD：Maven 打包 → Docker 构建 → Trivy 扫描 → Push Harbor → 验证 | harbor01 | `make ci` |
| `build-images.sh` | 构建并推送 4 服务 Docker 镜像 | harbor01 | `make push` |
| `deploy.sh` | 部署全部 K8s 资源（12 步） | master01 | `make deploy` |
| `smoke-test.sh` | 最快 Ingress 闭环验证（4 个 API 调用） | master01 | `make smoke-test` |
| `preflight.sh` | 部署前环境检查（7 项：集群/指标/镜像/HPA/Ingress/服务） | master01 | `make preflight` |
| `teardown.sh` | 销毁所有 bank-mall 资源（namespace 级别清理） | master01 | `make teardown` |
| `db-backup.sh` | 全库 mysqldump 基线备份 | master01 | — |
| `db-seed-accounts.sh` | 幂等插入测试账户（10 个账户，各 10 万余额） | master01 | — |

## 恢复脚本

`recover.sh` — 全栈恢复脚本。从 K8s 集群完全重建（含 Calico + Harbor + 所有组件），用于灾难恢复场景。通常不需要执行。

## 调试辅助

`tests/payment-load.sh` — 零依赖 curl 并发压测脚本。用法：
```bash
bash tests/payment-load.sh <并发数> <持续时间秒> <URL>
```
