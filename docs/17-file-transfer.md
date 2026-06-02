# 文件同步指南：Windows 工作站 → Linux 虚拟机

> 本文档解决一个实际问题：你在 Windows 上编辑了代码和 YAML，怎么把它们传到 VMware 里的 Linux 节点上执行？

---

## 一、工作流总览

```
Windows 工作站 (代码编辑)
    │
    │ 方式 1: Git clone (推荐)
    │ 方式 2: scp / rsync
    │ 方式 3: VMware 共享文件夹
    ▼
Linux VM (Harbor 节点 / Master 节点)
    │
    │ bash scripts/deploy.sh
    │ bash scripts/ci.sh
    ▼
K8s 集群 (10.0.0.31/41/42)
```

---

## 二、方式一：Git clone（推荐）

### 2.1 在 GitHub 上创建仓库

**步骤 1：登录 GitHub**

浏览器打开 https://github.com，登录你的账号。

**步骤 2：创建新仓库**

1. 点击右上角 **"+"** → **"New repository"**
2. 填写信息：

| 字段 | 填写内容 |
|------|---------|
| Repository name | `bank-mall-cloudnative` |
| Description | 某银行电子商城云原生改造与 Kubernetes 高可用部署实践 |
| Public / Private | **Private**（含密码等敏感信息，不建议公开） |
| Add a README file | ❌ 不勾选（本地已有 README） |
| Add .gitignore | ❌ 不勾选（本地会创建） |
| Choose a license | ❌ 不勾选 |

3. 点击 **"Create repository"**

**步骤 3：复制仓库地址**

创建成功后，页面会显示仓库地址，格式为：
```
https://github.com/<你的用户名>/bank-mall-cloudnative.git
```
复制这个地址，后面要用。

---

### 2.2 在 Windows 上初始化本地 Git 仓库

打开 PowerShell，进入项目目录：

```powershell
cd C:\LearningResources\k8s项目\bank-mall-cloudnative
```

**步骤 1：初始化 Git**

```powershell
git init
git branch -M main
```

**步骤 2：创建 .gitignore**

```powershell
@"
# Java
target/
*.class
*.jar
*.war

# IDE
.idea/
.vscode/
*.iml
*.ipr
*.iws

# OS
.DS_Store
Thumbs.db

# Env files (含敏感信息)
.env
*.env

# Harbor offline installer (大文件)
*.tgz

# Temporary
tmp/
*.log
"@ | Out-File -Encoding utf8 .gitignore
```

**步骤 3：添加所有文件并提交**

```powershell
git add .
git commit -m "init: bank-mall-cloudnative project with K8s manifests, monitoring, CI/CD"
```

**步骤 4：关联远程仓库并推送**

```powershell
# 替换 <你的用户名> 为实际 GitHub 用户名
git remote add origin https://github.com/<你的用户名>/bank-mall-cloudnative.git

# 首次推送
git push -u origin main
```

推送时会弹出浏览器让你登录 GitHub 授权，按提示操作即可。

---

### 2.3 后续更新流程

```powershell
# Windows 上修改代码后
git add .
git commit -m "feat: add ingress nginx deployment"
git push

# 在 Linux VM 上拉取最新代码
cd ~/bank-mall-cloudnative
git pull origin main
```

---

### 2.4 常见问题

**Q: 推送时提示 `remote origin already exists`**
```powershell
# 删除旧 remote 再重新添加
git remote remove origin
git remote add origin https://github.com/<你的用户名>/bank-mall-cloudnative.git
```

**Q: 推送失败 `rejected (fetch first)`**
```powershell
# 先拉取远程变更再推送
git pull origin main --rebase
git push
```

**Q: 不想每次输入密码**
```powershell
# 使用 GitHub CLI 或配置 credential helper
git config --global credential.helper manager
```

**Q: 仓库设为 Private 后 Linux VM 怎么 clone？**
```bash
# 方式 1: 使用 Personal Access Token (PAT)
git clone https://<你的用户名>:<PAT>@github.com/<你的用户名>/bank-mall-cloudnative.git

# 方式 2: 使用 SSH key（推荐）
# 在 Linux VM 上生成 SSH key 并添加到 GitHub Settings → SSH keys
git clone git@github.com:<你的用户名>/bank-mall-cloudnative.git
```

---

### 2.5 在 Linux VM 上克隆

```bash
# 在 Harbor 节点或 master 节点执行
cd ~
git clone https://github.com/<你的用户名>/bank-mall-cloudnative.git
cd bank-mall-cloudnative

# 确认文件完整
ls k8s/base/
ls scripts/
ls docs/
```

---

## 三、方式二：scp / rsync（不依赖外网）

### 适用场景

- VM 在纯内网，无法访问 GitHub
- 只需要传文件，不需要版本管理

### 操作步骤

**从 Windows PowerShell 执行：**

```powershell
# 前提：Windows 上有 SSH 客户端（Win10+ 自带）
# 替换为你的实际用户名和 VM IP

# 1. 传整个项目目录
scp -r C:\LearningResources\k8s项目\bank-mall-cloudnative user@10.0.0.61:~/

# 2. 或只传修改过的文件
scp C:\LearningResources\k8s项目\bank-mall-cloudnative\scripts\deploy.sh user@10.0.0.31:~/bank-mall-cloudnative/scripts/
scp C:\LearningResources\k8s项目\bank-mall-cloudnative\k8s\base\ingress\* user@10.0.0.31:~/bank-mall-cloudnative/k8s/base/ingress/
```

**从 Linux VM 上拉取（反向操作）：**

```bash
# 在 Linux VM 上执行
rsync -avz user@<windows-ip>:/path/to/bank-mall-cloudnative/ ~/bank-mall-cloudnative/
```

### 快捷脚本

在 Windows 上创建 `sync-to-vm.ps1`：

```powershell
# 同步到 Harbor 节点（构建+推送用）
scp -r .\bank-mall-cloudnative\* user@10.0.0.61:~/bank-mall-cloudnative/

# 同步到 Master 节点（kubectl apply 用）
scp -r .\bank-mall-cloudnative\k8s\* user@10.0.0.31:~/bank-mall-cloudnative/k8s/
scp -r .\bank-mall-cloudnative\scripts\* user@10.0.0.31:~/bank-mall-cloudnative/scripts/

Write-Host "Sync complete!" -ForegroundColor Green
```

---

## 四、方式三：VMware 共享文件夹

### 适用场景

- 不想每次手动传文件
- Windows 和 VM 在同一台物理机上

### 设置步骤

**1. 在 VMware Workstation 中设置共享文件夹：**

```
VM → Settings → Options → Shared Folders
→ Always enabled
→ Add → 选择 C:\LearningResources\k8s项目
→ 勾选 Enable this share
```

**2. 在 Linux VM 中挂载：**

```bash
# 共享文件夹默认挂载在 /mnt/hgfs/
ls /mnt/hgfs/k8s项目/bank-mall-cloudnative/

# 如果没有自动挂载：
sudo vmhgfs-fuse .host:/ /mnt/hgfs -o allow_other
```

**3. 在 VM 上直接操作：**

```bash
cd /mnt/hgfs/k8s项目/bank-mall-cloudnative
bash scripts/deploy.sh
```

### 注意事项

- 共享文件夹中的文件权限可能有问题（Linux 看到的 owner 是 root）
- 不适合执行 `git commit`（会产生权限问题）
- 适合"Windows 编辑 → VM 执行"的单向工作流

---

## 五、推荐工作流

根据你的实际环境选择：

| 场景 | 推荐方式 | 原因 |
|------|---------|------|
| VM 能上网 | Git clone + pull | 版本管理、多节点同步、可回滚 |
| VM 纯内网 | scp/rsync | 简单直接、不依赖外网 |
| 频繁修改 | VMware 共享文件夹 | 无需手动同步、实时生效 |

### 我的建议（当前项目）

**开发阶段：**
```
Windows: 编辑代码/YAML → git commit → git push
VM:      git pull → bash scripts/deploy.sh
```

**调试阶段：**
```
Windows: 修改单个文件 → scp 到 VM
VM:      验证效果
```

---

## 六、各节点同步策略

项目文件在不同节点上的用途不同：

| 节点 | IP | 需要项目源码？ | 需要容器镜像？ | 建议同步方式 |
|------|-----|:---:|:---:|------------|
| k8s-master01 | 10.0.0.31 | ✅ 需要 k8s/ scripts/ | ❌ | Windows scp 或从 Harbor scp |
| k8s-worker01 | 10.0.0.41 | ❌ | ✅ 需要 | `ctr pull` 拉取镜像（见下方） |
| k8s-worker02 | 10.0.0.42 | ❌ | ✅ 需要 | `ctr pull` 拉取镜像 |
| k8s-harbor01 | 10.0.0.61 | ✅ 需要全部（构建用） | ✅ Docker 本地 | Git clone / scp |

### Master 节点同步

```powershell
# Windows → Master（直接 scp）
scp -r C:\LearningResources\k8s项目\bank-mall-cloudnative root@10.0.0.31:~/
```

或者 Harbor 已经拉取了完整项目，内网传更快：

```bash
# Harbor → Master（内网 scp，速度快）
scp -r ~/bank-mall-cloudnative root@10.0.0.31:~/
```

### Worker 节点不需要项目源码

Worker 节点只负责运行 Pod，不执行 `kubectl` 命令。它们需要的只是从 Harbor 拉取容器镜像。

Harbor HTTP 模式下，手动预拉镜像到 worker：

```bash
# 在每台 worker 节点执行（k8s-worker01 / k8s-worker02）
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/auth-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/account-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/payment-service:1.0.0
sudo ctr -n k8s.io images pull --plain-http --user admin:<HARBOR_PASSWORD> 10.0.0.61/bank-mall/notification-service:1.0.0
```

> 生产环境建议 Harbor 配置 HTTPS + 各节点导入 CA 证书，或节点少时在 build-images.sh 里加自动分布式拉取。

## 七、常见问题

### Q1: scp 传输时提示 `Permission denied`

```bash
# 确保目标目录存在且有写权限
ssh user@10.0.0.61 "mkdir -p ~/bank-mall-cloudnative"
```

### Q2: 传上去的文件没有执行权限

```bash
# 在 VM 上执行
chmod +x ~/bank-mall-cloudnative/scripts/*.sh
```

### Q3: Windows 和 Linux 换行符不同导致脚本执行失败

```bash
# 在 VM 上转换
sudo apt install -y dos2unix
dos2unix ~/bank-mall-cloudnative/scripts/*.sh
```

### Q4: Git clone 时提示 SSL 证书错误

```bash
# 如果是自签证书或代理问题
git config --global http.sslVerify false
git clone https://github.com/...
```

---

## 八、检查清单

每次部署前确认：

- [ ] Harbor 节点有完整项目 + 镜像已推送到 Harbor
- [ ] Master 节点有 k8s/ 和 scripts/ 目录
- [ ] Worker 节点已预拉镜像（`ctr images ls | grep bank-mall`）
- [ ] 脚本有执行权限（`ls -l scripts/*.sh`）
- [ ] 换行符正确（`file scripts/deploy.sh` 应显示 `ASCII text` 而非 `CRLF`）

---

> 本文档配合 `docs/15-ingress-deployment.md`（Ingress 部署）、`docs/16-cicd-mysql-prometheus.md`（组合拳部署）一起使用。
> 传完文件后，按对应文档的部署步骤执行。
