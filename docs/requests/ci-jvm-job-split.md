# CI request (lead, 2026-10-22 19:30 UTC): split the "Shared, db and backend" job

**What happened.** INT run #1395 (train 9: db V0107 + seed 07, backend-reports 1.5.0 national summaries, web-admin, docs)
had its `jvm` job cancelled by the 45-minute job timeout at step "Build and test shared, db and backend"; no test failed
and every other job was green. The db lane's own run of the same job took 40 minutes (#1388), backend-reports' 26 minutes
(#1389); the two together on INT pass 45.

**Fix now (lead, lane/lead-ci-jvm-timeout).** `timeout-minutes: 75` on the `jvm` job only. Nothing else changes.

**Ask (infra or lead, next quiet slot, not tonight).** Split `jvm` into two jobs that run in parallel and keep the same
Gradle targets: `jvm-db` (`:shared:*`, `:db:build`, data dictionary steps) and `jvm-backend` (`:backend:*:build` without
`:backend:app:test`, which already has its own job). Each gets `timeout-minutes: 45` again. Keep the changed-areas gating
(`needs.changes.outputs.jvm`) for both, keep the Postgres service on both, and keep the "Upload test reports" artifact
names distinct. Expected wall-clock for a train like #1395: about 25 minutes instead of 45+.

**Not acceptable.** Skipping or sharding tests to fit the limit; dropping `--console=plain`; running the two halves
against one shared database.
