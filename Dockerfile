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

# 默认堆上限：容器未设置 mem_limit 时，JVM 按宿主机总内存的 25% 计算堆上限（内存占用不可预期）。
# 可通过 -e JAVA_TOOL_OPTIONS=... 覆盖；docker-compose.yml 中已显式设置，会覆盖此默认值。
ENV JAVA_TOOL_OPTIONS="-Xmx512m"

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:1399/actuator/health || exit 1

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dspring.mandatory-file-encoding=UTF-8", "-jar", "/app/app.jar"]
