FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY common-lib/pom.xml common-lib/
COPY common-lib/src common-lib/src
COPY notification-service/pom.xml notification-service/
COPY notification-service/settings.xml /root/.m2/settings.xml
COPY notification-service/src notification-service/src
RUN mvn install -pl common-lib -am -DskipTests -q && cd notification-service && mvn clean package -DskipTests -q

FROM eclipse-temurin:21-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/notification-service/target/*.jar app.jar
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8084/actuator/health/liveness || exit 1
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
