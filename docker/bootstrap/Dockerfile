# Default is the homelab Docker Hub cache (CI); local compose passes docker.io/library.
ARG BASE_REGISTRY=harbor.rzware.com/dockerhub-cache/library
FROM ${BASE_REGISTRY}/alpine:3.19
RUN apk add --no-cache bash curl jq
WORKDIR /scripts
COPY seed.sh .
RUN chmod +x seed.sh
CMD ["./seed.sh"]
