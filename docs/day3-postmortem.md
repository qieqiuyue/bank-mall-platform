# Day 3 事故复盘

**日期**: 2026-07-07  
**目标**: 启动 4-VM K8s 集群（27 天未开机），截图用于 README  
**结果**: 业务服务恢复，Prometheus/Grafana 截图成功，Jaeger UI 截图未获取（已知前端 bug，4 小时浪费在错误方向）  
**运维成熟度**: Level 2/5 — 可重复但脆弱

---

## 事故时间线

### 1. Docker bridge 故障（harbor01）— 10 min

* **症状**: `docker build` 失败
* **根因**: Docker 24+ on Ubuntu 24.04，冷启动后 docker0 无 IPv4 + iptables FORWARD policy DROP
* **修复**: `systemctl restart docker` + `iptables -P FORWARD ACCEPT`
* **预防**: 加入 boot 脚本自动化

### 2. 镜像 tag 不匹配 — 5 min

* **症状**: K8s pull 不到镜像
* **根因**: `build-images.sh` 默认用 git hash (`5b075aa`)，deployment YAML 写死 `2.0.0`
* **修复**: 手动 `docker tag` + `docker push`
* **预防**: 统一版本源，build 和 deploy 从同一变量读取

### 3. Harbor 认证 — 5 min

* **症状**: worker 节点 `ctr pull` 报 `no basic auth credentials`
* **根因**: Harbor 项目设为私有
* **修复**: `ctr pull --user admin:Harbor12345`
* **预防**: 学习环境 Harbor 项目改为公开

### 4. CrashLoopBackOff（已知问题）— 15 min

* **症状**: 所有 bank-mall 服务反复重启
* **根因**: livenessProbe `initialDelaySeconds=30` 对 Spring Boot 4.0.6 + OTEL agent（需 90-120s）太短
* **修复**: 加了 startupProbe（30 次 × 10s = 300s 窗口）
* **预防**: 手册已记载但未查阅——需自动化探针时间计算

### 5. Prometheus 孤儿 ReplicaSet — 5 min

* **症状**: Prometheus CrashLoopBackOff，PVC 被旧 RS 占用
* **根因**: 旧 Deployment 删除时残留 ReplicaSet 未清理
* **修复**: `kubectl delete rs <old-rs>`
* **预防**: 加 orphan RS 检测脚本

### 6. Jaeger 旧数据 OOM — 30 min

* **症状**: Jaeger query 端口 16686 始终不监听
* **根因**: PVC 含 50 张旧 badger 表 + 512Mi 内存限制 = 查询组件永远起不来
* **修复**: 删 PVC + 内存升到 1Gi
* **预防**: 部署前检查 PVC 状态或始终从空卷开始

### 7. 🔴 Jaeger UI 查不到 trace — 3.5h（**本次最大失败**）

* **症状**: Jaeger API 用 `curl` 查有数据，但浏览器 UI 显示 "No trace results"
* **根因**: **Jaeger 1.60 JS 前端 bug**——搜索时把 service 名拼进 URL 路径 (`/api/traces/payment-service`)，而不是 query string (`/api/traces?service=payment-service`)。Jaeger 把这个请求当按 traceID 查询 → `strconv.ParseUint: parsing "payment-service": invalid syntax` → 返回空
* **错误诊断路径**: 操作员以为是网络问题 → 尝试了 port-forward、QUERY_BASE_PATH 修改、Calico 重启、iptables flush（**让情况更糟**）、containerd/kubelet 重启——全是错的
* **正确诊断路径**: 打开浏览器 F12 → Network tab → 看搜索时发了什么请求 → 5 秒发现问题
* **预防**: 任何前端 UI 问题，先查 Network tab 中的实际请求 URL，再动后端基建

### 8. worker02 网络中断（人为）— 15 min

* **症状**: worker02 上 Pod 跨节点网络中断
* **根因**: 排查 Jaeger 问题时操作员执行了 `iptables -F FORWARD` 清空 Calico 规则
* **修复**: `systemctl restart containerd kubelet` 重建网络栈
* **预防**: 🚫 绝对不要在运行中的 K8s 节点上 flush iptables

---

## 系统性问题

| 问题 | 影响 |
|------|------|
| **无冷启动后自动化** | 每次重启手动重发现 8 个已知故障 |
| **探针时间不自动计算** | 加组件（OTEL）后没更新探针 |
| **F12 Network 不是默认调试步骤** | 在基建层面 debug 前端问题 |
| **已知问题手册存在但未查阅** | Troubleshooting handbook 有探针公式但没看 |
| **V2 组件部署无文档** | Jaeger 用未知状态部署 |
| **运维成熟度** | Level 2/5 — 有手册有脚本但无自动化无自愈 |

---

## 推荐修复（每项 < 1 小时）

1. ✅ **写 `scripts/post-boot.sh`** — 启动后一键自动化（30min）
2. ✅ **写 `scripts/quick-check.sh`** — 1 分钟集群健康快照（10min）
3. 🔜 **Jaeger → Grafana Tempo** — 解决 EOL + 前端 bug（30min）
4. 🔜 **探针时间写进 YAML 注释** — 不靠记忆（5min）
5. 🔜 **F12 Network 加进排障手册** — 前端问题先看请求（5min）
6. 🔜 **Harbor 项目公开 + `.image-version` 文件** — 消除 tag 不匹配（15min）

---

## 面试讲述模板

当面试官问"讲一次故障排查经历"：

> V1 上线后我们把集群关了 27 天，重开的时候遇到了一连串问题——Docker bridge、探针太短、Jaeger 不显示 trace。最坑的是 Jaeger 1.60 有个已知前端 bug，搜索时把请求 URL 构造错了，API 能查到数据但 UI 永远是空的。我刚接触这种问题的时候在基建层面绕了 3 个小时，后来复盘的时候写到排障手册里：**前端 UI 问题先看 Network tab，不要想当然去改后端**。这次之后我写了冷启动脚本和一键健康检查，确保下次不会重复踩坑。

**关键词**: cold-boot failure、Jaeger EOL、startupProbe、troubleshooting escalation、post-boot automation、LGTM stack migration
