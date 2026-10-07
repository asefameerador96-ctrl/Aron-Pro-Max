# Request (infra → lead): Service Health alert needs a subscription-scoped read right (AUD-REL-04)

From: infra lane, 2026-10-07.

AUD-REL-04 asks for a Service Health alert. A Service Health activity-log alert must be scoped to the subscription;
the deploy identity has rights on `rg-aron-dev` only (by design, N-012 scope check), so creating it would most likely
fail the deploy. The Bicep is written and switched off (`enableServiceHealthAlert` in `infra/modules/alerts.bicep`).

**Decided 2026-10-07 (lead): deferred to the final account; the deploy identity is not widened.** Options considered:
1. Grant the GitHub deploy identity **Reader** on the subscription (read only; the scope check still proves it cannot
   deploy elsewhere), then infra sets `enableServiceHealthAlert = true` in `infra/params/dev.bicepparam`.
2. Leave it for the final account, where `infra/bootstrap-azure.ps1` runs with the owner's subscription rights.

Everything else in AUD-REL-04 (api 5xx, restarts, no replica, PostgreSQL not alive, Resource Health for the group,
which includes PostgreSQL HA failover and degraded HA) deploys with the next infra change.
