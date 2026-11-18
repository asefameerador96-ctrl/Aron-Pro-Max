# Lead review: Loyalty sweep fix round 2 (8a90f1b4..d88b751b)

Reviewed 2026-11-18 by the lead (each blocking finding checked by three refuters). Read from code only: nothing built, run or walked on a device.

## loyalty-marker-lifecycle verdict=pass
SUMMARY: Head d88b751bc1d0 of lane/portal-loyalty-api-c, read from code only (nothing built or run). Verdict: PASS. The blocking finding is closed and I could not build a sequence that reopens it.

What I checked. Trigger in V0207: BEFORE UPDATE FOR EACH ROW, WHEN OLD.waiting_since IS NOT NULL AND (NEW.status <> 'open' OR OLD.status <> 'open' OR code changed); it nulls all seven marker columns. CHECK: waiting_since IS NULL OR status = 'open'. It is the only trigger on app.sync_quarantine, so there is no ordering issue. status and code are NOT NULL, so the WHEN and the CHECK have no NULL gap. A BEFORE UPDATE row trigger does fire for INSERT ... ON CONFLICT DO UPDATE.

Every writer of status, code and the marker (IngestService.kt and OpsApi.kt):
- OpsApi discard / return_to_device (status -> discarded): trigger clears; CHECK would refuse otherwise.
- closeItem, the two device-item closers (register() about line 1913, closeIntegrityItem): status leaves open -> cleared.
- quarantine() upsert: re-opens only a row returned to the device; that row already lost its marker at the return and the CHECK guarantees it, so it re-opens clean. This is the exact path of the failed review and it is closed.
- markWaiting: WHERE status = 'open', sets no status or code, so the trigger never undoes a marker being set (OLD.waiting_since is null for a first mark; for a re-mark the condition is false).
- clearWaiting, settleChild's code change, the sweep's touch, poison stamp and next-parent update: either clear explicitly or change neither status nor code.
- releaseHeld / sweepOne: the waiver passed is the stored waiting_release_code only; on any own hold the item is cleared to an ordinary held item; a stored release closes the item (marker cleared) with the audit row in the same transaction.

Sequences tried without success: return then resend held again (same or other code); discard then resend; re-open; code change on an open waiting row (cleared, becomes an ordinary held item a reviewer can still accept, so not stranded); reviewer accept racing the sweep (reviewer holds the row FOR UPDATE, sweep takes it SKIP LOCKED after the advisory lock; the loser sees status not open); a re-upload with another payload hash (a second row is a fresh INSERT with no marker and code payload_conflict; if it is handled first the old waiting row turns into an ordinary payload_conflict item at the next pass, nothing stored twice); a statement setting marker and status together (none exists).

The one path where an open row keeps its marker through a re-hold is the device-code upsert on an OPEN row with the same code. It is the same wait and the same waiver, so it does not store anything the reviewer's accept did not cover; it is listed as a low with a display side effect.

Backfill order inside V0207 is right for the CHECK to validate. The trigger function needs no grant for the API role. The data dictionary lists the new column but not the trigger or the CHECK.

Not verified: no test, PostgreSQL or squawk run; trigger behaviour, the EXECUTE rule for trigger functions and the CHECK validation are from reading and PostgreSQL's documented behaviour, not from a run; the lane's claim that the new tests fail with the trigger and CHECK removed; a real multi-replica run; each record handler's own use of the override.

Files: /tmp/claude-0/-home-user-Aron-Pro-Max/a5c3c45a-b89c-5768-9d5f-6bc05c04fc63/scratchpad/wt-verify/db/migrations/V0207__cfg_waiting_sweep_keys.sql, .../backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt, .../backend/analytics/src/main/kotlin/com/aktcl/aron/backend/analytics/OpsApi.kt, .../backend/app/src/test/kotlin/com/aktcl/aron/backend/app/GiftPhotoRecordsTest.kt, .../docs/data-dictionary.md (all read at revision d88b751bc1d0).

LOWS:
- One open-to-open path keeps the marker, by design of the trigger: quarantine()'s upsert also rewrites an OPEN row whose code is device_integrity_failed or device_not_enrolled (IngestService.kt about 1989-1994), and process() re-runs such a record on every phone resend (about 1186-1194). If a waiting child carries a device code and the phone resends it while it waits and the device gate holds it again with the same code, status stays open and the code is equal, so the trigger does not fire: the marker and waiver stay, but detail is overwritten with the fresh device text ('play integrity ...'). I judge this not a breach: it is the same wait, the same payload hash, and the waiver is the same device override the reviewer's accept carried for that child (the sweep would waive the device gate at release time anyway). Effects are cosmetic: the queue shows waiting=true with a non-waiting detail, and the sweep's withHint() edits a text that is not the waiting text. If the code differs (not_enrolled vs integrity_failed) the trigger clears the marker and the item becomes an ordinary held item. The lead should confirm this knowingly; no test covers it.
- Same family, registry side: a waiting device-code child resent while its other parent is still missing is parked by the phone path (registry goes quarantined -> parked) while its quarantine item stays open and waiting. Later the sweep's release still works (prior 'parked' with an open integrity item is processed), but the item is then closed by register()'s own UPDATE (about line 1913: status accepted, no resolver, no note) before closeItem runs, so closeItem is a no-op and the row lacks resolved_by/aron.system and the sweep note. The audit_log row is still written. Pre-existing pattern for device-code items, read from code only.
- The trigger keys only on OLD.waiting_since. The CHECK covers only waiting_since, not the other six columns. A stray write of waiting_release_code or awaited_uuid to a non-waiting row would not be cleared or refused. Harmless today: every reader (sweep, queue count, DTO) selects on waiting_since, markWaiting overwrites all columns, and no writer sets the others alone on a non-waiting row. touch() in sweepOne (about line 659) lacks 'AND waiting_since IS NOT NULL' but runs under the row lock on a row just read as waiting.
- The 'OLD.status <> open' arm of the trigger WHEN is dead once the CHECK exists (a non-open row can never have waiting_since set). Harmless belt and braces.
- The trigger guards against future writers only for the three listed transitions. A future UPDATE that keeps status open and the code equal but changes what the hold means (as the device-code upsert does) keeps the marker. Worth one sentence in D-LOY-API-31 so nobody assumes 'any re-hold clears it'.
- Data dictionary: awaited_field is listed and the app column count went 3044 -> 3045 (all seven marker columns are now there). The trigger sync_quarantine_clear_waiting, its function and the CHECK sync_quarantine_waiting_open are not mentioned anywhere in docs/data-dictionary.md (the dictionary lists relations and columns; constraints and triggers appear only when a table or column comment names them). The waiting_since comment does not say the marker is cleared automatically on close, re-open or code change. Suggest adding that to the column comment so the dictionary carries it.
- Trigger function grant safety, read only: plain plpgsql, SECURITY INVOKER, no search_path setting, touches only NEW, reads no table, so it needs no table grant and has no search_path risk. V0014 revokes EXECUTE on new functions from PUBLIC by default privileges and V0207 adds no GRANT; PostgreSQL checks EXECUTE on a trigger function when the trigger is created, not when it fires, so api_rw can update the table. I did not run it; the lane says its tests run as api_rw and pass, which would confirm this.
- Migration order is right: columns, backfill (open rows only, so every marked row is open), detail rewrite, then function and trigger, then CHECK NOT VALID and VALIDATE in the same transaction, then the index. The backfill runs before the trigger exists and would not be affected by it anyway (status and code unchanged). CREATE TRIGGER and ADD CONSTRAINT are not idempotent (no IF NOT EXISTS / DROP IF EXISTS) while the columns and index are; fine for a migration that runs once, but a lane database that already ran an earlier V0207 needs a rebuild.
- Test realism: the return-and-re-open test re-holds the photo on a line that is itself held, not with the awaited parent stored and a fresh hold of the same code as the stale waiver (the exact scenario of the finding). It does assert the re-opened row has no marker and no waiver, which is the property that matters, and the sweep then cannot select it. The CHECK test asserts only that some failure occurs, not which constraint. No test for the device-code resend of a waiting item or for a second row with another payload hash.
- Unchanged from the last review and still true: when awaitedState says STORED but the release keeps answering parent_missing for the SAME parent (registry accepted/voided but the row is not where the parent step looks), the new 'follow the next parent' code changes nothing and the item re-runs a full rolled-back release every pass. The waiver has no age limit inside one wait (ruled).

## loyalty-lows-tests verdict=pass
SUMMARY: Lens: the lows and tests of 8a90f1b4..d88b751b, read from code only; nothing built or run. No blocking finding.

What I checked and found sound:
- Poison stamp (IngestService.kt 632-637): runs on a fresh handle after the failed transaction rolled back, wrapped in runCatching, guarded by status = 'open' AND waiting_since IS NOT NULL, does not fire the trigger. A throwing item now gets waiting_checked_at and leaves the head of the order.
- Audit after.override: records `override?.wire ?: "none"`, the same value passed to releaseHeld; written in the release's transaction inside the savepoint. QuarantineAcceptTest asserts it (assertion strengthened, not weakened).
- Override scope: the sweep passes waiting_release_code only; children of the swept child get it only when same uploader and device, which matches what the original accept gave them. Never more.
- Awaited parent follow: updates awaited_uuid, awaited_field and detail only on an open waiting row, keeps the waiver and the first parent, does not fire the trigger.
- awaited_field: written by markWaiting, cleared by clearWaiting and the trigger, read by the sweep with a fallback to the detail text; in the data dictionary.
- Backfill: deterministic (anchored regexes, cast only on text that already matched the uuid pattern, no waiver). Unnamed old-form rows stay open with their code and a reviewer can still accept them.
- Race test: real accept endpoint against the real job on the API role, three rounds, asserts stored once, accepted, and resend answers duplicate. Lock order read: no deadlock path, loser gets 409. It cannot force a true interleaving, as with any timing test.
- The two blocking-fix tests go through the real return_to_device, discard, accept and sync endpoints and would fail without the trigger or CHECK (marker assertions and the direct UPDATE refused by the CHECK).
- No test deleted, skipped or weakened in the range: test diff is 88 additions and 2 changed lines (one assertion added).
- waiting_count predicate is the list's reach predicate verbatim: no leak outside a reviewer's reach.
- V0207: additive columns, lock_timeout 5s, no grant change (new columns ride the table grants; trigger functions need no EXECUTE for the firing role), depends only on app.sync_quarantine and app.cfg_key, so it should run after other lanes' V0203..V0207.
- DECISIONS.md: one line appended (D-LOY-API-31), nothing edited.

Could not verify: that the tests pass and fail as the lane says (nothing run); squawk and the repo gate scripts accept the file; app.cfg_key's column list is unchanged by other lanes' unlanded migrations; the state of the dev database's existing old-form rows.

Main thing for the lead: change the hard-coded V0207 path in GiftPhotoRecordsTest.kt when renumbering to V0208.

LOWS:
- RENUMBER TRAP: the backfill test hard-codes the file name. GiftPhotoRecordsTest.kt line 932 reads "db/migrations/V0207__cfg_waiting_sweep_keys.sql". When the lead renumbers the migration to V0208 this test fails with file-not-found unless the path is changed in the same commit. Comments naming V0207 also live in QuarantineWaiting.kt line 5, SchemaV1aTest.kt line 306, the migration header and the test name.
- No test for three of the round's lows: (a) the poison-item stamp (IngestService.kt line 636; the existing starvation test uses items that stay waiting, not items whose release throws), (b) the awaited-parent follow (lines 688-694), (c) the child that keeps the `parent <uuid> is quarantined (...)` detail (line 699; the lane states this one). The code reads correct for all three, but removing any of them would turn no test red.
- Old-form gift photo items lose the automatic release. The train-115 detail was `waits for another parent: <park detail or 'another parent'> (parent X was accepted)`; for photos it does not carry `<field> <uuid>`, so the backfill rewrites them to a plain held item. The awaited redemption is in the payload (`payload.redemption_client_uuid`, which is what markWaiting uses for new photos), so the backfill could have given them a marker. Safe side: a reviewer can still accept them (the item stays open with its code; acceptQuarantined has no dependency on the detail), and the integration branch has no sweep, so nothing that worked before stops working.
- The plain detail written for unnamed old-form items (`held after its first parent was accepted; the awaited parent is not named (train 115 form)`) drops the accepted parent's uuid, which for those rows was recorded only in the detail text. The reviewer loses that pointer.
- Deploy window: between the migration and the new API starting, the old API (train-115 markWaiting) can still write prefix-form details with no marker. Those rows are never backfilled and never swept; they show as ordinary held items with a 'waits for another parent' text. At pilot size this is at most a handful of rows, and a reviewer can accept them.
- Migration comment is wrong: `the column is new (every row is null)` above VALIDATE. The backfill just above has already set waiting_since on open rows. The CHECK still holds because the backfill only touches status = 'open'.
- NOT VALID and VALIDATE in the same migration departs from the repo's own pattern (V0135/V0136, V0199/V0200 split them across two files). The squawk-ignore reason (small review table, one transaction, lock_timeout 5s) is acceptable for this table. The whole migration holds an ACCESS EXCLUSIVE lock on app.sync_quarantine from ADD COLUMN through the index build: fine at dev size, and for a table of some thousands of rows.
- CREATE TRIGGER and ADD CONSTRAINT are not re-runnable while every other statement is IF NOT EXISTS / ON CONFLICT. Fine for a forward-only migration that runs once; a manual re-run after a partial failure outside a transaction would fail there.
- Awaited-parent follow: when the awaited parent is stored but the release still answers parent_missing with a detail that does not match `<field> <uuid>` (always the case for a gift photo, where awaitedOf returns the same redemption), the item keeps waiting on a parent that is already stored, and every pass runs a full release and rolls it back. Cost only; no wrong store.
- The poison-item stamp has no lock timeout: if a reviewer's transaction holds the item row, the stamp waits for it, which stalls the single sweep thread for that long. It cannot deadlock (the failed sweep transaction has already released its locks) and cannot fail the pass (runCatching).
- Stale test section comment at GiftPhotoRecordsTest.kt line 857 still lists `a non-national count` although that test was dropped.
- Dropped non-national waiting_count test: judged not a leak. The count uses exactly the list's reach predicate (`q.route_id IN (SELECT route_id FROM dw.dim_geo WHERE zone_id = ANY(:rz))`, empty zone list bound as -1; OpsApi.kt lines 310 and 324-326), so it can never count an item the same caller could not list by dropping filters. Items with a null route are excluded for non-national callers. Still unexercised by any test.
- Outside this round but seen: for an open waiting item whose code is device_integrity_failed or device_not_enrolled, a phone resend that is held again with the SAME code overwrites the detail through quarantine()'s upsert while the marker and waiver stay (the trigger fires only on a code change). It is the same open wait, so the waiver is still the one the reviewer's accept carried, but the queue then shows waiting = true with a detail that no longer says so. Read from code, not traced end to end.


## Landing check d88b751b..ccd4f156 (V0204, contract 1.20.0, merge of 39db6c734) verdict=pass
SUMMARY: Pass, no blocking finding. Everything was read from git; nothing was built or run.

1. **Whole change vs 39db6c734:** 21 files, all Loyalty/sync/ops backend, contract, shared contract test, db (V0204 and the SchemaV1aTest key list), web contract types, docs and DECISIONS.
   - V0201..V0203, RecordWriter, PayloadShapes and WireDtos are byte-identical to integration.
   - IngestService.kt merged without conflict: train 117's 29 changed lines plus the lane's 4 give the 33 seen, and no closed_by or attendance line is removed.
   - The five conflicts (DECISIONS, openapi.yaml, slices INDEX, data dictionary, AmoManualContractTest) each keep the integration side and add the lane's. The data dictionary count is 3039 + 7 = 3046.

2. **V0204:** the only new migration, with no V0207 file left. Against the reviewed V0207 it differs only in three comment lines (header number, the waiting_since column comment, the comment above VALIDATE).
   - On a database at v0203 with train-115 rows, the backfill touches only open rows and casts only text that already matched the uuid pattern.
   - Rows that do not match get a plain detail and no marker.
   - The trigger is created after the backfill.
   - The CHECK cannot fail, because the backfill sets the marker only on open rows.
   - Train 115 wrote "waits for another parent: <outcome detail> (parent <uuid> was accepted)", which fits both branches.

3. **Contract:** info.version is 1.20.0. The only changes vs 1.19.0 are four optional response members, all additive:
   - QuarantineItem.waiting, waits_for_uuid and waits_for_refused;
   - QuarantinePage.waiting_count.
   No path, parameter, required list or enum changed. The web uses TypeScript types only, so the closed schemas break no reader. Slices, INDEX (byte sizes match the blobs) and openapi.d.ts agree with the yaml. The history entry in the openapi description is missing (low).

4. **Code deltas since d88b751b:**
   - The poison stamp runs in its own db.jdbi.inTransaction with SET LOCAL lock_timeout = '2s'. The setting ends with that transaction, so it cannot leak to the pool, and the only statement it can abort is the one stamp UPDATE.
   - touch() now also requires waiting_since IS NOT NULL.
   - Two new tests (poison stamp through a raising trigger; awaited-parent follow) read as real. No test is weakened: one rename, one stale comment removed, and the migration is found by name suffix.

5. **Grants:** api_rw has table-level SELECT/INSERT/UPDATE on app.sync_quarantine, so the new columns are covered. There is no column guard trigger on that table. The audit_log insert was already granted. The trigger function needs no EXECUTE grant to fire.

6. **DECISIONS:** append-only vs 39db6c734 (5 lines added, 0 removed).

**Not verified:**
- whether the tests pass, in particular whether the follow test's edited awaited memo reads as stored after a backfill match;
- squawk on V0204;
- regenerating the slices, openapi.d.ts and data dictionary with their tools;
- oasdiff;
- the migration on the real dev data;
- the PostgreSQL trigger-privilege behaviour, which is from knowledge, not a run.

LOWS:
- db/migrations/V0204__cfg_waiting_sweep_keys.sql line 1 still says 'V0204 (PROVISIONAL number, lead 2026-11-18)'. The number is final, and the file cannot be edited once it ships, so fix the comment before the train or accept it for good.
- backend/analytics/src/main/kotlin/com/aktcl/aron/backend/analytics/OpsApi.kt:91 KDoc still says 'Read from the typed columns of V0207'. This is the only V0207 left in code; the rest are in DECISIONS history and copied review texts, which is fine.
- contract/openapi.yaml has no 1.20.0 entry in the history list of info.description (1.19.0 has one at line 128), and there is no docs/requests/contract-1.20-*.md. The gate does not need either for an additive change, and the list already skips 1.15, 1.17 and 1.18. The only history is the message in AmoManualContractTest, which still says 'provisional' and sits in a test still named versionIs1190.
- Train 117's code comments on the integration branch already call the attendance closed_by round 'Contract 1.20.0' (IngestService.kt:1021/1033/2301, RecordWriter.kt:104, DayStates.kt:169, WireDtos.kt:399, several tests), although it shipped as 1.19.0. With this train 1.20.0 means something else. Not this lane's change; worth a comment clean-up later.
- DECISIONS.md: D-LOY-API-32 is missing (28, 29, 30, 31, 33); the status file says it lives on the local slice branch. D-LOY-API-28 to -31 still speak of V0207 and 1.21.0; D-LOY-API-33 gives the mapping, which is right for an append-only file.
- The integration tip 29e8d86f (train 118) added 30 lines at the end of DECISIONS.md after 39db6c734, so the train merge will conflict at the end of that file; keep both sides. Train 118 touches no contract, backend, migration, shared-contract or data-dictionary file, and its highest migration is still V0203, so V0204 and 1.20.0 stay free.
- V0204 does not call app.apply_db_role_grants() for the new trigger function, unlike V0192/V0193. By reading this is not needed: PostgreSQL checks EXECUTE on a trigger function when the trigger is created, not when it fires, and the function only assigns NEW. The V0014 default privileges keep PUBLIC execute off, so the DbRolesTest rule holds. Not run here.
- The poison stamp (IngestService.kt:636) swallows its own failure in runCatching with no log line. If the 2s lock timeout hits, the item is not stamped and stays at the head until the next pass. Harmless, but silent.
- ccd4f156 is one merge commit that also carries the landing work, so there is no separate non-merge commit to read. I separated the lane's part with 'git show --remerge-diff'.
- Old-form (train 115) waiting rows whose text does not name '<field> <lowercase uuid>' get the plain detail and are never swept; a reviewer must accept them. This is the known, stated limit and is the safe side.
