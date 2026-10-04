# 05 — Geo-validation and Anti-spoofing

The rule, which must run offline: a sale is **geo-valid** only if the phone's GPS fix is within the territory's radius of the outlet's stored coordinates.

## The check

- **Distance:** Haversine between the current fix and `outlet.latitude/longitude`, compared to `territory_geo_config.radius_m`. Radius is per-territory because dense urban beats need a tight radius and rural ones a loose one.
- **Offline:** outlet coordinates and the radius come down in the login bundle, so the check is pure on-device maths; it never needs the network.
- **On every visit**, store the raw fix on the `visit` row: `lat`, `lng`, `gps_accuracy_m`, `mock_location`, and the computed `geo_validated`.

## Out of range or no fix → Force Sale

- The SR picks a reason (`internet_problem` or `location_change`) and takes an outlet photo.
- The visit is marked `photo_validated = true`, `geo_validated = false`. Keep the two flags separate everywhere — a force sale must never count as a clean geo sale in any KPI.
- The outlet photo updates the outlet's location (a correction path for moved/mislocated shops), routed through the normal outlet-change/verification flow so it can't be abused silently.

## Server re-check (source of truth)

On sync, the server recomputes distance from the stored fix and the outlet location and sets the authoritative `geo_validated`. The phone's flag is a convenience; the server's recomputation is the record. Mismatches (device said valid, server says not) are flagged.

## Anti-spoofing (first-class requirement)

Reps already defeat the current geofence with fake-GPS apps. Assume the fix is hostile.

- **Mock-location flag:** read Android's mock-location signal (`geolocator` exposes `isMocked`) and store `mock_location` on every fix. A mocked fix can never be `geo_validated`.
- **Plausibility checks (server-side):** implausible speed between consecutive fixes (teleport), fixes with impossibly perfect accuracy, zero jitter across a whole route, or every outlet on a route hit from one coordinate → flag the visit/route.
- **Integrity signals:** optionally record developer-options/rooted hints and a Play Integrity verdict at login (don't hard-block solely on these; weight them).
- **Report, don't just block.** Surface a "suspicious location" count to the AMO/TSO and in the dashboard. A silent hard block teaches reps to find the next workaround; a visible flag that supervisors act on changes behaviour. (Keep a configurable hard-block for mock locations if the business wants it.)
- **Audit trail:** because raw fixes are stored on every visit, spoofing shows up as a pattern over time even when a single fix looks clean.

## Battery note

Geo-validation uses a **single on-demand fused fix** at outlet open (and attendance/force-sale/capture) — never a continuous stream. See `docs/04`. Accuracy/power: request balanced/high accuracy for the single reading with a short timeout, then release.

## The geo-triggered volume suggestion

When a geo-valid visit opens, the current app suggests an order quantity for that outlet. The exact inputs/formula are not yet confirmed (listed in `docs/13`). Design the hook now: a per-outlet suggested-quantity field delivered in the bundle (computed server-side from history), shown at order entry. Reconstruct the formula from the migrated history or confirm with the business before relying on it.
