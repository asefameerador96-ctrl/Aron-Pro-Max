> **WITHDRAWN FOR THE TEST ACCOUNT (2026-10-06).** Do not submit these requests now. They are for the FINAL account only, after the move (docs/28).

# Azure quota requests for Aron (owner action, urgent)

Written by the infra lane, 2026-10-06. Quota requests can take **1 to 5 working days** (PostgreSQL: "24 to 48 hours"
per Microsoft Learn; the others are reviewed case by case and can be refused for regional capacity). Submit all of
them **today**, in the subscription that holds `rg-aron-dev`, region **Southeast Asia**.

## 1. What the snapshot showed, and why it is not the problem

`bootstrap-azure.ps1` saved **Total Regional vCPUs: 10** and **Low-priority vCPUs: 3**. Those are **virtual machine**
quotas. Aron runs **no virtual machines**: Container Apps (consumption profile), PostgreSQL Flexible Server, Front Door
and Azure Load Testing each have their **own** quota, listed below. The VM quota does not need to change.

## 2. What the design consumes

Sizing from `docs/18` s3.1 and s3.4 and `infra/params/*.bicepparam`. "Fleet" = 8,500 SRs, about 9,850 app users and
130 web users. "Day 6" = load test at 1.5 times the fleet, a 3 times burst, a failover drill and a point-in-time
restore drill (`docs/23` s5).

| Quota | Scope | Dev now (pilot) | Fleet / Day 6 peak | **Request** |
|---|---|---|---|---|
| **Azure Database for PostgreSQL flexible server: vCores, General Purpose (Ddsv5)**, Southeast Asia | subscription + region | 4 (D2ds_v5 primary + zone-redundant standby) | 24 = D8 primary 8 + HA standby 8 + read replica 8; plus 8 for the restore drill server = **32** | **40** (headroom for one more D8 during a migration or a second restore) |
| **PostgreSQL: region access with zonal dependency** (zone-redundant HA) | subscription + region | needed now | needed | **enable** for Southeast Asia (same support request) |
| **Container Apps: Managed Environment Consumption Cores** | per environment (`cae-aron-dev`) | 6.5 max (api 3 x 1 + worker 2 x 1 + web 2 x 0.5 + migrate 0.5) | 43.5 at the replica maxima (api 30 x 1, worker 10 x 1, web 6 x 0.5, migrate 0.5); a revision swap briefly runs old and new api replicas together, so up to about 75 | **100** |
| **Container Apps: Managed Environment Count** | subscription + region | 1 | 2 (dev + the move-rehearsal group, `docs/23` s6) | **2** if the current limit is lower |
| **Azure Load Testing: concurrent engine instances** | subscription + region | 0 | 1.5 x 9,850 users / about 500 users per engine = 30 engines; the 3 x burst raises the request rate, not the user count | **40** |
| **Azure Load Testing: engine instances per test run** | subscription + region | 0 | 30 | **40** |
| **Azure Load Testing: test duration** | subscription + region | n/a | 8-hour soak (battery day mirror) | **24 h** if the default is lower |
| Front Door Standard/Premium, Key Vault, Storage, ACR, Log Analytics, Event Grid | various | 1 each | 1 each | none (defaults are far above 1) |
| Total Regional vCPUs (VMs) | subscription + region | 0 | 0 | **none** |

The Load Testing resource itself is created on Day 6 only (not in the Bicep yet); the quota must exist before then.

## 3. Portal steps

Sign in to <https://portal.azure.com> as the subscription owner.

### 3a. PostgreSQL vCores and zone access (support request)

1. **Help + support** > **Create a support request** (or open <https://portal.azure.com/#create/Microsoft.Support>).
2. Problem description: type `quota`, select **Service and subscription limits (quotas)**, **Next**.
3. **Issue type**: Service and subscription limits (quotas). **Subscription**: the Aron subscription.
   **Quota type**: **Azure Database for PostgreSQL flexible server**. **Next**.
4. **Additional details** > **Enter details**:
   - Quota type **Region access with zonal dependency (Availability Zones)**, Location **Southeast Asia**,
     vCores **40**, series **General Purpose Ddsv5**. **Save and continue**.
   - If the form also lists a separate vCore quota item, set **General Purpose Ddsv5 vCores** in Southeast Asia to **40**.
5. Summary text to paste:
   > Field-sales platform for 8,500 users in Bangladesh. Zone-redundant HA Flexible Server D8ds_v5 plus an in-region
   > read replica and a restore drill in Southeast Asia, Premium SSD v2. Need 40 General Purpose Ddsv5 vCores with
   > zonal access by <date one week from today>.
6. Severity B or C, contact by e-mail, **Create**.

### 3b. Container Apps environment cores (from the environment)

Available only after the first deploy created `cae-aron-dev` (it exists once the deploy run reaches the infra stage):

1. Open **rg-aron-dev** > **cae-aron-dev** > **Settings** > **Quota**.
2. Select **Managed Environment Consumption Cores** > pencil icon > **New limit** **100** > **Submit**.
3. If it is approved at once, nothing else is needed; otherwise it becomes a support ticket automatically.

Environment count (only if the **Quotas** page shows a limit below 2):
**Quotas** (search "Quotas" in the portal) > **My quotas** > Provider **Azure Container Apps** > region
**Southeast Asia** > **Managed Environment Count** > pencil > **2** > **Submit**.

### 3c. Azure Load Testing engines (support request)

1. <https://portal.azure.com/#create/Microsoft.Support> > `quota` > **Service and subscription limits (quotas)**.
2. **Quota type**: **Azure Load Testing**. **Next** > **Enter details**.
3. Location **Southeast Asia**: **Concurrent engine instances 40**, **Engine instances per test run 40**,
   **Test duration 24 hours** (each is one line in the form). **Save and continue** > **Create**.

## 4. Before the quotas arrive

- Dev stays at the pilot size (`infra/params/dev*.bicepparam`): PostgreSQL D2ds_v5 with its zone-redundant standby
  (4 vCores), api 1 to 3 replicas, worker 1 to 2, web 1 to 2. Nothing fleet-sized is provisioned until the Day 6 load
  test, and only after these requests are approved.
- If the first deploy fails with "exceeding approved ... Cores quota" or "Provisioning is restricted in this region",
  that is the PostgreSQL request above; the deploy can be re-run unchanged once it is approved.

## 5. How to check what was granted

- PostgreSQL: the support ticket reply states the new limit.
- Container Apps: `cae-aron-dev` > **Quota** shows the limit and current usage.
- Load Testing and Container Apps environment count: **Quotas** > **My quotas**, filter by provider.
