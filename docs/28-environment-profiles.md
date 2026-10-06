# 28 — Environment profiles: TEST account now, FINAL account later (binding, 2026-10-06)

**Sponsor decision (stated more than once).** The Azure subscription and the Google account we use now are **temporary test accounts**. We build a full-featured system, but we run it at **pilot size: 5 to 10 users**. The sponsor will later give a **final** Azure account (and Google project) and we **move** everything there and size it for 8,500 users. Nothing fleet-sized is created in the test account.

## Two profiles, one set of templates

| | **TEST profile (now, `dev*`)** | **FINAL profile (later, `prod*`)** |
|---|---|---|
| Users | 5 to 10 | 8,500 SRs, about 9,850 app users |
| PostgreSQL | Burstable B2s (or B1ms), single zone, **no HA, no replica, no geo-redundant backup**, 32 GiB, 7-day backup | General Purpose D8ds_v5 or larger, zone-redundant HA, read replica, SSD v2, geo backup |
| Container Apps | api 0 to 2 replicas, worker 1 at minimum size, web 0 to 1; scale to zero allowed | the fleet sizing in docs/18 |
| Front Door / WAF | **not created** (use the Container Apps address) | Premium + WAF + Private Link |
| Storage / registry | LRS, Basic | ZRS / GRS, Premium where needed |
| Logs | Log Analytics daily cap 0.5 GB, short retention | per docs/21 |
| Load test | small smoke test only (tens of virtual users) | the 1.5x fleet test, burst, failover and restore drills |
| Quota requests | **none** | request in the final account (docs/setup/azure-quota-request.md) |
| Budget | alerts at 50/90/100 percent of the monthly test budget | set per the final account |

Rules:
1. The Bicep templates stay the same; only the parameter files differ. Every size, SKU, replica count and feature switch is a parameter, so the move is "new subscription + `prod` parameters + data restore".
2. Never put a fleet-sized value in a `dev*` file. A zone-redundant, geo-redundant or Premium choice needs the sponsor's written yes.
3. The 8,500-user proof (load test, failover and restore drills) can only run in the final account. Until then the system is *designed* for it (stateless api, idempotent sync, pooled connections, partitioned tables) and tested at small scale. The plan must keep time at the end for the move and those drills; the sponsor must hand over the final account early enough.
4. Google Maps follows the same split: one restricted key with a small daily cap and budget alert now; new keys in the final project later. The apps read keys from build secrets, never from code.
5. Keep a `docs/setup/move-to-final-account.md` runbook current (infra lane).
