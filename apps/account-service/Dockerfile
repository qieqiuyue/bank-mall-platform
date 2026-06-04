FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY account-service/pom.xml account-service/
COPY account-service/settings.xml /root/.m2/settings.xml
COPY account-service/src account-service/src
RUN cd account-service && mvn clean package -DskipTests

FROM eclipse-temurin:21-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/account-service/target/*.jar app.jar
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8082/api/accounts/health || exit 1
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
