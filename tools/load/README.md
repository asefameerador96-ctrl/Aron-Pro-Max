# Load smoke (SCALE-01)

`smoke.js` is the k6 smoke of the two daily storms at pilot size (docs/14 row 18, D-133/134): the morning login
storm (login, bundle, bundle with ETag, delta) and the evening submit storm (a 200-row batch, its replay, the same
rows as duplicates). It runs against dev in the TEST account or against a local API. It is a smoke, not the capacity
proof: the fleet shapes S1 to S10 (8,500 users, `cl_waiting 0`) run with Azure Load Testing in the FINAL account only
(docs/28 rule 3, T-4-157).

## Run

```bash
k6 run -e BASE_URL=https://<api host> -e USERS=sr1001,amo1001 -e PASSWORD='<pilot password>' -e VUS=5 tools/load/smoke.js
```

Variables: `BASE_URL`, `USERS` (comma list of pilot usernames; VUs take turns), `PASSWORD` (one pilot password; the
seed's pilot users share it on dev), `VUS` (default: one per user, at most 10: pilot size, docs/28), `EVENING_BATCHES`
(batches per VU, default 2), `ROWS` (rows per batch, default 200), `DEVICE_UUID_PREFIX` (the seed's dev phones:
`00000000-0000-4000-8000-0000000000` + two digits), `BUSINESS_DATE` (default: today in Dhaka).

The evening rows are `geo_breadcrumb` records: telemetry the seed accepts from every pilot phone without outlet or
memo state, so the run leaves no sales behind. The summary table (p50, p95 and count per endpoint) is printed and
written to `tools/load/last-run.md`; copy it into `docs/status/backend-core.md` with the date, the target and the
replica size.

## Thresholds (pilot, docs/24 s13.1)

login p95 < 1.5 s, bundle p95 < 2.5 s, bundle with ETag p95 < 0.5 s, delta p95 < 1.5 s, batch p95 < 3 s, replay
p95 < 0.8 s, duplicates p95 < 1.5 s, failed requests < 1 %. A failing threshold fails the run.

## In CI

`.github/workflows/load-smoke.yml` (manual dispatch) runs the script against dev with the repository secrets
`LOAD_BASE_URL`, `LOAD_USERS`, `LOAD_PASSWORD` and uploads `last-run.md`. It needs the dev API reachable from the
runner (Front Door or the public ingress of the TEST profile).
