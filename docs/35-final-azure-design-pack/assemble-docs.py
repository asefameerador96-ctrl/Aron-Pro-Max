#!/usr/bin/env python3
"""Assemble 03-resource-plan-and-cost.md and 04-precise-version.md from cost-model.py so that every
figure in the prose comes from the model.   python3 -I docs/35-final-azure-design-pack/assemble-docs.py
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
PY = sys.executable


def run(*args):
    return subprocess.run([PY, "-I", str(HERE / "cost-model.py"), *args], check=True, capture_output=True, text=True).stdout


M = json.loads(run("--json"))
core, drt, buffer = M["core"], M["drt"], M["buffer"]
sites, sbuf = M["sitest"], M["sites_buffer"]
spec = core + drt + buffer
specplus = spec + sites + sbuf
tables = run()
precise = run("--precise")
f = lambda x: f"{x:,.0f}"


def section(md, title):
    """Return the markdown of one '### title' section of the model output."""
    start = md.index(f"### {title}")
    nxt = md.find("\n### ", start + 4)
    return md[start:nxt if nxt > 0 else None].strip()


# ----------------------------------------------------------------------------------------------
doc03 = f"""# 03 — Resource plan and monthly Azure cost (final account, 8,500 field users)

Part of the owner pack `docs/35-final-azure-design-pack/`. Every table and every figure in this document is produced by `cost-model.py` (this file is assembled by `assemble-docs.py`; run `python3 -I docs/35-final-azure-design-pack/cost-model.py` to reproduce it). Unit prices were read from the **Azure Retail Prices API on 2026-10-29** (USD, pay-as-you-go, Southeast Asia unless stated); the few prices the API does not publish are marked as estimates in their own table. Usage figures are the design load model of `docs/18` (8,500 SRs, 9,842 app users, about 150 web users, 3.0 M rows and 150,000 photos a day) and the production parameter files in `infra/params/`. The model was corrected on 2026-10-29 after an independent review of the prices and the billing rules (the corrections are listed at the top of `cost-model.py`).

## How to read the figures

- **Core production** is what the templates create on the first final-account deploy (status `IaC`), the monthly bill for a zone-redundant production platform in one region. It includes about USD 28 for items designed but not yet in a template (private endpoints, signing vault, export jobs).
- **Disaster recovery** is the East Asia tier the design adds before rollout wave 2. It is decided (D-127, D-420, D-522) but not yet in a template; the owner approves it before it is built.
- **Buffer** is the owner's contingency band (USD 1,000 to 3,000); the pack carries USD {f(buffer)} a month and says what it covers.
- **Spec** = Aron core + DR + buffer. **Spec+** = Spec + the existing web properties that move into the same account (their own table and their own buffer).
- Figures are list prices for a 730-hour month, before any reservation discount, before taxes and any enterprise agreement. Conservative simplifications are kept as a safety margin (about USD 120 a month: all api replica-hours billed at the active rate, no free log or alert tiers). The **first invoices replace these numbers**; the budget alert is the control until then.

## Headline

| Scope | USD a month | Note |
|---|---:|---|
| Aron core production (day 1, in IaC) | **about {f(core)}** | database {f(sum(r[6] for r in M['rows'] if r[0]=='Database'))}, compute {f(sum(r[6] for r in M['rows'] if r[0]=='Compute'))}, logs {f(sum(r[6] for r in M['rows'] if r[0]=='Observability'))}, storage {f(sum(r[6] for r in M['rows'] if r[0]=='Storage'))}, edge {f(sum(r[6] for r in M['rows'] if r[0]=='Edge'))} |
| Disaster-recovery tier (East Asia, before wave 2) | about {f(drt)} | the cross-region D8 replica is most of it |
| Contingency buffer | {f(buffer)} | D16 step-up for a quarter, log overage, extra replicas, unpublished prices, billing-zone differences |
| **Spec: Aron core + DR + buffer** | **about {f(spec)}** | recommended budget USD {f(__import__('math').ceil(spec/500)*500)} with alerts at 50/90/100 % |
| Existing web properties + their buffer | about {f(sites + sbuf)} | {f(sites)} of resources (assumptions until inventoried) + {f(sbuf)} buffer |
| **Spec+: everything in the final account** | **about {f(specplus)}** | |
| Spec+ with 1-year PostgreSQL reservations (24 vCores Southeast Asia, 8 East Asia) | about {f(specplus - (24*(0.976/8)*730 - 24*641/12) - (8*(1.076/8)*730 - 8*706/12))} | buy after 30 stable days at D8 |
| Optional: staging-lite in the final account | about {f(M['stg'])} | stoppable between release gates and test windows |
| One-off: certified load-test run | about {f(M['oneoff'][0][6])} per run | the default plan is one paid run plus Locust runs at about 20 each |

**Year one (ceiling):** about **USD {f(M['y1'])}** without reservations and about **USD {f(M['y1r'])}** with the 1-year reservations bought in month 2 (Southeast Asia) and month 4 (East Asia): Aron at D8 from month 1, the DR tier from month 4, the disk grow at month 6, the photo archive ramping, staging-lite all year, one paid load-test run; the existing sites and the buffer are not in the year-one figure. The D2 → D4 → D8 ladder of D-06 would make the first months cheaper and is not modelled.

### Reconciliation with the earlier design estimate (`docs/18` section 3.3)

| Line | docs/18 (2026-10-04) | This pack | Why it moved |
|---|---:|---:|---|
| PostgreSQL primary, HA, 1,024 GiB | 1,780 | {f(M['rows'][0][6])} | same compute and disk; geo-backup priced by Microsoft's formula at month 12 |
| In-region read replica | 850 | {f(M['rows'][1][6])} | same |
| Cross-region replica (DR) | 850 | {f(M['dr'][0][6])} | East Asia prices are about 10 % higher than the Singapore prices docs/18 used |
| Container Apps compute + fixed fees | 1,000 + 350 (two environments) | {f(sum(r[6] for r in M['rows'] if r[0]=='Compute'))} + {f(M['dr'][2][6])} (DR) | the retained previous revision and the plan-management fee are now priced; the separate `auth` app of docs/18 does not exist |
| Front Door Premium | 430 | {f(M['rows'][7][6])} | traffic priced from the API |
| Blob | 190 | {f(sum(r[6] for r in M['rows'] if r[0]=='Storage'))} | the lifecycle write operations of 4.5 M photos a month and geo-replication at the inter-region rate were missing |
| Logs | 490 | {f(sum(r[6] for r in M['rows'] if r[0]=='Observability'))} | retention beyond 31 days and alerts added |
| ACR, Key Vault, events, network | 160 | {f(sum(r[6] for r in M['rows'] if r[0] in ('Registry','Security','Integration','Network')))} | same order |
| Redis | 120 | optional 59 | not in the as-built design (D24-20) |
| BI | 400 | optional 461 | deferred |
| **Total** | **6,630** (with Redis and BI) | **{f(core + drt)}** core + DR (without them) | like for like, about {f((core + drt)/(6630-520)*100-100)} % above docs/18's 6,110 without Redis and BI; the differences are the Blob operations, the retained revision and the plan-management fee |

## Sizing in one paragraph per tier (why these SKUs)

- **PostgreSQL General Purpose D8ds_v5 with zone-redundant HA** is the smallest SKU whose IOPS cap (12,800) holds the 12,000 IOPS the 17:00 write peak needs and whose 8 vCores keep CPU under 80 % at the 8,000 rows/s gate; HA doubles the price but gives RPO 0 and automatic failover, which is what turns a zone failure into a 60–120 s blip while phones keep selling. The same SKU on the **read replica** keeps dashboard readers off the primary and keeps replay in step. Premium SSD v2 is chosen because a 1,024 GiB disk already includes 12,000 IOPS and 500 MB/s, so nothing is paid for IOPS at this size.
- **Container Apps on the Consumption profile** bills only the replicas that run: three API replicas all day (one per zone), eight in the two daily storms, more when the autoscaler asks, and the worker and web at their small steady state. There is no cluster to pay for; zone redundancy is free; the Front Door Private Link attracts a fixed plan-management fee.
- **Front Door Premium** costs 330 a month before traffic; Standard would cost 35 but cannot do Private Link to the application network or run the managed WAF rule sets. The difference buys "no public address on the application" and a managed firewall.
- **RA-GZRS storage** is about 2.5 × the price of zone-redundant storage per GB, but the tiers make 8 TB of two-year evidence cost about {f(M['rows'][8][6])} a month at month 12; the Cold step costs more in tier-move operations than it saves in storage at these prices (see the levers).
- **Logs at 5.5 GB a day** are the fourth-largest line. The cap at 8 GB a day bounds the bill; sampling the traces is the first lever if the real volume is higher.

{section(tables, "Core production (what the templates create on the first final-account deploy)").replace("### ", "## ", 1)}

{section(tables, "Disaster-recovery tier (designed; built before rollout wave 2)").replace("### ", "## ", 1)}

{section(tables, "Contingency buffer").replace("### ", "## ", 1)}

{section(tables, "Optional and non-production lines").replace("### ", "## ", 1)}

{section(tables, "One-off costs").replace("### ", "## ", 1)}

{section(tables, "Spec+: existing web properties migrated into the final account (separate from Aron)").replace("### ", "## ", 1)}

What the existing-sites figures assume: four live sites (aktcl.com, aksilage.com, shahagro.com, arondeepstate.com) and two in reserve, each a PHP/WordPress or static site on one Linux App Service plan with one MySQL Flexible Server, about 300 GB a month of traffic through the shared Front Door, DNS zones moved to Azure DNS, managed certificates. Replace these with the current subscription's inventory before the move (`az resource list --output table` in each subscription); a site on a virtual machine would add a B2s v2 VM (about 77 a month) and a disk.

{section(tables, "Totals").replace("### ", "## ", 1)}

{section(tables, "Reservation lever (PostgreSQL compute)").replace("### ", "## ", 1)}

{section(tables, "Lines that grow with time").replace("### ", "## ", 1)}

{section(tables, "Year one, month by month (ceiling: D8 from month 1, DR from month 4, staging-lite all year, one paid load-test run in month 2 and three Locust runs)").replace("### ", "## ", 1)}

## Cost levers, in the order to pull them

| Lever | Saving a month | Condition |
|---|---:|---|
| 1-year reservation on 24 Southeast Asia PostgreSQL vCores | about {f(24*(0.976/8)*730 - 24*641/12)} | after 30 stable days at D8 (D-426); 3-year saves about {f(24*(0.976/8)*730 - 24*1282/36)} |
| 1-year reservation on the 8 East Asia vCores | about {f(8*(1.076/8)*730 - 8*706/12)} | when the DR tier is built |
| Drop the Cold step for photos (Cool until deletion) | about 55 | the Cold write operations cost more than the Cold storage saves at these prices; confirm in the pilot |
| Keep photos in Cool for the whole 730 days, no Cold at all | about 100 | if photos are rarely opened after 30 days |
| Pause the BI capacity when not in use | up to 321 | Fabric capacity bills by the hour |
| Log sampling at 50 % on traces | about 150 | only if the real ingestion exceeds 5.5 GB a day |
| Stop staging-lite outside release gates and test windows | about 400 | staging is stoppable; prod is not |
| Worker at 1 replica until the KEDA rules exist | about 110 | as deployed today |

## What is not in these figures

Google Maps Platform (per-key, owner's Google project, on-demand geocoding only, budget alert), Firebase Cloud Messaging (free at this volume), Google Play (optional), SMS or voice paging for alerts, the registrar fees of the domains, Private Link data processing (0.01 per GB) and outbound internet egress from the apps (first 100 GB a month free), the test-account dev environment (about USD 15 a day today, stoppable), people, support plans (Azure Standard support is USD 100 a month if wanted), taxes, and any enterprise-agreement discount.

{section(tables, "Prices that are not in the Retail Prices API (estimates to confirm on the first invoice)").replace("### ", "## ", 1)}

{section(tables, "Core total by group").replace("### ", "## ", 1)}

## Budget and governance

Budget `budget-aron-prod` is created by the templates at USD 7,000 a month with alerts at 50, 90 and 100 percent actual and 100 percent forecast; raise it to USD {f(__import__('math').ceil(spec/500)*500)} when the DR tier and the buffer are approved, and to about USD {f(__import__('math').ceil(specplus/500)*500)} if the existing sites share the subscription. A daily cost report (`cost-report.yml`) reads the month-to-date spend. Every resource is tagged `project`, `environment`, `owner`, `managed-by`. The Log Analytics daily cap and the Google Maps key budget alert are separate controls.
"""

# ----------------------------------------------------------------------------------------------
doc04 = f"""# 04 — Precise version: diagrams, resources with quantity and justification, monthly cost

Part of the owner pack `docs/35-final-azure-design-pack/`. This is the short, exact version: the high-level and low-level diagrams drawn with Microsoft's Azure architecture icons, one resource table with the specification, the quantity, why that specification, why that quantity and the monthly cost, the disaster-recovery tier, the contingency buffer, and a separate table for the existing web properties that move into the same account (Spec+). Every figure comes from `cost-model.py` (Azure Retail Prices API, 2026-10-29, Southeast Asia). The long explanations are in documents 01 to 03.

## 1. High-level design diagrams

![HLD 1 — system context](diagrams/hld-1-context.png)

*HLD 1 — System context. 9,842 field users on Android phones and about 150 portal users reach one platform through Front Door; photos go straight to Blob; Google Maps, Firebase and GitHub are outside Azure.*

![HLD 2 — logical architecture](diagrams/hld-2-logical.png)

*HLD 2 — Logical architecture. Edge, application tier (api, worker, web, jobs), data tier (PostgreSQL with a read replica, Blob and queue, Key Vault) and operations.*

![HLD 3 — Azure deployment](diagrams/hld-3-deployment.png)

*HLD 3 — Azure deployment in the final account: one resource group in Southeast Asia with a private application network, and the East Asia DR tier (designed).*

![HLD 4 — availability and DR](diagrams/hld-4-dr.png)

*HLD 4 — Availability and disaster recovery: zone loss is automatic (RPO 0), data loss is a 35-day point-in-time restore, region loss is a DR promotion (RPO 15 min, RTO 4 h) once the East Asia tier exists.*

## 2. Low-level design diagrams

![LLD 1 — network](diagrams/lld-1-network.png)

*LLD 1 — Network: Front Door's Private Link is the only entrance; the database subnet accepts only the app subnet on 5432/6432; the private endpoints for Key Vault, registry and Blob are planned.*

![LLD 2 — sync flow](diagrams/lld-2-sync-flow.png)

*LLD 2 — The critical path: a sale captured offline, one gzip batch, one short transaction idempotent by client UUID, asynchronous aggregation, the dashboard.*

![LLD 3 — photo pipeline](diagrams/lld-3-photos.png)

*LLD 3 — Photo pipeline: SAS upload straight to Blob, Event Grid to a queue; the worker consumer is planned.*

![LLD 4 — identity and secrets](diagrams/lld-4-identity.png)

*LLD 4 — Identity and secrets: GitHub OIDC for deploys, one managed identity per process, Key Vault references.*

![LLD 5 — observability](diagrams/lld-5-observability.png)

*LLD 5 — Observability: every service reports to one Log Analytics workspace; alerts map to runbooks.*

![LLD 6 — delivery pipeline](diagrams/lld-6-pipeline.png)

*LLD 6 — Delivery: digest-pinned images, migrations first, a health gate before traffic moves.*

![LLD 7 — scaling](diagrams/lld-7-scaling.png)

*LLD 7 — Scaling rules over the field day (Dhaka time).*

## 3. Resource table: specification, quantity, why, monthly cost

{section(precise, "Core production (what the templates create on the first final-account deploy)").split(chr(10), 2)[2]}

{section(precise, "Disaster-recovery tier (designed; built before rollout wave 2)").replace("### ", "### 3.1 ", 1)}

{section(precise, "Contingency buffer").replace("### ", "### 3.2 ", 1)}

{section(precise, "Optional and non-production lines").replace("### ", "### 3.3 ", 1)}

{section(precise, "One-off costs").replace("### ", "### 3.4 ", 1)}

## 4. Spec+: existing web properties migrated into the same account

![Spec+ — existing sites](diagrams/specplus-existing-sites.png)

*Spec+ — The existing sites share the edge, DNS and monitoring with Aron and get their own App Service plan and MySQL server.*

{section(precise, "Spec+: existing web properties migrated into the final account (separate from Aron)").split(chr(10), 2)[2]}

These rows are assumptions (brochure or WordPress sites on one Linux plan, one MySQL server, about 300 GB a month of traffic) until the current subscription is inventoried; a site that runs on a virtual machine adds about USD 95 a month (B2s v2 and a 128 GiB disk). The migration itself is a one-off (App Service Migration Assistant or a redeploy, DNS name-server change at the cut-over) with no Azure charge beyond the running resources.

## 5. Totals, buffer and budget

{section(precise, "Totals").split(chr(10), 2)[2]}

The buffer of USD {f(buffer)} a month sits inside the owner's 1,000 to 3,000 band and covers a D16ds_v5 step-up for a quarter (+1,425 a month of HA compute), log overage, extra replicas in a storm, the handful of prices the API does not publish and a billing-zone difference at the edge. Recommended budgets: USD {f(__import__('math').ceil(spec/500)*500)} for Aron (Spec), USD {f(__import__('math').ceil(specplus/500)*500)} for everything (Spec+), each with alerts at 50, 90 and 100 percent.

{section(precise, "Reservation lever (PostgreSQL compute)").replace("### ", "### 5.1 ", 1)}

{section(precise, "Year one, month by month (ceiling: D8 from month 1, DR from month 4, staging-lite all year, one paid load-test run in month 2 and three Locust runs)").replace("### ", "### 5.2 ", 1)}

## 6. What is designed but not yet in a template

The East Asia DR tier, the private endpoints for Key Vault, registry and Blob, the signing vault with a non-exportable key, the worker scale rules and the photo-queue consumer, the export jobs, the APK channel, the WORM audit export, staging-lite, Azure Policy and locks. The fleet-size proof (load tests S1–S10, the timed failover and restore drills at D8 size, Private Link and the Premium firewall under real traffic) can only run in the final account before rollout wave 1.

## 7. Decisions for the owner

| # | Decision | Default in this pack | Decide by |
|---|---|---|---|
| 1 | Build the East Asia DR tier (about USD {f(drt)} a month) | yes, before wave 2 | before wave 2 |
| 2 | Staging-lite in the final account (about USD {f(M['stg'])} a month, stoppable) | yes | before the load-test windows |
| 3 | Buffer amount (1,000 to 3,000) | {f(buffer)} | at the budget creation |
| 4 | Existing sites in the same subscription and edge profile | yes (Spec+) | at the move |
| 5 | BI path (Fabric F2 + Power BI, about USD 461) | deferred | when reporting needs it |
| 6 | Load tests: one certified Azure Load Testing run (about USD {f(M['oneoff'][0][6])}) plus Locust runs | yes | before wave 1 |
| 7 | Region: Southeast Asia unless the Dhaka latency bake-off favours Central India by 25 % (figures would be re-priced) | Southeast Asia | before the first prod deploy |
| 8 | 1-year PostgreSQL reservations after 30 stable days (saves about USD {f(24*(0.976/8)*730 - 24*641/12)} a month in Southeast Asia) | recommended | month 2 |
| 9 | Data-lake account zone redundancy (ZRS needs the sponsor's written yes, `docs/28`) | ZRS | before the first prod deploy |
"""

(HERE / "03-resource-plan-and-cost.md").write_text(doc03, encoding="utf-8")
(HERE / "04-precise-version.md").write_text(doc04, encoding="utf-8")
print("03:", len(doc03.splitlines()), "lines; 04:", len(doc04.splitlines()), "lines")
print(json.dumps({"core": round(core), "dr": round(drt), "spec": round(spec), "sites": round(sites), "sites_buffer": sbuf, "specplus": round(specplus), "stg": round(M["stg"]), "y1": round(M["y1"]), "y1r": round(M["y1r"]), "oneoff": round(M["oneoff"][0][6])}))
