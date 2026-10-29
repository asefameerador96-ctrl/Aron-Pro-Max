# 03 — Resource plan and monthly Azure cost (final account, 8,500 field users)

Part of the owner pack `docs/35-final-azure-design-pack/`. Every table and every figure in this document is produced by `cost-model.py` (this file is assembled by `assemble-docs.py`; run `python3 -I docs/35-final-azure-design-pack/cost-model.py` to reproduce it). Unit prices were read from the **Azure Retail Prices API on 2026-10-29** (USD, pay-as-you-go, Southeast Asia unless stated); the few prices the API does not publish are marked as estimates in their own table. Usage figures are the design load model of `docs/18` (8,500 SRs, 9,842 app users, about 150 web users, 3.0 M rows and 150,000 photos a day) and the production parameter files in `infra/params/`. The model was corrected on 2026-10-29 after an independent review of the prices and the billing rules (the corrections are listed at the top of `cost-model.py`).

## How to read the figures

- **Core production** is what the templates create on the first final-account deploy (status `IaC`), the monthly bill for a zone-redundant production platform in one region. It includes about USD 28 for items designed but not yet in a template (private endpoints, signing vault, export jobs).
- **Disaster recovery** is the East Asia tier the design adds before rollout wave 2. It is decided (D-127, D-420, D-522) but not yet in a template; the owner approves it before it is built.
- **Buffer** is the owner's contingency band (USD 1,000 to 3,000); the pack carries USD 2,000 a month and says what it covers.
- **Spec** = Aron core + DR + buffer. **Spec+** = Spec + the existing web properties that move into the same account (their own table and their own buffer).
- Figures are list prices for a 730-hour month, before any reservation discount, before taxes and any enterprise agreement. Conservative simplifications are kept as a safety margin (about USD 120 a month: all api replica-hours billed at the active rate, no free log or alert tiers). The **first invoices replace these numbers**; the budget alert is the control until then.

## Headline

| Scope | USD a month | Note |
|---|---:|---|
| Aron core production (day 1, in IaC) | **about 5,490** | database 2,683, compute 1,233, logs 552, storage 534, edge 403 |
| Disaster-recovery tier (East Asia, before wave 2) | about 1,212 | the cross-region D8 replica is most of it |
| Contingency buffer | 2,000 | D16 step-up for a quarter, log overage, extra replicas, unpublished prices, billing-zone differences |
| **Spec: Aron core + DR + buffer** | **about 8,702** | recommended budget USD 9,000 with alerts at 50/90/100 % |
| Existing web properties + their buffer | about 842 | 342 of resources (assumptions until inventoried) + 500 buffer |
| **Spec+: everything in the final account** | **about 9,544** | |
| Spec+ with 1-year PostgreSQL reservations (24 vCores Southeast Asia, 8 East Asia) | about 8,374 | buy after 30 stable days at D8 |
| Optional: staging-lite in the final account | about 708 | stoppable between release gates and test windows |
| One-off: certified load-test run | about 1,905 per run | the default plan is one paid run plus Locust runs at about 20 each |

**Year one (ceiling):** about **USD 89,542** without reservations and about **USD 77,299** with the 1-year reservations bought in month 2 (Southeast Asia) and month 4 (East Asia): Aron at D8 from month 1, the DR tier from month 4, the disk grow at month 6, the photo archive ramping, staging-lite all year, one paid load-test run; the existing sites and the buffer are not in the year-one figure. The D2 → D4 → D8 ladder of D-06 would make the first months cheaper and is not modelled.

### Reconciliation with the earlier design estimate (`docs/18` section 3.3)

| Line | docs/18 (2026-10-04) | This pack | Why it moved |
|---|---:|---:|---|
| PostgreSQL primary, HA, 1,024 GiB | 1,780 | 1,829 | same compute and disk; geo-backup priced by Microsoft's formula at month 12 |
| In-region read replica | 850 | 854 | same |
| Cross-region replica (DR) | 850 | 939 | East Asia prices are about 10 % higher than the Singapore prices docs/18 used |
| Container Apps compute + fixed fees | 1,000 + 350 (two environments) | 1,233 + 206 (DR) | the retained previous revision and the plan-management fee are now priced; the separate `auth` app of docs/18 does not exist |
| Front Door Premium | 430 | 403 | traffic priced from the API |
| Blob | 190 | 534 | the lifecycle write operations of 4.5 M photos a month and geo-replication at the inter-region rate were missing |
| Logs | 490 | 552 | retention beyond 31 days and alerts added |
| ACR, Key Vault, events, network | 160 | 85 | same order |
| Redis | 120 | optional 59 | not in the as-built design (D24-20) |
| BI | 400 | optional 461 | deferred |
| **Total** | **6,630** (with Redis and BI) | **6,702** core + DR (without them) | like for like, about 10 % above docs/18's 6,110 without Redis and BI; the differences are the Blob operations, the retained revision and the plan-management fee |

## Sizing in one paragraph per tier (why these SKUs)

- **PostgreSQL General Purpose D8ds_v5 with zone-redundant HA** is the smallest SKU whose IOPS cap (12,800) holds the 12,000 IOPS the 17:00 write peak needs and whose 8 vCores keep CPU under 80 % at the 8,000 rows/s gate; HA doubles the price but gives RPO 0 and automatic failover, which is what turns a zone failure into a 60–120 s blip while phones keep selling. The same SKU on the **read replica** keeps dashboard readers off the primary and keeps replay in step. Premium SSD v2 is chosen because a 1,024 GiB disk already includes 12,000 IOPS and 500 MB/s, so nothing is paid for IOPS at this size.
- **Container Apps on the Consumption profile** bills only the replicas that run: three API replicas all day (one per zone), eight in the two daily storms, more when the autoscaler asks, and the worker and web at their small steady state. There is no cluster to pay for; zone redundancy is free; the Front Door Private Link attracts a fixed plan-management fee.
- **Front Door Premium** costs 330 a month before traffic; Standard would cost 35 but cannot do Private Link to the application network or run the managed WAF rule sets. The difference buys "no public address on the application" and a managed firewall.
- **RA-GZRS storage** is about 2.5 × the price of zone-redundant storage per GB, but the tiers make 8 TB of two-year evidence cost about 530 a month at month 12; the Cold step costs more in tier-move operations than it saves in storage at these prices (see the levers).
- **Logs at 5.5 GB a day** are the fourth-largest line. The cap at 8 GB a day bounds the bill; sampling the traces is the first lever if the real volume is higher.

## Core production (what the templates create on the first final-account deploy)

| # | Resource | Specification | USD / month | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **PostgreSQL Flexible Server primary `psql-aron-prod`** (Database) | General Purpose D8ds_v5 (8 vCore, 32 GiB), PostgreSQL 16, zone-redundant HA, Premium SSD v2 1,024 GiB at 12,000 IOPS / 300 MB/s (included with the disk size), PITR 35 days, geo-redundant backup, built-in PgBouncer | 1,829 | compute 0.976 x 730 x 2 (HA standby billed) = 1,425; storage 1,024 x 0.138 x 2 = 283; IOPS and throughput 0 (12,000 IOPS and 500 MB/s included from 400 GiB); geo backup at month 12: (2 x 1,150 - 1,024) GB x 0.095 = 121 (0 in the first months) | IaC |
| 2 | **PostgreSQL read replica `psql-aron-prod-<s>-r1`** (Database) | same SKU and disk as the primary, zone 3, asynchronous, no HA, own PgBouncer | 854 | compute 712; storage 141; IOPS and throughput 0 | IaC template; creation path needs a pipeline fix (docs/33 B13.2 rows 36, 37) |
| 3 | **Container App `ca-aron-prod-api`** (Compute) | 1 vCPU / 2 GiB per replica (JVM heap ceiling about 1.5 GiB); HTTP scale rule 50 concurrent requests per replica; cron pre-scale 06:15-10:00 and 16:45-20:00 Dhaka; Multiple revisions; startup, liveness and readiness probes | 662 | 3,711 replica-hours x (0.1224 + 2 x 0.0144) = 561; retained previous revision 3 replicas idle x 730 h = 95; HTTP requests (18 M - 2 M free) x 0.4 = 6 | IaC |
| 4 | **Container App `ca-aron-prod-worker`** (Compute) | 1 vCPU / 2 GiB; no ingress; outbox aggregation, risk signals, route-day planning, geo re-check sweep, retention; KEDA rules on outbox depth and photo queue designed | 221 | 2 replicas x 730 h x 0.1512 = 221 (1 replica would be 110) | IaC (1 replica); scaling designed |
| 5 | **Container App `ca-aron-prod-web`** (Compute) | 0.5 vCPU / 1 GiB; Next.js dashboard and portal; its server-side calls reach the api through Front Door | 166 | 3 replicas x 730 h x (0.0612 + 0.0144) = 166 | IaC (16:45 pre-scale designed) |
| 6 | **Container Apps Jobs `caj-aron-prod-migrate`, `caj-aron-prod-dblogins` (+ export jobs designed)** (Compute) | run-to-completion jobs: Flyway migrations before any revision change; per-app database logins; large exports | 9 | 60 h x 0.1512 = 9 | IaC (exports designed) |
| 7 | **Container Apps environment `cae-aron-prod`** (Compute) | workload-profiles environment, Consumption profile, zone-redundant, VNet-injected, public access off, Front Door Private Link connection | 175 | environment private endpoint 0.14 x 730 = 102; Dedicated Plan Management 0.10 x 730 = 73 (a private endpoint attracts it on any plan; confirm on the first invoice) | IaC |
| 8 | **Front Door Premium `afd-aron-prod` + WAF policy `wafaronprod`** (Edge) | Premium tier (Private Link origins, managed rule sets), WAF in Prevention with managed rules in Log for the first wave, custom rules: IP backstop 60,000 / 5 min and method allow-list; origin timeout 60 s; probes HEAD /v1/health every 60 s | 403 | base 330; requests 18 M / 10,000 x 0.0168 = 30; egress 370 GB x 0.115 = 43 (zone 2 assumed; zone 5 edges differ by under 25); edge to origin 5 GB x 0.06 = 0.3; WAF included | IaC |
| 9 | **Storage account `staronprod` (Blob + queue)** (Storage) | StorageV2 Standard_RAGZRS Hot; containers media (photos), bundles, exports (7-day expiry); lifecycle photos Cool 30 d, Cold 90 d, delete 730 d; soft delete 14 d; queue media-events (message TTL 7 d) | 530 | month 12: Hot 684 GB, Cool 1,369 GB, Cold 6,159 GB stored = 158; 4.5 M writes and two tier moves = 312; geo-replication 55; queue 5; month 1 about 153, month 24 about 627 (steady after the 730-day delete) | IaC |
| 10 | **Data-lake account `staronprodlake`** (Storage) | StorageV2 Standard_ZRS with hierarchical namespace, container lake, soft delete 14 d; nightly Parquet export of the dw views (writer planned) | 5 | 100 GB x 0.028 + operations = 5 | IaC (ZRS needs the sponsor's written yes, docs/28; writer planned) |
| 11 | **Container Registry `craronprod`** (Registry) | Premium, zone-redundant, 500 GiB included, untagged manifests purged after 30 days | 51 | 1.6666 x 30.42 = 51 | IaC |
| 12 | **Key Vault `kv-aron-prod`** (Security) | Standard, RBAC, soft delete 90 d, purge protection on; secrets referenced by the Container Apps; JWT signing key as a secret (signing vault with a non-exportable key designed) | 6 | 2 M operations x 0.03 / 10K = 6 | IaC |
| 13 | **Log Analytics `log-aron-prod` + Application Insights `appi-aron-prod` + alerts + workbook** (Observability) | PerGB2018, 90-day retention, 8 GB/day cap; diagnostics from every service; Java agent traces; 5 log-search alerts, PostgreSQL and app metric alerts, resource-health alerts, e-mail action group; one workbook | 552 | 5.5 GB x 30.42 = 167 GB x 2.99 = 500; retention 59 extra days 42; alerts 9.5 | IaC |
| 14 | **Event Grid system topic `evgt-aron-prod-storage`** (Integration) | BlobCreated on media/photos to the queue, 30 attempts, event TTL 24 h | 3 | (4.5 M - 0.1 M free) x 0.6 = 2.6 | IaC (consumer planned) |
| 15 | **VNet `vnet-aron-prod`, 2 NSGs, private DNS zone (+ 3 private endpoints planned)** (Network) | 10.40.0.0/16; snet-aca /23 (Container Apps); snet-pg /28 (PostgreSQL) with NSG allow 5432/6432 from snet-aca and subnet-internal; private DNS for PostgreSQL | 26 | VNet, NSGs, identities free; DNS 0.9; inter-zone 300 GB x 0.01 = 3; 3 private endpoints x 0.01 x 730 = 22 (planned) | IaC (private endpoints planned) |
| 16 | **Budget `budget-aron-prod`, Cost Management, tags, GitHub OIDC deploy identity** (Governance) | USD 7,000 (raise with DR and buffer) with alerts at 50/90/100 % actual and 100 % forecast; daily cost report | 0 | no charge | IaC |

**Core total: about USD 5,490 a month** (list prices, 730-hour month, Southeast Asia). Includes about USD 28 for items designed but not yet in a template (private endpoints, signing vault, export jobs).

## Disaster-recovery tier (designed; built before rollout wave 2)

| # | Resource | Specification | USD / month | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **PostgreSQL cross-region replica, East Asia** (DR) | D8ds_v5, SSD v2 1,024 GiB (12,000 IOPS included), asynchronous; promoted only in a region outage | 939 | compute 1.076 x 730 = 785; storage 1,024 x 0.15 = 154; IOPS and throughput 0 | designed (D-127, D-420, D-522) |
| 2 | **Inter-region replication traffic** (DR) | WAL stream Southeast Asia to East Asia | 15 | 6.3 GB a day x 30.42 x 0.08 = 15 | designed |
| 3 | **Container Apps environment East Asia (warm, apps at 0)** (DR) | zone-redundant, VNet 10.42.0.0/16, Private Link, api/worker/web at 0 replicas until promotion; weekly smoke | 206 | private endpoint 0.155 x 730 = 113; plan management 73; smoke runs about 20 | designed |
| 4 | **ACR geo-replica, DR Key Vault, disabled DR Front Door origin** (DR) | image pulls and secrets survive a Southeast Asia outage | 52 | replica 1.6666 x 30.42 = 51; vault about 1 | designed |

**DR total: about USD 1,212 a month. Core + DR: about USD 6,702.**

## Contingency buffer

| Line | USD / month | What it covers |
|---|---:|---|
| Buffer (owner's 1,000-3,000 band) | 2,000 | a D16ds_v5 step-up for a quarter (+1,425 compute with HA), log overage above 5.5 GB/day, extra replicas in a storm, prices the API does not publish, a billing-zone difference |

**Core + DR + buffer: about USD 8,702 a month. Recommended budget: USD 9,000.**

## Optional and non-production lines

| # | Resource | Specification | USD / month | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **Staging-lite in the final account** (Optional) | D2ds_v5 zone-redundant HA on 128 GiB, three small Container Apps, Front Door Standard, 1 GB/day logs, ACR Standard; scaled to the prod shape only inside a test window (about 75 per 8-hour window); Premium Front Door + Private Link for the edge proof adds 295 | 708 | database 392; apps 166; Front Door 40; logs 91; registry 20 | parameters only: the committed stage file is prod-shaped and the deploy path does not accept stage yet (docs/33 B13.2 rows 2, 35) |
| 2 | **BI path: Microsoft Fabric F2 + 10 Power BI Pro licences (D-560)** (Optional) | F2 = 2 CU pay-as-you-go; VNet data gateway; archived partitions in the lakehouse | 461 | 2 CU x 0.22 x 730 = 321; 10 x 14 = 140 | deferred; pausing the capacity stops the charge |
| 3 | **Azure Managed Redis Balanced B3** (Optional) | replay cache, per-device rate limits, config fan-out | 59 | 0.081 x 730 = 59 | not in the as-built design (D24-20) |
| 4 | **Microsoft Defender for Cloud (3 PostgreSQL servers, Key Vault, Storage)** (Optional) | threat detection on the data stores | 60 | 3 x 15 + about 15 | subscription-level choice |

**Optional total if all are taken: about USD 1,289 a month.**

## One-off costs

| # | Item | Specification | USD per run | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **Load-test runs S1-S10** | one full-size hour = 12,750 virtual users (1.5 x 8,500 SRs) x 1 h = 12,700 billable VUH; 26 engines at 500 users each; 1 certified run with Azure Load Testing + Locust runs on Container Apps Jobs | 1,905 | 12,700 x 0.15 = 1,905 per Azure Load Testing run; a Locust run on Container Apps Jobs costs about 20 | final account only |

## Spec+: existing web properties migrated into the final account (separate from Aron)

| # | Resource | Specification | USD / month | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **App Service plan `asp-aktcl-web` (Linux Premium v3 P1)** (Sites) | 2 vCPU / 8 GiB, Linux, zone-redundant off, autoscale 1 to 2 instances, deployment slots, managed TLS certificates | 154 | 0.183 x 730 = 134; second instance allowance 20 | assumption: the sites run on App Service (inventory the current subscription) |
| 2 | **Azure Database for MySQL Flexible Server `mysql-aktcl-web`** (Sites) | Burstable B2s (2 vCore, 4 GiB), 64 GiB storage, 7-day backup, private access | 85 | 0.104 x 730 = 76; storage 64 x 0.138 = 9 | assumption: WordPress-style sites |
| 3 | **Storage account `staktclweb` (LRS)** (Sites) | media uploads and App Service backups | 3 | 100 GB x 0.0184 + operations = 3 | assumption |
| 4 | **Azure DNS public zones** (Sites) | aktcl.com, aksilage.com, shahagro.com, arondeepstate.com and two more | 4 | 6 x 0.5 + queries 0.8 | assumption |
| 5 | **Front Door routes and custom domains (shared Premium profile)** (Sites) | one route per domain on `afd-aron-prod`, WAF, managed certificates | 51 | requests 10 M x 0.0168 / 10K = 17; egress 300 GB x 0.115 = 34 | assumption on traffic |
| 6 | **Diagnostics into the shared Log Analytics workspace** (Sites) | 0.5 GB a day | 45 | 0.5 x 30.42 x 2.99 = 45 | assumption |

**Existing sites: about USD 342 a month, plus their own buffer of 500 = about USD 842.** These are assumptions until the current subscription is inventoried (one `az resource list` per subscription).

What the existing-sites figures assume: four live sites (aktcl.com, aksilage.com, shahagro.com, arondeepstate.com) and two in reserve, each a PHP/WordPress or static site on one Linux App Service plan with one MySQL Flexible Server, about 300 GB a month of traffic through the shared Front Door, DNS zones moved to Azure DNS, managed certificates. Replace these with the current subscription's inventory before the move (`az resource list --output table` in each subscription); a site on a virtual machine would add a B2s v2 VM (about 77 a month) and a disk.

## Totals

| Scope | USD / month |
|---|---:|
| Aron core production | 5,490 |
| Aron core + DR tier | 6,702 |
| Aron core + DR + buffer (Spec) | 8,702 |
| Existing sites + their buffer | 842 |
| Everything (Spec+) | 9,544 |
| Everything with 1-year PostgreSQL reservations (SEA 24 vCores, EA 8) | 8,374 |
| Staging-lite (optional) | 708 |

## Reservation lever (PostgreSQL compute)

| Term | USD / month | Saving a month | Rule |
|---|---:|---:|---|
| Southeast Asia 24 vCores pay as you go | 2,137 | - | primary 8 + standby 8 + replica 8 |
| 1-year reserved (641 per vCore) | 1,282 | 855 (40 %) | buy after 30 stable days at D8 (D-426) |
| 3-year reserved (1,282 per vCore) | 855 | 1,283 (60 %) | only after the load test and a year of running |
| East Asia DR replica 8 vCores, 1-year reserved (706 per vCore) | 471 | 315 | buy when the DR tier is built |
| Sensitivity: D16ds_v5 step-up on the three Southeast Asia servers | +2,137 | - | if the load test shows CPU above 80 % or IOPS above about 7,700 |

## Lines that grow with time

| Month of full-fleet running | Photos (Blob) USD / month | Database disk | Note |
|---|---:|---|---|
| 1 | 153 | 1,024 GiB | Hot 684 GB, Cool 0 GB, Cold 0 GB |
| 3 | 285 | 1,024 GiB | Hot 684 GB, Cool 1,369 GB, Cold 0 GB |
| 6 | 481 | grow to 2,048 GiB at about 70 % (+283 primary with HA, +141 replica, +154 DR replica) | Hot 684 GB, Cool 1,369 GB, Cold 2,053 GB |
| 12 | 530 | 2,048 GiB | Hot 684 GB, Cool 1,369 GB, Cold 6,159 GB |
| 24 | 627 | 2,048 GiB; photos older than 730 days are deleted, so Cold stops growing | Hot 684 GB, Cool 1,369 GB, Cold 14,372 GB |

## Year one, month by month (ceiling: D8 from month 1, DR from month 4, staging-lite all year, one paid load-test run in month 2 and three Locust runs)

| Month | Aron core | DR | Total | Total with 1-year reservations (SEA from month 2, EA from month 4) |
|---|---:|---:|---:|---:|
| 1 | 5,113 | 0 | 5,822 | 5,822 |
| 2 | 5,224 | 0 | 7,838 | 6,982 |
| 3 | 5,246 | 0 | 5,974 | 5,118 |
| 4 | 5,425 | 1,212 | 7,345 | 6,175 |
| 5 | 5,433 | 1,212 | 7,373 | 6,203 |
| 6 | 5,441 | 1,212 | 7,362 | 6,191 |
| 7 | 5,873 | 1,366 | 7,948 | 6,777 |
| 8 | 5,881 | 1,366 | 7,976 | 6,806 |
| 9 | 5,889 | 1,366 | 7,964 | 6,794 |
| 10 | 5,898 | 1,366 | 7,972 | 6,802 |
| 11 | 5,906 | 1,366 | 7,980 | 6,810 |
| 12 | 5,914 | 1,366 | 7,988 | 6,818 |
| **Year one** | | | **89,542** | **77,299** |

## Cost levers, in the order to pull them

| Lever | Saving a month | Condition |
|---|---:|---|
| 1-year reservation on 24 Southeast Asia PostgreSQL vCores | about 855 | after 30 stable days at D8 (D-426); 3-year saves about 1,283 |
| 1-year reservation on the 8 East Asia vCores | about 315 | when the DR tier is built |
| Drop the Cold step for photos (Cool until deletion) | about 55 | the Cold write operations cost more than the Cold storage saves at these prices; confirm in the pilot |
| Keep photos in Cool for the whole 730 days, no Cold at all | about 100 | if photos are rarely opened after 30 days |
| Pause the BI capacity when not in use | up to 321 | Fabric capacity bills by the hour |
| Log sampling at 50 % on traces | about 150 | only if the real ingestion exceeds 5.5 GB a day |
| Stop staging-lite outside release gates and test windows | about 400 | staging is stoppable; prod is not |
| Worker at 1 replica until the KEDA rules exist | about 110 | as deployed today |

## What is not in these figures

Google Maps Platform (per-key, owner's Google project, on-demand geocoding only, budget alert), Firebase Cloud Messaging (free at this volume), Google Play (optional), SMS or voice paging for alerts, the registrar fees of the domains, Private Link data processing (0.01 per GB) and outbound internet egress from the apps (first 100 GB a month free), the test-account dev environment (about USD 15 a day today, stoppable), people, support plans (Azure Standard support is USD 100 a month if wanted), taxes, and any enterprise-agreement discount.

## Prices that are not in the Retail Prices API (estimates to confirm on the first invoice)

| Key | Value | Unit | Source |
|---|---:|---|---|
| `dns_zone` | 0.5 | per DNS zone per month (public or private) | ESTIMATE: pricing page; the API's DNS rows carry no region |
| `dns_queries_m` | 0.4 | per million DNS queries | ESTIMATE: pricing page |
| `private_endpoint_h` | 0.01 | per private endpoint per hour | ESTIMATE: Private Link pricing page |
| `pbi_pro_user` | 14.0 | Power BI Pro per user per month | ESTIMATE: Microsoft 365 list price |
| `alt_vuh` | 0.15 | Azure Load Testing per virtual-user-hour after 50 free a month | ESTIMATE: pricing page |

## Core total by group

| Group | USD / month | Share |
|---|---:|---:|
| Database | 2,683 | 49 % |
| Compute | 1,233 | 22 % |
| Observability | 552 | 10 % |
| Storage | 534 | 10 % |
| Edge | 403 | 7 % |
| Registry | 51 | 1 % |
| Network | 26 | 0 % |
| Security | 6 | 0 % |
| Integration | 3 | 0 % |
| Governance | 0 | 0 % |

## Budget and governance

Budget `budget-aron-prod` is created by the templates at USD 7,000 a month with alerts at 50, 90 and 100 percent actual and 100 percent forecast; raise it to USD 9,000 when the DR tier and the buffer are approved, and to about USD 10,000 if the existing sites share the subscription. A daily cost report (`cost-report.yml`) reads the month-to-date spend. Every resource is tagged `project`, `environment`, `owner`, `managed-by`. The Log Analytics daily cap and the Google Maps key budget alert are separate controls.
