# 03 — Resource plan and monthly Azure cost (final account, 8,500 field users)

Part of the owner pack `docs/35-final-azure-design-pack/`. Every number on this page is produced by `cost-model.py` in this folder (run `python3 -I docs/35-final-azure-design-pack/cost-model.py`), so the arithmetic can be re-run and audited. Unit prices were read from the **Azure Retail Prices API on 2026-10-29** (USD, pay-as-you-go, region Southeast Asia unless stated); the handful of prices the API does not publish are marked as estimates in their own table. Usage figures are the design load model of `docs/18` (8,500 SRs, 9,842 app users, about 150 web users, 3.0 M rows and 150,000 photos a day) and the production parameter files in `infra/params/`.

## How to read the figures

- **Core production** is what the templates create on the first final-account deploy (status "in IaC"). It is the monthly bill for a zone-redundant production platform in one region.
- **Disaster recovery** is the East Asia tier the design adds before rollout wave 2. It is decided (D-127, D-420, D-522) but not yet in a template; the owner approves it before it is built.
- **Optional** lines are real choices the owner can take or leave; **one-off** costs are not monthly.
- Figures are list prices for a 730-hour month, before any reservation discount, before taxes and any enterprise agreement. The **first invoices replace these numbers**; the budget alert is the control until then.
- Cheaper is possible: the reservation lever at the end cuts the largest line by 40 to 60 percent once the SKU is confirmed.

## Headline

| Scope | USD a month | Note |
|---|---:|---|
| Core production (day 1, in IaC) | **about 5,980** | database 56 %, compute 19 %, logs 9 %, storage 7 %, edge 7 % |
| Disaster-recovery tier (East Asia, before wave 2) | about 1,440 | cross-region D8 replica is 1,240 of it |
| **Core + DR** | **about 7,420** | the figure to plan the final-account budget on (set the budget to 9,000 with alerts at 50/90/100 %) |
| Optional: staging in the final account | about 650 | needed for the 8,500-user proof and every release gate; can be stopped between windows |
| Optional: BI (Fabric F2 + Power BI), Redis, Defender | about 580 | each is a separate owner decision |
| One-off: load-test windows | about 1,900 per full-size run | or about 20 per run with self-hosted Locust on Container Apps Jobs |
| With a 1-year PostgreSQL reservation | core + DR about **6,560** | saves about 855 a month on the 24 Southeast Asia vCores |

**Year-one estimate** (core from month 1, DR from month 4, staging all year, four load-test runs, no reservation): about **USD 100,000**; with the 1-year reservation bought after 30 stable days about **USD 91,000**.

Compared with the earlier design estimate (`docs/18` section 3.3: about 6,630 a month including DR, Redis and BI), this pack is about 13 percent higher for the same scope because it prices three items the earlier table left out: the provisioned IOPS and throughput of Premium SSD v2 (about 500 a month across the primary, standby and replica), the lifecycle write operations that move 4.5 million photos a month down the storage tiers (about 215), and the retained previous API revision. The Redis line is gone (the as-built design has none) and BI is optional.

## Sizing in one paragraph per tier (why these SKUs)

- **PostgreSQL General Purpose D8ds_v5 with zone-redundant HA** is the smallest SKU whose IOPS cap (12,800) holds the 12,000 IOPS the 17:00 write peak needs and whose 8 vCores keep CPU under 80 % at the 8,000 rows/s gate; HA doubles the price but gives RPO 0 and automatic failover, which is the price of "no crash" at the data tier. The same SKU on the **read replica** keeps 1,500 dashboard readers off the primary and keeps replay in step. Premium SSD v2 is chosen because IOPS and throughput are bought independently of size, so the disk is 1,024 GiB for year one instead of a much larger disk bought only for its IOPS.
- **Container Apps on the Consumption profile** bills only the replicas that run: three API replicas all day (one per zone), eight in the two daily storms, more when the autoscaler asks, and the worker and web at their small steady state. There is no cluster to pay for, and zone redundancy is free.
- **Front Door Premium** costs 330 a month before traffic; Standard would cost 35 but cannot do Private Link to the application network or run the managed WAF rule sets. The 295 difference buys "no public address on the application" and a managed firewall.
- **RA-GZRS storage** is about 2.5 × the price of locally-redundant storage per GB, but the photo tiers make the whole account about 425 a month at month 12 for 8 TB of evidence that survives both a zone and a region loss.
- **Logs at 5.5 GB a day** are the fourth-largest line. The cap at 8 GB a day bounds the bill; sampling the traces is the first lever if the real volume is higher.

## Core production (what the IaC creates on day 1)

| # | Resource | Specification | USD / month | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **PostgreSQL Flexible Server primary `psql-aron-prod`** (Database) | GP D8ds_v5 (8 vCore, 32 GiB), zone-redundant HA, Premium SSD v2 1,024 GiB / 12,000 IOPS / 300 MB/s, PITR 35 d, geo-redundant backup | 2,240 | compute 0.976 x 730 x 2 (HA standby billed) = 1,425; storage 1,024 x 0.138 x 2 = 283; IOPS (12,000 - 3,000) x 0.026 x 2 = 468; throughput (300 - 125) x 0.104 x 2 = 36; backup above the free amount 150 GB x 0.19 = 28 | in IaC |
| 2 | **PostgreSQL in-region read replica `psql-aron-prod-r1`** (Database) | same SKU and disk as the primary, zone 3, no HA, own PgBouncer | 1,106 | compute 712; storage 141; IOPS 234; throughput 18 | in IaC (template; second deploy) |
| 3 | **Container App `ca-aron-prod-api`** (Compute) | 1 vCPU / 2 GiB per replica; min 3, max 30; pre-scale 8 at 06:15-10:00 and 16:45-20:00 Dhaka; HTTP rule 50 concurrent | 661 | 3,749 replica-hours a month x (0.1224 + 2 x 0.0144) = 567; retained previous revision 3 replicas idle x 730 h = 95 | in IaC |
| 4 | **Container App `ca-aron-prod-worker`** (Compute) | 1 vCPU / 2 GiB; min 1, max 10 (average 2); outbox, aggregation, photo linking, retention jobs | 221 | 2 replicas x 730 h x 0.1512 = 221 | in IaC (KEDA scale rules designed) |
| 5 | **Container App `ca-aron-prod-web`** (Compute) | 0.5 vCPU / 1 GiB; min 2, max 6 (average 3); Next.js dashboard and portal | 166 | 3 replicas x 730 h x (0.0612 + 0.0144) = 166 | in IaC |
| 6 | **Container Apps Jobs `caj-aron-prod-migrate`, `-dblogins`, export jobs** (Compute) | run on demand; 60 vCPU-hours a month allowance | 15 | 60 h x 0.1512 = 9; requests (18 M - 2 M free) x 0.4 = 6 | in IaC (export jobs designed) |
| 7 | **Container Apps environment `cae-aron-prod`** (Compute) | workload-profiles environment, Consumption profile only, zone-redundant, VNet-injected, public access off, private endpoint for Front Door Private Link | 102 | environment private endpoint 0.14 x 730 = 102; no Dedicated-plan management fee while no Dedicated profile exists | in IaC |
| 8 | **Front Door Premium `afd-aron-prod` + WAF `wafaronprod`** (Edge) | Premium tier (managed WAF rules and Private Link origins), Prevention mode, IP backstop 60,000 per 5 min, origin timeout 60 s; WAF included in Premium | 403 | base 330; requests 18 M / 10,000 x 0.0168 = 30; egress 370 GB x 0.115 = 43 (one APK release of 255 GB included); ingress 5 GB x 0.06 = 0.3 | in IaC |
| 9 | **Storage account `staronprod` (Blob + queue)** (Storage) | StorageV2 Standard_RAGZRS Hot; containers media, bundles, audit-export; lifecycle photos Hot 30 d, Cool 60 d, Cold to 365 d; soft delete 14 d; queue media-events | 425 | month-12 data: Hot 680 GB x 0.06125 = 42, Cool 1,350 GB x 0.0309 = 42, Cold 6,200 GB x 0.0119 = 74; 4.5 M photo writes x 0.0731/10K = 33; lifecycle moves 4.5 M to Cool (45) and 4.5 M to Cold (171); reads 0.4; geo-replication 675 GB x 0.02 = 14; queue 5 | in IaC; month-1 figure is about USD 60 and grows with the photo archive |
| 10 | **Container Registry `craronprod`** (Registry) | Premium (zone-redundant, 500 GiB included, content trust, private link ready); untagged retention 30 d | 51 | 1.6666 x 30.42 days = 51 | in IaC |
| 11 | **Key Vault `kv-aron-prod` (+ signing vault)** (Security) | Standard, RBAC, soft delete 90 d, purge protection on; secrets by Container Apps references; EC P-256 signing key, Argon2 pepper, SAS delegation | 7 | 2 M operations x 0.03/10K = 6; second vault about 1 | in IaC (second vault designed) |
| 12 | **Log Analytics `log-aron-prod` + Application Insights `appi-aron-prod` + alerts** (Observability) | PerGB2018, 90-day retention, 8 GB/day cap (design 5.5 GB/day: request logs, platform diagnostics, Java agent traces); 5 log-search alerts, about 20 metric time series, workbook, action group | 552 | ingestion 5.5 GB x 30.42 = 167 GB x 2.99 = 500; retention 59 extra days = 42; alerts 9.5 | in IaC |
| 13 | **Event Grid system topic `evgt-aron-prod-storage`** (Integration) | BlobCreated on media/ to the queue media-events, 30 retries, 24 h TTL | 3 | (4.5 M - 0.1 M free) x 0.6 = 2.6 | in IaC |
| 14 | **VNet `vnet-aron-prod`, NSGs, private DNS zone, private endpoints** (Network) | 10.40.0.0/16; snet-aca /23; snet-pg /28 with NSG; private DNS for PostgreSQL; 3 private endpoints (Key Vault, ACR, Blob) planned | 26 | VNet and NSGs free; private DNS zone 0.5 + queries 0.4; inter-zone traffic 300 GB x 0.01 = 3; 3 private endpoints x 0.01 x 730 = 22 | VNet in IaC; private endpoints planned |
| 15 | **Budget `budget-aron-prod`, Cost Management, managed identities, Entra ID, tags** (Governance) | budget USD 7,000 with alerts at 50/90/100 percent actual and 100 percent forecast; four user-assigned identities; GitHub OIDC federation | 0 | no charge | in IaC |

**Core total: about USD 5,978 a month** (list prices, 730-hour month, Southeast Asia).

## Disaster recovery (designed, built before wave 2)

| # | Resource | Specification | USD / month | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **PostgreSQL cross-region replica, East Asia** (DR) | D8ds_v5 (same SKU as the primary so WAL replay keeps up), SSD v2 1,024 GiB / 12,000 IOPS / 300 MB/s, async; promoted only in a region outage | 1,240 | compute 1.076 x 730 = 785; storage 1,024 x 0.15 = 154; IOPS 9,000 x 0.031 = 279; throughput 175 x 0.124 = 22 | designed (D-127, D-420, D-522) |
| 2 | **Inter-region replication traffic** (DR) | WAL stream Southeast Asia to East Asia | 15 | 6.3 GB a day x 30.42 x 0.08 = 15 | designed |
| 3 | **Container Apps environment East Asia (warm, apps at min 0)** (DR) | zone-redundant VNet-injected environment, api/worker/web at 0 replicas until promotion; weekly DR smoke | 133 | environment private endpoint 0.155 x 730 = 113; smoke runs about 20 | designed |
| 4 | **ACR geo-replica East Asia, DR Key Vault, DR Front Door origin (disabled)** (DR) | image pulls and secrets survive a Southeast Asia outage | 52 | replica 1.6666 x 30.42 = 51; vault about 1; Front Door origin no charge | designed |

**DR total: about USD 1,440 a month. Core + DR: about USD 7,418.**

## Optional and non-production lines

| # | Resource | Specification | USD / month | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **BI path: Microsoft Fabric F2 + 10 Power BI Pro licences (D-560)** (Optional) | F2 = 2 CU pay-as-you-go; VNet data gateway; archived partitions in the lakehouse | 461 | 2 CU x 0.22 x 730 = 321; 10 x 14 = 140 | deferred until the BI need is confirmed; pausing the capacity stops the charge |
| 2 | **Azure Managed Redis Balanced B3** (Optional) | replay cache, per-device rate limits, config version fan-out | 59 | 0.081 x 730 = 59 | not in the as-built design (D24-20); reinstate only if the load test shows the per-replica limiters are not enough |
| 3 | **Microsoft Defender for Cloud (PostgreSQL x 3 servers, Key Vault, Storage)** (Optional) | threat detection on the data stores | 60 | 3 x 15 + about 15 | subscription-level choice by the final-account owner |
| 4 | **Staging environment in the final account** (Optional) | D2ds_v5 HA (D-06 ladder), small Container Apps, Standard Front Door; scaled to the prod shape only inside a test window | 650 | about 650 steady (docs/18 s3.3 non-prod); about 75 per 8-hour window at prod size | needed for the 8,500-user proof and every release gate |

**Optional total if all are taken: about USD 1,230 a month.**

## One-off costs

| # | Resource | Specification | USD / month | How it is computed | Status |
|---|---|---|---:|---|---|
| 1 | **Load-test windows S1-S10 with Azure Load Testing** (One-off) | one full-fleet hour = 12,750 virtual users x 1 h = 12,750 VUH | 1,912 | 12,750 x 0.15 = 1,912 per full-size run; plan 3 to 5 runs | alternative: self-hosted Locust on Container Apps Jobs, about USD 20 per run |

## Prices that are not in the Retail Prices API (estimates to confirm on the first invoice)

| Key | Value | Unit | Source |
|---|---:|---|---|
| `pg_backup_geo_gb` | 0.19 | per GB-month, geo-redundant backup | ESTIMATE: 2 x LRS; the API lists no Flexible Server GRS meter in southeastasia |
| `blob_hot_write_10k` | 0.0731 | per 10,000 write operations, Hot GZRS | ESTIMATE from the API's 'GZRS List and Create Container' meter (0.07308); the write meter is not listed separately |
| `blob_cool_write_10k` | 0.1 | per 10,000 write operations, Cool | ESTIMATE (Azure prices Cool writes at about 1.4 x Hot ZRS writes 0.0625) |
| `blob_geo_egress_gb` | 0.02 | per GB replicated to the secondary region | ESTIMATE: pricing page, not in the API |
| `private_endpoint_h` | 0.01 | per private endpoint per hour | ESTIMATE: pricing page (Private Link), not in the API |
| `pbi_pro_user` | 14.0 | Power BI Pro per user per month | ESTIMATE: Microsoft 365 list price, not in the API |
| `alt_vuh` | 0.15 | Azure Load Testing per virtual-user-hour after 50 free | ESTIMATE: pricing page, not in the API |

## Core total by group

| Group | USD / month | Share |
|---|---:|---:|
| Database | 3,346 | 56 % |
| Compute | 1,165 | 19 % |
| Observability | 552 | 9 % |
| Storage | 425 | 7 % |
| Edge | 403 | 7 % |
| Registry | 51 | 1 % |
| Network | 26 | 0 % |
| Security | 7 | 0 % |
| Integration | 3 | 0 % |
| Governance | 0 | 0 % |

## Reservation lever (PostgreSQL compute, Southeast Asia, 24 vCores)

| Term | USD / month for 24 vCores | Saving a month | Saving a year | Rule |
|---|---:|---:|---:|---|
| Pay as you go | 2,137 | - | - | the figures in the tables above |
| 1-year reserved (641 per vCore) | 1,282 | 855 (40 %) | 10,265 | buy only after 30 stable days at D8 (D-426) |
| 3-year reserved (1,282 per vCore) | 855 | 1,283 (60 %) | 15,393 | only once the SKU is confirmed by the load test and one year of running |

## Lines that grow with time

| Month of full-fleet running | Photo archive (Blob) USD / month | Database disk | Note |
|---|---:|---|---|
| 1 | 93 | 1,024 GiB | Hot 680 GB, Cool 0 GB, Cold 0 GB |
| 3 | 180 | 1,024 GiB | Hot 680 GB, Cool 1,350 GB, Cold 0 GB |
| 6 | 376 | grow to 2,048 GiB (+USD 283 a month for the primary with HA, +141 per replica) | Hot 680 GB, Cool 1,350 GB, Cold 2,050 GB |
| 12 | 425 | 2,048 GiB | Hot 680 GB, Cool 1,350 GB, Cold 6,200 GB |
| 24 | 425 | 2,048 GiB; photos older than 365 days to Archive (0.004 per GB) | Hot 680 GB, Cool 1,350 GB, Cold 6,200 GB |

## Cost levers, in the order to pull them

| Lever | Saving a month | Condition |
|---|---:|---|
| 1-year reservation on 24 PostgreSQL vCores | about 855 | after 30 stable days at D8 (D-426); 3-year saves about 1,280 |
| Pause the BI capacity when not in use | up to 321 | Fabric capacity bills by the hour |
| Keep managed WAF rules in Log mode no longer than one wave | 0 (risk, not money) | Prevention is the target |
| Log sampling at 50 % on traces | about 150 | only if the real ingestion exceeds 5.5 GB a day |
| Photos: upload straight to Cool, skip Cold until day 180 | about 100 | if photos are rarely viewed after 30 days (measure in the pilot) |
| Stop staging outside release gates and test windows | about 400 | staging is stoppable; prod is not |
| APK releases via Front Door caching | about 25 per release | 255 GB per full-fleet release at 0.115 per GB without caching |

## What is not in these figures

Google Maps Platform (per-key, owner's Google project, on-demand geocoding only, budget alert), Firebase Cloud Messaging (free at this volume), Google Play (optional), SMS or voice paging for alerts, a custom domain's registrar fee (Azure DNS zone about 0.5 a month), the test-account dev environment (about USD 15 a day today, stoppable), people, support plans (Azure Standard support is USD 100 a month if wanted), taxes, and any enterprise-agreement discount.

## Budget and governance

Budget `budget-aron-prod` is created by the templates at USD 7,000 a month with alerts at 50, 90 and 100 percent actual and 100 percent forecast; raise it to about 9,000 when the DR tier is built. A daily cost report (`cost-report.yml`) reads the month-to-date spend. Every resource is tagged `project`, `environment`, `owner`, `managed-by`. The Log Analytics daily cap and the Google Maps key budget alert are separate controls.
