# 高可用架构设计

> **状态**：设计文档（V1 不实现，V2 参考）
> **日期**：2026-06-08
> **集群**：1 control plane + 2 worker（V1 实验集群）→ 3 master + 2 worker（V2 目标）

---

## 一、现状问题

| 维 | V1 现状 | 生产风险 |
|----|---------|---------|
| Control Plane | 1 个（master01, 10.0.0.31） | 单点故障。master01 宕机 → 整个集群不可调度，deployment 不工作 |
| etcd | 单节点，无备份 | 数据丢失 = 集群状态丢失（所有 Pod/Service/ConfigMap 定义） |
| API Server | 单 IP（10.0.0.31:6443） | kubectl 不可达，ArgoCD 不同步，HPA 不伸缩 |
| 网络 | Calico IPIP，单点无冗余 | Calico typha 挂了不影响已建立规则，但新规则不入栈 |

---

## 二、推荐方案：3 Master + HAProxy + Keepalived

### 2.1 拓扑

```
┌─────────────────────────────────────────────────────┐
│                  负载均衡层                           │
│   ┌──────────────┐          ┌──────────────┐        │
│   │ HAProxy-1    │          │ HAProxy-2    │        │
│   │ 10.0.0.11    │◄────────►│ 10.0.0.12    │        │
│   └──────┬───────┘          └──────┬───────┘        │
│          │                         │                │
│   ┌──────┴─────────────────────────┴──────┐         │
│   │      Keepalived VIP: 10.0.0.10       │         │
│   └──────────────────────────────────────┘         │
└─────────────────────────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
   │ k8s-master01 │ │ k8s-master02 │ │ k8s-master03 │
   │  10.0.0.31   │ │  10.0.0.32   │ │  10.0.0.33   │
   │  etcd-1      │ │  etcd-2      │ │  etcd-3      │
   │  api-server  │ │  api-server  │ │  api-server  │
   │  controller  │ │  controller  │ │  controller  │
   │  scheduler   │ │  scheduler   │ │  scheduler   │
   └──────────────┘ └──────────────┘ └──────────────┘
```

### 2.2 选型理由

| 方案 | 优点 | 缺点 | 选择？ |
|------|------|------|:---:|
| HAProxy + Keepalived | 轻量，零依赖，裸金属/VM 原生，社区 20+ 年 | 需手动配置，非 K8s-native | ✅ V2 推荐 |
| Nginx TCP Stream | 同轻量，语法熟悉 | 不支持 UDP health check，配置热重载不如 HAProxy | ❌ |
| 商业 LB（F5/A10） | 企业级，硬件加速 | VMware 环境没有 | ❌ |
| kube-vip | K8s-native，DaemonSet 部署 | 实验集群无 ARP 广播权限 | ❌ |
| MetalLB | 同 K8s-native | 需 BGP，VMware NAT 不支持 | ❌ |

### 2.3 etcd 拓扑

- **3 节点**（奇数 = Raft 共识）
- Quorum: `floor(N/2) + 1 = 2`（任何 1 节点宕机集群仍可写入）
- 2 节点宕机 = 丧失 quorum → 集群只读

---

## 三、脑裂防护（Split-Brain Protection）

### 3.1 Keepalived 配置

```bash
# /etc/keepalived/keepalived.conf — master01/02/03 三节点通用
global_defs {
    router_id K8S_HA
    enable_script_security
}

vrrp_script chk_haproxy {
    script "/usr/bin/killall -0 haproxy"
    interval 2
    weight -2
    fall 2
    rise 2
}

vrrp_instance VI_1 {
    state BACKUP                 # ⚠️ 全为 BACKUP，无 MASTER
    interface eth0
    virtual_router_id 51
    priority 100                 # master01=100, master02=99, master03=98
    advert_int 1                 # 1s 心跳（VMware NAT 足够稳定）
    nopreempt                    # ⚠️ 禁止抢占，避免 VIP 震荡
    virtual_ipaddress {
        10.0.0.10/24
    }
    track_script {
        chk_haproxy
    }
}
```

**配置要点**：
- `state BACKUP` + `nopreempt`：三节点都从 BACKUP 启动，谁的 `priority` 最高谁拿 VIP。拿了 VIP 后即使更高 priority 节点上线也不会被抢走（nopreempt）。避免 VIP 反复漂移导致的 API Server 连接中断。
- `advert_int 1`：1 秒心跳。VMware NAT 环境延迟 < 1ms，1 秒足够检测节点宕机。**不要设得太短**——VM 暂停/恢复（suspend/resume）可能触发 2-3 秒假死，设 0.3s 会误判。
- `track_script chk_haproxy`：HAProxy 进程被杀 → VIP 立即转移，不等 heartbeat 超时。

### 3.2 脑裂检测脚本

```bash
#!/bin/bash
# /usr/local/bin/split-brain-check.sh
# 每 5 秒 cron 执行

VIP="10.0.0.10"
MY_IP=$(ip addr show eth0 | grep "inet " | awk '{print $2}' | cut -d/ -f1)
OTHER_IPS=("10.0.0.11" "10.0.0.12")

# 1. 我有 VIP 吗？
HAVE_VIP=$(ip addr show eth0 | grep -c "$VIP")

if [ "$HAVE_VIP" -eq 1 ]; then
    # 2. 对方也有 VIP 吗？（脑裂：两个节点同时有 VIP）
    for peer in "${OTHER_IPS[@]}"; do
        if [ "$peer" != "$MY_IP" ]; then
            PEER_VIP=$(ssh -o ConnectTimeout=2 "$peer" "ip addr show eth0 | grep -c '$VIP'" 2>/dev/null || echo 0)
            if [ "$PEER_VIP" -eq 1 ]; then
                logger -t split-brain "SPLIT-BRAIN DETECTED: $MY_IP and $peer both have VIP $VIP"
                # 杀自己，让对方接管
                systemctl stop haproxy
                systemctl stop keepalived
                # 飞书/钉钉告警（替换为实际 webhook）
                curl -X POST "$ALERT_WEBHOOK" -H "Content-Type: application/json" \
                  -d "{\"msg_type\":\"text\",\"content\":{\"text\":\"[P0] Split-brain detected on $MY_IP\"}}" 2>/dev/null || true
                exit 1
            fi
        fi
    done
fi
```

**脑裂后果**：两个节点同时持有 VIP → 流量随机分到两个 HAProxy → kube-apiserver 收到来自两个客户端的相同写请求 → etcd Raft 本身可以处理重复 proposal，但性能下降 + 日志混乱。脚本每 5 秒检查，检测到脑裂后优先级低的节点自裁。

### 3.3 VMware NAT 特殊注意事项

- **VM 暂停/恢复**：`systemctl suspend` 或 VMware `Pause` 会导致时钟跳变和网络中断。Keepalived `advert_int 1` 在恢复后需要 3 个周期（3s）重新收敛。配置 `nopreempt` 防止恢复后抢 VIP。
- **MAC 地址冲突**：如果 VM 克隆了相同 MAC，VRRP 多播会混乱。新建 VM 后必须 `uuidgen > /etc/machine-id && rm /etc/ssh/ssh_host_* && dpkg-reconfigure openssh-server` 确保唯一性。

---

## 四、etcd 备份策略

### 4.1 Snapshot 命令

```bash
#!/bin/bash
# /usr/local/bin/etcd-backup.sh — cron: 0 */6 * * *

BACKUP_DIR="/backup/etcd"
RETENTION_DAYS=30
TIMESTAMP=$(date +%Y%m%d-%H%M)
BACKUP_FILE="${BACKUP_DIR}/etcd-${TIMESTAMP}.db"

# Pre-flight: 磁盘空间 ≥ 5GB
AVAIL_MB=$(df -m "$BACKUP_DIR" | tail -1 | awk '{print $4}')
if [ "$AVAIL_MB" -lt 5120 ]; then
    logger -t etcd-backup "ERROR: only ${AVAIL_MB}MB available on ${BACKUP_DIR}, aborting"
    exit 1
fi

mkdir -p "$BACKUP_DIR"

ETCDCTL_API=3 etcdctl snapshot save "$BACKUP_FILE" \
    --endpoints=https://127.0.0.1:2379 \
    --cacert=/etc/kubernetes/pki/etcd/ca.crt \
    --cert=/etc/kubernetes/pki/etcd/server.crt \
    --key=/etc/kubernetes/pki/etcd/server.key

# Verify
ETCDCTL_API=3 etcdctl snapshot status "$BACKUP_FILE" --write-out=table

# SCP to harbor01（备份多一份）
scp "$BACKUP_FILE" root@10.0.0.61:/backup/etcd/

# Cleanup
find "$BACKUP_DIR" -name "etcd-*.db" -mtime "+${RETENTION_DAYS}" -delete
```

### 4.2 恢复

```bash
# 1. 停 kube-apiserver（所有 master）
systemctl stop kube-apiserver

# 2. 恢复（在 master01 上）
ETCDCTL_API=3 etcdctl snapshot restore /backup/etcd/etcd-YYYYMMDD-HHMM.db \
    --name=master01 \
    --initial-cluster=master01=https://10.0.0.31:2380,master02=https://10.0.0.32:2380,master03=https://10.0.0.33:2380 \
    --initial-advertise-peer-urls=https://10.0.0.31:2380 \
    --data-dir=/var/lib/etcd-restore

# 3. 替换 data-dir 并重启 etcd
mv /var/lib/etcd /var/lib/etcd-bak
mv /var/lib/etcd-restore /var/lib/etcd
systemctl start etcd
systemctl start kube-apiserver
```

---

## 五、迁移步骤（三阶段）

### Phase 1：扩 etcd（1h，零停机）

```bash
# 在 master01 上导出 etcd CA 证书
scp /etc/kubernetes/pki/etcd/ca.* root@10.0.0.32:/etc/kubernetes/pki/etcd/
scp /etc/kubernetes/pki/etcd/ca.* root@10.0.0.33:/etc/kubernetes/pki/etcd/

# master02 + master03 加入 etcd 集群
kubeadm join phase etcd local-control-plane \
    --control-plane --apiserver-advertise-address=10.0.0.32

# 验证
ETCDCTL_API=3 etcdctl --endpoints=10.0.0.31:2379,10.0.0.32:2379,10.0.0.33:2379 \
    endpoint health
```

### Phase 2：加 Master（2h）

```bash
# master02 + master03 安装控制平面组件
kubeadm join 10.0.0.10:6443 --token <token> \
    --discovery-token-ca-cert-hash sha256:<hash> \
    --control-plane --apiserver-advertise-address=10.0.0.32

# 复制 admin.conf
scp /etc/kubernetes/admin.conf root@10.0.0.32:/etc/kubernetes/
```

### Phase 3：切 HAProxy（1h）

```bash
# 1. 安装 HAProxy + Keepalived（master01 + master02，两台 LB VM）
apt install -y haproxy keepalived

# 2. HAProxy 配置（/etc/haproxy/haproxy.cfg）
# frontend kubernetes
#     bind 10.0.0.10:6443
#     mode tcp
#     default_backend k8s-apiservers
# backend k8s-apiservers
#     mode tcp
#     balance roundrobin
#     option tcp-check
#     server master01 10.0.0.31:6443 check fall 3 rise 2
#     server master02 10.0.0.32:6443 check fall 3 rise 2
#     server master03 10.0.0.33:6443 check fall 3 rise 2

# 3. kubectl 配置改为 VIP
kubectl config set-cluster bank-mall --server=https://10.0.0.10:6443
```

---

## 六、回滚方案

| Phase | 回滚操作 | RTO |
|-------|---------|-----|
| Phase 1 | `kubeadm reset` master02/03 上的 etcd → 回退到单 etcd | <30min |
| Phase 2 | `kubeadm reset` master02/03 → kubectl 改回 10.0.0.31:6443 | <30min |
| Phase 3 | `systemctl stop haproxy keepalived` → kubectl 改回直连 master01 | <5min |

---

## 七、边界声明

- **不实现**：V1 保持 1 control plane + 2 worker。本文档为设计参考。
- VMware NAT 环境下 **未实测** Keepalived `nopreempt` + `advert_int 1` 的实际切换时间（计划 < 3s）。
- 脑裂检测脚本为 pseudo-code，实际部署前需在目标环境测试 ssh 免密连接和网卡接口名。
- kubeadm join 命令中的 `<token>` 和 `<hash>` 来自实际集群的 `kubeadm token create --print-join-command` 输出。
