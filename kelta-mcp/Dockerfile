# Multi-stage build for Kelta MCP Server

# =============================================================================
# Stage 1: Build runtime-platform dependencies
#
# kelta-mcp only needs runtime-core at runtime, but runtime-core has compile
# dependencies on runtime-events and runtime-jsonapi (e.g. JsonApiError,
# AtomicResult), so all three must be built. We mirror kelta-ai's module
# list for cache-key compatibility — Maven's -am handles the dep graph.
# =============================================================================
FROM harbor.rzware.com/dockerhub-cache/library/maven:3.9-eclipse-temurin-25 AS runtime-builder

# Survive transient Maven Central 429 / 503 / connection resets — both the
# legacy wagon HTTP transport and the newer Aether connector retry up to 10
# times with exponential backoff and honour Retry-After headers.
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=10 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true -Daether.connector.http.retryHandler.count=10"

WORKDIR /kelta-platform

# Route Maven Central through Google's GCS mirror to dodge 429 rate limits.
COPY ci/maven-settings.xml /root/.m2/settings.xml

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
    -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-jsonapi \
    -am || true

# Copy source for runtime-core and its compile-time prerequisites
COPY kelta-platform/runtime/runtime-core/src ./runtime/runtime-core/src
COPY kelta-platform/runtime/runtime-events/src ./runtime/runtime-events/src
COPY kelta-platform/runtime/runtime-jsonapi/src ./runtime/runtime-jsonapi/src

RUN mvn clean install -DskipTests -B \
    -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-jsonapi \
    -am

# =============================================================================
# Stage 2: Build the application
# =============================================================================
FROM harbor.rzware.com/dockerhub-cache/library/maven:3.9-eclipse-temurin-25 AS builder

ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=10 -Dmaven.wagon.http.retryHandler.requestSentEnabled=true -Daether.connector.http.retryHandler.count=10"

WORKDIR /build

# Route Maven Central through Google's GCS mirror to dodge 429 rate limits.
COPY ci/maven-settings.xml /root/.m2/settings.xml

# Copy runtime artifacts from previous stage
COPY --from=runtime-builder /root/.m2/repository/io/kelta /root/.m2/repository/io/kelta

# Copy POM first for dependency layer caching
COPY kelta-mcp/pom.xml .

# Download dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B || true

# Copy source and build
COPY kelta-mcp/src ./src
RUN mvn clean package -DskipTests -B && \
    mv target/kelta-mcp-*.jar target/kelta-mcp.jar

# Fetch the OpenTelemetry Java agent here — this stage already has curl and
# network egress from the Maven build, and far more disk headroom than the
# minimal JRE runtime stage.
RUN curl -fL -o opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.25.0/opentelemetry-javaagent.jar && \
    test -s opentelemetry-javaagent.jar

# =============================================================================
# Stage 3: Runtime image
# =============================================================================
FROM harbor.rzware.com/dockerhub-cache/library/eclipse-temurin:25-jre

WORKDIR /app

RUN groupadd -r kelta && useradd -r -g kelta kelta

COPY --from=builder /build/target/kelta-mcp.jar app.jar
COPY --from=builder /build/opentelemetry-javaagent.jar opentelemetry-javaagent.jar

RUN chown -R kelta:kelta /app

USER kelta

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -javaagent:/app/opentelemetry-javaagent.jar"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
