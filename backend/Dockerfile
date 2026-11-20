# AI Meeting backend Dockerfile
# Multi-stage build for a lean runtime image.

ARG BUILDER_IMAGE=maven:3.9.11-eclipse-temurin-17
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy

FROM ${BUILDER_IMAGE} AS builder

WORKDIR /app

COPY pom.xml .
COPY admin/pom.xml admin/

RUN mvn dependency:go-offline -B

COPY . .

RUN mvn clean package -Dmaven.test.skip=true -B

FROM ${RUNTIME_IMAGE}

LABEL maintainer="ai-meeting-team"
LABEL description="AI Meeting Backend Service"
LABEL version="1.0.0"

RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r aimeeting && useradd -r -g aimeeting aimeeting

WORKDIR /app

RUN mkdir -p /app/logs /app/data && chown -R aimeeting:aimeeting /app

COPY --from=builder /app/admin/target/ai-meeting-admin-*.jar app.jar

RUN chown aimeeting:aimeeting app.jar

USER aimeeting

EXPOSE 8002

ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/"
ENV SPRING_PROFILES_ACTIVE=prod
ENV AI_MEETING_STORAGE_BASE_DIR=/app/data
ENV AI_MEETING_LOG_DIR=/app/logs

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8002/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
