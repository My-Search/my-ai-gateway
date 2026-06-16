# syntax=docker/dockerfile:1.7

# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /workspace

COPY pom.xml ./
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN mkdir -p /app/data

COPY --from=builder /workspace/target/my-ai-gateway.jar /app/app.jar

EXPOSE 1399

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:1399/actuator/health || exit 1

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dspring.mandatory-file-encoding=UTF-8", "-jar", "/app/app.jar"]
