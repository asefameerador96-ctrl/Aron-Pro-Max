#!/usr/bin/env python3
"""Aron final-account monthly cost model (owner pack, docs/35).

Run:  python3 -I docs/35-final-azure-design-pack/cost-model.py            # prints the markdown tables
      python3 -I docs/35-final-azure-design-pack/cost-model.py --json     # machine-readable

Every unit price below was read from the Azure Retail Prices API
(https://prices.azure.com/api/retail/prices, currency USD, armRegionName southeastasia unless
stated) on 2026-10-29. A price that the API does not publish is marked SOURCE: page/estimate and is
listed in the "assumptions" section of the output. Usage figures come from docs/18 s1 (design load
model) and the production parameter files infra/params/prod*.bicepparam. Nothing here is an invoice;
the first real month in the final account replaces these numbers (docs/28, honesty rule).
"""
import json
import sys

HOURS = 730.0            # Azure's billing month
DAYS = HOURS / 24.0      # 30.42

# ----------------------------------------------------------------------------------------------
# Unit prices (USD). KEY: value, unit, source
# ----------------------------------------------------------------------------------------------
P = {
    # PostgreSQL Flexible Server, General Purpose Ddsv5, southeastasia (API: "8 vCore", "vCore")
    "pg_d8_sea_h": (0.976, "per hour, 8 vCores", "API 2026-10-29"),
    "pg_d16_sea_h": (1.952, "per hour, 16 vCores", "API 2026-10-29"),
    "pg_d8_ea_h": (1.076, "per hour, 8 vCores, eastasia", "API 2026-10-29"),
    "pg_ssdv2_gib_sea": (0.138, "per GiB-month", "API 2026-10-29"),
    "pg_ssdv2_iops_sea": (0.026, "per provisioned IOPS-month above the 3,000 baseline", "API 2026-10-29; baseline per Learn"),
    "pg_ssdv2_mbps_sea": (0.104, "per provisioned MB/s-month above the 125 baseline", "API 2026-10-29; baseline per Learn"),
    "pg_ssdv2_gib_ea": (0.15, "per GiB-month, eastasia", "API 2026-10-29"),
    "pg_ssdv2_iops_ea": (0.031, "per IOPS-month, eastasia", "API 2026-10-29"),
    "pg_ssdv2_mbps_ea": (0.124, "per MB/s-month, eastasia", "API 2026-10-29"),
    "pg_backup_lrs_gb": (0.095, "per GB-month above the free amount (100 percent of provisioned storage)", "API 2026-10-29"),
    "pg_backup_geo_gb": (0.19, "per GB-month, geo-redundant backup", "ESTIMATE: 2 x LRS; the API lists no Flexible Server GRS meter in southeastasia"),
    # Container Apps, Consumption workload profile
    "aca_vcpu_active_h": (0.1224, "per vCPU-hour active (API: 0.000034 per second)", "API 2026-10-29"),
    "aca_mem_active_h": (0.0144, "per GiB-hour active (API: 0.000004 per GiB-second)", "API 2026-10-29"),
    "aca_vcpu_idle_h": (0.0144, "per vCPU-hour idle", "API 2026-10-29"),
    "aca_mem_idle_h": (0.0144, "per GiB-hour idle", "API 2026-10-29"),
    "aca_requests_m": (0.4, "per million requests after the first 2 million", "API 2026-10-29"),
    "aca_env_pe_sea_h": (0.14, "environment private endpoint, per hour", "API 2026-10-29"),
    "aca_env_pe_ea_h": (0.155, "environment private endpoint, per hour, eastasia", "API 2026-10-29"),
    # Front Door Premium (billing zone 2 = Asia Pacific; Bangladesh traffic is assumed zone 2)
    "afd_premium_base": (330.0, "per month", "API 2026-10-29"),
    "afd_premium_req_10k": (0.0168, "per 10,000 requests, first tier", "API 2026-10-29"),
    "afd_dto_z2_gb": (0.115, "per GB out to clients, zone 2, first 10 TB", "API 2026-10-29"),
    "afd_dti_z2_gb": (0.06, "per GB in from clients, zone 2", "API 2026-10-29"),
    # Blob (General Block Blob v2), Standard_RAGZRS as in prod.bicepparam
    "blob_hot_ragzrs_gb": (0.06125, "per GB-month, first 50 TB", "API 2026-10-29"),
    "blob_cool_ragzrs_gb": (0.0309, "per GB-month", "API 2026-10-29"),
    "blob_cold_ragzrs_gb": (0.0119, "per GB-month", "API 2026-10-29"),
    "blob_hot_write_10k": (0.0731, "per 10,000 write operations, Hot GZRS", "ESTIMATE from the API's 'GZRS List and Create Container' meter (0.07308); the write meter is not listed separately"),
    "blob_cool_write_10k": (0.10, "per 10,000 write operations, Cool", "ESTIMATE (Azure prices Cool writes at about 1.4 x Hot ZRS writes 0.0625)"),
    "blob_cold_write_10k": (0.38, "per 10,000 write operations, Cold RA-GZRS", "API 2026-10-29"),
    "blob_hot_read_10k": (0.004, "per 10,000 read operations", "API 2026-10-29"),
    "blob_geo_egress_gb": (0.02, "per GB replicated to the secondary region", "ESTIMATE: pricing page, not in the API"),
    "queue_ops_10k": (0.004, "per 10,000 queue operations, Queues v2 ZRS", "API 2026-10-29"),
    # Registry, vault, logs, alerts, events, network
    "acr_premium_day": (1.6666, "per day, Premium registry", "API 2026-10-29"),
    "acr_premium_geo_day": (1.6666, "per day per geo-replica", "API 2026-10-29"),
    "kv_ops_10k": (0.03, "per 10,000 operations, Standard", "API 2026-10-29"),
    "la_ingest_gb": (2.99, "per GB ingested, Analytics Logs pay-as-you-go", "API 2026-10-29"),
    "la_retention_gb": (0.13, "per GB-month retained beyond 31 days", "API 2026-10-29"),
    "alert_log_5min": (1.5, "per log-search alert rule at 5-minute frequency per month", "API 2026-10-29"),
    "alert_metric_ts": (0.1, "per metric time series monitored per month", "API 2026-10-29"),
    "eg_ops_m": (0.6, "per million Event Grid operations after the first 100,000", "API 2026-10-29"),
    "dns_private_zone": (0.5, "per private DNS zone per month", "API 2026-10-29"),
    "dns_private_queries_m": (0.4, "per million private DNS queries", "API 2026-10-29"),
    "inter_az_gb": (0.01, "per GB between availability zones", "API 2026-10-29 (Microsoft has announced this meter is free; kept as a ceiling)"),
    "inter_region_gb": (0.08, "per GB between regions", "API 2026-10-29"),
    "private_endpoint_h": (0.01, "per private endpoint per hour", "ESTIMATE: pricing page (Private Link), not in the API"),
    # Optional lines
    "redis_b3_h": (0.081, "Azure Managed Redis Balanced B3, per hour", "API 2026-10-29"),
    "fabric_cu_h": (0.22, "Microsoft Fabric capacity, per CU-hour pay-as-you-go", "API 2026-10-29"),
    "pbi_pro_user": (14.0, "Power BI Pro per user per month", "ESTIMATE: Microsoft 365 list price, not in the API"),
    "defender_pg_month": (15.0, "Defender for PostgreSQL per server per month", "API 2026-10-29"),
    "alt_vuh": (0.15, "Azure Load Testing per virtual-user-hour after 50 free", "ESTIMATE: pricing page, not in the API"),
}


def p(k):
    return P[k][0]


# ----------------------------------------------------------------------------------------------
# Usage assumptions (docs/18 s1 design load model at 8,500 SRs; prod parameter files)
# ----------------------------------------------------------------------------------------------
U = {
    "pg_storage_gib": 1024, "pg_iops": 12000, "pg_mbps": 300,
    "pg_backup_excess_gb": 150,                 # 35-day PITR of a 1 TB database, about 15 percent above the free amount
    "api_min": 3, "api_prescale": 8, "api_prescale_hours_per_day": 7.25,   # 06:15-10:00 and 16:45-20:00 Dhaka
    "api_burst_replica_hours_per_day": 15,      # one hour a day at 15 replicas (storms and canaries)
    "api_vcpu": 1.0, "api_gib": 2.0,
    "api_retained_revision_replicas": 3,        # Multiple revisions: the previous revision stays at 0 percent traffic
    "worker_avg_replicas": 2, "worker_vcpu": 1.0, "worker_gib": 2.0,
    "web_avg_replicas": 3, "web_vcpu": 0.5, "web_gib": 1.0,
    "jobs_vcpu_hours": 60,                      # migrate, dblogins, export jobs (1 vCPU / 2 GiB)
    "requests_m": 18.0,                         # 14.5 M API (docs/18 s1.3) + about 3.5 M web and static
    "afd_egress_gb": 370,                       # bundles 10 + API 15 + web 90 + one APK release 255 (8,500 x 30 MB)
    "afd_ingress_gb": 5,
    "blob_hot_gb": 680, "blob_cool_gb": 1350, "blob_cold_gb": 6200,   # photos at month 12 (docs/18 s1.9)
    "blob_writes_m": 4.5,                       # 150,000 photos a day
    "blob_reads_m": 1.0,
    "blob_geo_gb": 675,                         # 22.5 GB a day replicated to the secondary region
    "queue_ops_m": 13.5,                        # 3 operations per photo event
    "acr_geo_replicas": 1,
    "kv_ops_m": 2.0,
    "la_gb_per_day": 5.5, "la_retention_days": 90,
    "alerts_log": 5, "alerts_metric_ts": 20,
    "eg_events_m": 4.5,
    "inter_az_gb": 300,
    "private_endpoints": 3,                     # Key Vault, ACR, Blob (planned)
    "dr_wal_gb_per_day": 6.3,                   # 3.0 M rows x 1.5 KB x 1.4
}


def pg_server(compute_h, gib_price, iops_price, mbps_price, ha):
    f = 2 if ha else 1
    compute = compute_h * HOURS * f
    storage = U["pg_storage_gib"] * gib_price * f
    iops = max(U["pg_iops"] - 3000, 0) * iops_price * f
    mbps = max(U["pg_mbps"] - 125, 0) * mbps_price * f
    return compute, storage, iops, mbps


def aca_replica_hours_api():
    per_day = U["api_min"] * (24 - U["api_prescale_hours_per_day"]) \
        + U["api_prescale"] * U["api_prescale_hours_per_day"] + U["api_burst_replica_hours_per_day"]
    return per_day * DAYS


def active_cost(replica_hours, vcpu, gib):
    return replica_hours * (vcpu * p("aca_vcpu_active_h") + gib * p("aca_mem_active_h"))


def idle_cost(replica_hours, vcpu, gib):
    return replica_hours * (vcpu * p("aca_vcpu_idle_h") + gib * p("aca_mem_idle_h"))


def model():
    rows = []  # (group, resource, spec, monthly, arithmetic, status)

    # --- Database -------------------------------------------------------------------------------
    c, s, i, m = pg_server(p("pg_d8_sea_h"), p("pg_ssdv2_gib_sea"), p("pg_ssdv2_iops_sea"), p("pg_ssdv2_mbps_sea"), ha=True)
    backup = U["pg_backup_excess_gb"] * p("pg_backup_geo_gb")
    rows.append(("Database", "PostgreSQL Flexible Server primary `psql-aron-prod`", "GP D8ds_v5 (8 vCore, 32 GiB), zone-redundant HA, Premium SSD v2 1,024 GiB / 12,000 IOPS / 300 MB/s, PITR 35 d, geo-redundant backup",
                 c + s + i + m + backup,
                 f"compute 0.976 x 730 x 2 (HA standby billed) = {c:,.0f}; storage 1,024 x 0.138 x 2 = {s:,.0f}; IOPS (12,000 - 3,000) x 0.026 x 2 = {i:,.0f}; throughput (300 - 125) x 0.104 x 2 = {m:,.0f}; backup above the free amount 150 GB x 0.19 = {backup:,.0f}",
                 "in IaC"))
    c, s, i, m = pg_server(p("pg_d8_sea_h"), p("pg_ssdv2_gib_sea"), p("pg_ssdv2_iops_sea"), p("pg_ssdv2_mbps_sea"), ha=False)
    rows.append(("Database", "PostgreSQL in-region read replica `psql-aron-prod-r1`", "same SKU and disk as the primary, zone 3, no HA, own PgBouncer",
                 c + s + i + m, f"compute {c:,.0f}; storage {s:,.0f}; IOPS {i:,.0f}; throughput {m:,.0f}", "in IaC (template; second deploy)"))

    # --- Compute --------------------------------------------------------------------------------
    api_h = aca_replica_hours_api()
    api = active_cost(api_h, U["api_vcpu"], U["api_gib"])
    retained = idle_cost(U["api_retained_revision_replicas"] * HOURS, U["api_vcpu"], U["api_gib"])
    worker = active_cost(U["worker_avg_replicas"] * HOURS, U["worker_vcpu"], U["worker_gib"])
    web = active_cost(U["web_avg_replicas"] * HOURS, U["web_vcpu"], U["web_gib"])
    jobs = active_cost(U["jobs_vcpu_hours"], 1.0, 2.0)
    reqs = max(U["requests_m"] - 2.0, 0) * p("aca_requests_m")
    pe = p("aca_env_pe_sea_h") * HOURS
    rows.append(("Compute", "Container App `ca-aron-prod-api`", "1 vCPU / 2 GiB per replica; min 3, max 30; pre-scale 8 at 06:15-10:00 and 16:45-20:00 Dhaka; HTTP rule 50 concurrent",
                 api + retained, f"{api_h:,.0f} replica-hours a month x (0.1224 + 2 x 0.0144) = {api:,.0f}; retained previous revision 3 replicas idle x 730 h = {retained:,.0f}", "in IaC"))
    rows.append(("Compute", "Container App `ca-aron-prod-worker`", "1 vCPU / 2 GiB; min 1, max 10 (average 2); outbox, aggregation, photo linking, retention jobs",
                 worker, f"2 replicas x 730 h x 0.1512 = {worker:,.0f}", "in IaC (KEDA scale rules designed)"))
    rows.append(("Compute", "Container App `ca-aron-prod-web`", "0.5 vCPU / 1 GiB; min 2, max 6 (average 3); Next.js dashboard and portal",
                 web, f"3 replicas x 730 h x (0.0612 + 0.0144) = {web:,.0f}", "in IaC"))
    rows.append(("Compute", "Container Apps Jobs `caj-aron-prod-migrate`, `-dblogins`, export jobs", "run on demand; 60 vCPU-hours a month allowance",
                 jobs + reqs, f"60 h x 0.1512 = {jobs:,.0f}; requests (18 M - 2 M free) x 0.4 = {reqs:,.0f}", "in IaC (export jobs designed)"))
    rows.append(("Compute", "Container Apps environment `cae-aron-prod`", "workload-profiles environment, Consumption profile only, zone-redundant, VNet-injected, public access off, private endpoint for Front Door Private Link",
                 pe, f"environment private endpoint 0.14 x 730 = {pe:,.0f}; no Dedicated-plan management fee while no Dedicated profile exists", "in IaC"))

    # --- Edge -----------------------------------------------------------------------------------
    afd_req = U["requests_m"] * 1e6 / 1e4 * p("afd_premium_req_10k")
    afd_out = U["afd_egress_gb"] * p("afd_dto_z2_gb")
    afd_in = U["afd_ingress_gb"] * p("afd_dti_z2_gb")
    rows.append(("Edge", "Front Door Premium `afd-aron-prod` + WAF `wafaronprod`", "Premium tier (managed WAF rules and Private Link origins), Prevention mode, IP backstop 60,000 per 5 min, origin timeout 60 s; WAF included in Premium",
                 p("afd_premium_base") + afd_req + afd_out + afd_in,
                 f"base 330; requests 18 M / 10,000 x 0.0168 = {afd_req:,.0f}; egress 370 GB x 0.115 = {afd_out:,.0f} (one APK release of 255 GB included); ingress 5 GB x 0.06 = {afd_in:,.1f}", "in IaC"))

    # --- Storage --------------------------------------------------------------------------------
    hot = U["blob_hot_gb"] * p("blob_hot_ragzrs_gb"); cool = U["blob_cool_gb"] * p("blob_cool_ragzrs_gb"); cold = U["blob_cold_gb"] * p("blob_cold_ragzrs_gb")
    w_hot = U["blob_writes_m"] * 1e6 / 1e4 * p("blob_hot_write_10k")
    w_cool = U["blob_writes_m"] * 1e6 / 1e4 * p("blob_cool_write_10k")
    w_cold = U["blob_writes_m"] * 1e6 / 1e4 * p("blob_cold_write_10k")
    r_hot = U["blob_reads_m"] * 1e6 / 1e4 * p("blob_hot_read_10k")
    geo = U["blob_geo_gb"] * p("blob_geo_egress_gb")
    q_ops = U["queue_ops_m"] * 1e6 / 1e4 * p("queue_ops_10k")
    rows.append(("Storage", "Storage account `staronprod` (Blob + queue)", "StorageV2 Standard_RAGZRS Hot; containers media, bundles, audit-export; lifecycle photos Hot 30 d, Cool 60 d, Cold to 365 d; soft delete 14 d; queue media-events",
                 hot + cool + cold + w_hot + w_cool + w_cold + r_hot + geo + q_ops,
                 f"month-12 data: Hot 680 GB x 0.06125 = {hot:,.0f}, Cool 1,350 GB x 0.0309 = {cool:,.0f}, Cold 6,200 GB x 0.0119 = {cold:,.0f}; 4.5 M photo writes x 0.0731/10K = {w_hot:,.0f}; lifecycle moves 4.5 M to Cool ({w_cool:,.0f}) and 4.5 M to Cold ({w_cold:,.0f}); reads {r_hot:,.1f}; geo-replication 675 GB x 0.02 = {geo:,.0f}; queue {q_ops:,.0f}",
                 "in IaC; month-1 figure is about USD 60 and grows with the photo archive"))

    # --- Registry, vault, logs, events, network -------------------------------------------------
    acr = p("acr_premium_day") * DAYS
    rows.append(("Registry", "Container Registry `craronprod`", "Premium (zone-redundant, 500 GiB included, content trust, private link ready); untagged retention 30 d",
                 acr, f"1.6666 x 30.42 days = {acr:,.0f}", "in IaC"))
    kv = U["kv_ops_m"] * 1e6 / 1e4 * p("kv_ops_10k")
    rows.append(("Security", "Key Vault `kv-aron-prod` (+ signing vault)", "Standard, RBAC, soft delete 90 d, purge protection on; secrets by Container Apps references; EC P-256 signing key, Argon2 pepper, SAS delegation",
                 kv + 1.0, f"2 M operations x 0.03/10K = {kv:,.0f}; second vault about 1", "in IaC (second vault designed)"))
    la_gb = U["la_gb_per_day"] * DAYS
    la_ing = la_gb * p("la_ingest_gb")
    la_ret = la_gb * max(U["la_retention_days"] - 31, 0) / DAYS * p("la_retention_gb")
    alerts = U["alerts_log"] * p("alert_log_5min") + U["alerts_metric_ts"] * p("alert_metric_ts")
    rows.append(("Observability", "Log Analytics `log-aron-prod` + Application Insights `appi-aron-prod` + alerts", "PerGB2018, 90-day retention, 8 GB/day cap (design 5.5 GB/day: request logs, platform diagnostics, Java agent traces); 5 log-search alerts, about 20 metric time series, workbook, action group",
                 la_ing + la_ret + alerts, f"ingestion 5.5 GB x 30.42 = {la_gb:,.0f} GB x 2.99 = {la_ing:,.0f}; retention 59 extra days = {la_ret:,.0f}; alerts {alerts:,.1f}", "in IaC"))
    eg = max(U["eg_events_m"] - 0.1, 0) * p("eg_ops_m")
    rows.append(("Integration", "Event Grid system topic `evgt-aron-prod-storage`", "BlobCreated on media/ to the queue media-events, 30 retries, 24 h TTL",
                 eg, f"(4.5 M - 0.1 M free) x 0.6 = {eg:,.1f}", "in IaC"))
    net = p("dns_private_zone") + 0.4 + U["inter_az_gb"] * p("inter_az_gb")
    pe_planned = U["private_endpoints"] * p("private_endpoint_h") * HOURS
    rows.append(("Network", "VNet `vnet-aron-prod`, NSGs, private DNS zone, private endpoints", "10.40.0.0/16; snet-aca /23; snet-pg /28 with NSG; private DNS for PostgreSQL; 3 private endpoints (Key Vault, ACR, Blob) planned",
                 net + pe_planned, f"VNet and NSGs free; private DNS zone 0.5 + queries 0.4; inter-zone traffic 300 GB x 0.01 = {U['inter_az_gb']*p('inter_az_gb'):,.0f}; 3 private endpoints x 0.01 x 730 = {pe_planned:,.0f}", "VNet in IaC; private endpoints planned"))
    rows.append(("Governance", "Budget `budget-aron-prod`, Cost Management, managed identities, Entra ID, tags", "budget USD 7,000 with alerts at 50/90/100 percent actual and 100 percent forecast; four user-assigned identities; GitHub OIDC federation",
                 0.0, "no charge", "in IaC"))

    core_total = sum(r[3] for r in rows)

    # --- DR (designed, before wave 2) -----------------------------------------------------------
    dr = []
    c, s, i, m = pg_server(p("pg_d8_ea_h"), p("pg_ssdv2_gib_ea"), p("pg_ssdv2_iops_ea"), p("pg_ssdv2_mbps_ea"), ha=False)
    dr.append(("DR", "PostgreSQL cross-region replica, East Asia", "D8ds_v5 (same SKU as the primary so WAL replay keeps up), SSD v2 1,024 GiB / 12,000 IOPS / 300 MB/s, async; promoted only in a region outage",
               c + s + i + m, f"compute 1.076 x 730 = {c:,.0f}; storage 1,024 x 0.15 = {s:,.0f}; IOPS 9,000 x 0.031 = {i:,.0f}; throughput 175 x 0.124 = {m:,.0f}", "designed (D-127, D-420, D-522)"))
    wal = U["dr_wal_gb_per_day"] * DAYS * p("inter_region_gb")
    dr.append(("DR", "Inter-region replication traffic", "WAL stream Southeast Asia to East Asia",
               wal, f"6.3 GB a day x 30.42 x 0.08 = {wal:,.0f}", "designed"))
    pe_ea = p("aca_env_pe_ea_h") * HOURS
    dr.append(("DR", "Container Apps environment East Asia (warm, apps at min 0)", "zone-redundant VNet-injected environment, api/worker/web at 0 replicas until promotion; weekly DR smoke",
               pe_ea + 20.0, f"environment private endpoint 0.155 x 730 = {pe_ea:,.0f}; smoke runs about 20", "designed"))
    acr_geo = U["acr_geo_replicas"] * p("acr_premium_geo_day") * DAYS
    dr.append(("DR", "ACR geo-replica East Asia, DR Key Vault, DR Front Door origin (disabled)", "image pulls and secrets survive a Southeast Asia outage",
               acr_geo + 1.0, f"replica 1.6666 x 30.42 = {acr_geo:,.0f}; vault about 1; Front Door origin no charge", "designed"))
    dr_total = sum(r[3] for r in dr)

    # --- Optional / outside the base ------------------------------------------------------------
    opt = []
    opt.append(("Optional", "BI path: Microsoft Fabric F2 + 10 Power BI Pro licences (D-560)", "F2 = 2 CU pay-as-you-go; VNet data gateway; archived partitions in the lakehouse",
                2 * p("fabric_cu_h") * HOURS + 10 * p("pbi_pro_user"), f"2 CU x 0.22 x 730 = {2*p('fabric_cu_h')*HOURS:,.0f}; 10 x 14 = 140", "deferred until the BI need is confirmed; pausing the capacity stops the charge"))
    opt.append(("Optional", "Azure Managed Redis Balanced B3", "replay cache, per-device rate limits, config version fan-out",
                p("redis_b3_h") * HOURS, f"0.081 x 730 = {p('redis_b3_h')*HOURS:,.0f}", "not in the as-built design (D24-20); reinstate only if the load test shows the per-replica limiters are not enough"))
    opt.append(("Optional", "Microsoft Defender for Cloud (PostgreSQL x 3 servers, Key Vault, Storage)", "threat detection on the data stores",
                3 * p("defender_pg_month") + 5.0 + 10.0, "3 x 15 + about 15", "subscription-level choice by the final-account owner"))
    opt.append(("Optional", "Staging environment in the final account", "D2ds_v5 HA (D-06 ladder), small Container Apps, Standard Front Door; scaled to the prod shape only inside a test window",
                650.0, "about 650 steady (docs/18 s3.3 non-prod); about 75 per 8-hour window at prod size", "needed for the 8,500-user proof and every release gate"))
    opt_total = sum(r[3] for r in opt)

    oneoff = []
    s1 = 12750 * 1.0
    oneoff.append(("One-off", "Load-test windows S1-S10 with Azure Load Testing", f"one full-fleet hour = 12,750 virtual users x 1 h = {s1:,.0f} VUH",
                   s1 * p("alt_vuh"), f"{s1:,.0f} x 0.15 = {s1*p('alt_vuh'):,.0f} per full-size run; plan 3 to 5 runs", "alternative: self-hosted Locust on Container Apps Jobs, about USD 20 per run"))

    assumptions = [(k, v[0], v[1], v[2]) for k, v in P.items() if "ESTIMATE" in v[2] or "page" in v[2]]
    return rows, core_total, dr, dr_total, opt, opt_total, oneoff, assumptions


def md_table(rows, with_status=True):
    out = ["| # | Resource | Specification | USD / month | How it is computed | Status |", "|---|---|---|---:|---|---|"]
    for n, (g, name, spec, cost, arith, status) in enumerate(rows, 1):
        out.append(f"| {n} | **{name}** ({g}) | {spec} | {cost:,.0f} | {arith} | {status} |")
    return "\n".join(out)


def main():
    rows, core, dr, drt, opt, optt, oneoff, assumptions = model()
    if "--json" in sys.argv:
        print(json.dumps({"core": rows, "core_total": core, "dr": dr, "dr_total": drt, "optional": opt, "optional_total": optt, "oneoff": oneoff, "prices": P, "usage": U}, indent=1))
        return
    print("### Core production (what the IaC creates on day 1)\n")
    print(md_table(rows))
    print(f"\n**Core total: about USD {core:,.0f} a month** (list prices, 730-hour month, Southeast Asia).\n")
    print("### Disaster recovery (designed, built before wave 2)\n")
    print(md_table(dr))
    print(f"\n**DR total: about USD {drt:,.0f} a month. Core + DR: about USD {core+drt:,.0f}.**\n")
    print("### Optional and non-production lines\n")
    print(md_table(opt))
    print(f"\n**Optional total if all are taken: about USD {optt:,.0f} a month.**\n")
    print("### One-off costs\n")
    print(md_table(oneoff))
    print("\n### Prices that are not in the Retail Prices API (estimates to confirm on the first invoice)\n")
    print("| Key | Value | Unit | Source |\n|---|---:|---|---|")
    for k, v, u, s in assumptions:
        print(f"| `{k}` | {v} | {u} | {s} |")
    by_group = {}
    for g, *_r in rows:
        by_group[g] = by_group.get(g, 0) + _r[2]
    print("\n### Core total by group\n")
    print("| Group | USD / month | Share |\n|---|---:|---:|")
    for g, v in sorted(by_group.items(), key=lambda kv: -kv[1]):
        print(f"| {g} | {v:,.0f} | {v/core*100:,.0f} % |")

    # Reservations (API 2026-10-29, priceType Reservation, Ddsv5 southeastasia: 641 per vCore for 1 year, 1,282 for 3 years)
    vcores_sea = 24          # primary 8 + HA standby 8 + in-region replica 8 (reservations cover all three)
    payg = vcores_sea * (p("pg_d8_sea_h") / 8) * HOURS
    r1 = vcores_sea * 641.0 / 12
    r3 = vcores_sea * 1282.0 / 36
    print("\n### Reservation lever (PostgreSQL compute, Southeast Asia, 24 vCores)\n")
    print("| Term | USD / month for 24 vCores | Saving a month | Saving a year | Rule |\n|---|---:|---:|---:|---|")
    print(f"| Pay as you go | {payg:,.0f} | - | - | the figures in the tables above |")
    print(f"| 1-year reserved (641 per vCore) | {r1:,.0f} | {payg-r1:,.0f} ({(payg-r1)/payg*100:,.0f} %) | {(payg-r1)*12:,.0f} | buy only after 30 stable days at D8 (D-426) |")
    print(f"| 3-year reserved (1,282 per vCore) | {r3:,.0f} | {payg-r3:,.0f} ({(payg-r3)/payg*100:,.0f} %) | {(payg-r3)*12:,.0f} | only once the SKU is confirmed by the load test and one year of running |")

    # Growth over time: the two lines that move (photos in Blob, database disk)
    print("\n### Lines that grow with time\n")
    print("| Month of full-fleet running | Photo archive (Blob) USD / month | Database disk | Note |\n|---|---:|---|---|")
    for month, hot, cool, cold, disk in ((1, 680, 0, 0, "1,024 GiB"), (3, 680, 1350, 0, "1,024 GiB"), (6, 680, 1350, 2050, "grow to 2,048 GiB (+USD 283 a month for the primary with HA, +141 per replica)"), (12, 680, 1350, 6200, "2,048 GiB"), (24, 680, 1350, 6200, "2,048 GiB; photos older than 365 days to Archive (0.004 per GB)")):
        cost = hot * p("blob_hot_ragzrs_gb") + cool * p("blob_cool_ragzrs_gb") + cold * p("blob_cold_ragzrs_gb") \
            + U["blob_writes_m"] * 100 * p("blob_hot_write_10k") + (U["blob_writes_m"] * 100 * p("blob_cool_write_10k") if month >= 2 else 0) \
            + (U["blob_writes_m"] * 100 * p("blob_cold_write_10k") if month >= 4 else 0) + U["blob_geo_gb"] * p("blob_geo_egress_gb") + U["queue_ops_m"] * 100 * p("queue_ops_10k")
        print(f"| {month} | {cost:,.0f} | {disk} | Hot {hot} GB, Cool {cool:,} GB, Cold {cold:,} GB |")


if __name__ == "__main__":
    main()
