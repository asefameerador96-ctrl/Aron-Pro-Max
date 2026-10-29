# Aron on Azure — final-account design pack (8,500 field users)

Owner pack, written 2026-10-29. It answers one question: **what does AKTCL need in the final Azure account to run Aron for 8,500 sales representatives with no lag and no crash, and what does it cost each month.** It is derived from the production templates in `infra/` (which are the single source of truth for every setting), the capacity design in `docs/18`, the verification pack in `docs/33` and the sponsor rules in `docs/28`. Where this pack and a template disagree, the template wins and this pack is corrected.

| Document | What it contains |
|---|---|
| [`01-high-level-design.md`](01-high-level-design.md) | HLD: what the system must do at 8,500 users, architecture drivers, context (level 0), container (level 1) and Azure deployment (level 2) diagrams, the key runtime flows as sequence diagrams, capacity against demand, availability and recovery targets, security summary, environments and delivery, what is proven where |
| [`02-low-level-design.md`](02-low-level-design.md) | LLD: every resource with its exact specification and a short justification, network plan and flows, edge and WAF settings, compute and scaling rules, database configuration, storage lifecycle, identities and secrets, observability and alert thresholds, delivery pipeline, runbooks and drills, the move to the final account |
| [`03-resource-plan-and-cost.md`](03-resource-plan-and-cost.md) | Resource plan and **monthly cost per resource** with the arithmetic, DR and optional lines, one-off costs, growth over time, reservation lever, what is excluded |
| [`cost-model.py`](cost-model.py) | the model that produces every figure in document 03 from the Retail Prices API unit prices (read 2026-10-29) and the design load model; re-run it to regenerate the tables |

## The architecture in six lines

1. **One entrance.** Azure Front Door Premium with a web application firewall terminates TLS near Bangladesh and reaches the application over Private Link; the application network has no public address.
2. **Stateless compute that follows the day.** The Ktor API, the worker and the Next.js web run on a zone-redundant Container Apps environment; the API runs three replicas all day (one per zone), is pre-scaled to eight before the 06:30 bundle storm and the 17:00 check-out, and autoscales to thirty.
3. **One database, built for the peaks.** PostgreSQL 16 General Purpose D8ds_v5 with zone-redundant HA (RPO 0), Premium SSD v2 at 12,000 IOPS, 35-day point-in-time restore with geo-redundant backup, and a same-size read replica so 1,500 dashboard readers never slow a sale.
4. **Bytes stay out of the database.** Photos go straight to geo-zone-redundant Blob by short-lived SAS; events link them to memos; route bundles and the APK channel are served from storage.
5. **Secrets and identity by the platform.** Key Vault with purge protection, one managed identity per process, GitHub OIDC for deploys, server-side scope in every token.
6. **Watched and drilled.** Log Analytics with a daily cap, Application Insights, alerts tied to runbooks, monthly failover and restore drills, a warm East Asia tier before wave 2.

## Headline numbers

| Item | Value |
|---|---|
| Users | 8,500 SR + 1,051 AMO + 291 TSO app users (9,842); about 150 web users; about 1,500 concurrent dashboard readers 17:00–19:00 |
| Design peaks | 400 requests/s (morning), 4,500 rows/s (17:00 check-out), 8,000 rows/s load-test gate, 150,000 photos a day |
| Region | Southeast Asia (3 zones); DR in East Asia |
| Availability target | 99.9 % of auth, sync and day-close calls 07:00–21:00 Dhaka on working days |
| Recovery | zone loss: RPO 0, 60–120 s; data loss: PITR 35 days, RTO 2 h; region loss: RPO 15 min, RTO 4 h (DR tier) |
| **Monthly cost, core production** | **about USD 5,980** (list prices, Southeast Asia, 730 h) |
| **Monthly cost, core + DR tier** | **about USD 7,420**; about 6,560 with a 1-year PostgreSQL reservation |
| Year one | about USD 100,000 without reservations (core from month 1, DR from month 4, staging all year, four load-test runs); about 91,000 with the 1-year reservation |

## Honesty statement (sponsor rule 6)

Everything here is **designed, written as infrastructure code and tested at pilot size in the test account**. The sponsor rule in `docs/28` forbids fleet-sized resources in the test account, so the 8,500-user proof — the load-test scenarios, the timed failover and restore drills at D8 size, Private Link and the Premium firewall under real traffic — can only run in the final account, before wave 1. The cost figures are list prices read from Microsoft's price API on the date above; the first invoices replace them. Nothing here says "cannot fail"; it says what is provisioned, what each piece is for, how far the headroom goes, and which drill proves it.

## Open decisions for the owner

| # | Decision | Default in this pack |
|---|---|---|
| 1 | Build the East Asia DR tier before wave 2 (about USD 1,440 a month) | yes, as designed (D-127, D-420) |
| 2 | Staging in the final account (about USD 650 a month, stoppable) | yes; the proof runs there |
| 3 | BI path (Fabric F2 + Power BI, about USD 460) | deferred until the reporting need is confirmed |
| 4 | Load tests with Azure Load Testing (about USD 1,900 per full run) or self-hosted Locust (about USD 20) | Locust on Container Apps Jobs, Azure Load Testing for one certified run |
| 5 | Region: Southeast Asia unless the Dhaka latency bake-off favours Central India by 25 % | Southeast Asia |
| 6 | 1-year PostgreSQL reservation after 30 stable days (saves about USD 855 a month) | recommended |
| 7 | Budget amount for `budget-aron-prod` | 7,000 now, 9,000 with DR |
