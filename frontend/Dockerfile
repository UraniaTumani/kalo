# Frontend image: builds the static bundle, then serves it from nginx and
# forwards /api to the backend.
#
# That proxy is what makes the app same-origin in production, which is the
# topology the client assumes — it calls /api/... with no host. A split-origin
# deployment instead needs VITE_API_URL at build time and CORS_ALLOWED_ORIGINS
# on the backend.

# ---------------------------------------------------------------- build stage
FROM node:22-alpine AS build

WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Baked in at build time, not read at runtime: Vite inlines these into the
# bundle. Empty means same-origin, which is the default topology.
ARG VITE_API_URL=""
ENV VITE_API_URL=$VITE_API_URL

RUN npm run build

# ---------------------------------------------------------------- run stage
FROM nginx:1.27-alpine

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# 127.0.0.1, not localhost: nginx's `listen 80` binds IPv4 only, while inside
# the container localhost resolves to ::1 first — so this probe answered
# "connection refused" against a server that was serving the site perfectly
# well. Harmless until something waits on the health status, at which point
# the container never becomes ready.
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
