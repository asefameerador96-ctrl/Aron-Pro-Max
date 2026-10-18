# Request (web-dashboard -> backend): validated-visit count on the geo-validation summary

Filed 2026-10-18 by web-dashboard, routed by the lead. Small additive field; no migration (the column exists).

## What
Add to `GeoValidationSummary` (`GET /v1/dashboards/geo-validation`, operationId `getGeoValidation`) and to its `children[]` items:

```yaml
geo_valid_visits: { type: integer, minimum: 0, description: "Visits counted as geo-valid in the period and node; the numerator of geo_valid_pct." }
```

Add it to `required` in both objects (the server always knows it). Kotlin: `GeoValidationSummary` and `GeoValidationChild` in `backend/analytics/.../DashboardApi.kt`.

## Rule
The same rule as `geo_valid_pct`: the sum of `geo_valid_visits` from the daily aggregate rows already read at `DashboardApi.kt:161` (`coalesce(sum(geo_valid_visits),0)`), over the same period, node and reach. `geo_valid_pct = round(100 * geo_valid_visits / visits, 2)`, null when `visits` is 0; the new field is that numerator, never null.

## Why not visits * pct in the web
`geo_valid_pct` has 2 decimals, so `visits * pct / 100` is ambiguous by up to `visits * 0.005 / 100`: from 20 000 visits on (a division or national day) the derived count can be off by one, and the web would print a number the server never computed. The owner's reference screenshot shows counts on the geo-fencing card; the web shows the ring (percent) and the visits count until this field lands, then adds "N of M validated".

## Test
Contract conformance: a response with 3 visits, 2 geo-valid answers `geo_valid_visits: 2`, `geo_valid_pct: 66.67`; a node with 0 visits answers `geo_valid_visits: 0`, `geo_valid_pct: null`; the children's counts sum to the parent's.
