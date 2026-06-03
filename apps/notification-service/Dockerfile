FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY settings.xml /root/.m2/settings.xml
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8084/api/notifications/health || exit 1
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
