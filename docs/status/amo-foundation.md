# amo-foundation status (lane/amo-foundation; contract + db + backend of the AMO manual build)

Brief: `docs/requests/amo-manual-build-2026-11.md` s5 "F amo-foundation". Two pushes = two trains. Decisions: D-AMO-FND-01 to 07 in DECISIONS.md.

## Session 1 (2026-11-06)

| Train | State |
|---|---|
| F1 | In progress: contract 1.11.0 (openapi, slices, WireDtos, PayloadShapes, web openapi.d.ts, docs/24 Appendix A), V0150, V0151, dictionary and view contract 1.5, seed 09, backend (rubric fields, force_sale_reason ingest, call-targets, team stock members, dw copy), tests |
| F2 | Not started |

Local setup that worked in this container: `pg_ctlcluster 16 main start`; `CREATE ROLE aron_test LOGIN SUPERUSER PASSWORD 'aron_test'` and `CREATE DATABASE aron_test OWNER aron_test` as postgres; `ARON_TEST_PG_URL='jdbc:postgresql://127.0.0.1:5432/aron_test?user=aron_test&password=aron_test'`; Gradle works online (not `--offline`); `cd web && npm ci --ignore-scripts` for the web contract regeneration.
