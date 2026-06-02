# Microservice Development Skill

Develop, build, and maintain bank-mall Spring Boot microservices.

## Service Overview

| Service | Package | Port | K8s Namespace | Health Endpoint |
|---------|---------|------|---------------|-----------------|
| auth-service | com.bank.auth | 8081 | bank-mall | /api/auth/health |
| account-service | com.bank.account | 8082 | bank-mall | /api/accounts/health |
| payment-service | com.bank.payment | 8083 | bank-mall | /api/payments/health |
| notification-service | com.bank.notification | 8084 | bank-mall | /api/notifications/health |

## Project Structure Convention

Each service follows this layout:

```
<service-name>/
├── pom.xml                    # Spring Boot 3.1.3 + Java 17
├── settings.xml               # Maven mirror config
├── Dockerfile                 # Multi-stage build (maven -> temurin)
├── k8s/                       # Legacy per-service K8s manifests (NodePort)
│   ├── deployment.yaml
│   └── service.yaml
└── src/main/
    ├── java/com/bank/<service>/
    │   ├── <Application>.java          # Spring Boot main class
    │   └── controller/
    │       └── <Service>Controller.java # REST controller
    └── resources/
        └── application.yml             # Config with env var injection
```

## Application.yml Convention

All services support environment variable injection via Spring Boot's `${ENV_VAR:default}` syntax:

```yaml
server:
  port: ${SERVER_PORT:8081}

spring:
  application:
    name: ${SPRING_APPLICATION_NAME:auth-service}
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:default}

logging:
  level:
    root: ${LOG_LEVEL:INFO}
    com.bank: DEBUG
```

Services with inter-service dependencies also include:

```yaml
bank:
  services:
    auth-url: ${AUTH_SERVICE_URL:http://auth-service:8081}
    notification-url: ${NOTIFICATION_SERVICE_URL:http://notification-service:8084}
```

These env vars are injected by K8s ConfigMap `bank-mall-config`.

## API Design Convention

- Base path: `/api/<service-name>` (e.g., `/api/auth`, `/api/payments`)
- Health endpoint: `/api/<service-name>/health` returning plain text `"<service-name> ok"`
- All response bodies are `Map<String, Object>` (demo stage, no DTO classes)
- Mock data only - no database backend (V1 status)

### Current API Catalog

**auth-service** (`/api/auth`):
- `POST /login` - Login with mock credentials (admin/123456)
- `POST /validate` - Validate Bearer token
- `GET /users/{userId}` - Get user profile
- `GET /health` - Health check

**account-service** (`/api/accounts`):
- `GET /{accountId}` - Get account info
- `GET /{accountId}/balance` - Get balance
- `GET /{accountId}/transactions` - Get transaction history
- `GET /balance/{id}` - Legacy balance endpoint
- `GET /health` - Health check

**payment-service** (`/api/payments`):
- `POST /` - Create payment
- `GET /{paymentId}` - Query payment
- `POST /transfer` - Transfer (legacy)
- `GET /health` - Health check

**notification-service** (`/api/notifications`):
- `POST /` - Send notification
- `GET /templates` - List notification templates
- `GET /notify?msg=...` - Legacy notify endpoint
- `GET /health` - Health check

## Dockerfile Convention

All services use multi-stage builds:

```dockerfile
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY settings.xml /root/.m2/settings.xml
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE <PORT>
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build command:
```bash
cd ~/bank-mall-cloudnative
REGISTRY=10.0.0.62 NAMESPACE=bank-mall VERSION=1.0.0 PUSH=true bash scripts/build-images.sh
```

## Adding a New Service (V2 Example)

1. Create `bank-digital-platform/<service-name>/` with the standard structure
2. Create `pom.xml` with `groupId: com.bank`, `artifactId: <service-name>`, `spring.boot.version: 3.1.3`
3. Create main class `<ServiceName>Application.java`
4. Create controller with standard path convention `/api/<service-name>/`
5. Add health endpoint `/api/<service-name>/health`
6. Create `application.yml` with env var injection
7. Create `Dockerfile` with multi-stage build
8. Create `k8s/base/<service-name>/deployment.yaml` and `service.yaml`
9. Add service URL to `k8s/base/configmap.yaml`
10. Add service to `scripts/build-images.sh` SERVICES array
11. Add deploy/teardown steps to scripts

## V2 Planned Services

| Service | Responsibility | Port |
|---------|---------------|------|
| product-service | Product list, product details, product status | 8085 |
| order-service | Order creation, order query, order status | 8086 |
| inventory-service | Stock query, stock deduction | 8087 |
| gateway-service | Unified entry point, routing, basic auth | 8080 |

Business flow: Login -> Browse Products -> Add to Cart -> Place Order -> Pay -> Deduct Inventory -> Notify