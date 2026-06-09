FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY common-lib/pom.xml common-lib/
COPY common-lib/src common-lib/src
COPY auth-service/pom.xml auth-service/
COPY auth-service/settings.xml /root/.m2/settings.xml
COPY auth-service/src auth-service/src
RUN mvn install -N -DskipTests -q && mvn install -pl common-lib -DskipTests -q && cd auth-service && mvn clean package -DskipTests -q

FROM eclipse-temurin:21-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/auth-service/target/*.jar app.jar
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/liveness || exit 1
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
