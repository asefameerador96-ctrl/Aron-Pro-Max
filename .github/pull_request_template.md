## Daily gate: INT to main

**Gate:** `gate-dayN-YYYYMMDD`   **INT head:** `<sha>`   **Date (Asia/Dhaka):**

### What this promotes
- Rows finished since the last gate (ids):
- Rows started and not finished:
- Deferred by docs/27 (not built, by design):

### Quality gates (docs/31)
- [ ] CI green on the INT head and on this merge (contract, jvm, android, web, images, infra)
- [ ] Sampled Opus audit of today's T2 rows done; defect rate: __ %
- [ ] Every T1 row has an Opus checker pass recorded in its status file
- [ ] No migration edited after shipping; contract change (if any) has an approved request file
- [ ] APK size within gate; no new continuous sensor; strings have Bangla twins
- [ ] Device checks run by the owner today (list in docs/status/device-checks.md): __ passed, __ pending

### Environments
- [ ] dev deployed from this head and `/v1/health` is 200 through Front Door
- Notes on cost (docs/28 exception review 2026-10-10):

### Risks and rollback
- Rollback: previous revision / previous tag `gate-day(N-1)-...`
