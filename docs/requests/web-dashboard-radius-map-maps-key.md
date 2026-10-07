# Request to web-config/web-admin: the geofence radius map bypasses the Maps cost guard and ships the key

Found by the web-dashboard checker (N-047). `web/src/components/admin/config/radius-map.tsx` (lines ~17 and ~42) reads
`process.env.NEXT_PUBLIC_MAPS_WEB_KEY` (inlined into public client JS) and loads the Maps script directly, so its loads are
not counted against `MAPS_DAILY_CAP`, and the key sits in a static chunk anyone can fetch without signing in.

**Ask.** Load Maps through the shared `MapPanel` loader (`web/src/components/map-panel.tsx`) or POST `/api/bff/maps/load`
for permission and key (the BFF counts the load, refuses above the cap, needs a session). Then the dashboard lane removes the
`NEXT_PUBLIC_MAPS_WEB_KEY` fallback from `web/src/lib/maps/guard.ts` and un-skips
`tests/checker-t1-maps.test.ts` ("every Maps script load goes through the capped BFF").
