# Multi-stage build for Kelta AI Service

# Base-image registry and Maven mirror are build args. The DEFAULTS are the homelab
# pull-through caches that CI and production builds use (LAN speed, and no Docker Hub /
# Maven Central rate limits when several images build in parallel) — builds that pass no
# args behave exactly as before. Local compose builds override both to public sources
# (docker-compose.yml; docker-compose.ci.yml resets them), so an outside contributor's
# build neither depends on the homelab being reachable nor downloads through it over the WAN.
ARG BASE_REGISTRY=harbor.rzware.com/dockerhub-cache/library

# =============================================================================
# Stage 1: Build runtime-platform dependencies
# =============================================================================
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-25 AS runtime-builder

# Survive transient Maven Central 429 / 503 / connection resets — both the
# legacy wagon HTTP transport and the newer Aether connector retry up to 10
# times with exponential backoff and honour Retry-After headers.
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=10 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true -Daether.connector.http.retryHandler.count=10"

WORKDIR /kelta-platform

# Route Maven Central through Google's GCS mirror to dodge 429 rate limits.
ARG MAVEN_MIRROR=nexus
COPY ci/maven-settings.xml /tmp/maven-settings.xml
RUN mkdir -p /root/.m2 && \
    if [ "$MAVEN_MIRROR" = "nexus" ]; then cp /tmp/maven-settings.xml /root/.m2/settings.xml; fi

# Copy only POMs first for dependency layer caching
COPY kelta-platform/pom.xml ./
COPY kelta-platform/runtime/pom.xml ./runtime/
COPY kelta-platform/runtime/runtime-core/pom.xml ./runtime/runtime-core/
COPY kelta-platform/runtime/runtime-events/pom.xml ./runtime/runtime-events/
COPY kelta-platform/runtime/runtime-jsonapi/pom.xml ./runtime/runtime-jsonapi/
COPY kelta-platform/runtime/runtime-module-core/pom.xml ./runtime/runtime-module-core/
COPY kelta-platform/runtime/runtime-module-integration/pom.xml ./runtime/runtime-module-integration/
COPY kelta-platform/runtime/runtime-messaging-nats/pom.xml ./runtime/runtime-messaging-nats/
COPY kelta-platform/runtime/runtime-module-schema/pom.xml ./runtime/runtime-module-schema/

# Download runtime dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B \
    -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-messaging-nats,runtime/runtime-jsonapi,runtime/runtime-module-core,runtime/runtime-module-integration,runtime/runtime-module-schema \
    -am || true

# Copy source and build runtime modules
COPY kelta-platform/runtime/runtime-core/src ./runtime/runtime-core/src
COPY kelta-platform/runtime/runtime-events/src ./runtime/runtime-events/src
COPY kelta-platform/runtime/runtime-messaging-nats/src ./runtime/runtime-messaging-nats/src
COPY kelta-platform/runtime/runtime-jsonapi/src ./runtime/runtime-jsonapi/src
COPY kelta-platform/runtime/runtime-module-core/src ./runtime/runtime-module-core/src
COPY kelta-platform/runtime/runtime-module-integration/src ./runtime/runtime-module-integration/src
COPY kelta-platform/runtime/runtime-module-schema/src ./runtime/runtime-module-schema/src

RUN mvn clean install -DskipTests -B \
    -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-messaging-nats,runtime/runtime-jsonapi,runtime/runtime-module-core,runtime/runtime-module-integration,runtime/runtime-module-schema \
    -am

# =============================================================================
# Stage 2: Build the application
# =============================================================================
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-25 AS builder

ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=10 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true -Daether.connector.http.retryHandler.count=10"

WORKDIR /build

# Route Maven Central through Google's GCS mirror to dodge 429 rate limits.
ARG MAVEN_MIRROR=nexus
COPY ci/maven-settings.xml /tmp/maven-settings.xml
RUN mkdir -p /root/.m2 && \
    if [ "$MAVEN_MIRROR" = "nexus" ]; then cp /tmp/maven-settings.xml /root/.m2/settings.xml; fi

# Copy runtime artifacts from previous stage
COPY --from=runtime-builder /root/.m2/repository/io/kelta /root/.m2/repository/io/kelta

# Copy POM first for dependency layer caching
COPY kelta-ai/pom.xml .

# Download dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B || true

# Copy source and build
COPY kelta-ai/src ./src
RUN mvn clean package -DskipTests -B && \
    mv target/kelta-ai-*.jar target/kelta-ai.jar

# =============================================================================
# Stage 3: Runtime image
# =============================================================================
FROM ${BASE_REGISTRY}/eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd -r kelta && useradd -r -g kelta kelta

COPY --from=builder /build/target/kelta-ai.jar app.jar

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    curl -fL -o opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.25.0/opentelemetry-javaagent.jar && \
    test -s opentelemetry-javaagent.jar

RUN chown -R kelta:kelta /app

USER kelta

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -javaagent:/app/opentelemetry-javaagent.jar"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
