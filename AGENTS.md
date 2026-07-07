# AGENTS.md

## 协作约定

- **默认多 agent 协同**：涉及 3+ 个独立文件或跨项目操作时，自动并行派 agent
- **记住用户的明确拒绝**：用户说"不接受 X 方案"后，不再重复提议
- **犯错后停下来**：连续 2 次同类型错误 → 停止执行，分析根因再继续
- **步骤标注依赖和备选**：每个操作步骤标注"若失败，备选方案是什么"
- **版本审计**：每个 Phase 收尾时检查依赖 EOL 状态（参照 `docs/tech-stack-audit.md` 模板）
- **事故复盘**：重大故障后写复盘报告，提取可复用技能/规则，写入本文件
- **前端 Bug 诊断**：任何 UI "无数据" → 先开 F12 Network Tab 确认 API 请求 URL 正确，再动后端
- **网络排障守则**：绝不 `iptables -F` 在运行中的 K8s 节点上
- **探针配置**：Spring Boot + OTEL 启动慢 → 用 `startupProbe`（30×10s=300s 窗口），不用 `initialDelaySeconds`

## 常见故障速查

### VM 冷启动
- Harbor: `systemctl restart docker && iptables -P FORWARD ACCEPT`
- Calico: `kubectl delete pod -n kube-system -l k8s-app=calico-node`
- kube-proxy: `kubectl delete pod -n kube-system -l k8s-app=kube-proxy`

### 镜像 tag 不匹配
- `build-images.sh` 默认用 `git describe --always`，deployment YAML 用 `2.0.0`
- 修复：构建后手动 `docker tag` 或统一版本源

### 探针太短
- 修复后 Bank-mall Pod 用 startupProbe (30×10s=300s)
- Jaeger/Tempo 用 livenessProbe initialDelaySeconds=300s

### 前端 UI Bug（Jaeger 1.60）
- Jaeger 1.60 JS 把搜索请求拼错 URL 路径 → 已迁移至 Grafana Tempo
- 通用方法：F12 Network Tab → 查实际 API 请求 → 对比文档中的正确 API URL
