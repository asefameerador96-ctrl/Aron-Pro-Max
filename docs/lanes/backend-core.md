# Lane brief: backend-core

Session model: **Opus** (docs/29 s3). Owns: backend modules `platform`, `auth`, `sync`, `notify`, `app` (wiring), and `backend/media`.

Read `docs/lanes/README.md` first.

- Scope: login and tokens, device binding and enrolment service, scope resolution, **sync bundle and ingest (idempotent upsert, registry, replay, quarantine, poison-row isolation)**, server recompute and geo re-check, plausibility flags, Play Integrity and attestation verification, push, route-day planning, audit log, day state, dues ledger, final and sales submit, admission control.
- This is the critical path of the whole product: the SR app cannot sync until `GET /sync/bundle` and `POST /sync/batch` exist. Do those first (F-API-005, F-API-006, F-SYS-055, F-SYS-048, F-SYS-062), then the rest in dependency order.
- Spec: `docs/24` s3 (sync), s4 (auth), s11 (geo integrity), s12 (data); `docs/17` s12 for the property tests (`N-024` fuzz suite is yours with `qa`).
- Other backend lanes own other Gradle modules (`analytics`: backend-reports; `config`, `masterdata`: backend-admin). Register routes through the `app` module with append-only lines; do not reformat shared files.
- Already done: N-009, F-SYS-001/002/005, F-API-001/002, N-017 (Day 1). The seed has `sr1001`, test accounts and a dev phone.
- Every T1 row needs an Opus checker that tries duplicate, reordered, partial and out-of-order batches.
