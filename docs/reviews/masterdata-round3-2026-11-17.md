# Lead review: masterdata-fit round 3 (1cd7892d..d1c16cf)

Reviewed 2026-11-17 by the lead (Opus pass, two lenses). Tests named below were run by the reviewer on a local PostgreSQL 16; the real workbook and the dev database were not touched.

## loader-r3 verdict=pass
SUMMARY: PASS. Nothing blocking; both blocking findings of the last review are really fixed.

**R1 (resumed run's report).**
- Every outlet batch now stores its counts and issue codes in `mdload.batch.detail` (MasterDataLoader.kt:719-725), in the same transaction as its writes. Every hierarchy, rep and assignment chunk does the same in `mdload.step` (:390-410). A skipped unit adds back exactly what it stored (:603-607, :391-395), including example codes, accepted rep/zone counts and the reactivated and generated-code issues.
- I traced a kill in each phase. A kill inside a unit rolls it back. Kills during hierarchy, between batches, during reps and assignments, or before 'finish' all replay the stored counts in file order, so every count, issue and sample matches an uninterrupted run. Issues are a sorted map, so jsonb key order does not matter. Only batchesApplied/Skipped and stepsSkipped differ, which is bookkeeping. Rows rejected at staging are recounted on every pass.
- Rows from an older build are refused, never silently re-reported: the old one-line options string fails the option check.

**R2 (resume with changed options).** Bound options: batchSize, coordinatesConfirmed, storeOutletsWithoutVisitPlan, unassignedOutletStatus, businessDate, by, shortRepIdsAllowedForTests.
- Not bound: dryRun, runId, syntheticTestData and the test hooks. None of these changes what is written.
- What else shapes the writes is database state (the short-id gate, geo classes, other-role users). It changes the staged statuses, so the batch fingerprints miss and those batches are re-applied.
- The refusal (:346-367) names only option names and hash prefixes, never a value.

**Lows of the earlier review:**
- **Fixed:**
  - Pass 2 now reads one immutable copy of the file.
  - A rep's home zone comes only from accepted rows.
  - A route stored in another zone is not renamed or re-planned and counts as an error.
  - A differing primary rep is HELD with `route_assignment_primary_differs_from_the_open_primary` and the route code.
  - A pair whose route was not created is an error.
  - Each outlet batch reads one REPEATABLE READ snapshot and retries on 40001/40P01.
  - The sub-channel join takes the exact case first, LIMIT 1, so it cannot fan out.
  - Held or other-role reps no longer make a route "have a rep".
  - A stored retired Silver tier is kept.
  - Case-variant codes and generated codes are reported.
  - The report says unreadable rows are part of the errors.
  - The 'Cluster ' leak token is back.
  - The advisory lock is re-checked before every transaction.
  - Env comes from Settings.
- **Not fixed as claimed:** `syntheticTestData` is "internal" only on its getter. The public constructor still accepts it from any module (proved by compiling a probe in backend:app). The test meant to prove otherwise cannot fail. No request reaches the loader, so this is a low.
- **The three disagreements** (dry run's single transaction, malformed record's code, server-log DETAIL) are acceptable, with the runbook notes in the lows.

**Pre-flight mirror for V0199.**
- Its SQL matches V0199's two guards exactly: the same codes, the same `<>` on a NOT NULL, CHECK-limited channel, and `IS DISTINCT FROM 'Astha'` on the item.
- It is read-only (`SET TRANSACTION READ ONLY` plus rollback) and has no repair.
- It fails in every environment, dev included.
- It prints only channel codes, or 'other'/'none', and row ids.
- It skips a fresh database or one below V0004/V0005 (to_regclass), and skips once V0199 has succeeded.
- The V0191 check and its dev repair are unchanged; the default whereLabel 'zone' keeps its report text the same.

**Repository checks.**
- V0199 is byte-identical to e18090f10, and so are V0195..V0198 and V0200.
- No V0001..V0191 file differs from 9bb7f5cf1.
- No .github, CI or gate script changed.
- No test was loosened: the archived-count assertion changed with fix (f) and was not weakened.
- Fixtures are synthetic.

**Tests I ran** on a local PostgreSQL 16, in a temporary worktree I have since deleted:
- MasterDataLoaderTest: 26/26 pass.
- MigrationPreflightTitaniumTest: pass.
- MigrationPreflightTest (V0191): 2/2 pass.
- The R1, R2 and snapshot tests would fail without the fix.

**Not checked:**
- The full gate, CI and the other modules.
- The dev or live database. I did not check whether dev already holds a Titanium row that would now stop the deploy.
- A crash during COMMIT.
- Azure log or diagnostic settings.
- The data-dictionary counts.
- The rest of the merged integration code beyond the conflict resolutions.

LOWS:
- Guard claim is false and its test cannot fail. backend/masterdata/.../loader/MasterDataLoader.kt:62 and :67: `internal val syntheticTestData` and `internal val shortRepIdsAllowedForTests` sit in a PUBLIC primary constructor. Kotlin visibility on a constructor property covers only the getter, so any module can still pass the parameter. I proved it in a throwaway worktree: a test file in another module (backend:app) containing `LoadOptions(syntheticTestData = true, shortRepIdsAllowedForTests = true)` compiled and ran. The probe lived only in that worktree, which I deleted. The guard test, MasterDataLoaderTest `methods.none { it.name == "getSyntheticTestData" }`, passes whatever the code does, because an internal getter's JVM name is mangled (`getSyntheticTestData$masterdata`). `Settings.load(mapOf("ARON_ENV" to "prod", ...))` can also be built by any caller. Today no request, CLI or job reaches the loader, so this is not a request bypass. Still, D-MD-37 and docs/reference/master-data-loader.md:20 and :96 claim more than the code does. Fix: make the constructor internal, or add an internal `forTests(...)` factory and a public constructor without those parameters. Replace the reflection test with one that checks the public constructor's parameter names. Runbook: a future CLI takes `Settings.load()` from the process only.
- Skipped batches read back their stored counts correctly, but rows from an older build are handled silently. MasterDataLoader.kt:106-107 and :584-588 (Outcome.from returns null when `detail` is NULL): such a batch is quietly re-applied, and the rows it created earlier would then be reported as unchanged. In practice this cannot happen: every round-2 mdload.run row stores options as one line (`batch=5000 confirmed=... ...`). That line parses to the key `batch` only (:359), so openRun refuses the resume as 'different options'. The refusal names option names only, no values. Worth a line in the runbook: a run started by an older build cannot be resumed; start a new run id (the load is idempotent).
- Lock re-check window. MasterDataLoader.kt:202-215 and :273: the lock is checked before each transaction, not at commit. If the holder connection drops during a batch, that batch still commits, and a second loader could start in the gap. The window is narrow, and the next transaction stops the run. A low only.
- A kill -9 leaves personal data in the temp directory. MasterDataLoader.kt:223-256: the gzip copy of the file (owner-only perms from Files.createTempFile) is deleted in `finally`, but not after kill -9 or OOM, and the next run does not sweep old `aron-masterdata-*.csv.gz` files. Runbook: run the loader with a dedicated, encrypted temp directory and clear it after a crash; or sweep stale copies at start.
- Report equality depends on database state staying the same between the kill and the resume. planReps (:559-572) and geo_class_def are re-read on resume. If the short-rep-id gate or a foreign-role user changed in between, the staged statuses change, the fingerprints miss, and those batches are re-applied and counted unchanged instead of created. The data stays correct; only the report differs from an uninterrupted run. Runbook: do not change cfg.release.short_rep_id_version_code or SR users while a run is open.
- R2: `businessDate` defaults to today in Dhaka (:48) and is bound. A resume on the next day without an explicit --businessDate is therefore refused, which is correct. Runbook: always pass the business date explicitly and resume with the same one.
- D-MD-36/37 disagreement 1, the dry run holds one READ COMMITTED transaction and its row locks for the whole file: acceptable. It writes nothing, the advisory lock keeps a real run out, and exactness needs the real statements. Runbook: run the fleet dry run on a PITR-restored copy, or outside selling hours, with lock_timeout set so portal writes are not left waiting.
- D-MD-36(h) disagreement 2, a malformed record's code does not enter duplicate detection: acceptable. The malformed row is an error and loads nothing, so its twin loads alone and the counts still add up. But the malformed issue line has no sample code (stage() :637-639 calls report.issue(it) with no sample), so the owner cannot find the row from the report. Runbook: fix the malformed rows and re-run; adding the row number as a sample would help.
- D-MD-37 disagreement 3, the PostgreSQL server log's DETAIL line: acceptable. It is outside the loader's control, and pass-1 validation makes a constraint error rare. Runbook for the final account, before the load: set the server parameter log_error_verbosity=terse (Azure Flexible Server parameter; it needs superuser, so it cannot be a per-session SET by the loader role). Check that the diagnostic settings do not ship postgres logs anywhere wider than the operator's workspace.
- Pre-flight mirror, V0199: `applies` resolves `flyway_schema_history` unqualified through search_path (MigrationPreflight.kt:173-175). This is consistent with today's Flyway configuration (no schemas or table override, same pool). If a future configuration moves the history table to another schema, the check would treat V0199 as pending forever: a later code-list edit putting Titanium under another channel would then block every deploy (fail closed, no data at risk). Consider pinning the schema.
- Merge: the conflicted DECISIONS.md, openapi.yaml (1.18.1 kept), slices INDEX (`tools/slice-contract.py --check` = up to date), data-dictionary and AmoManualContractTest were resolved as a union. Against a clean `git merge-tree` of the two parents, the only extra changes are the pre-flight files, docs/30 s4a and those resolutions. No code or decision from either side was lost. The data-dictionary counts (app 182/2997, dw 76/1233) were not re-derived by me.

## preflight-merge verdict=pass
SUMMARY: Nothing blocking in the V0199 pre-flight mirror or the merge. Lane round 3 (R1/R2) was outside this lens and was not re-reviewed here.

**Mirror (V0199 guards vs db/preflight/V0199 vs TitaniumTier)**
- The predicates are the same: exact-case code = 'Titanium' with channel <> 'Astha' (sub_channel.code is UNIQUE and channel is limited to six codes by a CHECK), and code_list_item list_key = 'sub_channel', code = 'titanium', attrs->>'channel' IS DISTINCT FROM 'Astha'. Neither side trims or filters by valid_to or status.
- I checked this on a real PostgreSQL 16 migrated by psql to V0194, with the earlier lane migrations still pending. For 14 seeded cases I ran the mirror SQL and V0199's own guard block (taken from the file) in rolled-back transactions. The mirror reported a row exactly when the guard raised in every case: Astha active/inactive, GT, lowercase sub-channel code, item channel Astha/GT/missing/JSON null/'astha'/' Astha'/object, a retired item, both at once, other list_key.
- The objects it reads exist from V0004/V0005 on, and V0195..V0198 do not touch them.
- It is read-only (SET TRANSACTION READ ONLY, then rollback). It fails in every environment through strict(), with devRepair=false and repair() throwing.
- Output is channel codes from a whitelist ('other'/'none' otherwise), counts and ids only. On a fresh database it skips (no tables); at V0199 or later it skips (flyway_schema_history).

**Framework**
- CHECKS is [ClusterNames, TitaniumTier]. The diff of MigrationPreflight.kt against 9bb7f5cf1 is purely additive.
- The V0191 path is unchanged: describe() keeps its default label 'zone'.
- strict() sits before the dev-repair branch; the exception path goes through PreflightFailed and Main's onFailure.
- I ran the tests with Gradle against a local PostgreSQL 16: MigrationPreflightTest passed (2 tests) and MigrationPreflightTitaniumTest passed (1 test). Gradle then reported a java.io.EOFException at task teardown, after both classes had reported success.

**Merge d1c16cf (checked with git show --remerge-diff)**
- Six files conflicted: DECISIONS.md, openapi.yaml, slices/INDEX.md, data-dictionary.md, AmoManualContractTest.kt; docs/30 had a manual edit.
- DECISIONS.md: every non-empty line of both parents is present and no conflict markers remain anywhere in the tree.
- openapi.yaml: the version is 1.18.1. All of the integration's lines added since the merge base 4e0645556 are present except its version line.
- Generated files are current:
  - `tools/slice-contract.py --check` says up to date.
  - openapi-typescript 7.13.0 (the version in the lock file) regenerates an identical openapi.d.ts.
  - The data-dictionary counts add up (app 2986 + 4 lane + 7 integration = 2997 columns, 182 relations; dw 76/1233), and every line either parent added is present apart from the count rows. I did not run DataDictionaryTest.
- `tools/ci/contract-breaking.sh 9bb7f5cf1` passes, with the lane's breaking changes approved by version bump and request file.

**Migrations**
- V0199 at d1c16cf is identical to e18090f10 (same blob c7a30ec3; git diff is empty).
- V0195..V0200 and REPLACED-UNAPPLIED.txt are unchanged since 1cd7892d.
- V0001..V0191 are byte-identical to 9bb7f5cf1, and nothing under .github or tools/ci changed.
- `tools/ci/migrations-check.sh 9bb7f5cf1`, with squawk 2.67.0 installed through install-tool.sh, reports 169 checksums, 6 new migrations in order, and 'migrations: ok'.

**What I could not check**
- The dev database's actual data, so I cannot say whether the next dev deploy will be stopped by this check.
- A full deploy through Main and the migrate role's real search_path in Azure.
- The rest of the CI gate.

The temporary database and worktree were removed; HEAD of the main repository was never moved.

LOWS:
- db/preflight/V0199__astha_tiers_cluster_types.sql:1 and MigrationPreflight.kt TitaniumTier.applies (about line 170): the check runs only when both app.sub_channel (V0004) and app.code_list_item (V0005) exist. On a database at exactly V0004 that already holds a Titanium row under another channel, the pre-flight is skipped and V0199 would still raise. This cannot happen on a real database. Both tables have existed since V0004/V0005, and V0195..V0198 do not touch either table, so the mirror cannot error out at V0194 when the earlier lane migrations are pending in the same deploy (checked on a database migrated to V0194).
- MigrationPreflight.kt describe() combined with whereLabel = "channel": the report line reads 'channel sub_channel under channel GT ids [n]'. 'channel' appears twice because the SQL's where_code already contains 'under channel'. The test asserts this exact text. It is cosmetic only and leaks nothing.
- MigrationPreflight.run: in dev, ClusterNames repairs and commits before TitaniumTier runs. If TitaniumTier then fails, the deploy stops with the V0191 dev repair already committed. The repair is idempotent and dev-only, so this is acceptable, but it is not called out in docs/30.
- MigrationPreflight.applies keys on to_regclass('flyway_schema_history') through the session search_path. This matches Flyway's default (first schema on the search_path) because Migrator.configure sets no schemas(). If Flyway is ever given an explicit schema, the check would run after V0199 is applied and could block deploys. It is a latent coupling, not documented next to Migrator.
- No DECISIONS entry records the V0199 pre-flight mirror or the merge. Only docs/30 s4a item 5 describes it. docs/status/masterdata-fit.md:10 still lists 'the integration merge (lead says when)' as not done, which is stale at d1c16cf.
- shared/contract/.../AmoManualContractTest.kt: after the merge the test is still named versionIs1150 while it asserts 1.18.1, and the history text puts 1.16.0 between 1.18.1 and 1.18.0. Naming and comment only.
- V0199 is frozen, outside this lane's power to change: its own RAISE for the code-list item prints attrs->>'channel', which can be arbitrary text (not personal data). The mirror whitelists channel codes ('other'/'none') and does not repeat it.
