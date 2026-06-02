#!/bin/bash
# bank-mall-cloudnative 集群恢复脚本
# 用途：VM 重启后一键恢复所有服务
# 运行位置：master01 (10.0.0.31)
#
# 用法：
#   bash scripts/recover.sh          # 正常恢复（检查+启动）
#   bash scripts/recover.sh --force  # 强制恢复（重置 containerd 数据库+重建）
#
# 注意：如果 VM 强制关机（断电/蓝屏），可能需要先做 fsck：
#   1. 重启 VM，在 GRUB 菜单选 "Advanced options" → "recovery mode" → "fsck"
#   2. 或在 initramfs shell 中执行：fsck -y /dev/mapper/ubuntu--vg-ubuntu--lv

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

FORCE_MODE=false
if [ "$1" = "--force" ]; then
    FORCE_MODE=true
fi

echo "========================================"
echo "  Bank-Mall K8s 集群恢复脚本"
echo "  force=$FORCE_MODE"
echo "========================================"

##############################################################################
# 1. 检查并关闭 swap
##############################################################################
echo ""
echo -e "${YELLOW}[1/10] 检查 swap...${NC}"
SWAP=$(free -m | grep Swap | awk '{print $2}')
if [ "$SWAP" != "0" ]; then
    echo -e "${RED}swap 未关闭，正在关闭...${NC}"
    sudo swapoff -a
    sudo sed -i '/swap/s/^/#/' /etc/fstab 2>/dev/null || true
    echo -e "${GREEN}swap 已关闭 ✓${NC}"
else
    echo -e "${GREEN}swap 已关闭 ✓${NC}"
fi

##############################################################################
# 2. 修复 containerd（数据库损坏+配置重置）
##############################################################################
echo ""
echo -e "${YELLOW}[2/10] 修复 containerd...${NC}"

# 确保 containerd 配置正确（VM 重启/fsck 后配置可能被重置）
echo "  检查 containerd 配置..."
CURRENT_SG=$(grep "SystemdCgroup" /etc/containerd/config.toml 2>/dev/null | head -1 | grep -o "true\|false" || echo "unknown")
CURRENT_SB=$(grep "sandbox = " /etc/containerd/config.toml 2>/dev/null | head -1 || echo "")

NEED_RECONFIG=false
if [ "$CURRENT_SG" != "true" ]; then
    echo -e "  ${RED}SystemdCgroup=false，需要修复${NC}"
    NEED_RECONFIG=true
fi
if ! echo "$CURRENT_SB" | grep -q "registry.aliyuncs.com"; then
    echo -e "  ${RED}sandbox_image 不是阿里云，需要修复${NC}"
    NEED_RECONFIG=true
fi

if [ "$NEED_RECONFIG" = "true" ]; then
    echo "  重新生成 containerd 配置..."
    sudo containerd config default | sudo tee /etc/containerd/config.toml > /dev/null
    sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
    sudo sed -i "s|sandbox = 'registry.k8s.io/pause:.*'|sandbox = 'registry.aliyuncs.com/google_containers/pause:3.10.2'|" /etc/containerd/config.toml
    echo -e "${GREEN}containerd 配置已修复 ✓${NC}"
else
    echo -e "${GREEN}containerd 配置正确 ✓${NC}"
fi

# 停止 containerd 清理损坏的数据库
echo "  检查 containerd 数据库..."
sudo systemctl stop containerd 2>/dev/null || true

# bolt 数据库可能损坏（VM 强制关机导致）
# 如果是目录则 rm -rf，如果是文件则 rm -f
if [ -d /var/lib/containerd/io.containerd.metadata.v1.bolt ]; then
    echo -e "  ${YELLOW}containerd bolt 数据库（目录）损坏，清理...${NC}"
    sudo rm -rf /var/lib/containerd/io.containerd.metadata.v1.bolt
elif [ -f /var/lib/containerd/io.containerd.metadata.v1.bolt ]; then
    echo -e "  ${YELLOW}containerd bolt 数据库（文件）损坏，清理...${NC}"
    sudo rm -f /var/lib/containerd/io.containerd.metadata.v1.bolt
fi

# 强制模式：彻底清理 containerd 状态
if [ "$FORCE_MODE" = true ]; then
    echo -e "  ${YELLOW}强制模式：清理所有 containerd 状态...${NC}"
    sudo rm -rf /var/lib/containerd/*
fi

# 启动 containerd
sudo systemctl start containerd
sleep 5

if systemctl is-active --quiet containerd; then
    echo -e "${GREEN}containerd 运行中 ✓${NC}"
else
    echo -e "${RED}containerd 启动失败！${NC}"
    echo "  尝试彻底清理后重启..."
    sudo systemctl stop containerd 2>/dev/null || true
    sudo rm -rf /var/lib/containerd/io.containerd.metadata.v1.bolt
    sudo systemctl start containerd
    sleep 5
    if systemctl is-active --quiet containerd; then
        echo -e "${GREEN}containerd 重启成功 ✓${NC}"
    else
        echo -e "${RED}containerd 仍然失败！需要手动排查${NC}"
        echo "  1. 检查文件系统：fsck -y /dev/mapper/ubuntu--vg-ubuntu--lv"
        echo "  2. 重新生成配置：sudo containerd config default | sudo tee /etc/containerd/config.toml"
        echo "  3. 然后重新运行此脚本"
        exit 1
    fi
fi

# 导入 K8s 控制平面镜像（如果本地没有）
echo "  检查 K8s 控制平面镜像..."
if ! sudo ctr -n k8s.io images ls | grep -q "kube-apiserver"; then
    echo -e "  ${YELLOW}K8s 控制平面镜像缺失，从 /tmp/k8s-images.tar.gz 导入...${NC}"
    if [ -f /tmp/k8s-images.tar.gz ]; then
        gunzip -c /tmp/k8s-images.tar.gz | sudo ctr -n k8s.io images import -
        echo -e "${GREEN}K8s 镜像导入完成 ✓${NC}"
    elif [ -f /tmp/k8s-images.tar ]; then
        sudo ctr -n k8s.io images import /tmp/k8s-images.tar
        echo -e "${GREEN}K8s 镜像导入完成 ✓${NC}"
    else
        echo -e "  ${RED}未找到 /tmp/k8s-images.tar.gz 或 /tmp/k8s-images.tar${NC}"
        echo "  请从 Harbor 节点传输镜像："
        echo "    # Harbor 节点上执行："
        echo "    docker save registry.aliyuncs.com/google_containers/kube-apiserver:v1.36.1 \\"
        echo "      registry.aliyuncs.com/google_containers/kube-controller-manager:v1.36.1 \\"
        echo "      registry.aliyuncs.com/google_containers/kube-scheduler:v1.36.1 \\"
        echo "      registry.aliyuncs.com/google_containers/etcd:3.6.8-0 \\"
        echo "      registry.aliyuncs.com/google_containers/kube-proxy:v1.36.1 \\"
        echo "      registry.aliyuncs.com/google_containers/coredns:v1.12.0 \\"
        echo "      registry.aliyuncs.com/google_containers/pause:3.10.2 | gzip > /tmp/k8s-images.tar.gz"
        echo "    scp /tmp/k8s-images.tar.gz root@10.0.0.31:/tmp/"
        echo "    scp /tmp/k8s-images.tar.gz qian@10.0.0.41:/tmp/"
        echo "    scp /tmp/k8s-images.tar.gz qian@10.0.0.42:/tmp/"
        exit 1
    fi
else
    echo -e "${GREEN}K8s 控制平面镜像已存在 ✓${NC}"
fi

# 导入 pause 镜像（单独检查）
if ! sudo ctr -n k8s.io images ls | grep -q "pause:3.10.2"; then
    echo -e "  ${YELLOW}pause 镜像缺失，从本地文件导入...${NC}"
    if [ -f /tmp/pause.tar.gz ]; then
        gunzip -c /tmp/pause.tar.gz | sudo ctr -n k8s.io images import -
    elif [ -f /tmp/pause.tar ]; then
        sudo ctr -n k8s.io images import /tmp/pause.tar
    else
        echo -e "  ${RED}未找到 pause 镜像文件，将尝试在线拉取${NC}"
        sudo ctr -n k8s.io images pull registry.aliyuncs.com/google_containers/pause:3.10.2 || true
    fi
fi

##############################################################################
# 3. 修复 kubelet（userns 损坏）
##############################################################################
echo ""
echo -e "${YELLOW}[3/10] 修复 kubelet...${NC}"

# 清理损坏的 userns 数据（VM 强制关机导致）
if ls /var/lib/kubelet/pods/*/userns 2>/dev/null; then
    echo -e "  ${YELLOW}发现损坏的 userns 数据，清理...${NC}"
    sudo find /var/lib/kubelet/pods -name userns -exec rm -rf {} + 2>/dev/null || true
fi

# 启动 kubelet
sudo systemctl restart kubelet
sleep 10

if systemctl is-active --quiet kubelet; then
    echo -e "${GREEN}kubelet 运行中 ✓${NC}"
else
    echo -e "${RED}kubelet 启动失败！检查日志：${NC}"
    echo "  journalctl -u kubelet -n 20 --no-pager"
    
    # 检查是否是 userns 问题
    if journalctl -u kubelet -n 5 --no-pager | grep -q "userns"; then
        echo -e "  ${YELLOW}检测到 userns 错误，清理 kubelet pods 目录...${NC}"
        sudo systemctl stop kubelet
        sudo rm -rf /var/lib/kubelet/pods/
        sudo systemctl start kubelet
        sleep 10
        if systemctl is-active --quiet kubelet; then
            echo -e "${GREEN}kubelet 修复成功 ✓${NC}"
        else
            echo -e "${RED}kubelet 仍然失败！可能需要 kubeadm reset 重建集群${NC}"
            echo "  运行：bash scripts/recover.sh --force"
            exit 1
        fi
    fi
fi

##############################################################################
# 4. 检查集群连接
##############################################################################
echo ""
echo -e "${YELLOW}[4/10] 检查 API Server...${NC}"
echo "等待 API Server 就绪（最多 180 秒）..."
API_READY=false
for i in $(seq 1 36); do
    if kubectl get nodes --no-headers 2>/dev/null | grep -q "Ready"; then
        API_READY=true
        break
    fi
    # 检查是否部分 Ready
    NODES=$(kubectl get nodes --no-headers 2>/dev/null | wc -l)
    if [ "$NODES" -gt 0 ]; then
        READY=$(kubectl get nodes --no-headers 2>/dev/null | grep " Ready" | wc -l)
        echo "  等待中... $READY/$NODES 节点 Ready（${i}/36）"
    else
        echo "  等待 API Server 启动...（${i}/36）"
    fi
    sleep 5
done

if [ "$API_READY" = true ]; then
    kubectl get nodes
    echo -e "${GREEN}API Server 就绪 ✓${NC}"
else
    echo -e "${RED}API Server 未就绪！${NC}"
    echo "  检查静态 Pod："
    echo "    sudo ctr -n k8s.io containers ls | grep kube"
    echo "    journalctl -u kubelet -n 30 --no-pager"
    echo ""
    echo "  如果需要完全重建集群，运行："
    echo "    bash scripts/recover.sh --force"
    exit 1
fi

##############################################################################
# 5. 检查 Calico 网络插件
##############################################################################
echo ""
echo -e "${YELLOW}[5/10] 检查 Calico...${NC}"
if kubectl get pods -n kube-system -l k8s-app=calico-node --no-headers 2>/dev/null | grep -q "Running"; then
    echo -e "${GREEN}Calico 运行中 ✓${NC}"
else
    echo -e "${YELLOW}Calico 未就绪，等待...${NC}"
    kubectl wait --for=condition=ready pods -l k8s-app=calico-node -n kube-system --timeout=120s 2>/dev/null || true
fi

# 修正 CIDR（Calico 默认可能用 192.168.0.0/16）
CURRENT_CIDR=$(kubectl get ippool default-ipv4-ippool -o jsonpath='{.spec.cidr}' 2>/dev/null || echo "unknown")
if [ "$CURRENT_CIDR" != "10.244.0.0/16" ]; then
    echo -e "  ${YELLOW}Calico CIDR 是 $CURRENT_CIDR，修正为 10.244.0.0/16...${NC}"
    kubectl patch ippool default-ipv4-ippool -p '{"spec":{"cidr":"10.244.0.0/16"}}' --type=merge 2>/dev/null || echo "  IPPool patch 失败，可能需要手动修正"
else
    echo -e "${GREEN}Calico CIDR 正确 (${CURRENT_CIDR}) ✓${NC}"
fi

##############################################################################
# 6. 检查节点状态
##############################################################################
echo ""
echo -e "${YELLOW}[6/10] 检查节点状态...${NC}"
echo "等待所有节点 Ready（最多 180 秒）..."
for i in $(seq 1 36); do
    NOT_READY=$(kubectl get nodes --no-headers 2>/dev/null | grep -v "Ready" | wc -l)
    READY=$(kubectl get nodes --no-headers 2>/dev/null | grep " Ready" | wc -l)
    TOTAL=$(kubectl get nodes --no-headers 2>/dev/null | wc -l)
    if [ "$NOT_READY" -eq 0 ] && [ "$TOTAL" -ge 1 ]; then
        kubectl get nodes
        echo -e "${GREEN}所有 $TOTAL 节点 Ready ✓${NC}"
        break
    fi
    if [ "$i" -eq 36 ]; then
        echo -e "${RED}部分节点未就绪：${NC}"
        kubectl get nodes
        echo "  Worker 节点加入命令："
        echo "    sudo kubeadm join 10.0.0.31:6443 --token <token> --discovery-token-ca-cert-hash sha256:<hash>"
        echo "  获取新 token：kubeadm token create --print-join-command"
    fi
    echo "  等待中... $READY/$TOTAL Ready（${i}/36）"
    sleep 5
done

##############################################################################
# 7. 检查 Harbor
##############################################################################
echo ""
echo -e "${YELLOW}[7/10] 检查 Harbor (10.0.0.61)...${NC}"
if curl -sf -u admin:<HARBOR_PASSWORD> http://10.0.0.61/api/v2.0/systeminfo > /dev/null 2>&1; then
    echo -e "${GREEN}Harbor 运行中 ✓${NC}"
else
    echo -e "${RED}Harbor 不可达！${NC}"
    echo "  请在 Harbor 节点 (10.0.0.61) 执行："
    echo "    cd ~/harbor && sudo docker compose up -d"
    echo ""
    read -p "  Harbor 是否已启动？(y/n) " -t 30 HARBOR_READY || HARBOR_READY="n"
    if [ "$HARBOR_READY" != "y" ]; then
        echo -e "${YELLOW}跳过 Harbor 检查，业务镜像拉取可能失败${NC}"
    fi
fi

##############################################################################
# 8. 部署业务应用（如果集群是新建的）
##############################################################################
echo ""
echo -e "${YELLOW}[8/10] 检查业务 Pod...${NC}"
if kubectl get ns bank-mall 2>/dev/null | grep -q "bank-mall"; then
    echo "  bank-mall namespace 已存在"
else
    echo -e "  ${YELLOW}bank-mall namespace 不存在，需要部署${NC}"
    if [ -f ~/bank-mall-cloudnative/scripts/deploy.sh ]; then
        echo "  执行 deploy.sh..."
        cd ~/bank-mall-cloudnative && bash scripts/deploy.sh
        cd ~
    else
        echo "  未找到 deploy.sh，请手动部署"
        echo "    scp -r root@10.0.0.61:~/bank-mall-cloudnative/k8s ~/bank-mall-cloudnative/"
        echo "    scp -r root@10.0.0.61:~/bank-mall-cloudnative/scripts ~/bank-mall-cloudnative/"
        echo "    cd ~/bank-mall-cloudnative && bash scripts/deploy.sh"
    fi
fi

# 检查 Pod 状态
echo "  等待 Pod 恢复（最多 180 秒）..."
for i in $(seq 1 36); do
    NOT_RUNNING=$(kubectl get pods -n bank-mall --no-headers 2>/dev/null | grep -v "Running" | grep -v "Completed" | wc -l)
    TOTAL=$(kubectl get pods -n bank-mall --no-headers 2>/dev/null | wc -l)
    if [ "$NOT_RUNNING" -eq 0 ] && [ "$TOTAL" -gt 0 ]; then
        kubectl get pods -n bank-mall
        echo -e "${GREEN}所有 bank-mall Pod Running ✓${NC}"
        break
    fi
    if [ "$TOTAL" -eq 0 ]; then
        echo "  等待 Pod 创建...（${i}/36）"
    else
        READY=$(kubectl get pods -n bank-mall --no-headers 2>/dev/null | grep " Running" | wc -l)
        echo "  等待中... $READY/$TOTAL Running（${i}/36）"
    fi
    if [ "$i" -eq 36 ]; then
        echo -e "${YELLOW}部分 Pod 未恢复：${NC}"
        kubectl get pods -n bank-mall
        echo ""
        echo "  常见问题："
        echo "    ImagePullBackOff → 需要在 worker 节点预拉镜像"
        echo "    CrashLoopBackOff → 检查日志：kubectl logs <pod> -n bank-mall"
    fi
    sleep 5
done

##############################################################################
# 9. 检查监控
##############################################################################
echo ""
echo -e "${YELLOW}[9/10] 检查监控组件...${NC}"
MON_OK=true
for ns in monitoring ingress-nginx; do
    if kubectl get ns $ns 2>/dev/null | grep -q "$ns"; then
        NOT_RUNNING=$(kubectl get pods -n $ns --no-headers 2>/dev/null | grep -v "Running" | grep -v "Completed" | wc -l)
        if [ "$NOT_RUNNING" -gt 0 ]; then
            echo -e "  ${YELLOW}$ns 有 Pod 未恢复${NC}"
            kubectl get pods -n $ns
            MON_OK=false
        else
            echo -e "${GREEN}$ns 全部 Running ✓${NC}"
        fi
    else
        echo -e "  ${YELLOW}$ns namespace 不存在${NC}"
        MON_OK=false
    fi
done

##############################################################################
# 10. 验证服务可访问性
##############################################################################
echo ""
echo -e "${YELLOW}[10/10] 验证服务可访问性...${NC}"
sleep 2

for svc in auth account payment notification; do
    STATUS=$(curl -sf -o /dev/null -w "%{http_code}" http://10.0.0.41:30080/${svc}/actuator/health 2>/dev/null || echo "000")
    if [ "$STATUS" = "200" ]; then
        echo -e "${GREEN}${svc}-service: OK (${STATUS})${NC}"
    elif [ "$STATUS" = "000" ]; then
        echo -e "${YELLOW}${svc}-service: 超时（可能还在启动中）${NC}"
    else
        echo -e "${RED}${svc}-service: FAIL (${STATUS})${NC}"
    fi
done

echo ""
echo "========================================"
echo "  恢复检查完成"
echo "========================================"
echo ""
echo "  访问地址："
echo "    Ingress:  http://10.0.0.41:30080/auth/actuator/health"
echo "    Grafana:  http://10.0.0.41:30300 (admin/<GRAFANA_PASSWORD>)"
echo "    Prom:     http://10.0.0.41:30090"
echo ""
echo "  如需完全重建集群（kubeadm reset）："
echo "    bash scripts/recover.sh --force"
echo ""
echo "========================================"