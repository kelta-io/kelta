# Base-image registry is a build arg. The DEFAULT is the homelab pull-through cache
# that CI and production builds use (LAN speed, and no Docker Hub rate limits when
# several images build in parallel) — builds that pass no args behave exactly as
# before. Outside the homelab pass the public source:
#   --build-arg BASE_REGISTRY=docker.io/library
ARG BASE_REGISTRY=harbor.rzware.com/dockerhub-cache/library

FROM ${BASE_REGISTRY}/alpine:3.19
RUN apk add --no-cache bash curl jq
WORKDIR /scripts
COPY seed.sh .
RUN chmod +x seed.sh
CMD ["./seed.sh"]
