# Request (mdm → lead, 2026-10-22): queue line for `docs/requests/contract-v1.7-queue.md`

The queue file lives on lane/backend-authz, so this lane files the line here; please paste it into the batch table.

| # | Ruled | Source | Shape |
|---|---|---|---|
| (next) | 2026-10-22 (lead, mdm #2) | D-MDM-01, `mdm-contract-network-type.md` | `DeviceStatusReport.network_type`: optional, `enum [wifi, mobile, offline]`, additive. The MDM list already reads `report->>'network_type'` and shows `unknown` when absent. With the batch: mirror in `shared:contract` and `DeviceStatusReportDto`; the app fills it from one `ConnectivityManager` read per report (`DeviceStatusReporter`, core-sync), no polling. No migration (jsonb). |

No contract bump by this lane. The app side (`DeviceStatusReporter`) is added by mdm after the batch lands.
