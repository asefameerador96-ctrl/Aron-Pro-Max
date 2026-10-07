# android-core to backend-core: config delta must carry outlet radius changes (F-SYS-053)

Opus checker on the phone side of F-SYS-053 (2026-10-07), CONFIRMED against `backend/.../ConfigDelta.kt`:

1. **Radius.** The phone's visit radius (`geo_radius_m_used`) comes from `outlet.radius_m`, which the bundle resolves per
   outlet (`BundleService.kt` ~330-372). `GET /v1/config/delta` never sends `outlet_radius_changes` (contract `ConfigDelta`
   has the member), so a portal radius change reaches the phone's config table but not its outlets until the next full
   bundle. Ask: when a change touches `cfg.geo.radius_m` or `cfg.geo.max_accuracy_m`, re-resolve them for the outlets of
   the caller's routes and send the changed ones in `outlet_radius_changes`. The phone already applies them in the delta's
   transaction, and now acknowledges a radius key only when that list is present (so the reach view never counts a phone
   that does not use the new radius).
2. **Fresh server version without records.** `X-Config-Version` reaches the phone on batch answers only when the outbox has
   rows; with an empty outbox the resume check (5-min gap) and FCM `config_pull` cover it. No change asked; noted.
3. **304.** The phone now treats `304` with `X-Config-Version: V` as "this phone holds V" (it moves its held version), so
   please keep sending `X-Config-Version` on the 304 of `/v1/config/delta`.
