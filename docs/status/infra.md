# Infra lane status

Updated 2026-10-07 20:35 UTC (fifth infra session).

## Fifth infra session, 2026-10-07 17:40 UTC: read this first (the fourth session's handover below still applies)

**First slice smoke, deploy run 37676392733 (zw, 19:41 UTC):** dev seed Succeeded (image built, 04 left out,
aron-dev-seed-password created), smoke steps 1 login, 2 bundle (outlet SMOKE-SR-001 = 61, route 1), 3 baseline PASSED;
step 4 upload FAILED 401 ERR_DEVICE_PROOF_INVALID "device key unknown" (SyncApi refuses keyless devices while
cfg.device.require_enrolled is on, kept on per the lead). Fix lane/infra ff7579c4: the seed device gets a REAL P-256 key
(private in Key Vault aron-dev-smoke-device-key, public JWK + RFC 7638 thumbprint set by the seed job only over the
placeholder) and the smoke signs X-Device-Proof; nothing relaxed. Same head: dbPerAppLogins = true in dev (db closed the
grants gap, V0029; db confirmed app_jobs ⊇ worker_rw) and the worker check (image, Running replica, 0 restarts, 90 s).

**Deploy run 37673797109 (INT b85d81fd, 19:21 to 19:31 UTC): ALL GREEN, dblogins FIXED.** "database logins succeeded"
(first time; SQL as a mounted file), "main.bicep skipped" (infra unchanged since 9941cfe), health gate build = b85d81fd,
ready 200, web /login 200, release marker written. Worker revision with 9114b63 (no signing key) deployed without error;
the health gate checks the api only, so the worker's start is not separately proven. Next for per-app logins: switch
`dbPerAppLogins = true` only after `docs/requests/db-runtime-roles-gaps.md` (api_rw DELETE grants) is closed by db.

**dblogins ROOT CAUSE (deploy run 37669977875, 19:00 UTC):** for the first time the console log arrived:
`psql:/tmp/logins.sql:47: ERROR: syntax error at or near "$" / LINE 1: DO $`. Container Apps (Kubernetes) expands
`$(VAR)` in env values and turns `$$` into `$`, so the SQL's `DO $$ ... $$` blocks arrived broken through `ARON_SQL`.
The fix already on INT (b8a83d87: the SQL as a mounted secret file, no expansion) is right; run 147 deployed f8ba8a16,
which predates it, so the first deploy with the fix is run 148 (b85d81fd). Also proven in run 147: **"main.bicep
skipped"** (infra unchanged since 9941cfe) and the release marker. Trap for the future: never pass text containing
`$` through a Container Apps env value.

**SR slice smoke (lead request 18:27, ruling 18:29), lane/infra 634dbd69, waits for promotion:** after the health gate,
non-blocking, table in the run summary. `infra/scripts/slice-smoke.py` as `sr1001` on the seeded dev phone through
Front Door: login, bundle, one sale at its own outlet SMOKE-SR-001 (visit, memo, line, close), the same records in a new
batch (must be duplicate), the batch replayed, server_totals of the batch answers unchanged by the re-upload, memo read
(SKIPPED: `GET /v1/memos` is in the contract but not served; neither is `GET /v1/sync/totals`), app/home tile polled up
to 5 min, then the sale is voided (whenever the memo was accepted). Dev seed: committed `param devSeed = true` in
`infra/params/dev.apps.bicepparam`; the seed image (psql image + argon2 + db/seed WITHOUT 04, the global dev relaxations,
+ `infra/sql/devseed-smoke-outlet.sql`) runs through the dblogins job by template override; `aron-dev-seed-password` is
generated in Key Vault and hashed in the job. Not proven here: the image build (no Docker daemon in the lane container).
If login fails on enrolment: the server reads cfg globally (no user/device-scoped override exists): tell the lead.
Opus checker: 3 defects (unserved endpoints, void not covering steps 5 to 8, stub inventing endpoints), all fixed.

**Deploy run 145 (INT eaac3ad5 = zl with the probe, 17:27 to 17:51 UTC): SUCCESS.** Health gate through Front Door:
`/v1/health` 200 with `X-Aron-Api: 1` and build = eaac3ad5 (the INT head), ready 200, web `/login` 200; migrations
succeeded; images by digest. Still "Argument list too long" there (5f37940 not promoted yet), so main.bicep re-applied.
**dblogins probe read:** probe A (image only) **Succeeded** (`psql 16.15`), probe B (A + the four Key Vault refs)
**Succeeded** (all four "set"); the real execution failed again ("No replicas found"). So neither the image nor a secret
reference: the cause is the job's 7 KB `ARON_SQL` env value. **Fix on lane/infra (this commit):** the SQL is a Container
Apps secret projected as a file (`storageType: Secret`, only `runtime-logins.sql`, mounted at `/sql`, the same path CI's
image smoke uses); the command is `psql ... -f /sql/runtime-logins.sql`; no SQL in the environment. `dbPerAppLogins`
stays false until a dev deploy shows dblogins Succeeded.

**Deploy run 144 (INT 4fd5c4d, 16:49 UTC) failed; dev stays on c992c9c (healthy).** Two infra defects, both fixed on
lane/infra (needs promotion; until then every INT deploy can fail the same way):
1. **App Insights agent download:** Maven Central answered HTTP 429 on all 5 attempts at the image build. Fix:
   `fetch-ai-agent.sh` tries Microsoft's GitHub release of the agent after Maven Central on every attempt (the download
   Learn documents); the same pinned SHA-256 decides (checked here: the GitHub jar is byte-identical, 93a70c8f...).
2. **Infra stage never skipped:** `params_unchanged` passed the parameter JSON through argv; with the attestation roots
   it exceeds the 128 KiB per-argument limit ("/usr/bin/python3: Argument list too long", deploy.sh line 248), so
   every deploy re-applied main.bicep (and re-PUT Front Door). Fix: files instead of argv; the test reproduces the old
   error. The first deploy after promotion should log "main.bicep skipped" when infra/ is unchanged.

**Not yet seen:** the dblogins probe (zj, 0cd495f) and the worker without the signing key (9114b63) are NOT on INT yet
(checked 17:00); run 144 stopped before dblogins. Read probe A/B in the first deploy after they land (guide below).

**Built this session (lane/infra):**
- **N-062 observability:** log alerts `syncErrors` (aron.sync.* errors) and `aggregationStuck` (3+ aggregation worker
  errors in 15 min), over `union traces, exceptions` (the Java agent sends errors with a throwable to `exceptions`),
  evaluated every minute (ingestion 1 to 3 min + 1 min evaluation: inside 5 minutes in practice, not guaranteed);
  shared ops workbook "Aron operations (appi-aron-dev)" (sync volume/5xx/p95, errors by logger, aggregation failures,
  slow endpoints, latest errors) with release annotations on its charts; `infra/scripts/release-marker.sh` writes a
  release annotation (Category Deployment) after the health gate, never fails the deploy. Budget alert: present in
  code; on dev Azure reports the cost policy off, so no budget exists (warning in every run, owner's billing setting).
  Opus checker: 1 defect (showAnnotations placement), fixed; notes taken (a stopped worker raises no alert: add a
  no-telemetry alert later; dev-lite has log alerts off, so the proof runs in the dev profile). **Proof still to run on
  dev:** a seeded sync error and a seeded aggregation failure, each alerting within 5 minutes and visible in the workbook.
- **drill.sh** picks the profile server by the `aron-infra` output `postgresServerName` (handover item 6 done).
- **Bookkeeping:** infra rows built on Day 3 recorded in infra.csv by their backlog ids (proofs still open are named
  in each note); `tools/my-rows.py infra --todo` now lists only N-062 (proof), N-064 (Day 7).
- **N-057:** already built as parameters (autoscale, read replica, pools; on only in stage/prod); load-test acceptance
  is a final-account item.

**Wall-clock gate (task 3):** no infra or workflow script depends on it. The scan reads only Kotlin/Java test sources
(backend, android, shared, db); infra tests are Python/shell and read no clock to decide pass/fail (date calls in
infra/scripts are runtime timestamps, lock deadlines and drill names). The flip to `--blocking` on 2026-10-09 is a
one-line ci.yml edit (`wallclock-scan.py --blocking .`); offenders are backend's.

**Governance:** `tools/github-governance.ps1` already requires "Repository gates (secrets, migrations, contract)"; the
live protection on main still lists "Contract lint" until someone with admin rights re-runs the protection step
(laptop session, before the first gate pull request). Not a blocker today.

**N-064 (Day 7) plan:** signed release APKs already come from ci.yml on INT (`aron-release-signed-dev-<run>`);
release-app.yml stays inert until the final account. Left: a release manifest (versions, SHA-256, api/web digests),
upload to the admin release store (F-ADM-027) and the install/upgrade proof on enrolled phones (owner's hands).

Restore drill: still only after "owner approved restore drill".

## HANDOVER (fourth cloud infra session -> next), 2026-10-07 16:55 UTC: read this first

**Proven on dev (deploy run 37649162760, c992c9c, first green dev deploy):** what-if guard with live PostgreSQL zones
(primary 2, standby 1), main.bicep, images built once and deployed by digest, PITR point before migrations, migrations,
apps + Front Door routes, health gate (build = commit, ready 200, web /login 200), storage CORS by preflight (Front Door
origin + PUT + `x-ms-blob-type,content-type` only; other origins and methods 403), `aron-dev-resource-health-recovered`
alert created. Table: "FIRST GREEN DEV DEPLOY" below.

**Open, in order:**
1. **dblogins** fails on every deploy: execution Failed, "No replicas found for execution", no console or system log =
   the replica is never created (psql never runs). Does NOT block the apps while `dbPerAppLogins` is false (warning +
   summary row "Database logins | FAILED"); blocks again once the apps use those logins. Ruled out: wrong-arch image,
   identity/ACR/Key Vault roles (same identity as the working migrate job), secret names, the 300 s timeout (fails in
   ~1 min), the compiled command. **Read the probe** (lane/infra 0cd495f, train candidate zj): in the first deploy log
   after zj, lines `probe A (...)` and `probe B (...)` follow the dblogins warning.
   - A Failed or "could not start" -> image/registry pull of `tools/postgres@sha256:7218...`.
   - A Succeeded, B Failed -> a Key Vault secret reference of the job (db-direct-url, pw-app-api/worker/jobs).
   - Both Succeeded (B prints "ARON_... set") -> the 7 KB `ARON_SQL` env value or the real command: move the SQL out of
     the env (into the image, or a secret volume).
   After it passes: `dbPerAppLogins = true` in dev params (db's V0029 grants are on INT).
2. **lane/infra 9114b63** (worker gets no token signing key; backend change on INT since c992c9c): after its deploy,
   check the worker revision starts. Caveat: the worker identity still has vault-wide Secrets User; per-secret scoping is
   a final-account item.
3. **Infra-stage skip:** run 143 wrote the resource-group tag `aron-infra-sha`; a deploy with no infra change should log
   "main.bicep skipped" (verify; it also stops the per-apply Front Door rollout behind the 13:11 Sev4 mail).
4. **Wall-clock gate** flips to `--blocking` on 2026-10-09; 14 offenders (backend) at last count; tell the lead daily.
5. Rows: N-062, N-057 (prod-only parameters), N-064 (release candidate, Day 7), seeded-failure proof of the REL-04 alerts,
   support key script (`docs/requests/android-sys-support-key.md`, needs Key Vault write: deploy identity or the owner).
6. Follow-up from the laptop session (below): `drill.sh` should pick its server by exact name, before the next drill.

**Waits on people:** restore drill only after the lead relays "owner approved restore drill". main's branch protection
still requires "Contract lint" (folded into Repository gates): re-run the protection step of
`tools/github-governance.ps1` before the first gate pull request. Nobody but the deploy workflow has `az` now.

**Final-account items:** Service Health alert, per-secret Key Vault scoping per identity, `activeRevisionsMode` Multiple
(stage, prod), restore drill rehearsal, deploy freeze window.

**Traps:** `az deployment group create` has no `--tags` (broke run 142; check new az flags against the CLI reference,
there is no az here); `az postgres flexible-server list` tsv prints a list one value per line; `az acr import` takes a
tag or a digest, never both; a laptop session (owner account, +0600 commits) may push lane/infra: fetch and merge it
before every push.

## Day 3, 16:30 UTC (fresh session after the team stall): deploy run 37608044223 fixed

- **Failure:** the `deploy` run on INT 107d3a5 (10:32 UTC) stopped at the what-if guard: "psql-aron-dev-7i7g53:
  protected property properties.highAvailability.standbyAvailabilityZone would change '1' -> '2'". Nothing changed in Azure.
- **Cause:** the forced-failover drill swapped the server (live now: primary zone 2, standby zone 1, HA healthy). main.bicep
  never passed zones, so the module defaults (primary 1, standby 2) asked Azure to move the server back.
- **Fix (lane/infra):** `postgresPrimaryZone` / `postgresStandbyZone` parameters in main.bicep (creation defaults 1/2), read from
  `ARON_PG_PRIMARY_ZONE` / `ARON_PG_STANDBY_ZONE` in every profile; deploy.sh reads the live zones of the exact profile server
  (`psql-aron-<env>-<suffix>`; drill restores and the replica are ignored; rollbacks skip the lookup) before the parameter
  comparison and the what-if. The first deploy after this re-runs the infra stage once (new parameters).
- **Proof:** read-only what-if against rg-aron-dev with the live zones, then the guard: exit 0 (test account). Test
  `test_postgres_zones_follow_the_live_server_after_a_failover` runs the deploy.sh block with a fake az over 11 cases.
  Opus checker: 1 defect confirmed (prefix match caught drill restores), fixed; Opus re-check: a given ARON_NAME_SUFFIX
  with '-' was missed (now exact-name match) and two test gaps (closed; 8 of 8 mutations of the block caught).
- **Follow-up (not done):** `infra/scripts/drill.sh` picks its server with `starts_with(name,'psql-aron-') | [0]`, so a
  leftover `-drill-` restore or a promoted `-r1` could be chosen; apply the same exact-name match before the next drill.
- **Proven on Azure (test account):** INT deploy run 37619397240 logged "PostgreSQL live zones: primary 2, HA ZoneRedundant,
  standby 1", passed the what-if guard and applied main.bicep. It then failed later, at the psql image import:
  `az acr import` refused a source with a tag AND a digest. Fixed (digest-only source, test
  `test_psql_image_is_imported_by_digest_only`, Opus checker: no defect); waits for the next INT deploy to prove it.
- **Superseded old-session fix:** 93d351f / 58b3068 (old infra session) read the standby zone as empty (az tsv prints a
  `[0].[a,b]` list one value per line), so INT runs 37613509210 and 37613922726 were refused again; replaced by the block above.
- **Trap for the next drill:** every forced failover swaps the zones again; the deploy now follows that by itself.
- Restore drill stays blocked until the owner says "owner approved restore drill".

## Day 3, 16:30 UTC: FIRST GREEN DEV DEPLOY (run 37649162760, c992c9c) — proven on Azure

| Item | Result |
|---|---|
| what-if guard with live PostgreSQL zones (primary 2, standby 1) | passed |
| main.bicep apply (new `aron-dev-resource-health-recovered` alert created) | passed |
| images built once, deployed **by digest** (backend `@sha256:5657854f...`, web `@sha256:336eeb80...`) | passed |
| PITR restore point recorded before migrations (16:16:13Z) | passed |
| migrations (by digest) | passed |
| apps + Front Door routes | passed |
| health gate: `/v1/health` 200 with `X-Aron-Api: 1` and build = c992c9c, `/v1/health/ready` 200, web `/login` 200 | passed |
| storage CORS (preflight from this session, 16:35 UTC): Front Door origin + PUT + `x-ms-blob-type,content-type` -> 200 with exactly those; foreign origin -> 403; DELETE -> 403 | passed |
| alerts deployed (seeded-failure proof still to run) | deployed |
| dblogins | **FAILED, not blocking** (per-app logins off): execution `5uyo1ah` status Failed, end null, "No replicas found for execution" = the replica was never created (not psql). No console or system log. |

dblogins next: `infra/scripts/dblogins-probe.sh` now runs on that failure in the deploy itself, two short executions of the
same job with template overrides: A (image only, `psql --version`) and B (A plus the four Key Vault secret refs; prints
only set/unset). A fails -> image/pull; B fails -> a secret reference; both pass -> the 7 KB `ARON_SQL` value or the
command. The next INT deploy reports it. `aron-infra-sha` tag: written by this run's apply, so the next deploy shows
whether the infra stage is skipped.

## Day 3, 16:10 UTC: worker without the token signing key (backend-core request, AUD-SEC-07)

`docs/requests/infra-worker-no-signing-key.md`: the backend change (worker and migrate start without
`ARON_JWT_SIGNING_KEY`, `SettingsTest`) is on INT since c992c9c, so the worker container no longer gets the
`jwt-signing-key` / `jwt-kid` Key Vault references; only api replicas do (test `WorkerWithoutSigningKey`). Note: the
worker identity still has Key Vault Secrets User on the whole vault (identities.bicep), so this removes the key from the
worker's environment, not its ability to read it; per-secret role scoping is a final-account item (logged here, not built).
The api's ~24 s drain fits Container Apps' default 30 s termination grace; nothing here lowers it.

## Day 3, 15:20 UTC: my regression fixed (deploy run 37639072495)

Deploy run 142 (62ee65f) failed at `az deployment group create ... --tags aron-sha=...`: "unrecognized arguments"
(`az deployment group create` has no `--tags`; my 14:00 change, not caught locally because no az here). Nothing was
changed in Azure (the error is argument parsing, before any request). Fix: the commit is recorded after a successful
apply as the resource-group tag `aron-infra-sha` (`az tag update --operation Merge`, the same call and right the
deploy lock already uses) and read with `az group show`. The test now also asserts the create call carries no `--tags`.
Lesson: every new az flag gets checked against the CLI reference before push (no az in lane containers).

## Day 3, 14:30 UTC: dblogins no longer blocks the apps while per-app logins are off

Deploy runs 37630304505 and 37632012265 (with the system-log query) failed like 37624445094: dblogins execution Failed
within about a minute, and Log Analytics has neither console nor system log for it. The job is checked against the
working migrate job (same identity, registry, Key Vault, environment; image is a multi-arch index with linux/amd64;
compiled command correct). Nobody has `az` until the owner runs the two read-only commands sent to the lead.
Change: a dblogins failure now prints the execution record and the container log stream straight from the platform
(`az containerapp job execution show`, `az containerapp job logs show`), and it stops the deploy only when the apps
use the per-app logins (`dbPerAppLogins` output of aron-apps-migrate is not false). With the switch off (today) it is a
warning plus a "Database logins | FAILED" summary row, so the apps, the health gate, alerts and CORS get deployed and
proven. Test `DbLoginsGate`. The system-log query now matches with `contains` (hyphenated names).

## Day 3, 14:10 UTC: Front Door health alert, infra-stage skip, recovered alert (lead)

- **Front Door Sev4 alert 13:11 UTC:** fired during deploy run 37624445094, while main.bicep was applying (12:58 to 13:07)
  and right after. main.bicep re-PUTs the Front Door profile and endpoint (modules/frontdoor.bicep) on EVERY apply, and
  apps.bicep re-PUTs the api/web origin groups, origins and routes on every apps deploy. An identical PUT still starts
  a Front Door configuration rollout, the likely cause of a transient Degraded event. Dev answered 200 throughout
  later probes (lead 13:52).
- **Infra stage skip fixed:** the skip compared infra/ with the commit live in the api, which never advanced while
  dblogins failed, so every INT push re-applied main.bicep. main.bicep's deployment now carries the tag
  `aron-sha=<commit>` and the skip diffs against that commit (fallback: the live api commit). The first apply after
  this lands still runs once and writes the tag. Test `InfraStageSkip`.
- **Alert wiring (as built):** action group `ag-aron-dev` (email, common alert schema) <- `aron-dev-resource-health`,
  an Activity Log alert on category ResourceHealth with status Unavailable or Degraded, scoped to the resource group.
  Activity-log alerts have no severity field, so the mail shows Azure's default Sev4, and they are stateless: **no
  "Resolved" mail ever follows.** Added `aron-dev-resource-health-recovered` (Available after Unavailable or Degraded,
  free) so a transient event is closed in the inbox. Metric alerts (pg-not-alive Sev1, pg-cpu Sev2, ...) do send Resolved.

## Day 3, 13:30 UTC: dev deploy reaches the database logins job

Deploy run 37624445094 (c13e75d), the first with the zone fix (laptop session, 347687d) and the psql import by digest
(laptop session, ac9e44a): **proven on Azure:** what-if guard passes with the live zones (primary 2, standby 1),
main.bicep applied, the psql image imported into ACR by digest, both app images built once and run **by digest**
(`aron-backend@sha256:b3c0e071...`), migrations succeeded. **Failed:** the dblogins job execution
`caj-aron-dev-dblogins-gf9ulpy` ended Failed with NO console log in Log Analytics (6 min), so the apps were not
updated (the old revision keeps serving). Not yet proven: health gate, alerts, storage CORS (all after dblogins).
deploy.sh now also prints the Container Apps system log of a failed execution (image pull / start errors). The
console log of gf9ulpy is needed from a session with az (asked via the lead). Zone block in deploy.sh: not touched.

## Day 3, 11:00 UTC: first green INT deploy blocked by the failover drill (fixed on lane/infra)

(Superseded at 16:30 UTC by the section above: the 11:00 lookup took `[0]` of a name-prefix match, could read a drill
restore, and hid az errors; its test `PostgresZonesAfterFailover` was folded into the new behavioural test.)

- **Deploy run 128 (12a823e, first green INT head) failed safely in the what-if guard:** `psql-aron-dev-7i7g53`
  `properties.highAvailability.standbyAvailabilityZone would change`. The 06:27 failover drill moved the primary to
  zone 2 (standby now 1), and main.bicep re-sent the creation zones 1/2, i.e. asked Azure to move the standby. Nothing
  was changed (the guard stops before any write). Fix: deploy.sh reads the server's live primary and standby zones
  before the what-if and passes them (`ARON_PG_PRIMARY_ZONE`, `ARON_PG_STANDBY_ZONE` -> `postgresPrimaryZone`,
  `postgresStandbyZone`; empty for a new server = 1/2) in every profile. The guard itself is unchanged and still refuses
  any HA or zone change. Test `PostgresZonesAfterFailover`. The digest deploy, dblogins, health gate, alerts and storage
  CORS are still unproven on Azure: they run after the infra stage, on the first deploy that gets past it.

## Day 3, 10:00 UTC: three CI blockers for the first INT promotion (lead)

- **APK size baseline regenerated (SR armeabi-v7a +16.1 % on lane/android-core 2e34ef7, run 372).** Measured by
  building SR release locally on INT 97feb99 and on 2e34ef7 and diffing the APKs (apkanalyzer with the R8 mappings):
  the universal APK grew 0.63 MB: dex +463 KB compressed (+755 KB uncompressed), the new CameraX native library
  `libimage_processing_util_jni.so` (+24 KB armeabi-v7a, +33 KB arm64-v8a), nothing else above 5 KB. Of the dex growth,
  `androidx.camera` (camera-core, camera-camera2 with its CameraPipe backend, camera-view; F-SYS-030 still capture) is
  +658 KB (87 %), `androidx.exifinterface` +34 KB, our own code +93 KB (`core.media` +47 KB, `core.database` +24 KB),
  the rest under 21 KB each. One CameraX backend artifact is declared (no camera-video, no duplicates); no unshrunk
  library, no font or asset growth. Explained by approved code, so the baseline is reset to the CI sizes of 2e34ef7
  (MB to two decimals from the run 372 size table, times 2^20). SR armeabi-v7a is now 4.37 MB against the 30 MB
  budget (docs/31). The +15 % fail and +5 % warn rules are unchanged.
- **Container images red on f1db789:** fixed in f9429de (`infra/scripts/fetch-ai-agent.sh`, see the commit).
- **Web red on 7b0b015, 8afb986, 3828ab0:** flaky e2e in `web/e2e/config-journeys.spec.ts` (lines 26 and 112), no web or
  contract change on those heads; routed to web-config via the lead.

## Day 3, 08:45 UTC (fourth infra session): CI audit items 2 to 6, drill polling, enrolment settings

- **Last green INT run (audit item 2):** `tools/ci/last-green-int.sh` prints one line (run number, sha, finish time,
  age in minutes, INT head verdict); exit 3 when INT has had no green run for more than 30 minutes
  (`LAST_GREEN_ALERT_MIN`). Every ci run writes the line to its summary (step in "Detect changed areas", never fails).
  First reading, 08:40 UTC: **last green INT run #163 0c63e39 finished 04:57 UTC, 221 min ago; INT head 97feb99: failure.**
- **Train candidates (item 3), verified in ci.yml:** the concurrency group is per ref, and each candidate has its own ref
  `lane/train-<time>`, so no lane push can replace a candidate run; only a newer push to the SAME candidate ref replaces
  its pending run. A candidate cancelled while pending is re-run by `workflow_dispatch` on that ref (no inputs; the
  changes job then runs everything). The deploy never starts from a candidate (it needs a push run on INT).
- **Gate jobs folded (item 4):** the "Contract lint" job is now three steps of "Repository gates (secrets, migrations,
  contract)" (same `changes.contract` condition). "Detect changed areas" stays separate: every job fans out from it,
  so folding it into the gates job would make all jobs wait for the gates. `tools/github-governance.ps1` drops the
  required check "Contract lint" in the same commit (a test now asserts every ci job is a required check and no
  required check lacks a job). **Lead: main's live protection still lists "Contract lint"; the laptop operator re-runs
  the protection step of the governance script before the first gate pull request, or that PR waits forever.**
- **Dependabot (item 5):** no major bump is proposed in any ecosystem (gradle, npm, actions, docker); the Kotlin, AGP and
  KSP lines stay held entirely.
- **Local Android builds and HTTP 429 (item 6), measured in this lane container 06:43 to 06:48 UTC:** single requests to
  repo.maven.apache.org, the Google mirror of Central, dl.google.com (Google Maven: AndroidX, AGP), plugins.gradle.org,
  services.gradle.org and Robolectric android-all on Central all answered 200. A cold build (empty Gradle cache, fresh
  SDK via `tools/android-sdk.sh` in 26 s) of `:android:feature-memo:testDebugUnitTest :android:core-ui:testDebugUnitTest`
  passed in 5 min 20 s with no 429. **What can still 429:** only Maven Central itself (repo1/repo.maven.apache.org) under
  bursts from the shared lane IP. Gradle tries the mirror first (settings.gradle.kts); Google Maven is not mirrored and
  did not rate-limit. Robolectric fetched its android-all jars from Central directly (outside Gradle), so it had no
  mirror: **fixed** in build.gradle.kts (`robolectric.dependency.repo.url` = the mirror in lane containers; CI keeps
  Central). Both android-all jars (API 34 and 36, 150 and 213 MB) came through the mirror. So the status lines "local
  Gradle is unusable / CI is the compiler" in android-core-ui, android-sr-a and android-sr-b are stale: local compile and
  Robolectric tests work (first run downloads about 1 GB; later runs are cached).
- **Drill polling (lead item):** `infra/scripts/drill.sh failover` probes readiness in the background every 5 s from
  BEFORE the Azure call; the summary separates the call duration ("NOT the outage") from the user-visible outage (first
  failed probe to ready again) and fails when the api never comes back or fails again. Behavioural test with stub az
  and curl (`check_infra.py Drills`). RB-02 updated. Restore drill: still waiting for "owner approved restore drill".
- **Device enrolment settings (lead #3, N-031):** the api gets `ARON_PUBLIC_API_URL` (Front Door https URL; without Front
  Door the api's own Container Apps address) and `ARON_ATTESTATION_ROOTS` (comma list) on every deploy, in every profile
  (dev, dev-lite, stage, prod) from `infra/params/attestation-roots.json` (default of the `attestationRootsSha256`
  parameter; a profile may override). Source:
  https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate , retrieved 2026-10-07:
  SHA-256 of each root certificate DER, the two current roots (RSA to 2042, ECDSA "Key Attestation CA1" to 2035) plus the
  two earlier roots still valid (to 2034 and 2036); the earlier root that expired 2026-05-24 is left out.
- **INT red (audit priority):** INT run 346 (97feb99) fails only "Android debug APKs, unit tests and lint"; reproducing
  locally to name the failing task and owner.

## Day 3 late (06:45 UTC): failover drill measured, process change, handoff

**Forced failover drill, dev, 2026-10-07 06:27 UTC (lead approved; run 37581596131):** primary zone 1 -> 2. The Azure
`restart --failover Forced` call took 429 s to return. Independent probe through Front Door every 5 to 8 s:
06:27:49 ready 200; 06:27:57 ready 503 (liveness 200); 06:28:06 both timed out; from 06:28:27 both 200 again. So the
**user-visible outage was about 30 s** and the api reconnected on its own (no revision restart). Caveat: drill.sh
polls readiness only after the Azure call returns, so its "430 s" line overstates the outage: next session, poll in the
background during the call (small fix in infra/scripts/drill.sh). RB-02 gets the measured figure.
Restore drill: waits for the owner's yes, relayed by the lead as "owner approved restore drill".

**Process change (lead, 06:30 UTC, docs/26 s3):** push only to `lane/infra`; the integrator promotes green lane heads
via `lane/train-*` to INT. First lane/infra push: deploy concurrency moved to the job (CI audit s5 item 5).

**Next session, in order (lead's CI-audit list, then rows):**
1. Done: deploy concurrency on the job (lane/infra). Verify after promotion that a red ci run no longer replaces a
   waiting green deploy.
2. `tools/ci/last-green-int.sh` (last green INT run and its age, for the evening report) plus a summary line on every
   ci run; alert idea: INT without a green run for 30 min.
3. ci.yml: `lane/train-*` candidate runs never replaced by lane pushes (one group per ref does it; verify) and a
   cancelled pending candidate can be re-dispatched (workflow_dispatch exists; verify inputs).
4. Fold the three tiny gate jobs into one (keep the required-check names in tools/github-governance.ps1 in step; tell
   the lead if a name changes).
5. Dependabot: ignore major bumps of eslint and of any package failing CI today.
6. Android local compile: confirm the committed Maven mirror covers Google Maven, AndroidX/AGP and Robolectric
   android-all, or document what still 429s; update the stale lines in core-ui and sr-b status (via the lead).
7. Checker on storage CORS and the Semgrep lane base: CORS confirmed working (headers match the web upload code);
   Semgrep override limited to lane pushes (PRs and main keep the gate base), pushed. Custom domain later: add to uploadOrigins.
8. Switch `dbPerAppLogins` to true when db says the DELETE-grant migration is on INT; watch the first deploy.
9. Flip the wall-clock scan to `--blocking` on 2026-10-09 (lead routes the 14 offenders).
10. Rows: N-062 observability, N-057 (prod-only parameters), N-064 release candidate (Day 7); seeded-failure proof of
    the REL-04 alerts; drill.sh background polling.
**Not yet proven on Azure** (INT deploys were skipped while INT was red): deploy by digest, the dblogins job and psql
import, the health gate with build check, the new alerts, storage CORS. The first green INT deploy proves them; read
its summary.

## Day 3 afternoon (replacement session): deploy safety, supply-chain gates, per-app database logins

Rows built, each with an independent Opus checker (3 rounds so far: 8, 7 and pending findings; all fixed or noted):

- **AUD-DG-07, AUD-DG-06, AUD-REL-06, AUD-DG-04 (deploy safety).** `infra/deploy.sh`:
  - The ordering guard re-reads the live commit (the api's `ARON_BUILD`) right before the migrations and the apps,
    so a newer live commit makes the run exit 0 "Skipped". A re-run of the live commit always deploys fully, so the
    health gate runs again.
  - Each image is pushed once, its tag locked (`--write-enabled false`), and deployed **by digest**. A re-run or a
    rollback reuses the registry image. `acr-purge.yml` (weekly, `infra/scripts/acr-purge.sh`) keeps the newest 30
    tagged images per repository, anything younger than 3 days and anything in use; untagged manifests are never touched.
  - Health gate (`infra/scripts/smoke.sh`): `build` must equal the commit, `/v1/health/ready` 200 (the response is
    printed when not), web `/login` 200. Behavioural tests with a stub curl (retry while the old build answers).
  - Rollback: deploy dispatch input `rollback_sha` (own concurrency group): earlier integration commit only, no build,
    no migrations, no infra stage, migrate job stays on the newest image. Runbooks: `docs/runbooks/` (RB-01 deploy part,
    RB-14 migration part); dev rollback drill not yet run.
  - PITR restore point written to the summary before the migrations. `ARON_DEPLOY_FREEZE_DHAKA` (unset until real
    users) refuses deploys in a Dhaka window, re-checked before migrations and apps, wraps midnight.
  - api `activeRevisionsMode` is a parameter: Single in dev and dev-lite (unchanged), Multiple in stage and prod, where a
    failed gate puts traffic back on a serving revision of another build. Proven only in the final account.
- **AUD-SEC-05 + scanning (lead item 2).** In ci.yml, job names unchanged: OSV-Scanner 2.6.0 on `web/package-lock.json`
  (high or critical in a production dependency fails; dev-only and unknown severity warn; dated allow file),
  Semgrep 1.179.0 (image by digest; Kotlin, TypeScript, React, Next.js, Actions, Dockerfile packs; only findings NEW since
  the gate base fail; test code skipped), `npm audit --omit=dev --audit-level=high`, Trivy 0.75.0 on both images
  (fixable CRITICAL fails), dependency-review on pull requests, `web/.npmrc ignore-scripts=true` with a reviewed list
  (`tools/ci/npm-install-scripts.txt`) enforced by a lockfile check, `@redocly/cli` pinned to 2.59.0. The web runtime
  image now takes Debian security updates (the newest node:22-bookworm-slim carries three fixable perl-base criticals).
  Existing Semgrep finding routed to web: `docs/requests/web-supply-chain-gates.md` (GCM tag length).
- **Per-app database logins (`docs/requests/db-runtime-roles.md`).** `infra/sql/runtime-logins.sql` (idempotent,
  exactly one membership, INHERIT, never superuser/CREATEROLE/BYPASSRLS, passwords via `\getenv`, never echoed),
  run by the new `dblogins` job (psql image imported into ACR by digest) after the migrations and before the apps.
  api connects as `app_api` (api_rw), worker as `app_jobs` (jobs_rw ⊇ worker_rw); migrate keeps the admin login.
  `infra/scripts/db-login-secrets.sh` generates the passwords once and derives the URLs from the Bicep admin URLs.
  CI image smoke runs the same SQL and boots api/worker on those logins. Proven locally on PostgreSQL 16 with a
  non-superuser admin (as on Azure); first proof on Azure is the next dev deploy. No PostgreSQL server setting changed.
- **Per-ABI APKs, CI side.** `tools/ci/release-apks.py` accepts one APK per app or ABI splits plus the universal APK;
  size gate and signing (ci.yml, release-app.yml) use it, so android-core can enable splits without a CI change.

- **Per-app logins: switch OFF (checker finding).** `api_rw` lacks DELETE on `route_planned`, `mfa_secret`, `user_scope`
  that admin flows use; `dbPerAppLogins = false` everywhere until `docs/requests/db-runtime-roles-gaps.md` lands. The
  logins are still created and repaired on every deploy (now with V0020 memberships, `apply_login_limits()`, per-grantor
  revoke and an exact-membership check; a superuser-made grant stops the job with a clear message).
- **Azure Blob SAS issuer** (`docs/requests/backend-admin-blob-sas.md`, lead item): `backend/app/.../AzureBlobSasIssuer.kt`,
  user-delegation SAS via the api managed identity, JDK HTTP client; offline tests; full backend:app suite green.
  Follow-up for backend-admin in the request (a stored 24 h read URL expires).
- **SR Maps key** in the release job (lead item). **APK baseline** regenerated after N-041 (SR armeabi-v7a +18 %).
- **AUD-DG-08**: no manual prod dispatch; docs/30 s5 item 2. The governance script stays create-only (lead ruling: it never deletes; `staging`/`prod` do not exist).
- **AUD-REL-04**: alerts api 5xx, restarts, no-replica (only where min replicas > 0), PostgreSQL not alive, Resource
  Health for the group; Service Health **deferred to the final account** (lead ruling: no wider deploy identity; listed in docs/28). Seeded-failure
  proof still to run.
- **AUD-TP-6**: warnings-as-errors mechanism (list empty until `docs/requests/kotlin-warnings-as-errors.md`), flaky e2e
  reported, SeededDayLoadTest bound 5 s.
- **AUD-REL-05**: `drill.yml` (failover: phrase "lead approved failover drill"; pitr: "owner approved restore drill",
  copy deleted in the same run, cost in the summary), runbooks RB-02 and RB-14. Drills not run yet (approvals).
- **CI red on INT (06:10 UTC), not infra:** `:backend:config:test` `ConfigToolsTest.whatIfCountsVisitsWhoseVerdictWouldChange`
  (expected 0, was -4) in runs 293, 300 and 309; routed to the lead. The APK size failure in the same runs is fixed above.

- **Semgrep triage (lead, 06:00 UTC).** The gate fails only on findings NEW since its base (Semgrep diff-aware
  `--baseline-commit`), ERROR and WARNING only. On a lane branch the base is now the integration commit the lane last
  merged, so another lane's landed code never counts as new; on the integration branch it is the last green head.

  | Finding | File | Decision | Reason |
  |---|---|---|---|
  | react-insecure-request x5 | `web/e2e/config-journeys.spec.ts` | skipped (test code) | Playwright calls the local contract mock over http://127.0.0.1; test code never ships. `.semgrepignore` skips `web/e2e/`, `web/tests/`, `**/src/test/`, `*.test.ts(x)`, `*.spec.ts` (05:47 UTC push) |
  | gcm-no-tag-length | `web/src/lib/auth/seal.ts:35` | kept, routed | tag is sliced at exactly 16 bytes, so not exploitable as written; `{ authTagLength: 16 }` asked of web (`docs/requests/web-supply-chain-gates.md`) |
  | workflow-run-target-code-checkout | `.github/workflows/deploy.yml` | `nosemgrep` with reason | the job runs only for a green ci run of a PUSH to the integration branch, never a pull request |
  | secrets-inherit | `.github/workflows/promote-prod.yml` | `nosemgrep` with reason | same repository; deploy.yml reads only the Azure, FCM and Maps secrets |
  | parse warning | `.github/workflows/promote-prod.yml:87` | noted | Semgrep's bash parser does not handle that `$(( ... ))` line; a warning, not a finding, and it never fails the gate |

  No rule pack was dropped.
- **Browser uploads** (`docs/requests/web-admin-asset-upload-csp.md`): storage CORS for the web origin (Front Door
  endpoint, or the web app address without it), PUT only, headers `x-ms-blob-type` and `content-type`; `ARON_BLOB_ORIGIN`
  on the web app.

- **Wall-clock reads in tests (lead item):** `tools/ci/wallclock-scan.py`, a step in "Repository gates", REPORT mode
  until 2026-10-09 (then `--blocking`), escape `// wall-clock-ok: <reason>`, self-tests in `tools/ci/test_gates.py`.
  First run, 2026-10-07: **14 offenders in 4 modules**, all backend (android, shared and db: none):

  | Owner lane | Module | Reads |
  |---|---|---|
  | backend | `backend/analytics` | 2 |
  | backend | `backend/config` | 4 |
  | backend | `backend/masterdata` | 6 |
  | backend | `backend/platform` | 2 |

**Dev health (05:04 UTC):** `/v1/health`, `/v1/health/ready` and web `/login` 200 through Front Door.

## Day 3 (2026-10-07): CI gates of docs/31 s2, stage profile, promotion workflows, cost reading

Lead decisions applied: the nightly PostgreSQL stop/start is **not** approved and not built; the GitHub governance
script is not run and no branch settings changed.

**CI gates (all green on the real runner, run of c22aa48; full run: checks 3 to 5 min, deploy 5 to 10 min):**

| Gate | Where | Fails on |
|---|---|---|
| gitleaks 8.30.1 (pinned SHA-256) | ci.yml `gates`, every push and PR | any secret in the pushed commits (whole history when the base is unknown); 10 reviewed false positives pinned by fingerprint (`tools/ci/gitleaksignore`), generated `contract/slices/` skipped (its source is scanned) |
| Migrations | ci.yml `gates` when db/migrations changes | an edited, renamed or deleted shipped migration; duplicate or out-of-order versions; squawk 2.67.0 findings on NEW migrations (`tools/ci/squawk.toml`: lock_timeout, CONCURRENTLY, NOT VALID, no drop/rename) |
| Contract | ci.yml `gates` when contract/ changes | an oasdiff 1.33.0 breaking change, unless the same push bumps `info.version` AND adds `docs/requests/contract-*.md` |
| SR release + APK size (F-SYS-036) | ci.yml `android-release` | `assembleRelease` breaking (android-core ask); 30 MB per ABI, 70 MB installed (estimate), +15 % over `tools/ci/apk-size-baseline.json` (warn +5 %). SR release today: **10.2 MB universal, 4.3 MB arm64-v8a, 3.2 MB armeabi-v7a** |
| SBOM | ci.yml `images` | SPDX for aron-backend and aron-web (syft 1.54.1 pinned), artifact `sbom-<sha>`, 90 days |
| CodeQL | codeql.yml (separate, never blocks the deploy) | java-kotlin (shared, backend, the three apps; real compile) and javascript-typescript, `security-extended`; push, PR, weekly. First run: 3.3 min and 1.2 min |

Each gate is proven to fail on a deliberate violation: `tools/ci/test_gates.py` (15 tests, run in the gates job with
the real binaries and in `infra/validate.sh`). Two fresh checkers: 5 + 3 confirmed defects, all fixed.

**Concurrency, verified:** every push-triggered workflow groups by ref and commit and never cancels (test over all
workflows); only pull requests cancel their own older run. The deploy is serialised in Azure, not by GitHub: since
today a **lock held for the whole deploy** (tag `aron-deploy-lock` on the group), after two runs interleaved
(`DeploymentActive` on `aron-apps`, run of 4385442). An older commit waiting for the lock skips once a newer one is live.

**Migrate failure followed up:** the repeat on e2bcabc showed its cause (the console log is now in the deploy log):
the job's first database connection timed out after 5 s on a fresh replica (`total=0`), not a migration problem. The
deploy runs the job once more on exactly that error; the root fix is requested from backend-core
(`docs/requests/backend-migrate-connect-retries.md`).

**Stage and promotion (docs/30 s1, s2):** `infra/params/stage*.bicepparam`, prod-shaped, own VNet 10.41/16, a parameter
file only. `promote-prod.yml` (a server-v* tag on main, CI green, a successful staging deploy soaked, then deploy.yml
prod) and `release-app.yml` (app-v* tag: three release APKs against the final origin, signed with apksigner from the
four `ANDROID_SIGNING_*` secrets, size gate, GitHub Release with SHA256SUMS) are **inert** until
`ARON_FINAL_ACCOUNT=true`. deploy.yml refuses prod outside promote-prod, outside the final account and off a server-v*
tag. Open for the lead: `tools/github-governance.ps1` creates environments `staging` and `prod`, but the workflows
and the OIDC subjects use `azure-dev` / `azure-prod`; `azure-prod` needs the owner as reviewer and a tag policy
`server-v*` before the first promotion.

**Cost reading (g):** `cost-report.yml` (daily 08:05 Dhaka, read-only) asked Azure for the month-to-date cost.
Answer: `SubscriptionCostDisabled: Cost views are disabled for subscription users because the cost policy is turned
off by your account admin` (run 37570748133). **No Azure cost figure is readable** by the deploy identity until the
owner turns that billing policy on (the same switch as the budget); the owner can read it in the portal.
Estimate from list prices (Southeast Asia retail API, 2026-10-07), with the apps now running:

| Item | USD/day |
|---|---|
| PostgreSQL D2ds_v5 zone-redundant HA (2 x 0.244/h) + SSD v2 128 GiB x 2 | 12.9 |
| Container Apps: api (0.5 vCPU, 1 GiB), worker (0.25, 0.5), web (0.25, 0.5) always on; active 0.000034/vCPU-s, idle 0.000004, memory 0.000004/GiB-s, minus the monthly free grant | 1.0 to 3.6 |
| Front Door Standard + WAF policy | 1.4 |
| Log Analytics ingestion after the free 5 GB a month (cap 1 GB/day at 2.99/GB) | 0 to 3.0 |
| Container Apps environment network, registry, DNS, alerts, storage, Key Vault | about 1.2 |
| **Total** | **about 16.5 to 22 a day (central about 18; about 560 a month)** |

This is about USD 2.5 a day above the 15.5 reported before the apps went live. For 2026-10-10: about 4 days since
2026-10-06 13:09 UTC, so roughly USD 65 to 85 spent on this group. These are estimates, not readings.

### Day 3 later: lead's throughput, deploy-safety and audit items

- **Dev health (lead question, 04:40 UTC):** `GET /v1/health` and `/v1/health/ready` through Front Door: 200,
  `X-Aron-Api: 1`, build e2bbf8e. The failed deploy of e2bcabc was the migrate connect timeout (fixed, see above).
- **AUD-DG-03 done in CI:** dev origin and increasing versionCode in every APK; three release APKs signed with the
  release key on integration pushes (`aron-release-signed-dev-<run>`). Per-ABI splits requested from android-core
  (`docs/requests/android-core-abi-splits.md`).
- **CI throughput:**
  - Docs-only pushes start no run.
  - One ci run per ref, and a newer push cancels the older one. Comparisons use the last green head, and gitleaks
    scans the whole history.
  - The deploy is its own workflow (`workflow_run`), never cancelled, and only the newest pending deploy waits.
  - CodeQL runs only on pull requests to main and weekly.
- **Runner minutes per full run (measured job times):**

  | Job | Minutes |
  |---|---|
  | gates | 0.3 |
  | contract | 0.2 |
  | JVM | 2.5 |
  | Android debug | 4.5 |
  | release APKs (3 apps) | about 3 |
  | web | 1.6 |
  | images | 2.6 |
  | infra | 1.2 |
  | **ci total** | **about 16** |
  | deploy, when something deployable changed | 6 to 10 |
  | CodeQL (PR or weekly only) | about 5 |

  Wall time is about 5 minutes of checks plus the deploy. The repository is public, so these minutes cost nothing
  today. Before it goes private, the owner sets an Actions spending limit.
- **Governance check (lead question):** the six required checks in `tools/github-governance.ps1` match the ci.yml
  job names exactly. Two required checks should be added there: `Repository gates (secrets, migrations, contract)`
  and `Release APKs and APK size gate`. On pull requests the workflow-level path filter does not apply. A job skipped
  by the changes filter reports success, so no required check stays pending.
- **Queue time, before and after (lead item 5):**
  - Before, per the lead's evidence: run 145's jobs waited about 13 minutes in the queue to run 3 minutes, and
    runs 172 to 181 were all queued. Every push had its own group, so nothing was ever superseded.
  - After the change (05:00 UTC): new-configuration runs 190 to 195 started their first job with no queue wait
    (0.0 min). There is one group per ref; a running run finishes and the older pending one is replaced.
  - 21 queued runs from before the change were superseded commits already contained in the head; I cancelled them
    once (queued only, never started). One more had already completed.
  - CodeQL now runs nightly plus on pull requests to main. Dependabot opens grouped weekly PRs, at most 3 per
    ecosystem. Dependabot PR #1 (eslint 10) was already closed.
- **Run 145 (0a947b7) "Shared, db and backend" failure:** not flaky. `AggregationTest >
  aCrashedWorkersClaimIsTakenOverAfterTheLease` inserted a `memo.created` event with a NULL version, which the db
  lane's `app.domain_event_type` registry rejects. That is a backend and db mismatch, fixed by a later commit.
  No infra action.
- **Next three rows (handoff):**
  1. AUD-DG-07, REL-06, DG-06 deploy safety: Container Apps revision traffic split with a health gate, and deploy
     the tested image by digest. The whole-deploy lock is done.
  2. Scanning gates that also work for a private repo: OSV-scanner for dependencies, Semgrep OSS with Kotlin and
     TypeScript rules, and `npm audit --omit=dev` as a warning.
  3. `docs/requests/db-runtime-roles.md`: per-app database logins (`app_api`, `app_worker`, `app_jobs`) as members
     of the V0014 roles, through a small job inside the VNet with passwords in Key Vault. The PostgreSQL server
     settings stay untouched (HOLD). Then AUD-DG-04, -08, REL-04, -05, SEC-05, TP-6 by day; Gradle dependency
     verification on Day 5 or 6.
- **Traps found:**
  - A called or `workflow_run` workflow sees null inputs, and `null == false` is true in Actions expressions. Always
    test `github.event_name` first.
  - `tools/slice-contract.py` has no `--help`: any argument other than `--check` regenerates every slice.
  - The deploy identity can write resource-group tags (needed for the lock). If it cannot, the deploy now stops at once.

## Owner decision 2026-10-06: HOLD (docs/28 "Exception approved")

The full-size resources the first deploy created in `rg-aron-dev` are **kept** for up to one week to rehearse the final
topology; **review date 2026-10-10** (spend report, then keep / shrink / delete). Nothing is deleted; no quota
requests; no fleet-sized additions.

- **Two profiles, same templates.** `infra/params/dev*.bicepparam` = **rehearsal profile**: exactly what exists
  (PostgreSQL GeneralPurpose D2ds_v5 zone-redundant HA, SSD v2 128 GiB, geo backup; Front Door Standard + WAF;
  VNet-injected Container Apps environment; ZRS storage; budget USD 130 with 50/90/100 % and forecast 100 %).
  `infra/params/dev-lite*.bicepparam` = the cheap **TEST profile** (Burstable B1ms, no VNet, no Front Door, scale to
  zero; about USD 35 to 55 a month), used only after an owner-ordered reset. `prod*` = FINAL profile.
- **Adopt, never recreate.** `infra/deploy.sh dev` runs `az deployment group what-if` first;
  `infra/scripts/whatif-guard.py` stops the deploy (nothing changed) if a PostgreSQL server would be deleted, created
  next to the existing one, or changed in sku, storage, HA, backup, network, version, zone or admin login. The
  reset check (`reset-to-profile.sh` in check mode) confirms the group matches the dev profile (nothing to delete).
- **Reset locked.** The `reset` workflow deletes only when the profile is `dev-lite` AND "confirm" is exactly
  `owner approved reset`; the script itself also refuses deletion without `OWNER_APPROVED=yes` and a `*-lite`
  profile. Everything else is a dry run.

- **CI GREEN on `dev`: run 37489113157 (commit aac06e0), 2026-10-06 15:45 UTC.** Migrations succeeded; apps updated;
  the deploy's own smoke test passed through Front Door (`GET /v1/health` -> 200 with `X-Aron-Api: 1`, build aac06e0;
  `HEAD` -> 200). One earlier run (37484856611, commit dd3fb58, same code) had its migrate job execution end `Failed`;
  the cause was not captured (the log was only in Log Analytics). The next run migrated fine; deploy.sh now prints a
  failed execution's console log, so a repeat will show its cause. Not called a flake: open until it repeats or not.
- **Nightly PostgreSQL stop/start (lead approved 2026-10-06): NOT built yet.** The lane's tool permissions blocked
  writing the stop logic in this session; it waits for the owner's direct confirmation. The server runs 24 h.

- **DEPLOYED on profile `dev` (CI run 37479574147, commit db50ae9), adopting the existing resources; nothing was
  deleted or recreated, and PostgreSQL was untouched by the what-if guard.** The run did: infra (adopted), Key Vault
  secrets (JWT key, FCM, web session secret), images, **migrations succeeded**, api + worker + web + Front Door routes.
  **Front Door address: `https://fde-aron-dev-7i7g53-gpgaa3fpgrhmdgaz.z03.azurefd.net`.** The run's own smoke
  test gave up after 10 minutes on Front Door's edge 404 (the routes were still propagating), so the CI job is red.
  Checked from outside at 15:09 UTC (about 23 minutes after the routes were created):
  `GET /v1/health` -> **200**, `X-Aron-Api: 1`, body `{"status":"ok","api":"/v1",...,"build":"db50ae94..."}`;
  `HEAD /v1/health` -> 200; `GET /v1/health/ready` -> 200; `GET /` -> 307 to `/login`, and `/login` -> 200 (web);
  `http://` -> 307 to `https://`. Smoke timeout on Front Door hosts raised to 45 minutes (commit after db50ae9).
  **No budget** yet (cost policy off, below).

- **Deploy of `dev` (CI run 37474197061, commit 9e5abfa): stopped at the what-if, nothing changed.** The reset check
  passed (all 28 resources KEEP, "nothing to reset: rg-aron-dev matches the dev profile"). Azure then refused the
  what-if: `401 - Budget experiences are disabled for subscription users because the cost policy is turned off by your
  account admin`. **No budget can exist in this subscription until the billing account admin (the owner, or the
  partner if the subscription is bought through a CSP) allows subscription users to view charges** (Cost Management +
  Billing > Policies; for a CSP the partner's "Azure usage" customer policy). Fix in the deploy: it reads the budgets
  first; on exactly that refusal it deploys without the budget and prints a warning (`::warning::No budget`). Any other
  failure stops the deploy. Once the policy is on, the next deploy creates the USD 130 budget. **Until then, spend
  e-mails do not exist**; the 2026-10-10 spend report is read from Cost Management by the owner or estimated from the
  inventory (about USD 15.5/day).

- **Second try (CI run 37477074848, commit 54f734b): the what-if guard stopped it, nothing changed.** The budget was
  skipped with its warning as designed. The guard refused `psql-aron-dev-7i7g53: properties.network.delegatedSubnetResourceId
  (Modify)`. Cause: the subnet id came from the network module's output, which what-if cannot evaluate, so every
  adopted server and environment looked as if it moved subnet. Fix: main.bicep builds the VNet and subnet ids from the
  names (`resourceId(...)`, with `dependsOn: [network]`); the guard now ignores case-only id differences and prints
  before -> after for anything it refuses. The other what-if lines (role assignment principalId, diagnostic-setting
  retention, ACR/VNet/storage read-only defaults) are what-if noise on unchanged resources, not PostgreSQL.

### Can the PostgreSQL server be stopped overnight with HA on? Yes (Learn), not done without the lead's word

- Learn, *High availability concepts*: "You perform operations such as stop, start, and restart on both primary and
  standby database servers at the same time." *Stop compute of a server*: compute billing stops immediately; the
  server can be briefly restarted for monthly maintenance and starts automatically after 7 days; no other management
  operation (including a deploy that touches the server) works while it is stopped. Storage keeps billing.
- Saving: compute is 2 x USD 0.244/h = USD 0.488/h (primary + standby; storage about USD 1.2/day continues).
  Stopped 20:00 to 08:00 Dhaka (12 h): **about USD 5.9 a day**, the day cost falls from about USD 15.5 to about
  USD 9.6; also stopping on Fridays (weekly off-day): about USD 11.7 more per Friday. For the rest of the rehearsal
  week (to 2026-10-10): about USD 25 to 35 saved.
- Effect: while stopped the api's readiness check fails, so Front Door answers 503; phones keep selling offline and
  upload when it is back (offline-first); the worker's jobs pause; a start takes a few minutes (5 to 8 when maintenance
  is pending). If ordered, a scheduled GitHub workflow can stop at 20:00 and start at 07:30 Dhaka using the deploy
  identity (Contributor on the group); not built yet.

## TEST profile = `dev-lite` (docs/28), used only after an owner-ordered reset

`infra/params/dev-lite*.bicepparam` = TEST profile; `dev*` = rehearsal profile; `prod*` = FINAL profile; same templates, every size and switch a
parameter (`privateNetworking`, `deployFrontDoor`, `enableLogAlerts`, `postgresSkuTier`, `postgresStorageType`, app
sizes, connection pools). TEST: PostgreSQL **Burstable B1ms** (B2s is USD 0.104/h = USD 76 a month on its own, so
B1ms), single zone, no HA, no replica, no geo backup, 32 GiB SSD v1, 7-day backup, public endpoint limited to Azure
services by the firewall, TLS; Container Apps without a VNet (no environment load balancer or public IPs), api 0-2
(scale to zero, 0.5 vCPU), worker 1 x 0.25 vCPU, web 0-1; **no Front Door/WAF** (the deploy smoke-tests the Container
Apps address); Storage LRS, ACR Basic; Log Analytics cap 0.5 GB/day, 30 days; no log-search alert rules; budget USD
70 (the Azure share of the owner's USD 100) with e-mails at 50/90/100 % and forecast 100 %. No Load Testing resource.

**Monthly estimate (list prices, Southeast Asia, Azure Retail Prices API 2026-10-06):**

| Line | USD/month |
|---|---|
| PostgreSQL B1ms compute (0.026/h) | 19 |
| PostgreSQL storage 32 GiB (0.138/GB) and backup (within the free 100 %) | 4.4 |
| Container Apps: worker 0.25 vCPU / 0.5 GiB always on (about 6 at the idle rate, about 20 if the JVM keeps it above 0.01 vCPU and it bills active), api and web scale to zero; monthly free grant 180,000 vCPU-s / 360,000 GiB-s / 2 M requests | 6 to 20 |
| Container Registry Basic (0.1666/day) | 5 |
| Log Analytics + Application Insights (pilot volume under the 5 GB/month free; cap 0.5 GB/day) | 0 to 5 |
| Key Vault, Storage LRS, Event Grid, metric alerts | about 1 |
| **Total** | **about 35 to 55** |

No environment management fee: it applies only to private endpoints, planned maintenance or dedicated profiles, none
of which the TEST profile uses (Learn, Container Apps billing).

## Inventory of rg-aron-dev (reset dry run, 2026-10-06 13:27 UTC, run 37470818234)

The ARM deployment of the cancelled run **completed** after the cancel. Nothing outside `rg-aron-dev` was read or
changed by the dry run (it lists only that group). Estimated cost at Southeast Asia list prices:

| Action | Type | Created (UTC) | Name | Est. USD/day |
|---|---|---|---|---|
| DELETE | PostgreSQL flexible server (GeneralPurpose D2ds_v5, zone-redundant HA standby, SSD v2 128 GiB, geo backup) | 13:09:48 | psql-aron-dev-7i7g53 | **12.9** (2 x 0.244/h compute + 2 x 128 GiB x 0.138/month) |
| DELETE | Container Apps environment (VNet-injected; its platform load balancer and 2 public IPs) | 13:08:11 | cae-aron-dev | about 0.8 |
| DELETE | Front Door Standard profile (+ endpoint fde-aron-dev-7i7g53, deleted with it) | 13:08:09 | afd-aron-dev | about 1.2 |
| DELETE | Front Door WAF policy | 13:08:09 | wafarondev | about 0.2 |
| DELETE | Storage account Standard_ZRS (empty) | 13:08:08 | starondev7i7g53 | about 0 |
| DELETE | Event Grid system topic | 13:08:30 | evgt-aron-dev-storage | 0 |
| DELETE | Virtual network | 13:07:43 | vnet-aron-dev | 0 |
| DELETE | NSG x 2 | 13:07:41 | vnet-aron-dev-snet-aca-nsg, vnet-aron-dev-snet-pg-nsg | 0 |
| DELETE | Private DNS zone (+ its VNet link) | 13:08:08 | aron-dev-7i7g53.private.postgres.database.azure.com | about 0.02 |
| DELETE (fixed script) | Log-search alert rules x 3 (billed per rule; off in the TEST profile) | 13:21:01 | aron-dev-batch5xx, aron-dev-batchP95, aron-dev-dashboardsP95 | about 0.15 |
| KEEP | Key Vault | 13:21:02 | kv-aron-dev-7i7g53 | about 0 |
| KEEP | Log Analytics workspace | 13:07:39 | log-aron-dev | about 0 (free 5 GB/month) |
| KEEP | Application Insights (+ its automatic "Failure Anomalies" rule) | 13:08:01 | appi-aron-dev | 0 |
| KEEP | Container Registry Basic | 13:08:08 | crarondev7i7g53 | 0.17 |
| KEEP | Managed identities x 4 | 13:21:31 | id-aron-dev-api/-worker/-migrate/-web | 0 |
| KEEP | Metric alerts x 3, action group | 13:21:01 | aron-dev-pg-cpu, -pg-storage, -pg-connections-failed; ag-aron-dev | about 0.01 |
| KEEP | Budget (not listed by the resource API; it is a group-scope Consumption resource) | | budget-aron-dev | 0 |
| **Total now** | | | | **about 15.5 a day (about 465 a month)** |

Confirmed by a second dry run on the fail-closed script (run 37471894786, 13:35 UTC): 13 resources marked DELETE
(the rows above, including the 3 log-search rules); everything listed as KEEP above is KEEP. Two child rows show as
KEEP in the raw listing (the DNS zone's `vnet-link` and the Front Door endpoint `fde-aron-dev-7i7g53`); they are
removed with their parents (the link explicitly before the zone).

After the reset and the TEST-profile deploy: about USD 35 to 55 a month. The three metric alerts point at the
PostgreSQL server; they are re-pointed at the new server by the next deploy (same names).

## URGENT: resources created by the cancelled run 37467503909 (old dev values)

Run 37467503909 (commit 0f90002) passed the OIDC sign-in and the **scope check** (a test deployment into
`rg-aron-scope-probe` was denied, creating a group was denied: N-012 acceptance proven), then submitted `main.bicep`
at 13:07 UTC with the **old** dev values (PostgreSQL General Purpose D2ds_v5 zone-redundant HA on SSD v2 with geo
backup, Front Door Standard, VNet-injected Container Apps environment, ZRS storage). Cancelling the GitHub job does
**not** cancel the ARM deployment, so these resources were most likely created (about USD 12 a day for PostgreSQL
alone). Their creation-time settings cannot be changed to the TEST profile in place.

- `infra/deploy.sh` now runs `infra/scripts/reset-to-profile.sh` in check mode before `main.bicep` and **stops without
  changing anything** when the group holds resources the profile cannot adopt, listing them.
- New manual workflow **reset** (GitHub > Actions > reset): dry run by default; deletes only the incompatible resources
  (PostgreSQL server, the VNet-injected environment with its apps and jobs, Front Door and WAF, ZRS storage and its
  Event Grid topic, VNet/NSGs/private DNS zone) when the resource group name is typed into "confirm". Key Vault, logs,
  registry, identities, alerts and budget are kept.
- **Owner decision: HOLD** (no reset). The reset now needs profile `dev-lite` and the words `owner approved reset`.

## Move runbook

`docs/setup/move-to-final-account.md` (new subscription, bootstrap, quotas, prod parameters, dump/restore and
reconciliation, blob copy, new Maps/Firebase keys, re-enrol phones, Day 6 proofs in the final account).

## First real deploy (CI run 35, commit 1991868): failed at the Azure sign-in, nothing reached Azure

- Container images and infra validation passed on GitHub's runner; the deploy job then failed at `azure/login`:
  `AADSTS700213: No matching federated identity record found for presented assertion subject
  'repo:asefameerador96-ctrl@252441038/Aron-Pro-Max@1403881436:environment:azure-dev'`.
- Cause: GitHub presents the **ID-qualified** subject for this repository; the bootstrap trusted only the classic
  `repo:<owner>/<repo>:environment:azure-dev`. Fixed in `infra/bootstrap-azure.ps1` (trusts both forms, exact match on
  this repository and environment only). **Owner action: re-run `infra/bootstrap-azure.ps1 -AlertEmails ...` once**
  (idempotent; it only adds the credential `github-environment-azure-dev-ids`). Then re-run the failed deploy job
  (GitHub > Actions > the latest ci run > Re-run failed jobs) or push any infra/backend change.
- The deploy now prints an explanation when the sign-in fails.

## Quotas (urgent owner action): `docs/setup/azure-quota-request.md`

The 10-vCPU snapshot is the VM quota, which Aron does not use. What the design needs: PostgreSQL Flexible Server
General Purpose Ddsv5 vCores 40 with zonal access, Container Apps consumption cores 100 per environment (after the
first deploy creates the environment), environment count 2, Azure Load Testing 40 engines. Dev stays pilot-sized
(PostgreSQL D2ds_v5 + standby = 4 vCores) until the load test.

## Done

- **Lead request (CI concurrency).** `ci.yml`: push runs are grouped by branch and commit and never cancelled; only
  `pull_request` runs cancel their predecessor. A dependency-free `changes` job gates every heavy job, so a docs-only
  push builds nothing heavy (a `docs/24` edit still re-runs the JVM drift tests). The Android job uploads the three
  debug APKs (`aron-debug-apks-<sha>`) on every successful run.
- **N-012 Azure IaC** (`infra/`, Bicep, one resource group, no subscription scope). Front Door (Standard dev / Premium
  prod) with WAF; zone-redundant Container Apps environment (workload profiles, VNet); ACR; PostgreSQL 16 Flexible
  Server with zone-redundant HA, PITR, geo-redundant backup, built-in PgBouncer, private access, optional read replica
  (with its own PgBouncer); Storage (media + bundles, user-delegation SAS only, Event Grid BlobCreated to a queue);
  Key Vault (RBAC) with the secret names of docs/24; Log Analytics + App Insights; alerts of docs/24 s13.1; budget on
  the group; four least-privilege managed identities; diagnostic settings everywhere. Dev (pilot) and prod (full
  fleet) parameter files. One command: `infra/deploy.sh <env>`.
- **N-013 CI/CD.** `ci.yml`: all actions pinned by SHA; new `web` job (web lane request) and `infra` job
  (`infra/validate.sh`); `deploy` job calls `deploy.yml` only on a push to the integration branch after every check
  passed. `deploy.yml`: branch and settings preflight (clear error when Azure is not set up), OIDC sign-in, scope check,
  then `infra/deploy.sh` (lock, ordering guard, infra, Key Vault seeding, images, migrate job first, apps, Private
  Link, smoke test through Front Door, budget check). No GitHub concurrency group (it would cancel pending CI runs).
- Bootstrap hardened: environment `azure-dev` accepts deployments only from the integration branch; the pull-request
  federated credential is removed; RBAC Administrator is limited by an ABAC condition to the eight data-plane roles.

Validation: `infra/validate.sh` passes (bicep build + lint with warnings as errors, 4 parameter files plus the
empty-variables case, 28 template/workflow checks, actionlint + shellcheck). Mutation run of the checker's list:
see the commit message. **Not validated (no Azure credentials in this session):** what-if, SKU/zone/quota
availability, ARM acceptance of property values, RBAC propagation timing, Private Link approval, budget API, Event
Grid delivery, the end-to-end smoke test. The first deploy run proves them.

## Checker findings (fresh reviewer, 13 confirmed, all fixed)

1 empty `ARON_BUDGET_AMOUNT` broke the first deploy; 2 push deploys blocked by the missing migrate role and the
exiting worker (now detected and skipped with a warning); 3 superseded-skip plus path filter could drop a deploy
(replaced by an ancestor check against the deployed commit); 4 job concurrency cancelled pending runs, push group
lacked the ref; 5 environment had no branch policy; 6 pull-request federated credential; 7 unconditional RBAC
Administrator; 8 scope check never tried another group; 9 replica without PgBouncer; 10 fixed budget start date;
11 Key Vault name reuse after a teardown (`ARON_NAME_SUFFIX`); 12 checks did not guard HA, geo backup, PgBouncer,
zone redundancy, budget notifications, WAF mode, workflow conditions (now asserted on compiled resources and exact
workflow expressions); 13 docs/24 edits skipped the JVM drift tests. Lower-confidence items also handled: infra stage
skipped when unchanged, password generation only on NotFound, self-repair of the deployer's Key Vault role, replica
deferred on the first prod deploy, Private Link waits for every origin, preflight inside the environment job, trimmed
alert e-mails.

## Day 2 (lead re-prioritisation, docs/27 scope change: nothing to provision for deferred programmes)

1. **Backend image and deploy** (`docs/requests/infra-backend-runtime.md`): new CI job **Container images** builds the
   backend and web images and boots them without Azure (`infra/scripts/image-smoke.sh`): migrate twice against
   PostgreSQL 16, api `/v1/health` and `/v1/health/ready` with `X-Aron-Api: 1`, worker still running after 15 s, web
   serving. The deploy now also needs this job. Locally (no Docker daemon here) the same sequence ran on the Gradle
   distribution against PostgreSQL 16 with a **non-superuser** database owner: 11 migrations then 0 (idempotent,
   `btree_gist` created), health and readiness 200 with the marker, worker alive, api healthy with the App Insights
   agent attached and no connection string. `ARON_BUILD` = image tag (commit) is now set for every backend role.
2. **btree_gist** (`docs/requests/db-azure-btree-gist.md`): already allow-listed since N-012
   (`azure.extensions = BTREE_GIST,PGCRYPTO,PG_STAT_STATEMENTS,POSTGIS`, asserted by the template checks). Flyway's
   `public` schema: the migrate job connects as the server admin login, which owns the `aron` database it creates;
   verified locally that a non-superuser owner can run all migrations. Residual risk until the first Azure run: the
   ownership of an ARM-created database (if it fails, the migrate job stops the deploy before any app changes).
3. **Web standalone and CI job** (`infra-web-standalone.md`, `web-infra-ci-job.md`): done on Day 1 (web job in
   `ci.yml`, web image, session secret from Key Vault); the image now also boots in CI.

### What waited for `infra/bootstrap-azure.ps1` (done 2026-10-06; re-run needed, see the top of this file)

Everything that needs a subscription; nothing else does:
- the first `deploy` run (infra, Key Vault seeding, images to ACR, migrate job in Azure, apps, Front Door smoke test);
- what-if of `main.bicep` (run by `validate.sh` only when `az` is signed in);
- the N-012 acceptance checks that need Azure: scope check against `rg-aron-scope-probe`, budget exists;
- SKU, zone and quota availability in Southeast Asia, ARM acceptance of every property, RBAC timing, Private Link
  approval (prod), Event Grid delivery.
Until then CI is green without Azure except the deploy job, which stops at its preflight with "Azure is not set up for
this repository" (no Azure call is made).

## Second review (fresh re-checker on the fixes, 6 confirmed, all fixed)

1 `infra/deploy.sh` committed without the executable bit (validate.sh now checks modes); 2 web session secret seeding
died on SIGPIPE under pipefail (now `openssl rand`); 3 the migrations gate grepped for a string the new backend no
longer has (source sniffing removed: migrations always run; the backend now has the migrate role, a blocking worker
and `/v1/health/ready`, so dev probes readiness too); 4 `deploy.sh` was not shellchecked; 5 the infra-skip ignored
changed GitHub variables (now compares compiled parameters with the last deployment); 6 checks did not pair app
secret references with their creation, nor guard the replica PgBouncer loop. Also: scope probe uses an existing empty
group `rg-aron-scope-probe` created by the bootstrap; password read keeps stderr apart; bootstrap creates the
conditional role assignment before deleting an old one and checks every `az` call. Mutation run: 14 of 14 mutations
of guarded properties fail validation.

## In progress

- Next: N-062 observability (workbooks, sync-health and error alerts), the data-dictionary CI check when
  `docs/requests/db-data-dictionary-ci.md` arrives, N-057 as parameters for prod only, N-064 release candidate.

## Blocked / waiting

- First real deploy: needs the sponsor to re-run `infra/bootstrap-azure.ps1 -AlertEmails ...` (README "Sponsor steps").
- Backend runtime items 1 to 3 (migrate role, readiness, blocking worker) and the Front Door id have landed; items 5 to 8 of
  `docs/requests/infra-backend-runtime.md` (queue, client id, FCM placeholder, metric names) remain open.

## Requests filed

- `docs/requests/infra-backend-runtime.md` (backend lane; architect for docs/24 s6.4).
- `docs/requests/infra-web-standalone.md` (web lane; mostly already satisfied by web/: standalone output and lockfile
  exist; the session secret is now provided from Key Vault).

## Decisions taken (infra)

| Date | Decision | Reason |
|---|---|---|
| 2026-10-05 | Two Bicep stages: `main.bicep` (infra) and `apps.bicep` (apps, run twice: migrate job, then services) | Migrations must finish before any app revision changes; apps need an image that only exists after the registry |
| 2026-10-05 | Database credentials as complete JDBC URLs in Key Vault: pooled (6432) for api, direct (5432) for worker and migrate | Flyway and run-once jobs take session advisory locks that PgBouncer transaction mode breaks |
| 2026-10-05 | App uses the PostgreSQL admin login in Phase 1 | Per-role logins need db-lane migrations and a rotation design; logged as a follow-up |
| 2026-10-05 | Front Door Standard in dev, Premium + Private Link in prod | Managed WAF rules and Private Link are Premium only; Premium costs about USD 330/month more |
| 2026-10-05 | Premium SSD v2 for PostgreSQL in both environments | Supports HA + geo backup in Southeast Asia (Learn); IOPS independent of size; no autogrow, so a storage alert at 70 % |
| 2026-10-05 | Geo-redundant backup and zone redundancy on in dev too | Both are creation-time only; dev hosts the Day 6 failover drill |
| 2026-10-05 | No GitHub concurrency for deploys; an Azure-side wait plus an ancestor check | GitHub cancels pending jobs in a group, which would cancel CI runs |
| 2026-10-05 | Blob-created events via Event Grid to a storage queue drained by the worker | The contract has no webhook endpoint for it; a queue needs no endpoint validation and is at-least-once |
| 2026-10-05 | Temporary source-based detection of the missing migrate role and placeholder worker in `deploy.sh` | Keeps push deploys green on Day 1 without hiding the gap (warnings in every run) |
