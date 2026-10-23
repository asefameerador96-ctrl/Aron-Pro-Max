# infra-qa-1 — the dev slice smoke is red on a Friday, not skipped

Deploy #243 (build 384ecee2, Friday 2026-10-23 Dhaka), job log: `slice: PASS 1 login`, `PASS 2 bundle 2026-10-23:1`,
`slice: FAIL 2b route, outlet, SKU: no planned route with a smoke outlet ... (Friday: no selling route by design)`,
`##[warning]SR slice smoke failed (non-blocking)`, summary row `SR slice smoke | FAILED`. The brief expected "skipped".
Every Friday deploy therefore shows a red smoke that hides a real failure on the other six days. Suggested: in
`infra/scripts/slice-smoke.py` make 2b on a Friday a `SKIP` row (steps 3-10 skipped, exit 0) when the bundle has no planned
route and today is a Friday; keep FAIL for any other day. Also print the route and outlet codes next to the ids (train 10
does) so route 22636 can be named; until a deploy prints them it is only inferable: the Friday result (no planned route
although `MIR-SR-D` would be daily) shows seed 06 is applied on dev, so Thursday 2026-10-22's route 22636 is `BAN-SR-11`
(Mon+Thu) with outlet 28201 `SMOKE-SR-003` — an inference, not a confirmation.
No dev login is needed by qa for this wave (see the status file); unauthenticated probes of dev: `/v1/health` 200 build
384ecee2, `/v1/health/ready` 200, dashboards/bundle/reports/me without a token 401, unknown-user login 401 `ERR_AUTH_INVALID_CREDENTIALS`.
