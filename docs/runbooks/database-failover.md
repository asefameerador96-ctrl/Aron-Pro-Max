# Database failover (RB-02)

**Trigger:** alert `aron-<env>-pg-not-alive` or `aron-<env>-resource-health` names the PostgreSQL server, or the api
answers 503 on `/v1/health/ready` while `/v1/health` is 200 (process up, database unreachable).
**Who:** infra lane or the lead; the owner is told, not asked. **Time:** dev drill 2026-10-07 06:27 UTC: about **30 s** of
user-visible outage (readiness 503, then two timed-out probes), api reconnected by itself; the Azure failover call
itself returned after 429 s (zone 1 -> 2).

## What happens by itself

- Zone-redundant HA (dev rehearsal server and prod): Azure promotes the standby in the other zone; the server name and
  DNS stay the same; open connections drop.
- Phones keep selling offline; uploads retry with the same client UUIDs, so nothing is lost or doubled.

## Steps

1. Confirm the state (read only):
   ```
   az postgres flexible-server show -g <rg> -n <server> --query "{state:state, ha:highAvailability.state, zone:availabilityZone}" -o table
   curl -s -o /dev/null -w '%{http_code}\n' https://<front-door-host>/v1/health/ready
   ```
2. If HA is `FailingOver`, wait (up to 5 minutes) and keep polling `/v1/health/ready`.
3. If the server is `Ready` but the api stays at 503 for more than 3 minutes, the api has not reconnected: restart the
   active api revision (and the worker), which reopens the pools:
   ```
   az containerapp revision restart -g <rg> -n ca-aron-<env>-api --revision "$(az containerapp show -g <rg> -n ca-aron-<env>-api --query properties.latestReadyRevisionName -o tsv)"
   az containerapp revision restart -g <rg> -n ca-aron-<env>-worker --revision "$(az containerapp show -g <rg> -n ca-aron-<env>-worker --query properties.latestReadyRevisionName -o tsv)"
   ```
4. If the server itself is down with no HA (TEST profile `dev-lite` has none) or stays `Stopped`/`Updating` past 15
   minutes: open an Azure support case from the portal and consider RB-14 (restore) only if data is damaged.
5. Record in `docs/status/infra.md`: start, end, what fixed it, minutes.

## Drill

Actions > **drill** > `mode: failover`, `confirm: lead approved failover drill` (dev is unavailable for about 1 to 2
minutes; pick a quiet time). The summary gives the failover duration and the time until the api is ready again.
