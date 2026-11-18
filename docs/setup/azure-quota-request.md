> **FOR THE FINAL ACCOUNT ONLY.** Do not submit any of these in the TEST account (`docs/28`: no quota requests there). They
> are filed in the **final** subscription, in the region chosen for the platform (default **Southeast Asia**), now that it exists.

# Azure quota requests for Aron (owner action, final account)

First written by the infra lane 2026-10-06; rewritten 2026-11-18 (D-FINAL-INFRA-07) for the final subscription, with the numbers
reconciled across `docs/35` (02 s4.3) and `docs/33` B13.2 row 18, which disagreed (40 vs 64 vCores, 100 vs 128 cores). The ordered
list of everything the owner does, and the cost of each size, is in `docs/status/infra-final.md`.

Quota requests can take **1 to 5 working days** (PostgreSQL: "24 to 48 hours" per Microsoft Learn; the others are reviewed case by
case and can be refused for regional capacity). Microsoft Learn ("Troubleshoot environment capacity errors in Azure Container Apps")
is explicit that **capacity is not quota**: a refusal for capacity is solved by retrying later or by another region, not by a
bigger request. File them **as soon as the subscription is handed over**.

## 1. Why the VM quota is not the problem

The bootstrap's compute snapshot shows **Total Regional vCPUs** and low-priority vCPUs. Those are **virtual machine** quotas. Aron
runs **no virtual machines**: Container Apps (consumption profile), PostgreSQL Flexible Server, Front Door and Azure Load Testing
each have their **own** quota, listed below. The VM quota does not need to change.

## 2. What the design consumes

Sizing from `docs/18` s3.1 and s3.4, `docs/35` 02 s4.3 and `infra/params/{stage,prod}*.bicepparam`. "Fleet" = 8,500 SRs, about 9,850
app users and 130 web users. "Day 6" = load test at 1.5 times the fleet, a 3 times burst, a failover drill and a point-in-time
restore drill (`docs/23` s5). **Two environments** live in the final subscription: `prod` and `stage`.

| Quota | Scope | Request | Arithmetic |
|---|---|---|---|
| **Azure Database for PostgreSQL flexible server: vCores, General Purpose (Ddsv5)**, region | subscription and region | **64** if staging stays prod-shaped (as committed); **40** if staging is "staging-lite" (D2ds_v5, decision D3 in the status file) | prod: D8 primary 8 + zone-redundant standby 8 + in-region read replica 8 = **24**; a restore-drill server 8 = **32**; headroom for one more D8 during a migration or a second restore 8 = **40**. Stage prod-shaped adds another 24 = **64**; staging-lite (D2ds_v5 with HA) adds 6 and fits in 40. |
| **PostgreSQL: region access with zonal dependency** (zone-redundant HA) | subscription and region | **enable**, same support request | zone-redundant HA needs it where it is gated |
| PostgreSQL vCores, East Asia (DR tier, **only when the owner approves the DR tier, before wave 2**) | subscription and region | **24** | cross-region replica 8; after promotion 8 + 8 standby |
| **Container Apps: Managed Environment Consumption Cores** | per managed environment | **100** for `cae-aron-prod`; **100** for `cae-aron-stage` (needed only during test windows) | replica maxima api 30 x 1 + worker 10 x 1 + web 6 x 0.5 + migrate 0.5 = 43.5 vCPU at the maxima; a revision swap in Multiple revision mode briefly runs old and new api replicas together, so up to about 75; 100 is 1.3 times that |
| **Container Apps: Managed Environment Count** | subscription and region | **2** in the platform region (stage + prod); **1** in East Asia for the DR tier | one environment per stage and region; "when you request a change to your regional environment quota, you should request a corresponding change to your global environment quota" (Learn, Container Apps FAQ) |
| **Azure Load Testing: concurrent engine instances / engine instances per run / test duration** | subscription and region | **40 / 40 / 24 h** | 1.5 x 9,842 app users at about 500 users per engine = 30 engines; the 3 x burst raises the request rate, not the user count; 8-hour soak. Needed only for the one certified run (about USD 1,905); the Locust-on-Jobs alternative needs no quota. |
| Front Door Standard/Premium, Key Vault, Storage, ACR, Log Analytics, Event Grid | various | **none** | defaults are far above one of each |
| Total Regional vCPUs (VMs) | subscription and region | **none** | Aron runs no virtual machines |

The Load Testing resource itself is created on Day 6 only (not in the Bicep yet); the quota must exist before then. Premium SSD v2
for PostgreSQL with HA and geo-redundant backup is supported in Southeast Asia and East Asia (Learn, "Premium SSD v2 storage option in
Azure Database for PostgreSQL flexible server", supported features); confirm both on the support ticket.

## 3. Portal steps

Sign in to <https://portal.azure.com> as the subscription owner, with the final subscription selected.

### 3a. PostgreSQL vCores and zone access (support request)

1. **Help + support** > **Create a support request** (or <https://portal.azure.com/#create/Microsoft.Support>).
2. Problem description: type `quota`, select **Service and subscription limits (quotas)**, **Next**.
3. **Issue type**: Service and subscription limits (quotas). **Subscription**: the final subscription.
   **Quota type**: **Azure Database for PostgreSQL flexible server**. **Next**.
4. **Additional details** > **Enter details**:
   - Quota type **Region access with zonal dependency (Availability Zones)**, Location the platform region,
     vCores **40** or **64** (see the table), series **General Purpose Ddsv5**. **Save and continue**.
   - If the form also lists a separate vCore quota item, set **General Purpose Ddsv5 vCores** in the region to the same number.
5. Summary text to paste:
   > Field-sales platform for 8,500 users in Bangladesh. Zone-redundant HA Flexible Server D8ds_v5 plus an in-region read replica,
   > a staging environment and a restore drill in <region>, Premium SSD v2. Need <40 or 64> General Purpose Ddsv5 vCores with
   > zonal access by <date one week from today>.
6. Severity B or C, contact by e-mail, **Create**.

### 3b. Container Apps (portal)

Subscription-level quota: portal > **Quotas** > **My quotas** > Provider **Azure Container Apps** > the subscription > search
**Managed Environment Count** > the region > pencil icon > **New limit** **2** > **Submit** (Learn, "Request quota changes for Azure
Container Apps"; the request is fulfilled automatically or routed to Azure Support).

Per-environment quota: only after the first deploy created the environment (`cae-aron-stage`, then `cae-aron-prod`):
**Resource group** > the environment > **Settings** > **Quota** > **Managed Environment Consumption Cores** > pencil > **New limit**
**100** > **Submit**.

### 3c. Azure Load Testing engines (support request)

1. <https://portal.azure.com/#create/Microsoft.Support> > `quota` > **Service and subscription limits (quotas)**.
2. **Quota type**: **Azure Load Testing**. **Next** > **Enter details**.
3. Location the platform region: **Concurrent engine instances 40**, **Engine instances per test run 40**, **Test duration 24 hours**
   (one line each). **Save and continue** > **Create**.

## 4. Before the quotas arrive

- Nothing fleet-sized is created before the owner says yes to the size and the monthly budget (`docs/28` rule 2,
  `docs/status/infra-final.md` section 3c).
- If the first deploy fails with "exceeding approved ... Cores quota" or "Provisioning is restricted in this region", that is the
  PostgreSQL request above; the deploy can be re-run unchanged once it is approved. "Maximum Allowed Cores exceeded for the Managed
  Environment" is the Container Apps request (3b).

## 5. How to check what was granted

- PostgreSQL: the support ticket reply states the new limit.
- Container Apps: the environment's **Quota** page shows the limit and the current usage; the Quotas page shows the environment count.
- Load Testing: **Quotas** > **My quotas**, filter by provider.
