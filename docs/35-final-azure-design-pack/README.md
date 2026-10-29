# Aron on Azure — final-account design pack (8,500 field users)

Owner pack, written 2026-10-29 and corrected the same day after three independent review passes (prices and billing rules, every setting against the templates, readability and diagrams). It answers one question: **what does AKTCL need in the final Azure account to run Aron for 8,500 sales representatives with no lag and no crash, and what does it cost each month.** It is derived from the production templates in `infra/` (the single source of truth for every setting), the capacity design in `docs/18`, the verification pack in `docs/33` and the sponsor rules in `docs/28`. Where this pack and a template disagree, the template wins and this pack is corrected.

| Document | What it contains |
|---|---|
| [`01-high-level-design.md`](01-high-level-design.md) | HLD: what the system must do at 8,500 users, architecture drivers, the context, logical and Azure deployment diagrams, the key runtime flows, capacity against demand, availability and recovery targets, security summary, environments and delivery, what is proven where |
| [`02-low-level-design.md`](02-low-level-design.md) | LLD: all 26 resources with their exact specification, a short justification and their status, the designed items, network plan, edge and WAF settings, compute and scaling rules, database configuration, storage lifecycle, identities and secrets, alert thresholds, pipeline, runbooks with their status, the move, known gaps |
| [`03-resource-plan-and-cost.md`](03-resource-plan-and-cost.md) | Resource plan and **monthly cost per resource** with the arithmetic, the DR tier, the buffer, optional and one-off lines, the existing sites (Spec+), growth over time, reservations, year one month by month, levers, exclusions |
| [`04-precise-version.md`](04-precise-version.md) | The short exact version: HLD and LLD diagrams, one resource table with specification, **quantity, why this spec, why this quantity** and cost, Spec+ for the existing sites, buffer and budgets, decisions |
| `cost-model.py`, `assemble-docs.py` | the model that produces every figure (Retail Prices API unit prices read 2026-10-29) and the script that assembles documents 03 and 04 from it |
| `diagrams.py`, `render-png.js`, `diagrams/` | the diagrams drawn with Microsoft's Azure architecture icons in the Visio style (SVG and PNG) |
| `build-html.py`, `build-docx.js` | the shareable web page and the two Word files (version 1 full, version 2 precise) |

## Diagrams in this pack

| Figure | Level | Shows |
|---|---|---|
| HLD 1 `diagrams/hld-1-context.png` | 0, context | users, phones, printers, the platform, Google Maps, Firebase, GitHub |
| HLD 2 `diagrams/hld-2-logical.png` | 1, containers | edge, api, worker, web, jobs, PostgreSQL and replica, Blob and queue, Key Vault, operations |
| HLD 3 `diagrams/hld-3-deployment.png` | 2, Azure deployment | the resource group in Southeast Asia, VNet and subnets, every service, the East Asia DR tier |
| HLD 4 `diagrams/hld-4-dr.png` | availability and DR | three zones, HA standby, replica, geo backup, the DR region |
| LLD 1 `diagrams/lld-1-network.png` | network | Private Link, subnets, NSG, ports, planned private endpoints |
| LLD 2 `diagrams/lld-2-sync-flow.png` | data flow | a sale from the offline phone to the dashboard |
| LLD 3 `diagrams/lld-3-photos.png` | data flow | SAS upload, Event Grid, queue, consumer |
| LLD 4 `diagrams/lld-4-identity.png` | identity and secrets | OIDC deploys, managed identities and their roles, Key Vault |
| LLD 5 `diagrams/lld-5-observability.png` | operations | diagnostics, Log Analytics, App Insights, alerts, runbooks |
| LLD 6 `diagrams/lld-6-pipeline.png` | delivery | CI, registry, deploy, migrations, health gate, rollback |
| LLD 7 `diagrams/lld-7-scaling.png` | scaling | replicas over the field day |
| Spec+ `diagrams/specplus-existing-sites.png` | existing sites | App Service plan, MySQL, DNS, shared Front Door |

## The architecture in six lines

1. **One entrance.** Azure Front Door Premium with a web application firewall terminates TLS near Bangladesh and reaches the application over Private Link; the application network has no public address.
2. **Stateless compute that follows the day.** The Ktor API, the worker and the Next.js web run on a zone-redundant Container Apps environment; the API runs three replicas all day (one per zone), is pre-scaled to eight before the 06:30 bundle storm and the 17:00 check-out, and autoscales to thirty.
3. **One database, built for the peaks.** PostgreSQL 16 General Purpose D8ds_v5 with zone-redundant HA (RPO 0), Premium SSD v2 with 12,000 IOPS included, 35-day point-in-time restore with geo-redundant backup, and a same-size read replica that keeps dashboard readers off the write path.
4. **Bytes stay out of the database.** Photos go straight to geo-zone-redundant Blob by short-lived SAS; events are queued for the (planned) linking consumer; report exports live in Blob for a week.
5. **Secrets and identity by the platform.** Key Vault with purge protection, one managed identity per process, GitHub OIDC for deploys, server-side scope in every token.
6. **Watched and drilled.** Log Analytics with a daily cap, Application Insights, alerts tied to runbooks, a failover drill done once and restore drills planned, a warm East Asia tier designed for wave 2.

## Headline numbers

"8,500 simultaneous users" is designed as **8,500 phones that sync in two daily bursts** (up to 400 requests/s in the morning and 4,500 rows/s at the 17:00 check-out), with a load-test gate at 12,750 users. The 1,500 dashboard readers are 1,051 AMO + 291 TSO in their apps plus about 150 head-office users on the portal, all watching at once in the worst case.

| Item | Value |
|---|---|
| Users | 8,500 SR + 1,051 AMO + 291 TSO app users (9,842); about 150 web users |
| Design peaks | 400 requests/s (morning), 4,500 rows/s (17:00), 8,000 rows/s load-test gate, 150,000 photos a day |
| Region | Southeast Asia (3 zones); DR in East Asia |
| Availability target | 99.9 % of auth, sync and day-close calls 07:00–21:00 Dhaka on working days |
| Recovery | zone loss: RPO 0, 60–120 s; data loss: PITR 35 days, RTO 2 h assumed; region loss before the DR tier: geo-restore, RPO about 1 h, RTO unmeasured; with the DR tier: RPO 15 min, RTO 4 h |
| **Monthly cost, Aron core production** | **about USD 5,490** (list prices, Southeast Asia, 730 h) |
| **Monthly cost, Aron core + DR tier** | **about USD 6,700** |
| **Spec: core + DR + buffer (USD 2,000)** | **about USD 8,700**; recommended budget USD 9,000 |
| Existing sites (aktcl.com, aksilage.com, shahagro.com, arondeepstate.com and two reserve) + their buffer | about USD 840 (342 of resources + 500 buffer; assumptions until inventoried) |
| **Spec+: everything in the final account** | **about USD 9,540**; about 8,370 with 1-year PostgreSQL reservations; recommended budget USD 10,000 |
| Staging-lite (optional, stoppable) | about USD 710 a month |
| Year one, Aron only (ceiling) | about USD 89,500 without reservations; about 77,300 with |

## Honesty statement (sponsor rule 6), by layer

- **Written as infrastructure code:** the platform, the apps, Front Door and WAF, alerts, budget, all in the prod parameter files.
- **Rehearsed at pilot size in the test account:** the zone-redundant, VNet-injected topology at D2 size; one database failover (about 30 s user-visible); offline sync, memo print and the dashboard tile on every deploy.
- **Never run at any size:** Front Door Premium with Private Link, an environment with public access off, D8ds_v5 with the read replica, the Multiple-revision rollback, a point-in-time restore.
- **Designed only:** the DR tier, private endpoints, the signing vault, worker scale rules, the photo-queue consumer, export jobs, the APK channel, the WORM audit export, staging-lite.
- **Final account only:** the 8,500-user proof (load tests S1–S10), the timed failover and restore drills at D8 size, Private Link and the Premium firewall under real traffic.

Cost figures are list prices read from Microsoft's price API on the date above; the first invoices replace them. Nothing here says "cannot fail"; it says what is provisioned, what each piece is for, how far the headroom goes, which gaps are known (`02` section 12) and which drill proves what.

## Open decisions for the owner

| # | Decision | Default in this pack | Decide by |
|---|---|---|---|
| 1 | Build the East Asia DR tier (about USD 1,210 a month) | yes, as designed (D-127, D-420) | before wave 2 |
| 2 | Staging-lite in the final account (about USD 710 a month, stoppable) | yes; the proof and the drills run there | before the load-test windows |
| 3 | Buffer amount inside the 1,000–3,000 band | USD 2,000 on Aron, 500 on the sites | at budget creation |
| 4 | Existing sites in the same subscription and edge profile (Spec+) | yes | at the move |
| 5 | BI path (Fabric F2 + Power BI, about USD 460) | deferred until the reporting need is confirmed | when reporting needs it |
| 6 | Load tests: one certified Azure Load Testing run (about USD 1,900) plus Locust runs on Container Apps Jobs (about USD 20 each) | yes | before wave 1 |
| 7 | Region: Southeast Asia unless the Dhaka latency bake-off favours Central India by 25 % (all figures are Southeast Asia prices and would be re-priced) | Southeast Asia | before the first prod deploy |
| 8 | 1-year PostgreSQL reservations after 30 stable days (saves about USD 855 a month in Southeast Asia, 315 in East Asia) | recommended | month 2 and when DR is built |
| 9 | Data-lake account zone redundancy (ZRS needs the sponsor's written yes, `docs/28`) | ZRS | before the first prod deploy |
| 10 | Budget amount for `budget-aron-prod` | 7,000 now; 9,000 with DR and buffer; 10,000 with the sites | at the move |

## Glossary

| Term | Plain meaning |
|---|---|
| RPO | recovery point objective: how much data could be lost in a failure (0 = none) |
| RTO | recovery time objective: how long the server side is unavailable; phones keep selling throughout |
| HA | high availability: a synchronous standby copy of the database in a second zone that takes over automatically |
| PITR | point-in-time restore: restoring the database to any minute of the last 35 days, onto a new server |
| IOPS | input/output operations per second: how many disk reads and writes a second the database disk can do |
| WAL | write-ahead log: the stream of database changes that the standby and the replicas replay |
| RA-GZRS | read-access geo-zone-redundant storage: three copies in three zones plus a readable copy in a second region |
| SAS | shared access signature: a short-lived, write-only link that lets a phone upload one photo straight to storage |
| WAF | web application firewall: the filter in front of the API that blocks attacks and limits abusive traffic |
| Private Link | a private network path from Front Door to the application, so the application has no public address |
| PgBouncer | the connection pooler built into the database that turns hundreds of app connections into a few server connections |
| KEDA | the scaler that adds worker replicas when a queue or a backlog grows |
| OIDC | the federation that lets GitHub deploy to Azure without any stored password |
| BFF | backend-for-frontend: the web server's own calls to the API on behalf of the browser |
| p95 | the time that 95 % of requests finish within |
| SLO | service-level objective: the availability or latency target the team commits to |
| Sev1/2/3 | alert severities: act now / within the hour / next working day |
| S1–S10 | the ten load-test scenarios of `docs/18` section 8 (morning storm, trickle, check-out, dashboards, photos, failover, restore, re-sync) |
| D-nnn | an entry in `DECISIONS.md`, the repository's decision log |
