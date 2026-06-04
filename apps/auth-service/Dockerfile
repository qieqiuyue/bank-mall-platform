FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
# Parent POM — must be at ../ relative to service pom.xml for Maven resolution
COPY pom.xml ./
# Service files
COPY auth-service/pom.xml auth-service/
COPY auth-service/settings.xml /root/.m2/settings.xml
COPY auth-service/src auth-service/src
RUN cd auth-service && mvn clean package -DskipTests

FROM eclipse-temurin:21-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/auth-service/target/*.jar app.jar
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8081/api/auth/health || exit 1
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
