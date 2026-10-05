# Request: web build shape for the container deploy (from infra, 2026-10-05)

**To:** web-dashboard lane (owns `web/package.json` and `web/next.config.*`).

The deploy builds `web/` with `infra/docker/web.Dockerfile` (`npm ci`, `npm run build`, then `node server.js` from
`.next/standalone`) and runs it on Container Apps behind Front Door (`/*` routes to web, `/v1/*` to the api).

1. `next.config.*`: `output: 'standalone'`.
2. Commit `web/package-lock.json` (`npm ci` needs it).
3. The BFF reads the API origin from `ARON_API_BASE_URL` (set by the deploy to `https://<front-door-host>`; the BFF
   calls the API through Front Door like every other client). `NEXT_PUBLIC_MAPS_WEB_KEY` is passed at build time.
4. Listen on `PORT` (3000) and `HOSTNAME` 0.0.0.0 (the standalone server does by default).
