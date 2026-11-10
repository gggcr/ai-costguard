# The web client: Vite build served by nginx, which also proxies /api to the core (no CORS, SSE unbuffered).
# Keycloak address is baked in at build time: --build-arg VITE_KEYCLOAK_URL=https://id.example.ru
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY src ./src
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ARG VITE_KEYCLOAK_REALM=costguard
ARG VITE_KEYCLOAK_CLIENT=costguard-frontend
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# CORE_URL: where the core listens, seen from this container
ENV CORE_URL=http://core:8080
# DNS server for CORE_URL: Docker's by default; in Kubernetes kube-dns, e.g. kube-dns.kube-system.svc.cluster.local
ENV NGINX_RESOLVER=127.0.0.11
# Only these two are substituted, nginx's own $variables stay as they are
ENV NGINX_ENVSUBST_FILTER=^(CORE_URL|NGINX_RESOLVER)$
EXPOSE 3000
