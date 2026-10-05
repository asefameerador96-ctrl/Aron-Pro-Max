# syntax=docker/dockerfile:1.7
# Aron web image: Next.js standalone server (BFF with the HttpOnly refresh cookie needs a server runtime, so this
# runs on Container Apps, not static hosting; docs/24 s6.5). Build context = web/.
#   docker build -f infra/docker/web.Dockerfile --build-arg NEXT_PUBLIC_MAPS_WEB_KEY=... web
# Requires `output: 'standalone'` in web/next.config.* (docs/requests/infra-web-standalone.md).

ARG NODE_IMAGE=node:22-bookworm-slim@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c

FROM ${NODE_IMAGE} AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN mkdir -p public
# Referrer-restricted browser key, public by design (docs/24 s13.6); empty = maps show no tiles.
ARG NEXT_PUBLIC_MAPS_WEB_KEY=""
ENV NEXT_PUBLIC_MAPS_WEB_KEY=${NEXT_PUBLIC_MAPS_WEB_KEY} NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM ${NODE_IMAGE}
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=node:node /src/.next/standalone ./
COPY --from=build --chown=node:node /src/.next/static ./.next/static
COPY --from=build --chown=node:node /src/public ./public
USER node
EXPOSE 3000
CMD ["node", "server.js"]
