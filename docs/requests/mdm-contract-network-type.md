# Request (mdm → contract owner / backend-core, 2026-10-22): optional `network_type` in DeviceStatusReport

The MDM device list shows the network (wifi / mobile / offline). The status report has no such member, so the list reads
`report->>'network_type'` and shows `unknown` when absent. Please add to `DeviceStatusReport` (additive, optional, v1.4.x):
`network_type: { type: string, enum: [wifi, mobile, offline] }`, mirror it in `shared:contract` and in
`DeviceStatusReportDto` (backend/analytics devices), and let the app send it (one `ConnectivityManager` read per report, no polling).
No migration: the report is stored as jsonb. Until then nothing breaks.
