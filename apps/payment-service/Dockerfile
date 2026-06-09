FROM maven:3.9-eclipse-temurin-21-alpine@sha256:<PIN_ON_NEXT_BUILD> AS builder
WORKDIR /app
COPY pom.xml ./
COPY payment-service/pom.xml payment-service/
COPY payment-service/settings.xml /root/.m2/settings.xml
COPY payment-service/src payment-service/src
RUN cd payment-service && mvn clean package -DskipTests

FROM eclipse-temurin:21-alpine@sha256:<PIN_ON_NEXT_BUILD>
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/payment-service/target/*.jar app.jar
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8083/actuator/health/liveness || exit 1
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
