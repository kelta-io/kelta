# Custom GitHub Actions Runner with JDK 21, Maven, and Node.js pre-installed.
#
# Eliminates per-job downloads of:
#   - JDK 21 (~190 MB via actions/setup-java)
#   - Maven 3.9.9 (~10 MB via curl)
#   - Node.js 24 (~30 MB via actions/setup-node)
#
# Build:
#   docker build -t ghcr.io/cklinker/kelta-runner:latest .github/runner/
#
# Use in your runner controller (e.g. actions-runner-controller):
#   spec:
#     containers:
#       - name: runner
#         image: ghcr.io/cklinker/kelta-runner:latest
#
# If using this image, you can remove the "Set up JDK", "Install Maven",
# and "Set up Node.js" steps from your workflows — they'll be no-ops or
# already satisfied.

FROM ghcr.io/actions/actions-runner:latest

USER root

# ---- JDK 21 (Temurin) ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends wget apt-transport-https gnupg && \
    mkdir -p /etc/apt/keyrings && \
    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends temurin-21-jdk && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
ENV PATH="$JAVA_HOME/bin:$PATH"

# ---- Maven 3.9.9 ----
ARG MAVEN_VERSION=3.9.9
RUN curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz | tar xz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

# ---- Node.js 24 + npm ----
ARG NODE_VERSION=24
RUN curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# ---- Docker CLI (for buildx / docker build-push-action) ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends docker.io && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# ---- Helpful tools ----
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl jq git && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Pre-create cache directories with correct ownership
RUN mkdir -p /home/runner/.m2/repository /home/runner/.npm && \
    chown -R runner:runner /home/runner/.m2 /home/runner/.npm

USER runner

# Verify installations
RUN java -version && mvn --version && node --version && npm --version
