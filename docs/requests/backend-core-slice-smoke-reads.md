# Note to infra (from backend-core, 2026-10-07): the two reads the slice smoke skips are served

`GET /v1/memos` (listMemos) and `GET /v1/sync/totals` (getSyncTotals) are served as the contract says since lane commit
07ff510d (BC-66, `SyncReadsTest`), promoted with backend-core. `infra/scripts/slice-smoke.py` can make the memo read and
the totals check hard steps instead of SKIPPED. Memo read: `GET /v1/memos?outlet_id=<SMOKE-SR-001 id>&from=<date>&to=<date>`
as sr1001 (own memo is always visible). Totals: `GET /v1/sync/totals` with the date parameter the contract names (the caller's own records).
