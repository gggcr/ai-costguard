# The web client: Vite build served by nginx, which also proxies /api to the core (no CORS, SSE unbuffered).
# Keycloak address is baked in at build time: --build-arg VITE_KEYCLOAK_URL=https://id.example.ru
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts csp-origins.mjs csp-origins.d.mts ./
COPY src ./src
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ARG VITE_KEYCLOAK_REALM=costguard
ARG VITE_KEYCLOAK_CLIENT=costguard-frontend
# empty: the API is this origin, proxied below; set only when the core answers on another origin
ARG VITE_API_URL=
RUN npm run build
# CSP connect-src from the same addresses the bundle was built with (security-headers.conf)
RUN node csp-origins.mjs > /app/csp-connect.conf

FROM nginxinc/nginx-unprivileged:1.31-alpine
# Alpine publishes fixes faster than nginx rebuilds its image: take them at build time (the security workflow fails
# on a fixable critical), then run as the image's unprivileged user again.
USER root
RUN apk upgrade --no-cache
USER 101
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY security-headers.conf /etc/nginx/security-headers.conf
COPY --from=build /app/csp-connect.conf /etc/nginx/conf.d/csp-connect.conf
COPY --from=build /app/dist /usr/share/nginx/html
# CORE_URL: where the core listens, seen from this container
ENV CORE_URL=http://core:8080
# DNS server for CORE_URL: Docker's by default; in Kubernetes kube-dns, e.g. kube-dns.kube-system.svc.cluster.local
ENV NGINX_RESOLVER=127.0.0.11
# Only these two are substituted, nginx's own $variables stay as they are
ENV NGINX_ENVSUBST_FILTER=^(CORE_URL|NGINX_RESOLVER)$
EXPOSE 3000
