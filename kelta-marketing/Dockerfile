# Build context is the REPOSITORY ROOT (like every other image): the docs engine
# reads docs/authoring/*.md, the generated CLI docs and README.md from outside this module.
# Dockerfile.dockerignore next to this file keeps the context to those paths.
FROM harbor.rzware.com/dockerhub-cache/library/node:22-alpine AS build
WORKDIR /app/kelta-marketing
COPY kelta-marketing/package.json kelta-marketing/package-lock.json ./
RUN npm ci
# Read in place by src/content.config.ts (base: '../docs/authoring', '../kelta-web/packages/cli').
COPY docs/authoring /app/docs/authoring
COPY kelta-web/packages/cli/COMMANDS.md kelta-web/packages/cli/AGENTS.md /app/kelta-web/packages/cli/
# README.md's Quickstart is spliced into the docs quickstart page (astro.config.mjs).
COPY README.md /app/README.md
COPY kelta-marketing/ ./
# Kelta collection URL the /waitlist form POSTs to (e.g. https://<tenant-host>/api/<collection>).
# Empty (the default) builds the page in its "not open yet" state with no form.
ARG PUBLIC_WAITLIST_ENDPOINT=
ENV PUBLIC_WAITLIST_ENDPOINT=$PUBLIC_WAITLIST_ENDPOINT
# postbuild runs `pagefind --site dist` (musl-static linux binary, fine on alpine).
RUN npm run build

# Production stage — serve static files with nginx
FROM harbor.rzware.com/dockerhub-cache/library/nginx:1.27-alpine
COPY --from=build /app/kelta-marketing/dist /usr/share/nginx/html
COPY kelta-marketing/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
