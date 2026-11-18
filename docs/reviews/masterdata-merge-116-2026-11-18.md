# Lead review: masterdata-fit merge for train 116 (d1c16cf..cd079fb2)

Reviewed 2026-11-18 by the lead (Opus pass, two lenses). Verdict: PASS, lows only. One reviewer applied V0001..V0194 then V0195..V0200 on a local PostgreSQL 16 and compared catalogue snapshots; the dev database was not touched.

## migrations-vs-v0194 verdict=pass
SUMMARY: Verdict: pass. Nothing blocking in d1c16cf..cd079fb25, and the deploy should apply V0195..V0200 cleanly on the dev database at V0194.

**What I ran** (read-only on the main repo; HEAD stayed 2e66b5fbb). I used a temp worktree at cd079fb25 under the scratchpad (since removed) and the local PostgreSQL 16.15, started with pg_ctlcluster.
- **Migrations by hand.** I applied V0001..V0194 with psql, one transaction per file, into a fresh database. I took a catalogue snapshot (constraints with their definitions and validated flags, indexes, function hashes, triggers, views, cfg_key rows, code_list_item rows, table grants). Then I applied V0195..V0200 the same way and snapshotted again. I did this twice: once from a worktree another reviewer was also using, then from my own clean one. Both gave the same result.
  - All six files applied without error.
  - The only catalogue changes are widenings:
    - visit_kind gains 'none' and the mask may be 0, on app.route and app.route_planned.
    - Memo usernames and memo_no allow {2,31}, on app_user, memo and every partition, memo_void and sale_abort.
    - code_list_list_key_check keeps all previous keys and adds cluster_type.
    - outlet_location_history basis adds 'candidate'.
    - Two new outlet length checks; visit_kind_matches is replaced; one new cfg_key (cfg.release.short_rep_id_version_code); six cluster_type items and the titanium sub_channel item are added.
  - No grant changed.
  - sync_rejected_code_check is untouched and validated, and still holds gift_locked, gift_not_for_tier and cluster_name_exists.
  - Everything V0192..V0194 added is present and valid: all gift_choice and gift_photo checks, the two partial unique indexes on gift_photo, the gift_choice_roles default ["SR","TSO"] and the accept_and_flag bounds. The only NOT VALID constraints are four that existed before V0195.
  - No object is touched by both sides (no shared name, cfg_key, code-list key, view, function or CHECK).
- **Gradle tests, all green:** MigrationPreflightTest (3), MigrationPreflightTitaniumTest (1), DataDictionaryTest, SchemaV1bTest, CodeListsTest, MasterDataFitV0195Test, MasterDataFitV0199Test, and MasterDataLoaderTest (28, including the new constructor-parameter test, the stale-copy sweep test and the lock-lost-inside-batch test).
- **Gates and generated files:**
  - `tools/ci/migrations-check.sh f5bfee390` with squawk: ok, 172 shipped migrations checked, 6 new.
  - `tools/ci/contract-breaking.sh f5bfee390` with oasdiff: ok. Every breaking item is a 1.18.x master-data change already reviewed.
  - `tools/slice-contract.py --check`: up to date.
  - openapi.d.ts regenerated with openapi-typescript 7.13.0: identical.

**What I checked by git and by reading:**
- V0001..V0194 are byte-identical to f5bfee390; the only db/migrations difference is REPLACED-UNAPPLIED.txt, unchanged since d1c16cf.
- V0195..V0200 are unchanged since d1c16cf, and V0199 matches e18090f10.
- No file under .github, tools/ci or infra changed against d1c16cf or f5bfee390.
- **Merge:** every file either side changed is kept. The only hand resolutions are DECISIONS.md (both sets of entries kept, nothing lost), the docs/30 list (renumbered only), MigrationPreflight.kt (the integration's raw-`ARON_ENV` `run` plus the lane's strict path and TitaniumTier with the new `lock` override), the Titanium test moved to raw env strings, and the contract version test.
- The GeoRecheck merge keeps the lane's candidate filter. The new GiftPhotoGeoRecheck goes through GeoRecheckHandler.recheck, so photos also ignore candidate pins.
- **Contract:** 1.18.1 contains everything in 1.17.0, including the gift_locked and gift_not_for_tier codes.
- **Pre-flight:** both checks go through a READ ONLY transaction that is rolled back, TitaniumTier fails in every environment with codes and ids only, and its applicability is keyed on flyway_schema_history.
- **Logs and fixtures:** the new 'row N' samples carry only the 1-based data-row number (MasterCsv.Row.no), never a value. The test fixtures are synthetic.

**What I could not check:**
- The real dev database's hand-typed rows (a Titanium row or item under another channel would stop the deploy by design).
- Lock contention with live writers.
- The full CI suites (android, web, the other backend modules).
- The fleet-size behaviour.

LOWS:
- backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/loader/MasterDataLoader.kt:285-290 (sweepStaleCopies): the sweep deletes every aron-masterdata-*.csv.gz in java.io.tmpdir. The comment's safety argument is the advisory lock, but pg advisory locks are per database. Scenario: two loaders on one host, aimed at two different databases (a rehearsal on a PITR copy next to the real load, or two test JVMs) and sharing /tmp. The second run's sweep deletes the first run's copy, and the first run's pass-2 reopen (line ~254, Files.newInputStream(copy)) fails with NoSuchFileException mid-run. Batches already committed are resumable, so nothing is corrupted. Runbook item 1 says to use a dedicated tmpdir; making a per-run subdirectory, or scoping the sweep to the database name, would remove the trap.
- MasterDataLoader.kt:83 (forTests) and the internal var ... private set switches: Kotlin 'internal' compiles to public JVM members with mangled names (forTests$masterdata). The guard holds for Kotlin source in other modules; the new reflection test checks KVisibility and passes. Java code, or Kotlin with @Suppress("INVISIBLE_MEMBER"), in another module could still call forTests. So the claim in D-MD-38 and the runbook ('no module can reach it') holds at source level only. Wording, not a hole on any CLI or API path today.
- tools/ci/migrations-check.sh with base d1c16cf (the lane's own earlier tip) now reports V0192..V0194 'not above the highest shipped version 200'. This is the accepted lane artifact. Any lane or test database that applied V0195..V0200 before V0192..V0194 would refuse them without outOfOrder. No persistent database did (REPLACED-UNAPPLIED.txt records this, unchanged since d1c16cf). The dev database at V0194 is unaffected. With base f5bfee390 the check is clean.
- MigrationPreflightTitaniumTest moved from ServerEnv.entries to raw strings [dev, prod, staging, typo, null]. That is a widening (adds typo and unset), not a loosening. The one changed assertion (label 'channel' -> 'finding') follows the whereLabel rename in 6c2ed868 and still asserts the channel code, the id and the group count.

## merge-and-followups verdict=pass
SUMMARY: Reviewed d1c16cf..cd079fb25 (commit 6c2ed8680 and merge cd079fb25 of f5bfee390). No blocking findings; five lows.

HOW CHECKED
- Hard rules:
  - V0001..V0194 are byte-identical to f5bfee390 (empty diff).
  - V0195..V0200 and db/preflight are unchanged since d1c16cf; V0199 is byte-identical to e18090f10.
  - .github, tools/ci and tools/checks are unchanged versus f5bfee390.
  - REPLACED-UNAPPLIED.txt only gains the masterdata lines.
- Merge resolution: git merge-tree of the parents gave conflicts in DECISIONS.md, openapi.yaml, INDEX.md, docs/30, data-dictionary and AmoManualContractTest. Checked each:
  - DECISIONS.md: every line of both parents is in the merge (0 missing).
  - docs/30 s4a: renumbered 1..8, both checks listed, the integration's raw-dev and flyway_schema_history rules kept.
  - docs/19: Loyalty C rows (negative_balance_policy, gift_choice_roles [sr, tso]) and masterdata's tiers row both present.
  - AmoManualContractTest: 1.18.1 with 1.17.0 noted.
- MigrationPreflight.kt merged correctly:
  - The integration's version is kept: run(ds, declaredEnv: String?), DEV exactly "dev", lock-then-findings inside the repair transaction, SQLState-only error logging, ClusterNames applicability on flyway_schema_history.
  - Added on top: devRepair/whereLabel defaults, strict(), and TitaniumTier with lock() {}. TitaniumTier fails in every environment, is read-only and keyed on flyway_schema_history.
  - CHECKS = ClusterNames, TitaniumTier. Main.kt is identical to the integration (passes raw System.getenv("ARON_ENV")).
  - The Titanium test was moved to raw env strings ("dev", "prod", "staging", "typo", null) with the same assertions.
- Contract: a structural YAML walk of f5bfee390's openapi against cd079fb25's finds nothing of 1.17.0 missing (gift_choice RecordType/GiftChoiceRecord/GiftChoicePayload/SyncRecord mapping, gift_locked and gift_not_for_tier, LoyaltyBalance.overdrawn required, truncated_sections maxItems 5 with pending_gift_verifications, the GiftPhotoPayload text). The only differences are the 1.18.x master-data widenings already passed (MemoNo {2,31}, visit_days_mask minimum 0, OutletImportRow.channel nullable). ContractCatalogues/ContractEnums carry both sides.
- Regenerated files:
  - tools/slice-contract.py --check: up to date.
  - openapi-typescript 7.13.0 regenerated web/src/contract/openapi.d.ts byte-identical.
  - data-dictionary has both sides' rows (gift_choice, gift_photo check_flag/is_duplicate, outlet source_*, basis candidate). App totals are consistent: base 182/2993; masterdata +4 columns; Loyalty C +1 table, +41 columns; merge 183/3038.
  - :shared:contract:jvmTest is green; :backend:app and :backend:masterdata test sources compile.
- Migration hazard (dev at V0194 holding Loyalty C objects):
  - V0192..V0194 touch sync_rejected_code_check, cfg_key gift_choice_roles and negative_balance_policy, gift_choice, and gift_photo triggers and functions.
  - V0195..V0200 touch route/route_planned checks and visit_kind_matches, app_user and memo/memo_void/sale_abort memo_no checks, sub_channel, code_list_item, code_list_list_key_check (rebuilt dynamically from the current definition), outlet source columns, outlet_location_history_basis_check (V0044 list plus 'candidate'), and a new cfg_key (ON CONFLICT DO NOTHING).
  - No object, constraint, cfg key, code-list key, view or function is touched by both sides, so no CHECK list is narrowed.
- Commit 6c2ed8680:
  - The guard is real (compile probe from backend:app refused). The new reflection test would fail if a test parameter were put back on the public constructor.
  - The sweep runs inside run(), after pg_try_advisory_lock, with pattern aron-masterdata-*.csv.gz in java.io.tmpdir.
  - The in-transaction assertLockHeld runs on the holder connection inside inTransaction, so a lost lock rolls back the batch. The new test expects 0 outlets; the old afterBatch test still expects 300.
  - Error samples are "row N" (a number) only. Fixtures are synthetic: codes and a 'Zqtitan' marker, no phone or email patterns.

COULD NOT CHECK
- I could not run any database-backed test (MasterDataLoaderTest, the two MigrationPreflight tests, DataDictionaryTest, SchemaV1bTest) or apply V0195..V0200 on a V0194 database. The local PostgreSQL 16 only accepts password logins over TCP; I have no test-role credentials, and creating a role was denied. Their green state rests on the lane's claim.
- I did not see the dev database's real data, so whether dev holds a Titanium/titanium row outside Astha (which would correctly stop the deploy in dev) is unknown.
- The AmoManualContractTest class did not show by name in the jvmTest log, although the task passed.

LOWS:
- backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/loader/MasterDataLoader.kt:285 sweepStaleCopies(): the advisory lock is per database, but the sweep deletes every aron-masterdata-*.csv.gz in java.io.tmpdir. Scenario: two loaders run on one host against two different databases (say staging and prod from one operator machine) with the default tmpdir. The second run deletes the first run's live copy, and the first run's pass 2 re-open (reopen = Files.newInputStream(copy)) fails with NoSuchFileException, turned into a sanitized LoaderException. The run fails safe and can be resumed, and nothing is corrupted. The runbook's dedicated-tmpdir advice covers it. The KDoc wording 'no other loader is reading a copy' only holds for the same database.
- MasterDataLoader.kt:673 and docs/reference/master-data-loader.md runbook item 6: 'row N' is MasterCsv.Row.no, the 1-based DATA row number (header not counted, blank lines skipped and not counted; MasterCsv.kt:26/66). In the workbook that is spreadsheet row N+1, plus any blank rows above it. The runbook says 'fix those rows in the workbook' without saying this, so an owner could open the row above the bad one. Fix: state the numbering in the runbook, or emit the physical line. Only row numbers and codes are printed (no value): checked.
- MasterDataLoaderTest.kt theGuardHolds... (around line 303): the old assertion that no getEnv method exists was replaced. The new test checks 'env' only among PUBLIC CONSTRUCTOR parameters, and the public-members check lists only syntheticTestData, shortRepIdsAllowedForTests and forTests. A public body property 'env' (or a public test-hook property) added later would not be caught. This is slightly narrower in that one respect, though stronger overall: re-adding any constructor parameter now fails the test. Suggest adding 'env', 'afterBatchForTests' and 'insideBatchForTests' to the public-member set.
- LoadOptions.forTests is internal, so from Kotlin it cannot be reached from another module (proved: a probe in backend:app fails with 'No parameter with name syntheticTestData', 'forTests ... is internal' and 'var syntheticTestData ... is internal'). From Java it is still callable under the mangled name forTests$masterdata. No Java sources call it today; note it for the future CLI.
- MigrationPreflight.TitaniumTier: the empty lock() is never called, because devRepair=false sends it down strict(), which goes through readOnly (SET TRANSACTION READ ONLY), and repair() throws. Harmless as stated.
