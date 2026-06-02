# Interview Guide Skill

Prepare resume descriptions and interview talking points for the bank-mall cloud-native project.

## Project Title

某银行电子商城云原生改造与 Kubernetes 高可用部署实践

## Resume Description

参考银行电子商城业务场景，基于 Spring Boot 微服务和 Kubernetes 搭建云原生部署实战环境。项目包含用户认证、账户查询、支付转账、通知服务等基础模块，并规划扩展商品、订单、库存等电子商城核心服务。通过 Docker 完成服务镜像构建，使用 Kubernetes Deployment、Service、ConfigMap、Secret、健康检查和资源限制完成服务部署与治理，并设计 Harbor 私有镜像仓库、Ingress 统一入口、HPA 自动扩缩容、Prometheus/Grafana 监控和高可用集群部署方案。

## Tech Stack Keywords

Spring Boot, Docker, Kubernetes, containerd, Harbor, Ingress Nginx, ConfigMap, Secret, HPA, Prometheus, Grafana, Linux, Shell

## Key Talking Points

### What I Did (Landed)

- Designed microservice decomposition for bank e-commerce: auth, account, payment, notification + planned product/order/inventory
- Wrote Dockerfile with multi-stage builds for all services
- Wrote Kubernetes Deployment/Service manifests with probes, resource limits, and ConfigMap/Secret injection
- Configured Harbor private registry with bank-mall project
- Built end-to-end pipeline: code -> Docker image -> Harbor push -> K8s deploy -> service verification

### What I Enhanced (Progressive)

- Extracted config into ConfigMap (service URLs, log levels, timezone)
- Moved secrets into Kubernetes Secret (JWT key, DB credentials, Harbor credentials)
- Unified API responses across all 4 V1 services with `code/message/data/timestamp`
- Added a minimal Ingress smoke test to verify auth/account/payment/notification in one command
- Added livenessProbe and readinessProbe to all deployments
- Set resources requests/limits to prevent noisy-neighbor problems
- Used Spring Boot environment variable injection to avoid hardcoding config in images
- Employed Kubernetes recommended labels (app.kubernetes.io/part-of, app.kubernetes.io/component)

### What I Designed (Planned)

- Ingress unified entry point to replace NodePort
- HPA auto-scaling based on CPU/memory metrics
- Prometheus + Grafana monitoring stack for node, pod, and service metrics
- Log collection (EFK/Loki) and distributed tracing (OpenTelemetry + Jaeger/Tempo)
- NetworkPolicy for service-to-service isolation
- 3-master high availability cluster with HAProxy + Keepalived API Server VIP

## Common Interview Questions and Answers

### Q: Why split into microservices?

Traditional monolithic banking systems have tight coupling between auth, account, payment, and notification modules. A single module failure cascades to the entire system. Microservices allow independent deployment, scaling, and failure isolation. For example, notification-service can be scaled separately during high-volume notification periods without affecting payment processing.

### Q: Why use ConfigMap and Secret instead of embedding config in the image?

Images should be environment-agnostic. ConfigMap lets us change service URLs, log levels, and timezone without rebuilding images. Secret handles sensitive data (JWT keys, DB passwords) separately from the image, supporting different credentials per environment and preventing accidental exposure in image layers.

### Q: How do livenessProbe and readinessProbe work in your project?

- `livenessProbe`: Checks if the application is running. If it fails, Kubernetes restarts the pod. We use HTTP GET on `/api/<service>/health` with `initialDelaySeconds: 30` to give Spring Boot time to start.
- `readinessProbe`: Checks if the application is ready to serve traffic. If it fails, the pod is removed from Service endpoints but not restarted. We use `initialDelaySeconds: 10` with shorter check intervals.

This prevents traffic from reaching a pod that hasn't finished initializing, and ensures crashed pods are automatically restarted.

### Q: Why resources requests and limits?

- `requests`: Scheduler uses this to place pods on nodes with sufficient capacity. We set 100m CPU and 256Mi memory per service.
- `limits`: Hard ceiling to prevent a single pod from consuming all node resources. We set 500m CPU and 512Mi memory.
- Without limits, a misbehaving pod could starve other pods on the same node (noisy neighbor problem).

### Q: How do you handle service-to-service communication?

Services communicate via Kubernetes Service DNS (e.g., `http://auth-service:8081`). Service URLs are stored in ConfigMap and injected as environment variables via `envFrom`. This means we can change service routing without modifying any application code.

### Q: What did you harden in V1 before adding V2 services?

I deliberately stopped adding new mock services and hardened the V1 surface:

1. Unified response contract: `code/message/data/timestamp`
2. Standardized the health endpoints across all 4 services
3. Kept API paths and Ingress paths stable
4. Added `scripts/smoke-test.sh` to verify the Ingress path to auth/account/payment/notification

This makes the project easier to verify and explain. A Tech Lead cares less about adding another mock service and more about whether the current V1 can be deployed, tested, and trusted.

### Q: What's your image build strategy?

We use multi-stage Docker builds:
1. Stage 1 (builder): Maven compile inside `maven:3.9.6-eclipse-temurin-17-alpine`
2. Stage 2 (runtime): Copy JAR to `eclipse-temurin:17-alpine` (much smaller image)

This produces ~180MB images instead of ~500MB if we included the Maven build tools. Images are tagged with semantic versions (1.0.0) and pushed to Harbor private registry.

### Q: How would you design high availability?

Current setup: 1 master + 2 workers + 1 Harbor (single point of failure at master)

HA design:
- 3 control plane nodes with stacked etcd
- 2 load balancers (HAProxy + Keepalived) providing VIP for API Server
- etcd periodic backups with `etcdctl snapshot save`
- PodDisruptionBudget for critical services
- Anti-affinity rules to spread replicas across nodes

### Q: What happens when a pod crashes?

1. livenessProbe detects the failure
2. Kubernetes restarts the pod (recreate container)
3. If pod keeps crashing (CrashLoopBackOff), check `kubectl describe pod` and `kubectl logs`
4. If node fails, pods with `replicas` setting are rescheduled to healthy nodes

### Q: Interview Expression Boundaries

Recommended:
- "Based on a bank e-commerce business scenario, I built a Kubernetes cloud-native deployment practical environment"
- "Completed core service containerization, K8s deployment, config management, health checks, and designed production-grade improvement plans"

Avoid:
- "I was responsible for a bank production system deployment" (overstates reality)
- Better: "Simulated enterprise real scenarios" / "Learning/practical project" / "Completed core flow and designed production transformation plans"

### Q: Why does MySQL's NetworkPolicy only allow auth-service?

**Core Answer:**
V1 architecture decision: auth-service is the data gateway. Other services (account, payment, notification) access user data through auth-service's HTTP API, not directly to MySQL. This follows the "database-per-service" pattern — one MySQL instance, but logically separate databases.

**Why DB configs exist in ConfigMap:**
DB_NAME_ACCOUNT, DB_NAME_PAYMENT, DB_NAME_NOTIFICATION are reserved for V2. When product/order/inventory services are deployed, each will have its own schema. The egress NetworkPolicy already allows all services to reach MySQL, so V2 only needs an ingress rule update.

**Follow-up: "But egress allows all services to MySQL?"**
Yes, intentionally:
1. Designed with V2 in mind — egress rule is pre-positioned
2. Egress-only doesn't grant access — ingress rule on MySQL is the actual gatekeeper
3. Defense-in-depth: even if a pod tries, the ingress rule blocks it

**Follow-up: "How would you fix this for V2?"**
Change ingress from `matchLabels: app: auth-service` to `matchExpressions` with `operator: In` covering all service labels, or use common label `app.kubernetes.io/part-of: bank-mall`.

### Q: How does your alerting work?

**Core Answer:**
I use Grafana Unified Alerting (built into Grafana 10.x) as the alert evaluation engine. Alert rules are provisioned as code via ConfigMap and mounted into Grafana's `/etc/grafana/provisioning/alerting/` directory. Three rules are configured:

1. **Service Down** (critical): `up{job=~"bank-mall/.*"} == 0` for 1m
2. **High CPU** (warning): CPU rate > 80% for 5m
3. **High JVM Heap** (warning): heap usage > 85% for 5m

**Notification flow:** Grafana evaluates rules → fires alert → routes to contact point (webhook) → production would route to PagerDuty/Slack/email.

**Why Grafana Alerting over AlertManager?**
- Zero additional components (Grafana already deployed)
- Unified UI for dashboards + alerts
- Same PromQL query language
- Provisioning as code (GitOps friendly)

**Follow-up: "What about AlertManager?"**
AlertManager is the traditional Prometheus alerting stack. It excels at alert deduplication, grouping, and silencing across multiple Prometheus instances. For a single-cluster setup, Grafana Unified Alerting covers the same use cases with less operational overhead. In production with multiple Prometheus instances, I would deploy AlertManager for its HA gossip protocol and inhibition rules.

### Deep Dive: K8s Scheduling Framework

**One-line answer:** The Scheduling Framework is a plugin-based architecture that replaced the hard-coded predicates/priorities model in v1.19. It has extension points like Filter (feasibility), Score (ranking), and Reserve (resource booking).

**Expansion:** CycleState is a per-scheduling-cycle key-value store. A plugin in Reserve phase can write data to CycleState, and a later plugin in PreBind can read it. This decouples plugins without global state.

> Read: K8s Scheduling Framework docs, 20min. Key terms: QueueSort, PreFilter, Filter, PostFilter, PreScore, Score, Reserve, Permit, PreBind, Bind, PostBind.

### Deep Dive: Calico BGP vs IPIP

**One-line answer:** BGP is pure routing (no encapsulation, zero overhead, requires router support). IPIP wraps the original packet in an outer IP header (20 bytes overhead). Calico defaults to IPIP for cross-subnet, BGP for same-subnet.

**Expansion:** My small cluster uses IPIP (the default). The 20-byte overhead reduces MTU from 1500 to 1480, which is why Pod-to-Pod ping with `-M do -s 1452` is the correct MTU test value. In production on bare metal, switch to BGP for zero overhead.

> Read: Calico Networking docs, 15min. Key terms: IPIP tunnel, BGP peering, Felix, cross-subnet, MTU 1480.

### Deep Dive: Prometheus WAL

**One-line answer:** WAL (Write-Ahead Log) is Prometheus TSDB's durability mechanism. New samples are first written to WAL on disk, then held in memory. On crash, in-memory data is replayed from WAL — preventing data loss for the 2-hour window between block compactions.

**Expansion:** Every 2 hours, a WAL segment is cut and compressed into a TSDB block (chunks + index + meta.json). Blocks older than `--storage.tsdb.retention.time` are auto-deleted. This is the same design pattern as Loki's ingester WAL — if you understand one, you understand both.

> Read: Prometheus Storage docs, 15min. Key terms: WAL segment, head block, compaction, tombstone, mmap chunks.

### Deep Dive: Loki Compactor

**One-line answer:** The compactor merges and deduplicates index chunks from boltdb-shipper or TSDB to improve query performance. It does NOT deduplicate log lines — Loki is append-only storage.

**Expansion:** My cluster uses Loki 2.9.12 with boltdb-shipper + schema v11 (downgraded from 3.0). The compactor runs as a separate component in microservices mode, or as an internal routine in single-binary mode. Key config: `compaction_interval: 10m`.

> Read: Loki Architecture docs, 15min. Key terms: boltdb-shipper, TSDB index, compactor, retention_period, schema v11/v13.

### Deep Dive: CRI vs Docker Shim

**One-line answer:** CRI (Container Runtime Interface) is a gRPC API standard between kubelet and container runtimes. Docker originally needed `dockershim` to translate CRI calls to Docker API. dockershim was deprecated in K8s v1.20 and removed in v1.24. containerd implements CRI natively since v1.1 — no shim needed.

**Expansion:** This is why you can't use Docker as a K8s container runtime anymore — Kubelet speaks CRI, Docker speaks Docker API. containerd and CRI-O both speak CRI natively. My cluster uses containerd v2.2.1 with SystemdCgroup=true. The real-world impact: no `docker ps` on worker nodes, use `crictl ps` or `sudo ctr -n k8s.io containers ls` instead.

> Read: containerd CRI docs, 20min. Key terms: CRI gRPC, OCI runtime spec, runc, CRI-O, dockershim removal timeline (v1.20 deprecation, v1.24 removal).

### Deep Dive: Harbor Garbage Collection

**One-line answer:** Harbor image deletion is "soft delete" — it removes the manifest reference but does not free disk space. You must run GC to scan blobs, check if any manifest still references them, and physically delete unreferenced blobs.

**Expansion:** My cluster uses Harbor v2.10.3 which supports online GC (no downtime needed). The GC process: (1) mark phase — identify unreferenced blobs, (2) sweep phase — delete them from storage. Running GC requires Harbor to be in read-only mode in older versions, but v2.10+ supports online GC with a dry-run option.

> Read: Harbor GC docs, 10min. Key terms: soft delete, blob, manifest, mark-and-sweep, online GC, `registryctl`.
