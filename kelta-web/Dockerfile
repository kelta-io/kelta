# Kelta Web UI Dockerfile
# Multi-stage build: Node.js build -> Nginx runtime

# Base-image registry is a build arg. The DEFAULT is the homelab pull-through cache
# that CI and production builds use (LAN speed, and no Docker Hub rate limits when
# several images build in parallel) — builds that pass no args behave exactly as
# before. Outside the homelab pass the public source:
#   --build-arg BASE_REGISTRY=docker.io/library
ARG BASE_REGISTRY=harbor.rzware.com/dockerhub-cache/library

# =============================================================================
# Stage 1: Install dependencies and build
# =============================================================================
FROM ${BASE_REGISTRY}/node:24.22-alpine3.24 AS builder

WORKDIR /app

# Copy package files for dependency caching
COPY kelta-web/package.json kelta-web/package-lock.json* kelta-web/tsconfig.json ./
COPY kelta-web/packages/sdk/package.json ./packages/sdk/
COPY kelta-web/packages/components/package.json ./packages/components/
COPY kelta-web/packages/plugin-sdk/package.json ./packages/plugin-sdk/

# Install dependencies
RUN npm install

# Copy source code
COPY kelta-web/packages ./packages

# Build all packages
RUN npm run build

# =============================================================================
# Stage 2: Runtime with Nginx
# =============================================================================
FROM ${BASE_REGISTRY}/nginx:1.25-alpine AS runtime

LABEL org.opencontainers.image.title="Kelta Web UI"
LABEL org.opencontainers.image.description="Admin UI for Kelta Platform"
LABEL org.opencontainers.image.vendor="Kelta"
LABEL org.opencontainers.image.version="1.0.0-SNAPSHOT"

# Remove default nginx config
RUN rm /etc/nginx/conf.d/default.conf

# Add custom nginx config
COPY kelta-web/nginx.conf /etc/nginx/conf.d/default.conf

# Copy built assets from builder stage
# Note: Update the source path when an actual app build output directory exists
COPY --from=builder /app/packages/components/dist /usr/share/nginx/html

# Create non-root user
RUN addgroup -g 1000 -S kelta && \
    adduser -u 1000 -S kelta -G kelta && \
    chown -R kelta:kelta /usr/share/nginx/html && \
    chown -R kelta:kelta /var/cache/nginx && \
    chown -R kelta:kelta /var/log/nginx && \
    touch /var/run/nginx.pid && \
    chown -R kelta:kelta /var/run/nginx.pid

USER kelta

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

CMD ["nginx", "-g", "daemon off;"]
