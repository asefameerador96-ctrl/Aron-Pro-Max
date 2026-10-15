# Request to backend-reports (from web-dashboard, 2026-10-15): report handlers versus the web pages

Checked INT 950ddbff: `backend/analytics` report handlers against `web/src/lib/reports/catalog.ts` (the web renders whatever columns the server returns, so only the places where a page depends on a named column or on a report key matter).

## Web pages with no server handler (the page answers 404 ERR_NOT_FOUND today)
| Web page | report_key | Note |
|---|---|---|
| F-WEB-002 Retailers | `retailer-list` | columns the page expects: code, name, owner_name (pii), phone (pii), sub_channel, status |
| F-WEB-008 SKUs | `sku-list` | code, name, retail_price_mtk, trade_price_mtk (pii-gated), status |
| F-WEB-067 Geofence calibration | `geofence-calibration` | distance_band_m, geo_class, territory, visits, force_sale_share |

Either add the three handlers, or tell web-dashboard to read `GET /v1/admin/outlets` and `GET /v1/admin/skus` instead (then the PII mask and the Excel export are the web's job; I would rather keep one engine).

## Column dependencies not met by the handlers
- `qc-report` (F-WEB-061): the page shows Market and Warehouse apart by splitting on a `source` column; the handler has `fault_group` but no `source`. Please add `source` (market | warehouse) or say which column carries it.
- `dss` (F-WEB-055): the page links the route cell to outlet-wise sales using `route_name` and `route_id`; the handler is zone-level (`zone_name`, `period`, ...) and has neither. Is DSS meant to be route-level (the manual's DSS lists routes)? If zone-level is right, the drill is dropped on the web.

## Handlers with no web page (not mine unless a row says so)
`daily-tracking`, `final-submit-status`, `sales-summary`, `settlement` (and `discount`, deferred by docs/27). No action unless the lead assigns rows.

Everything else in the catalog (22 keys) has a handler with the columns the pages use (checked: `suspicious-location` business_date and suspicious for the trend chart).
