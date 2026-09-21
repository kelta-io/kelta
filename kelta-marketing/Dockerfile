# Build context is the REPOSITORY ROOT (like every other image): the docs engine
# reads docs/authoring/*.md and the generated CLI docs from outside this module.
# Dockerfile.dockerignore next to this file keeps the context to those paths.
FROM harbor.rzware.com/dockerhub-cache/library/node:22-alpine AS build
WORKDIR /app/kelta-marketing
COPY kelta-marketing/package.json kelta-marketing/package-lock.json ./
RUN npm ci
# Read in place by src/content.config.ts (base: '../docs/authoring', '../kelta-web/packages/cli').
COPY docs/authoring /app/docs/authoring
COPY kelta-web/packages/cli/COMMANDS.md kelta-web/packages/cli/AGENTS.md /app/kelta-web/packages/cli/
COPY kelta-marketing/ ./
# postbuild runs `pagefind --site dist` (musl-static linux binary, fine on alpine).
RUN npm run build

# Production stage — serve static files with nginx
FROM harbor.rzware.com/dockerhub-cache/library/nginx:1.27-alpine
COPY --from=build /app/kelta-marketing/dist /usr/share/nginx/html
COPY kelta-marketing/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
