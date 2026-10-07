# Runbooks

Owner: infra lane (deploy, rollback, platform) with the lead. Numbering follows docs/18 s7.6 (RB-01 to RB-54).
Each runbook states its trigger, the commands, who runs them, and the time measured in a drill. A time marked
"not drilled" is a design figure, not a measurement.

| Runbook | docs/18 id | Status |
|---|---|---|
| [rollback-bad-deploy.md](rollback-bad-deploy.md) | RB-01 (API availability), deploy part | written 2026-10-07; dev drill not yet run |
| [rollback-bad-migration.md](rollback-bad-migration.md) | RB-14 (point-in-time restore), migration part | written 2026-10-07; restore drill is N-059 / move doc s5a |

Still to write from the drill results (AUD-REL-05): RB-02 database failover, RB-14 full restore, RB-52 geo-restore
(after the final account).
