# Multi-stage native image build for Kelta Auth Server
# Builder: GraalVM CE 25 + Maven  |  Runtime: debian:12-slim (no JRE needed)

ARG MAVEN_VERSION=3.9.9
ARG GRAALVM_IMAGE=ghcr.io/graalvm/native-image-community:25-ol9

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
FROM ${GRAALVM_IMAGE} AS runtime-builder

ARG MAVEN_VERSION
ARG MAVEN_MIRROR=nexus

WORKDIR /kelta-platform

RUN microdnf install -y findutils && \
    if [ "$MAVEN_MIRROR" = "nexus" ]; then MAVEN_REPO=https://nexus.rzware.com/repository/maven-public; \
    else MAVEN_REPO=https://repo.maven.apache.org/maven2; fi && \
    curl -fsSL --retry 8 --retry-delay 5 --retry-connrefused --retry-all-errors \
      "${MAVEN_REPO}/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" | \
    tar xzf - -C /opt && \
    ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

# Route Maven Central through Google's GCS mirror to dodge 429 rate limits.
COPY ci/maven-settings.xml /tmp/maven-settings.xml
RUN mkdir -p /root/.m2 && \
    if [ "$MAVEN_MIRROR" = "nexus" ]; then cp /tmp/maven-settings.xml /root/.m2/settings.xml; fi

# Belt-and-braces: retry on transient 429 / 503 / connection resets in case the
# mirror is unreachable and Maven falls back to central.
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=10 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true -Daether.connector.http.retryHandler.count=10"

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

RUN mvn dependency:go-offline -B \
    -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-messaging-nats,runtime/runtime-jsonapi,runtime/runtime-module-core,runtime/runtime-module-integration,runtime/runtime-module-schema \
    -am || true

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
# Stage 2: Compile native image
# =============================================================================
FROM ${GRAALVM_IMAGE} AS builder

ARG MAVEN_VERSION
ARG MAVEN_MIRROR=nexus

WORKDIR /build

RUN microdnf install -y findutils && \
    if [ "$MAVEN_MIRROR" = "nexus" ]; then MAVEN_REPO=https://nexus.rzware.com/repository/maven-public; \
    else MAVEN_REPO=https://repo.maven.apache.org/maven2; fi && \
    curl -fsSL --retry 8 --retry-delay 5 --retry-connrefused --retry-all-errors \
      "${MAVEN_REPO}/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" | \
    tar xzf - -C /opt && \
    ln -sf /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

# Route Maven Central through Google's GCS mirror to dodge 429 rate limits.
COPY ci/maven-settings.xml /tmp/maven-settings.xml
RUN mkdir -p /root/.m2 && \
    if [ "$MAVEN_MIRROR" = "nexus" ]; then cp /tmp/maven-settings.xml /root/.m2/settings.xml; fi

# Belt-and-braces: retry on transient 429 / 503 / connection resets in case the
# mirror is unreachable and Maven falls back to central.
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=10 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true -Daether.connector.http.retryHandler.count=10"

COPY --from=runtime-builder /root/.m2/repository /root/.m2/repository

COPY kelta-auth/pom.xml .
RUN mvn dependency:go-offline -B -Pnative || true

COPY kelta-auth/src ./src
RUN mvn -Pnative native:compile -DskipTests -B

# =============================================================================
# Stage 3: Minimal runtime (no JRE — binary is self-contained)
# =============================================================================
FROM ${BASE_REGISTRY}/debian:12-slim

WORKDIR /app

RUN groupadd -r kelta && useradd -r -g kelta kelta && \
    apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/target/kelta-auth .

RUN chown kelta:kelta kelta-auth

USER kelta

EXPOSE 8080

# Native startup is ~50ms — start-period can be much tighter than JVM
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# OTEL is configured via OTEL_EXPORTER_OTLP_ENDPOINT / OTEL_SERVICE_NAME env vars;
# Spring Boot's native opentelemetry starter exports OTLP directly — no Java agent needed.
ENTRYPOINT ["./kelta-auth"]
