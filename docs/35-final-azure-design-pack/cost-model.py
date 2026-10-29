#!/usr/bin/env python3
"""Aron final-account monthly cost model (owner pack, docs/35), version 2 (2026-10-29, after review).

Run:  python3 -I docs/35-final-azure-design-pack/cost-model.py            # cost tables (document 03)
      python3 -I docs/35-final-azure-design-pack/cost-model.py --precise  # spec / quantity / why / cost (document 04)
      python3 -I docs/35-final-azure-design-pack/cost-model.py --json

Unit prices were read from the Azure Retail Prices API (https://prices.azure.com/api/retail/prices,
USD, pay-as-you-go, armRegionName southeastasia unless stated) on 2026-10-29. A price the API does not
publish is marked ESTIMATE and appears in the "estimates" table. Usage comes from the design load model
in docs/18 s1 and the production parameter files infra/params/prod*.bicepparam. Billing rules that are
not prices (free baselines, what HA doubles) are marked RULE with their source. Nothing here is an
invoice; the first real month in the final account replaces these numbers.

Review corrections folded in (2026-10-29): Premium SSD v2 includes 12,000 IOPS and 500 MB/s with a disk
of 400 GiB or more (so the provisioned IOPS and throughput cost nothing here); Blob write meters from
the API (0.1125 Hot, 0.20 Cool per 10K); Blob geo-replication at the inter-region rate; the Container
Apps Dedicated Plan Management charge that a private endpoint attracts; no Archive tier on RA-GZRS
(photos are deleted at 730 days as the template says); the geo-backup formula; pre-scale 7.0 h a day;
HTTP requests billed on the api, not on jobs; the data-lake account; year one computed month by month.
"""
import json
import math
import sys

HOURS = 730.0
DAYS = HOURS / 24.0

# ----------------------------------------------------------------------------------------------
# Unit prices: key -> (value, unit, source)
# ----------------------------------------------------------------------------------------------
P = {
    "pg_d8_sea_h": (0.976, "per hour, 8 vCores (0.122 per vCore-hour)", "API 2026-10-29"),
    "pg_d16_sea_h": (1.952, "per hour, 16 vCores", "API 2026-10-29"),
    "pg_d2_sea_h": (0.244, "per hour, 2 vCores", "API 2026-10-29"),
    "pg_d8_ea_h": (1.076, "per hour, 8 vCores, eastasia", "API 2026-10-29"),
    "pg_res_1y_sea": (641.0, "per vCore, 1-year reservation, southeastasia", "API 2026-10-29 (priceType Reservation)"),
    "pg_res_3y_sea": (1282.0, "per vCore, 3-year reservation", "API 2026-10-29"),
    "pg_res_1y_ea": (706.0, "per vCore, 1-year reservation, eastasia", "API 2026-10-29"),
    "pg_ssdv2_gib_sea": (0.138, "per GiB-month", "API 2026-10-29"),
    "pg_ssdv2_iops_sea": (0.026, "per IOPS-month above the included amount", "API 2026-10-29"),
    "pg_ssdv2_mbps_sea": (0.104, "per MB/s-month above the included amount", "API 2026-10-29"),
    "pg_ssdv2_gib_ea": (0.15, "per GiB-month, eastasia", "API 2026-10-29"),
    "pg_ssdv2_iops_ea": (0.031, "per IOPS-month above the included amount, eastasia", "API 2026-10-29"),
    "pg_ssdv2_mbps_ea": (0.124, "per MB/s-month above the included amount, eastasia", "API 2026-10-29"),
    "pg_backup_gb": (0.095, "per GB-month of backup storage above the free amount", "API 2026-10-29"),
    "aca_vcpu_active_h": (0.1224, "per vCPU-hour active", "API 2026-10-29"),
    "aca_mem_active_h": (0.0144, "per GiB-hour active", "API 2026-10-29"),
    "aca_vcpu_idle_h": (0.0144, "per vCPU-hour idle", "API 2026-10-29"),
    "aca_mem_idle_h": (0.0144, "per GiB-hour idle", "API 2026-10-29"),
    "aca_requests_m": (0.4, "per million HTTP requests after the first 2 million", "API 2026-10-29"),
    "aca_env_pe_sea_h": (0.14, "environment private endpoint, per hour", "API 2026-10-29"),
    "aca_env_pe_ea_h": (0.155, "environment private endpoint, per hour, eastasia", "API 2026-10-29"),
    "aca_plan_mgmt_h": (0.10, "Dedicated Plan Management, per hour (charged when the environment has a private endpoint)", "API 2026-10-29; RULE per Learn 'Billing in Azure Container Apps'"),
    "afd_premium_base": (330.0, "per month", "API 2026-10-29"),
    "afd_premium_req_10k": (0.0168, "per 10,000 requests, first tier (same in zones 2 and 5)", "API 2026-10-29"),
    "afd_dto_z2_gb": (0.115, "per GB edge to client, zone 2 (zone 5 is 0.109)", "API 2026-10-29; the zone is that of the serving edge"),
    "afd_dti_z2_gb": (0.06, "per GB edge to origin, zone 2 (zone 5 is 0.16)", "API 2026-10-29"),
    "afd_standard_base": (35.0, "per month, Standard tier (staging-lite)", "API 2026-10-29"),
    "blob_hot_ragzrs_gb": (0.06125, "per GB-month, first 50 TB", "API 2026-10-29"),
    "blob_cool_ragzrs_gb": (0.0309, "per GB-month", "API 2026-10-29"),
    "blob_cold_ragzrs_gb": (0.0119, "per GB-month", "API 2026-10-29"),
    "blob_hot_write_10k": (0.1125, "per 10,000 write operations, Hot GZRS", "API 2026-10-29 (ADLS Gen2 flat-namespace meter, same as block blob)"),
    "blob_cool_write_10k": (0.20, "per 10,000 write operations, Cool GZRS", "API 2026-10-29 (ADLS Gen2 flat-namespace meter)"),
    "blob_cold_write_10k": (0.38, "per 10,000 write operations, Cold RA-GZRS", "API 2026-10-29"),
    "blob_hot_read_10k": (0.004, "per 10,000 read operations", "API 2026-10-29"),
    "blob_zrs_gb": (0.028, "per GB-month, Standard ZRS (data lake)", "API 2026-10-29"),
    "blob_lrs_gb": (0.0184, "per GB-month, Hot LRS (existing sites)", "API 2026-10-29"),
    "inter_region_gb": (0.08, "per GB between regions (WAL stream, Blob geo-replication)", "API 2026-10-29"),
    "queue_ops_10k": (0.004, "per 10,000 queue operations", "API 2026-10-29 (Queues v2 ZRS meter; RA-GZRS not listed separately)"),
    "acr_premium_day": (1.6666, "per day, Premium registry", "API 2026-10-29"),
    "acr_standard_day": (0.6666, "per day, Standard registry (staging-lite)", "API 2026-10-29"),
    "kv_ops_10k": (0.03, "per 10,000 operations", "API 2026-10-29"),
    "la_ingest_gb": (2.99, "per GB ingested (first 5 GB a month free, ignored)", "API 2026-10-29"),
    "la_retention_gb": (0.13, "per GB-month retained beyond 31 days", "API 2026-10-29"),
    "alert_log_5min": (1.5, "per log-search alert rule at 5-minute frequency per month", "API 2026-10-29"),
    "alert_metric_ts": (0.1, "per metric time series per month (first 10 free, ignored)", "API 2026-10-29"),
    "eg_ops_m": (0.6, "per million Event Grid operations after the first 100,000", "API 2026-10-29"),
    "dns_zone": (0.5, "per DNS zone per month (public or private)", "ESTIMATE: pricing page; the API's DNS rows carry no region"),
    "dns_queries_m": (0.4, "per million DNS queries", "ESTIMATE: pricing page"),
    "inter_az_gb": (0.01, "per GB between availability zones (announced free; kept as a ceiling)", "API 2026-10-29"),
    "private_endpoint_h": (0.01, "per private endpoint per hour", "ESTIMATE: Private Link pricing page"),
    "redis_b3_h": (0.081, "Azure Managed Redis Balanced B3, per hour", "API 2026-10-29"),
    "fabric_cu_h": (0.22, "Microsoft Fabric capacity, per CU-hour", "API 2026-10-29"),
    "pbi_pro_user": (14.0, "Power BI Pro per user per month", "ESTIMATE: Microsoft 365 list price"),
    "defender_pg_month": (15.0, "Defender for PostgreSQL per server per month", "API 2026-10-29"),
    "alt_vuh": (0.15, "Azure Load Testing per virtual-user-hour after 50 free a month", "ESTIMATE: pricing page"),
    # existing web properties (spec+)
    "asp_p1v3_h": (0.183, "App Service plan Premium v3 P1 (2 vCPU, 8 GiB), Linux, per hour", "API 2026-10-29"),
    "asp_p0v3_h": (0.0915, "App Service plan Premium v3 P0 (1 vCPU, 4 GiB), Linux, per hour", "API 2026-10-29"),
    "mysql_b2s_h": (0.104, "Azure Database for MySQL Flexible Server Burstable B2s, per hour", "API 2026-10-29"),
    "mysql_storage_gb": (0.138, "MySQL Flexible Server storage per GB-month", "API 2026-10-29"),
}


def p(k):
    return P[k][0]


# ----------------------------------------------------------------------------------------------
# Usage (docs/18 s1 design load model at 8,500 SRs; prod parameter files)
# ----------------------------------------------------------------------------------------------
U = {
    "pg_storage_gib": 1024, "pg_iops": 12000, "pg_mbps": 300,
    "pg_db_size_gb_m12": 1000, "pg_wal_35d_gb": 150,          # backup size at month 12 (2.8 GB/day x 365; 35 days of WAL)
    "api_min": 3, "api_prescale": 8, "api_prescale_hours_per_day": 7.0,   # 06:15-10:00 and 16:45-20:00 Dhaka
    "api_burst_replica_hours_per_day": 15,
    "api_vcpu": 1.0, "api_gib": 2.0, "api_retained_revision_replicas": 3,
    "worker_avg_replicas": 2, "worker_vcpu": 1.0, "worker_gib": 2.0,        # deployed: 1 replica, no rule; designed KEDA to 10
    "web_avg_replicas": 3, "web_vcpu": 0.5, "web_gib": 1.0,
    "jobs_vcpu_hours": 60,
    "requests_m": 18.0,
    "afd_egress_gb": 370, "afd_ingress_gb": 5,
    "photos_gb_per_day": 22.5, "blob_writes_m": 4.5, "blob_reads_m": 1.0, "queue_ops_m": 13.5,
    "lake_gb": 100,
    "kv_ops_m": 2.0,
    "la_gb_per_day": 5.5, "la_retention_days": 90,
    "alerts_log": 5, "alerts_metric_ts": 20,
    "eg_events_m": 4.5,
    "inter_az_gb": 300,
    "private_endpoints": 3,
    "dr_wal_gb_per_day": 6.3,
    "buffer": 2000.0,          # owner's contingency band (1,000 to 3,000): D16 step-up, log overage, unpriced lines
    # existing web properties (assumptions until the current subscription is inventoried)
    "sites": 4, "sites_reserve": 2, "sites_egress_gb": 300, "sites_requests_m": 10.0, "sites_la_gb_per_day": 0.5,
    "sites_storage_gb": 100, "mysql_storage_gb": 64, "sites_buffer": 500.0,
}


def ssdv2_included(gib):
    """RULE (Learn, Premium SSD v2 on Flexible Server): 3,000 IOPS and 125 MB/s included below 400 GiB; 12,000 IOPS and 500 MB/s from 400 GiB."""
    return (12000, 500) if gib >= 400 else (3000, 125)


def pg_server(compute_h, gib_price, iops_price, mbps_price, ha, gib=None):
    gib = gib or U["pg_storage_gib"]
    f = 2 if ha else 1
    inc_iops, inc_mbps = ssdv2_included(gib)
    compute = compute_h * HOURS * f
    storage = gib * gib_price * f
    iops = max(U["pg_iops"] - inc_iops, 0) * iops_price * f
    mbps = max(U["pg_mbps"] - inc_mbps, 0) * mbps_price * f
    return compute, storage, iops, mbps


def pg_geo_backup(db_gb, wal_gb, provisioned_gib):
    """RULE (Learn, Flexible Server backup billing): geo-redundant backup bills (2 x backup size) - provisioned storage, at the backup rate."""
    return max(2 * (db_gb + wal_gb) - provisioned_gib, 0) * p("pg_backup_gb")


def api_replica_hours():
    per_day = U["api_min"] * (24 - U["api_prescale_hours_per_day"]) + U["api_prescale"] * U["api_prescale_hours_per_day"] + U["api_burst_replica_hours_per_day"]
    return per_day * DAYS


def active(replica_hours, vcpu, gib):
    return replica_hours * (vcpu * p("aca_vcpu_active_h") + gib * p("aca_mem_active_h"))


def idle(replica_hours, vcpu, gib):
    return replica_hours * (vcpu * p("aca_vcpu_idle_h") + gib * p("aca_mem_idle_h"))


def photos_cost(month):
    """Photo archive at the end of month n of full-fleet running: Hot 30 d, Cool 60 d, Cold until deletion at 730 d (template rule). No Archive tier on RA-GZRS."""
    per_month = U["photos_gb_per_day"] * DAYS
    hot = min(month, 1) * per_month
    cool = min(max(month - 1, 0), 2) * per_month
    cold = min(max(month - 3, 0), 21) * per_month          # 730 - 90 days = 640 days = 21 months in Cold
    stored = hot * p("blob_hot_ragzrs_gb") + cool * p("blob_cool_ragzrs_gb") + cold * p("blob_cold_ragzrs_gb")
    w = U["blob_writes_m"] * 100
    ops = w * p("blob_hot_write_10k") + (w * p("blob_cool_write_10k") if month >= 2 else 0) + (w * p("blob_cold_write_10k") if month >= 4 else 0)
    reads = U["blob_reads_m"] * 100 * p("blob_hot_read_10k")
    geo = per_month * p("inter_region_gb")
    queue = U["queue_ops_m"] * 100 * p("queue_ops_10k")
    return stored + ops + reads + geo + queue, dict(hot=hot, cool=cool, cold=cold, stored=stored, ops=ops, geo=geo, queue=queue)


# ----------------------------------------------------------------------------------------------
# Rows: (group, name, spec, qty, why_spec, why_qty, cost, arithmetic, status)
# ----------------------------------------------------------------------------------------------
def model():
    rows = []
    c, s, i, m = pg_server(p("pg_d8_sea_h"), p("pg_ssdv2_gib_sea"), p("pg_ssdv2_iops_sea"), p("pg_ssdv2_mbps_sea"), ha=True)
    backup = pg_geo_backup(U["pg_db_size_gb_m12"], U["pg_wal_35d_gb"], U["pg_storage_gib"])
    rows.append(("Database", "PostgreSQL Flexible Server primary `psql-aron-prod`",
                 "General Purpose D8ds_v5 (8 vCore, 32 GiB), PostgreSQL 16, zone-redundant HA, Premium SSD v2 1,024 GiB at 12,000 IOPS / 300 MB/s (included with the disk size), PITR 35 days, geo-redundant backup, built-in PgBouncer",
                 "1 server = 2 nodes (primary zone 1 + synchronous standby zone 2)",
                 "D8 is the first SKU whose 12,800 IOPS cap and 8 vCores hold the 17:00 write peak (4,500 rows/s) and the 8,000 rows/s load gate under 80 % CPU; SSD v2 gives 12,000 IOPS free with a 1,024 GiB disk",
                 "One system of record keeps idempotency, money and scope transactional; the standby is what makes a zone loss a 60-120 s blip with zero data loss (RPO 0)",
                 c + s + i + m + backup,
                 f"compute 0.976 x 730 x 2 (HA standby billed) = {c:,.0f}; storage 1,024 x 0.138 x 2 = {s:,.0f}; IOPS and throughput {i + m:,.0f} (12,000 IOPS and 500 MB/s included from 400 GiB); geo backup at month 12: (2 x 1,150 - 1,024) GB x 0.095 = {backup:,.0f} (0 in the first months)",
                 "IaC"))
    c, s, i, m = pg_server(p("pg_d8_sea_h"), p("pg_ssdv2_gib_sea"), p("pg_ssdv2_iops_sea"), p("pg_ssdv2_mbps_sea"), ha=False)
    rows.append(("Database", "PostgreSQL read replica `psql-aron-prod-<s>-r1`",
                 "same SKU and disk as the primary, zone 3, asynchronous, no HA, own PgBouncer",
                 "1",
                 "The same SKU because WAL replay is single-threaded and a smaller replica falls behind at the 17:00 peak",
                 "One replica serves the 150 queries/s read budget with 3x headroom; a second would only isolate BI later",
                 c + s + i + m, f"compute {c:,.0f}; storage {s:,.0f}; IOPS and throughput {i + m:,.0f}", "IaC template; creation path needs a pipeline fix (docs/33 B13.2 rows 36, 37)"))
    api_h = api_replica_hours()
    api = active(api_h, U["api_vcpu"], U["api_gib"])
    retained = idle(U["api_retained_revision_replicas"] * HOURS, U["api_vcpu"], U["api_gib"])
    reqs = max(U["requests_m"] - 2.0, 0) * p("aca_requests_m")
    rows.append(("Compute", "Container App `ca-aron-prod-api`",
                 "1 vCPU / 2 GiB per replica (JVM heap ceiling about 1.5 GiB); HTTP scale rule 50 concurrent requests per replica; cron pre-scale 06:15-10:00 and 16:45-20:00 Dhaka; Multiple revisions; startup, liveness and readiness probes",
                 "3 to 30 replicas (8 pre-scaled in the storms; about 5 on average)",
                 "1 vCPU / 2 GiB is the smallest step that holds a Ktor JVM at 150-300 requests/s; small steps scale finely",
                 "3 = one replica per availability zone; 8 pre-scaled covers the expected 131 requests/s with 1.8x; 30 = 900 requests/s, 2.3x the design-for storm of 400",
                 api + retained + reqs, f"{api_h:,.0f} replica-hours x (0.1224 + 2 x 0.0144) = {api:,.0f}; retained previous revision 3 replicas idle x 730 h = {retained:,.0f}; HTTP requests (18 M - 2 M free) x 0.4 = {reqs:,.0f}", "IaC"))
    worker = active(U["worker_avg_replicas"] * HOURS, U["worker_vcpu"], U["worker_gib"])
    rows.append(("Compute", "Container App `ca-aron-prod-worker`",
                 "1 vCPU / 2 GiB; no ingress; outbox aggregation, risk signals, route-day planning, geo re-check sweep, retention; KEDA rules on outbox depth and photo queue designed",
                 "1 to 10 replicas (deployed: 1 and no scale rule; costed at 2)",
                 "Aggregation is bursty (17:00) and must not run inside the request path",
                 "1 steady replica drains the trickle; 10 drain a 30-minute backlog (38,000 rows) in about a minute once the KEDA rules exist",
                 worker, f"2 replicas x 730 h x 0.1512 = {worker:,.0f} (1 replica would be {worker/2:,.0f})", "IaC (1 replica); scaling designed"))
    web = active(U["web_avg_replicas"] * HOURS, U["web_vcpu"], U["web_gib"])
    rows.append(("Compute", "Container App `ca-aron-prod-web`",
                 "0.5 vCPU / 1 GiB; Next.js dashboard and portal; its server-side calls reach the api through Front Door",
                 "2 to 6 replicas (costed at 3)",
                 "Server-rendered pages are light; half a vCPU per replica is enough",
                 "2 for zone spread; 6 gives 2x on the pessimistic 25 pages/s reader load",
                 web, f"3 replicas x 730 h x (0.0612 + 0.0144) = {web:,.0f}", "IaC (16:45 pre-scale designed)"))
    jobs = active(U["jobs_vcpu_hours"], 1.0, 2.0)
    rows.append(("Compute", "Container Apps Jobs `caj-aron-prod-migrate`, `caj-aron-prod-dblogins` (+ export jobs designed)",
                 "run-to-completion jobs: Flyway migrations before any revision change; per-app database logins; large exports",
                 "2 jobs defined (+ exports designed); about 60 vCPU-hours a month",
                 "Migrations and grants must finish before code changes; they do not belong in a serving replica",
                 "One job per task; they run only when invoked",
                 jobs, f"60 h x 0.1512 = {jobs:,.0f}", "IaC (exports designed)"))
    pe = p("aca_env_pe_sea_h") * HOURS
    mgmt = p("aca_plan_mgmt_h") * HOURS
    rows.append(("Compute", "Container Apps environment `cae-aron-prod`",
                 "workload-profiles environment, Consumption profile, zone-redundant, VNet-injected, public access off, Front Door Private Link connection",
                 "1",
                 "Zone redundancy spreads replicas over three zones; disabling public access leaves Front Door as the only way in",
                 "One environment per region and environment; the DR region gets its own",
                 pe + mgmt, f"environment private endpoint 0.14 x 730 = {pe:,.0f}; Dedicated Plan Management 0.10 x 730 = {mgmt:,.0f} (a private endpoint attracts it on any plan; confirm on the first invoice)", "IaC"))
    afd_req = U["requests_m"] * 1e6 / 1e4 * p("afd_premium_req_10k")
    afd_out = U["afd_egress_gb"] * p("afd_dto_z2_gb")
    afd_in = U["afd_ingress_gb"] * p("afd_dti_z2_gb")
    rows.append(("Edge", "Front Door Premium `afd-aron-prod` + WAF policy `wafaronprod`",
                 "Premium tier (Private Link origins, managed rule sets), WAF in Prevention with managed rules in Log for the first wave, custom rules: IP backstop 60,000 / 5 min and method allow-list; origin timeout 60 s; probes HEAD /v1/health every 60 s",
                 "1 profile, 1 endpoint, 2 origin groups, 2 routes, 1 WAF policy",
                 "Only Premium can reach a private origin and run the managed firewall rules; the edge terminates TLS close to Dhaka",
                 "One entrance for phones and browsers; a second route only for the APK channel",
                 p("afd_premium_base") + afd_req + afd_out + afd_in,
                 f"base 330; requests 18 M / 10,000 x 0.0168 = {afd_req:,.0f}; egress 370 GB x 0.115 = {afd_out:,.0f} (zone 2 assumed; zone 5 edges differ by under 25); edge to origin 5 GB x 0.06 = {afd_in:,.1f}; WAF included", "IaC"))
    photos12, parts = photos_cost(12)
    rows.append(("Storage", "Storage account `staronprod` (Blob + queue)",
                 "StorageV2 Standard_RAGZRS Hot; containers media (photos), bundles, exports (7-day expiry); lifecycle photos Cool 30 d, Cold 90 d, delete 730 d; soft delete 14 d; queue media-events (message TTL 7 d)",
                 "1 account, 3 containers, 1 queue",
                 "Read-access geo-zone-redundant keeps evidence photos readable through a zone or region loss; lifecycle tiers cut stored-data cost by about 70 %",
                 "One account is within the 40,000 requests/s limit by a wide margin; a second account only for a separate APK/content channel later",
                 photos12,
                 f"month 12: Hot {parts['hot']:,.0f} GB, Cool {parts['cool']:,.0f} GB, Cold {parts['cold']:,.0f} GB stored = {parts['stored']:,.0f}; 4.5 M writes and two tier moves = {parts['ops']:,.0f}; geo-replication {parts['geo']:,.0f}; queue {parts['queue']:,.0f}; month 1 about {photos_cost(1)[0]:,.0f}, month 24 about {photos_cost(24)[0]:,.0f} (steady after the 730-day delete)", "IaC"))
    lake = U["lake_gb"] * p("blob_zrs_gb") + 2.0
    rows.append(("Storage", "Data-lake account `staronprodlake`",
                 "StorageV2 Standard_ZRS with hierarchical namespace, container lake, soft delete 14 d; nightly Parquet export of the dw views (writer planned)",
                 "1", "BI and archived partitions are read from files, not from the live database", "One lake per environment", lake, f"100 GB x 0.028 + operations = {lake:,.0f}", "IaC (ZRS needs the sponsor's written yes, docs/28; writer planned)"))
    acr = p("acr_premium_day") * DAYS
    rows.append(("Registry", "Container Registry `craronprod`", "Premium, zone-redundant, 500 GiB included, untagged manifests purged after 30 days", "1 (+1 geo-replica in DR)",
                 "Only Premium is zone-redundant and can geo-replicate so image pulls survive a region loss", "One registry per subscription is enough; images are pulled by digest", acr, f"1.6666 x 30.42 = {acr:,.0f}", "IaC"))
    kv = U["kv_ops_m"] * 100 * p("kv_ops_10k")
    rows.append(("Security", "Key Vault `kv-aron-prod`", "Standard, RBAC, soft delete 90 d, purge protection on; secrets referenced by the Container Apps; JWT signing key as a secret (signing vault with a non-exportable key designed)", "1 (+1 signing vault designed)",
                 "Purge protection makes a wrong delete recoverable for 90 days; references keep vault clients out of the code", "One vault per environment; the signing key gets its own later", kv, f"2 M operations x 0.03 / 10K = {kv:,.0f}", "IaC"))
    la_gb = U["la_gb_per_day"] * DAYS
    la_ing = la_gb * p("la_ingest_gb")
    la_ret = la_gb * max(U["la_retention_days"] - 31, 0) / DAYS * p("la_retention_gb")
    alerts = U["alerts_log"] * p("alert_log_5min") + U["alerts_metric_ts"] * p("alert_metric_ts")
    rows.append(("Observability", "Log Analytics `log-aron-prod` + Application Insights `appi-aron-prod` + alerts + workbook",
                 "PerGB2018, 90-day retention, 8 GB/day cap; diagnostics from every service; Java agent traces; 5 log-search alerts, PostgreSQL and app metric alerts, resource-health alerts, e-mail action group; one workbook",
                 "1 workspace, 1 App Insights, about 15 alert rules, 1 workbook, 1 action group",
                 "90 days covers a quarter-end investigation; the daily cap bounds the bill if a logging bug appears", "One workspace keeps queries simple; a second (sync) workspace is designed", la_ing + la_ret + alerts,
                 f"5.5 GB x 30.42 = {la_gb:,.0f} GB x 2.99 = {la_ing:,.0f}; retention 59 extra days {la_ret:,.0f}; alerts {alerts:,.1f}", "IaC"))
    eg = max(U["eg_events_m"] - 0.1, 0) * p("eg_ops_m")
    rows.append(("Integration", "Event Grid system topic `evgt-aron-prod-storage`", "BlobCreated on media/photos to the queue, 30 attempts, event TTL 24 h", "1", "At-least-once delivery with an idempotent consumer; no Service Bus needed", "One topic per storage account", eg, f"(4.5 M - 0.1 M free) x 0.6 = {eg:,.1f}", "IaC (consumer planned)"))
    net = p("dns_zone") + p("dns_queries_m") + U["inter_az_gb"] * p("inter_az_gb")
    pe_planned = U["private_endpoints"] * p("private_endpoint_h") * HOURS
    rows.append(("Network", "VNet `vnet-aron-prod`, 2 NSGs, private DNS zone (+ 3 private endpoints planned)",
                 "10.40.0.0/16; snet-aca /23 (Container Apps); snet-pg /28 (PostgreSQL) with NSG allow 5432/6432 from snet-aca and subnet-internal; private DNS for PostgreSQL",
                 "1 VNet, 2 subnets (+1 planned), 2 NSGs, 1 DNS zone, 4 managed identities",
                 "A /23 holds up to 250 replica addresses during a revision swap; the database subnet is reachable only from the app subnet", "One network per environment with non-overlapping ranges so environments can be peered later",
                 net + pe_planned, f"VNet, NSGs, identities free; DNS {p('dns_zone') + p('dns_queries_m'):,.1f}; inter-zone 300 GB x 0.01 = {U['inter_az_gb']*p('inter_az_gb'):,.0f}; 3 private endpoints x 0.01 x 730 = {pe_planned:,.0f} (planned)", "IaC (private endpoints planned)"))
    rows.append(("Governance", "Budget `budget-aron-prod`, Cost Management, tags, GitHub OIDC deploy identity", "USD 7,000 (raise with DR and buffer) with alerts at 50/90/100 % actual and 100 % forecast; daily cost report", "1", "An early warning independent of any alert rule", "One budget per environment", 0.0, "no charge", "IaC"))
    core = sum(r[6] for r in rows)

    dr = []
    c, s, i, m = pg_server(p("pg_d8_ea_h"), p("pg_ssdv2_gib_ea"), p("pg_ssdv2_iops_ea"), p("pg_ssdv2_mbps_ea"), ha=False)
    dr.append(("DR", "PostgreSQL cross-region replica, East Asia", "D8ds_v5, SSD v2 1,024 GiB (12,000 IOPS included), asynchronous; promoted only in a region outage", "1",
               "Same SKU as the primary so replay keeps up with the 17:00 WAL burst", "One replica gives RPO 15 min / RTO 4 h without a second writer", c + s + i + m, f"compute 1.076 x 730 = {c:,.0f}; storage 1,024 x 0.15 = {s:,.0f}; IOPS and throughput {i + m:,.0f}", "designed (D-127, D-420, D-522)"))
    wal = U["dr_wal_gb_per_day"] * DAYS * p("inter_region_gb")
    dr.append(("DR", "Inter-region replication traffic", "WAL stream Southeast Asia to East Asia", "about 190 GB a month", "", "", wal, f"6.3 GB a day x 30.42 x 0.08 = {wal:,.0f}", "designed"))
    pe_ea = p("aca_env_pe_ea_h") * HOURS
    dr.append(("DR", "Container Apps environment East Asia (warm, apps at 0)", "zone-redundant, VNet 10.42.0.0/16, Private Link, api/worker/web at 0 replicas until promotion; weekly smoke", "1",
               "Promotion inside 4 h needs the environment to exist already", "One warm environment", pe_ea + mgmt + 20.0, f"private endpoint 0.155 x 730 = {pe_ea:,.0f}; plan management {mgmt:,.0f}; smoke runs about 20", "designed"))
    acr_geo = p("acr_premium_day") * DAYS
    dr.append(("DR", "ACR geo-replica, DR Key Vault, disabled DR Front Door origin", "image pulls and secrets survive a Southeast Asia outage", "1 each", "", "", acr_geo + 1.0, f"replica 1.6666 x 30.42 = {acr_geo:,.0f}; vault about 1", "designed"))
    drt = sum(r[6] for r in dr)

    buffer = U["buffer"]

    # Optional and non-production
    opt = []
    stg_c, stg_s, _, _ = pg_server(p("pg_d2_sea_h"), p("pg_ssdv2_gib_sea"), p("pg_ssdv2_iops_sea"), p("pg_ssdv2_mbps_sea"), ha=True, gib=128)
    stg_aca = active(3 * HOURS, 0.5, 1.0)
    stg_la = 1.0 * DAYS * p("la_ingest_gb")
    stg = stg_c + stg_s + stg_aca + p("afd_standard_base") + 5 + stg_la + p("acr_standard_day") * DAYS
    opt.append(("Optional", "Staging-lite in the final account", "D2ds_v5 zone-redundant HA on 128 GiB, three small Container Apps, Front Door Standard, 1 GB/day logs, ACR Standard; scaled to the prod shape only inside a test window (about 75 per 8-hour window); Premium Front Door + Private Link for the edge proof adds 295",
                "1 environment", "Release gates and drills need a prod-like twin; D2 keeps it cheap between windows", "One staging; dev stays in the test account",
                stg, f"database {stg_c + stg_s:,.0f}; apps {stg_aca:,.0f}; Front Door 40; logs {stg_la:,.0f}; registry {p('acr_standard_day') * DAYS:,.0f}", "parameters only: the committed stage file is prod-shaped and the deploy path does not accept stage yet (docs/33 B13.2 rows 2, 35)"))
    opt.append(("Optional", "BI path: Microsoft Fabric F2 + 10 Power BI Pro licences (D-560)", "F2 = 2 CU pay-as-you-go; VNet data gateway; archived partitions in the lakehouse", "1 capacity, 10 users", "", "", 2 * p("fabric_cu_h") * HOURS + 10 * p("pbi_pro_user"), f"2 CU x 0.22 x 730 = {2*p('fabric_cu_h')*HOURS:,.0f}; 10 x 14 = 140", "deferred; pausing the capacity stops the charge"))
    opt.append(("Optional", "Azure Managed Redis Balanced B3", "replay cache, per-device rate limits, config fan-out", "1", "", "", p("redis_b3_h") * HOURS, f"0.081 x 730 = {p('redis_b3_h')*HOURS:,.0f}", "not in the as-built design (D24-20)"))
    opt.append(("Optional", "Microsoft Defender for Cloud (3 PostgreSQL servers, Key Vault, Storage)", "threat detection on the data stores", "", "", "", 3 * p("defender_pg_month") + 15.0, "3 x 15 + about 15", "subscription-level choice"))
    optt = sum(r[6] for r in opt)

    vuh = 12750 - 50
    oneoff = [("One-off", "Load-test runs S1-S10", f"one full-size hour = 12,750 virtual users (1.5 x 8,500 SRs) x 1 h = {vuh:,} billable VUH; 26 engines at 500 users each", "1 certified run with Azure Load Testing + Locust runs on Container Apps Jobs",
               "", "", vuh * p("alt_vuh"), f"{vuh:,} x 0.15 = {vuh*p('alt_vuh'):,.0f} per Azure Load Testing run; a Locust run on Container Apps Jobs costs about 20", "final account only")]

    # Spec+: existing web properties
    sites = []
    asp = p("asp_p1v3_h") * HOURS
    sites.append(("Sites", "App Service plan `asp-aktcl-web` (Linux Premium v3 P1)", "2 vCPU / 8 GiB, Linux, zone-redundant off, autoscale 1 to 2 instances, deployment slots, managed TLS certificates", f"1 plan hosting {U['sites']} sites + {U['sites_reserve']} reserve",
                  "P1v3 runs four to six PHP/WordPress or Node sites with headroom; Basic tiers have no autoscale or slots", "One plan shares its vCPU across the sites; a second instance only during campaigns", asp + 20.0, f"0.183 x 730 = {asp:,.0f}; second instance allowance 20", "assumption: the sites run on App Service (inventory the current subscription)"))
    my = p("mysql_b2s_h") * HOURS + U["mysql_storage_gb"] * p("mysql_storage_gb")
    sites.append(("Sites", "Azure Database for MySQL Flexible Server `mysql-aktcl-web`", "Burstable B2s (2 vCore, 4 GiB), 64 GiB storage, 7-day backup, private access", "1 server, one database per site",
                  "A burstable server fits brochure sites whose database load is small and spiky", "One server for all sites; the free backup covers the storage size", my, f"0.104 x 730 = {p('mysql_b2s_h')*HOURS:,.0f}; storage 64 x 0.138 = {U['mysql_storage_gb']*p('mysql_storage_gb'):,.0f}", "assumption: WordPress-style sites"))
    st = U["sites_storage_gb"] * p("blob_lrs_gb") + 1.0
    sites.append(("Sites", "Storage account `staktclweb` (LRS)", "media uploads and App Service backups", "1", "Static media and nightly backups off the web server", "", st, f"100 GB x 0.0184 + operations = {st:,.0f}", "assumption"))
    dns = (U["sites"] + 2) * p("dns_zone") + 2 * p("dns_queries_m")
    sites.append(("Sites", "Azure DNS public zones", "aktcl.com, aksilage.com, shahagro.com, arondeepstate.com and two more", f"{U['sites'] + 2} zones", "Zones move with the sites so the cut-over is a name-server change", "", dns, f"6 x 0.5 + queries {2*p('dns_queries_m'):,.1f}", "assumption"))
    afd_sites = U["sites_requests_m"] * 100 * p("afd_premium_req_10k") + U["sites_egress_gb"] * p("afd_dto_z2_gb")
    sites.append(("Sites", "Front Door routes and custom domains (shared Premium profile)", "one route per domain on `afd-aron-prod`, WAF, managed certificates", f"{U['sites'] + 2} domains", "No second edge profile: the Aron profile already pays the base fee", "", afd_sites, f"requests 10 M x 0.0168 / 10K = {U['sites_requests_m']*100*p('afd_premium_req_10k'):,.0f}; egress 300 GB x 0.115 = {U['sites_egress_gb']*p('afd_dto_z2_gb'):,.0f}", "assumption on traffic"))
    la_s = U["sites_la_gb_per_day"] * DAYS * p("la_ingest_gb")
    sites.append(("Sites", "Diagnostics into the shared Log Analytics workspace", "0.5 GB a day", "", "", "", la_s, f"0.5 x 30.42 x 2.99 = {la_s:,.0f}", "assumption"))
    sitest = sum(r[6] for r in sites)
    sites_buffer = U["sites_buffer"]

    # Year one, month by month (ceiling: D8 from month 1; DR from month 4; staging all year; one paid load test in month 2)
    year = []
    core_base_no_photos = core - photos12
    for mth in range(1, 13):
        ph, _ = photos_cost(mth)
        grow = (283.0 + 141.0) if mth >= 7 else 0.0          # 2,048 GiB from month 7 on primary (HA) and replica
        c_m = core_base_no_photos + ph + grow
        d_m = (drt + (154.0 if mth >= 7 else 0.0)) if mth >= 4 else 0.0
        t_m = c_m + d_m + stg + (vuh * p("alt_vuh") if mth == 2 else 0.0) + (20.0 if mth in (3, 5, 8) else 0.0)
        res = (vcore_saving_sea() if mth >= 2 else 0.0) + (vcore_saving_ea() if mth >= 4 else 0.0)
        year.append((mth, c_m, d_m, t_m, t_m - res))
    y1 = sum(r[3] for r in year); y1r = sum(r[4] for r in year)

    assumptions = [(k, v[0], v[1], v[2]) for k, v in P.items() if "ESTIMATE" in v[2]]
    return dict(rows=rows, core=core, dr=dr, drt=drt, buffer=buffer, opt=opt, optt=optt, oneoff=oneoff, sites=sites, sitest=sitest,
                sites_buffer=sites_buffer, year=year, y1=y1, y1r=y1r, assumptions=assumptions, stg=stg)


def vcore_saving_sea():
    payg = 24 * (p("pg_d8_sea_h") / 8) * HOURS
    return payg - 24 * p("pg_res_1y_sea") / 12


def vcore_saving_ea():
    payg = 8 * (p("pg_d8_ea_h") / 8) * HOURS
    return payg - 8 * p("pg_res_1y_ea") / 12


def md_cost(rows):
    out = ["| # | Resource | Specification | USD / month | How it is computed | Status |", "|---|---|---|---:|---|---|"]
    for n, r in enumerate(rows, 1):
        g, name, spec, qty, why_s, why_q, cost, arith, status = r
        out.append(f"| {n} | **{name}** ({g}) | {spec} | {cost:,.0f} | {arith} | {status} |")
    return "\n".join(out)


def md_precise(rows):
    out = ["| # | Resource | Specification | Quantity | Why this specification | Why this quantity | USD / month |", "|---|---|---|---|---|---|---:|"]
    for n, r in enumerate(rows, 1):
        g, name, spec, qty, why_s, why_q, cost, arith, status = r
        out.append(f"| {n} | **{name}** | {spec} | {qty} | {why_s} | {why_q} | {cost:,.0f} |")
    return "\n".join(out)


def main():
    M = model()
    if "--json" in sys.argv:
        print(json.dumps({k: v for k, v in M.items()}, indent=1, default=str)); return
    precise = "--precise" in sys.argv
    tbl = md_precise if precise else md_cost
    core, drt, buffer = M["core"], M["drt"], M["buffer"]
    print("### Core production (what the templates create on the first final-account deploy)\n")
    print(tbl(M["rows"]))
    print(f"\n**Core total: about USD {core:,.0f} a month** (list prices, 730-hour month, Southeast Asia). Includes about USD 28 for items designed but not yet in a template (private endpoints, signing vault, export jobs).\n")
    print("### Disaster-recovery tier (designed; built before rollout wave 2)\n")
    print(tbl(M["dr"]))
    print(f"\n**DR total: about USD {drt:,.0f} a month. Core + DR: about USD {core + drt:,.0f}.**\n")
    print("### Contingency buffer\n")
    print("| Line | USD / month | What it covers |\n|---|---:|---|")
    print(f"| Buffer (owner's 1,000-3,000 band) | {buffer:,.0f} | a D16ds_v5 step-up for a quarter (+1,425 compute with HA), log overage above 5.5 GB/day, extra replicas in a storm, prices the API does not publish, a billing-zone difference |")
    print(f"\n**Core + DR + buffer: about USD {core + drt + buffer:,.0f} a month. Recommended budget: USD {math.ceil((core + drt + buffer) / 500) * 500:,.0f}.**\n")
    print("### Optional and non-production lines\n")
    print(tbl(M["opt"]))
    print(f"\n**Optional total if all are taken: about USD {M['optt']:,.0f} a month.**\n")
    print("### One-off costs\n")
    print("| # | Item | Specification | USD per run | How it is computed | Status |\n|---|---|---|---:|---|---|")
    for n, r in enumerate(M["oneoff"], 1):
        g, name, spec, qty, why_s, why_q, cost, arith, status = r
        print(f"| {n} | **{name}** | {spec}; {qty} | {cost:,.0f} | {arith} | {status} |")
    print("\n### Spec+: existing web properties migrated into the final account (separate from Aron)\n")
    print(tbl(M["sites"]))
    print(f"\n**Existing sites: about USD {M['sitest']:,.0f} a month, plus their own buffer of {M['sites_buffer']:,.0f} = about USD {M['sitest'] + M['sites_buffer']:,.0f}.** These are assumptions until the current subscription is inventoried (one `az resource list` per subscription).\n")
    print("### Totals\n")
    print("| Scope | USD / month |\n|---|---:|")
    print(f"| Aron core production | {core:,.0f} |")
    print(f"| Aron core + DR tier | {core + drt:,.0f} |")
    print(f"| Aron core + DR + buffer (Spec) | {core + drt + buffer:,.0f} |")
    print(f"| Existing sites + their buffer | {M['sitest'] + M['sites_buffer']:,.0f} |")
    print(f"| Everything (Spec+) | {core + drt + buffer + M['sitest'] + M['sites_buffer']:,.0f} |")
    print(f"| Everything with 1-year PostgreSQL reservations (SEA 24 vCores, EA 8) | {core + drt + buffer + M['sitest'] + M['sites_buffer'] - vcore_saving_sea() - vcore_saving_ea():,.0f} |")
    print(f"| Staging-lite (optional) | {M['stg']:,.0f} |")
    print("\n### Reservation lever (PostgreSQL compute)\n")
    payg = 24 * (p("pg_d8_sea_h") / 8) * HOURS
    r1 = 24 * p("pg_res_1y_sea") / 12; r3 = 24 * p("pg_res_3y_sea") / 36
    print("| Term | USD / month | Saving a month | Rule |\n|---|---:|---:|---|")
    print(f"| Southeast Asia 24 vCores pay as you go | {payg:,.0f} | - | primary 8 + standby 8 + replica 8 |")
    print(f"| 1-year reserved (641 per vCore) | {r1:,.0f} | {payg - r1:,.0f} ({(payg - r1)/payg*100:,.0f} %) | buy after 30 stable days at D8 (D-426) |")
    print(f"| 3-year reserved (1,282 per vCore) | {r3:,.0f} | {payg - r3:,.0f} ({(payg - r3)/payg*100:,.0f} %) | only after the load test and a year of running |")
    payg_ea = 8 * (p("pg_d8_ea_h") / 8) * HOURS; r1e = 8 * p("pg_res_1y_ea") / 12
    print(f"| East Asia DR replica 8 vCores, 1-year reserved (706 per vCore) | {r1e:,.0f} | {payg_ea - r1e:,.0f} | buy when the DR tier is built |")
    d16 = 3 * (p('pg_d16_sea_h') - p('pg_d8_sea_h')) * HOURS
    print(f"| Sensitivity: D16ds_v5 step-up on the three Southeast Asia servers | +{d16:,.0f} | - | if the load test shows CPU above 80 % or IOPS above about 7,700 |")
    print("\n### Lines that grow with time\n")
    print("| Month of full-fleet running | Photos (Blob) USD / month | Database disk | Note |\n|---|---:|---|---|")
    for mth, note in ((1, "1,024 GiB"), (3, "1,024 GiB"), (6, "grow to 2,048 GiB at about 70 % (+283 primary with HA, +141 replica, +154 DR replica)"), (12, "2,048 GiB"), (24, "2,048 GiB; photos older than 730 days are deleted, so Cold stops growing")):
        c, parts = photos_cost(mth)
        print(f"| {mth} | {c:,.0f} | {note} | Hot {parts['hot']:,.0f} GB, Cool {parts['cool']:,.0f} GB, Cold {parts['cold']:,.0f} GB |")
    print("\n### Year one, month by month (ceiling: D8 from month 1, DR from month 4, staging-lite all year, one paid load-test run in month 2 and three Locust runs)\n")
    print("| Month | Aron core | DR | Total | Total with 1-year reservations (SEA from month 2, EA from month 4) |\n|---|---:|---:|---:|---:|")
    for mth, c_m, d_m, t_m, t_r in M["year"]:
        print(f"| {mth} | {c_m:,.0f} | {d_m:,.0f} | {t_m:,.0f} | {t_r:,.0f} |")
    print(f"| **Year one** | | | **{M['y1']:,.0f}** | **{M['y1r']:,.0f}** |")
    print("\n### Prices that are not in the Retail Prices API (estimates to confirm on the first invoice)\n")
    print("| Key | Value | Unit | Source |\n|---|---:|---|---|")
    for k, v, u, s in M["assumptions"]:
        print(f"| `{k}` | {v} | {u} | {s} |")
    by_group = {}
    for r in M["rows"]:
        by_group[r[0]] = by_group.get(r[0], 0) + r[6]
    print("\n### Core total by group\n")
    print("| Group | USD / month | Share |\n|---|---:|---:|")
    for g, v in sorted(by_group.items(), key=lambda kv: -kv[1]):
        print(f"| {g} | {v:,.0f} | {v/core*100:,.0f} % |")


if __name__ == "__main__":
    main()
