# Lead check: lane/sr-server merge 9b69fba3 (V0209 + D-SV2-22 ingest order), 2026-11-18

Renumbering/contract/DECISIONS part: PASS (see ops-diagnose-api review file, re-check 6 section, lens v0209). Sync logic part below. Read from code only; nothing built or run.

## registry-interaction verdict=pass
I fetched lane/sr-server and read the whole ingest path at 9b69fba3 and at 9e6765d0. Everything was read-only. I looked at process steps 1 to 3c, finalReject, park, quarantine, register, release, releaseHeld and processInSavepoint in backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/IngestService.kt, plus the test diffs. The only change to the ingest logic is the move of closedByProblem. The base diff a4691a061..ce6792f67 touches IngestService.kt by only 2 lines, so the rest of the diff comes from the integration side.

What runs before step 3a: for a new uuid, nothing is written. Step 2 takes pg_advisory_xact_lock and reads the registry FOR UPDATE, and step 3 (the business-date window) returns or only logs. A rejection at 3a then calls finalReject: it writes a sync_rejected row, closes any open integrity item, and inserts a registry row with status rejected, schema_invalid and the hash. That is exactly what the old position wrote.

Case 1. First upload with a bad closed_by: rejected as schema_invalid and a registry 'rejected' row is left, in both versions. Same bad payload resent: before the merge, the closed_by check rejected it again and the registry insert changed nothing. Now step 2 finds the rejected row with the same hash and answers schema_invalid. Same outcome either way, and the new test checks it. Corrected payload under the same uuid: quarantined as payload_conflict in both versions, because step 2 compares hashes for any prior status. This is not new and not caused by the move (see lows).

Case 2. A check-out that was accepted, then resent with another payload whose closed_by is bad:
- Before the merge it answered schema_invalid. That ran outside the uuid lock, wrote a sync_rejected row, and called closeIntegrityItem, which closes by uuid only. So a different-payload resend could discard the open device-integrity review item of the record held under that uuid.
- The registry did not change, because register's ON CONFLICT never overwrites an accepted row.
- Now it answers payload_conflict: quarantined with register=false, no rejected row, nothing overwritten, and closed_by stays 'rep'. The new test checks this. The change is correct and closes that side effect.

Case 3. An out-of-window record with a bad closed_by:
- Before, it was a final schema_invalid. Now it is quarantined as business_date_out_of_window and registered as quarantined. A resend answers the same code from the registry, so it is stable.
- If a reviewer accepts it, release sets the registry to parked and process reaches 3a. finalReject turns the parked row into rejected, releaseHeld throws a 409 and the transaction rolls back, so the item stays open. An accept with a fix can correct closed_by.
- An invalid closed_by is never stored on any path: 3a is in every process run, and the database CHECK is a second line. This change is correct and matches the "quarantined, never dropped" rule for the window.

Case 4. Two concurrent uploads of the same uuid: 3a now runs under the uuid lock. The second upload waits, sees the first one's row, and answers the same outcome: duplicate, rejected or quarantined. Before, both could run the rejection in parallel. The ON CONFLICT clauses kept that safe, and the outcomes were the same.

Wrong actor: no change. The actor still comes from the token, and 'phone' is still allowed only on a check_out.

Tests: only added, nothing weakened. CheckoutClosedByTest has a new Order(7) test covering cases a, b and c. TeamPhoneCloseBase gains a kind parameter (default check_out, so earlier calls are unchanged) and a positive control for check-ins without a mock flag.

Not verified: nothing was built, compiled or run here. That includes the Gradle tests, the fuzz suite, the migrations, the dev database and a real concurrent run. I also did not check the TeamPhoneClose positive control's timing against the actual timestamps of its world, or the non-ingest hunks of the merge (RiskSignals, WaitingChildSweep, PhoneEvidenceShape, bundle) beyond seeing that they are in the diff.

LOWS:
- Old problem, also at 9e6765d0 and not caused by this merge: any final reject (a schema_invalid at step 1 or at step 3a) leaves a registry 'rejected' row with the payload hash. A corrected resend under the same client_uuid is then quarantined as payload_conflict instead of being processed. It is reviewable, not silently dropped, but it goes against the stated rule that a schema_invalid rejection must not make a corrected resend look like a payload_conflict. If the rule is meant literally, it needs a decision for all final rejects (for example, letting a different hash through when the prior row is rejected/schema_invalid). Changing only the closed_by check would make it inconsistent with the others.
- Outcome change for a uuid already seen on dev (closed_by validation went live with deploy #357): a different-payload resend of an accepted check-out, which the old order answered schema_invalid without changing the registry, now answers payload_conflict. Nothing stored changes and both outcomes are final for the phone, but a resend's answer differs across the deploy. Worth one line in D-SV2-22.
- Outcome change for an out-of-window record with a bad closed_by that has not been seen before: the old order gave a final schema_invalid, the new order gives a quarantined business_date_out_of_window. A reviewer's plain accept then gets a 409 from step 3a, and only an accept with a fix can clear it. That is correct, but the review queue may hold such items, and no test checks the accept-then-409 path for a bad closed_by.
- The old order called finalReject outside the uuid advisory lock. Its closeIntegrityItem closes by client_uuid only, so a different-payload resend with a bad closed_by could discard the open device-integrity review item of the record already held under that uuid. The new order fixes this as a side effect, but no test pins it: case (a) of the new test uses an accepted record, not a device-held one.

## precedence-and-tests verdict=pass
Read-only review of lane/sr-server head 9b69fba3 (fetched; origin/lane/sr-server = 9b69fba32a). I compared the IngestService.kt hunk against ce6792f67 and 9e6765d0. Apart from comment wording, the only lane-side code change in the merge is the closedByProblem move. It now runs as step 3a, after the registry (step 2) and the business-date window (step 3), and before checkout_too_early, the config stamp check, references, scope, the device gate, parents and storage. The check body is unchanged: only rep or phone is accepted, phone only on a check_out, and a null or a number is refused. The DB CHECK still backs it.

Precedence matches the rest of process(). The other content-level schema_invalid refusals already sit after the registry and the window: 5a verification decision and cluster zone, 5c the outlet_call photo, 9 parent UUID and parent type, and the processInSavepoint DB refusal. Only the envelope or member shape and the money bound (step 1) run before the registry. It also matches the written rule: docs/requests/contract-1.19-sr-server-checkout.md line 56 (D-SV2-22) states this order. Contract text: openapi closed_by says only that phone on a check_in is refused schema_invalid. RecordOutcomeCode and docs/17 s475 treat schema_invalid, business_date_out_of_window and payload_conflict as terminal on the phone; none of them is retried. So no client depends on getting schema_invalid first.

The Android client never sends a bad value. RecordMapping sends closed_by "phone" only for a phone close. For every other attendance event it sends null, and with encodeDefaults=false and the DTO default of null the member is left out (WireDtos.kt:402).

Outcomes across resends and reorders:
- Fresh in-window bad value: final schema_invalid, the same as before; its resend returns the stored rejected code.
- Known uuid with a different payload: payload_conflict quarantine (register=false). Nothing is stored and the first copy stands.
- Accepted record resent: duplicate. The registry check runs before 3a, and an accepted copy had a valid value anyway.
- Out-of-window bad value: now business_date_out_of_window, quarantined. On a reviewer accept, release() runs process again with reviewed=true, which reaches 3a, gives schema_invalid, throws 409 and rolls back. The item stays open, so no invalid closed_by is ever stored. Accept-with-fix stores the corrected copy, and the phone's original copy resent later is a duplicate via fixedCopy.
- Device-hold release: those records pass 3a as well.
- Wrong actor: phone on a check_in is still refused before any store.
No resend or reorder changes an outcome. The registry is no more affected than before: a closed_by refusal writes the same rejected row as at 9e6765d0, which a later corrected copy under the same uuid would hit as payload_conflict; that behaviour is older than this merge (Low 1).

Tests:
- CheckoutClosedByTest has only an addition, @Order(7). It asserts payload_conflict for a known uuid with a bad value (no sync_rejected row; the first copy stays rep), business_date_out_of_window for both a past and a future date, and a final schema_invalid on the first send and on the resend, with the named reason.
- TeamPhoneCloseTest/TeamPhoneCloseBase only adds a kind parameter (default check_out, so earlier calls are unchanged) and a positive control. A newer non-mock rep check_in fix (180 s ago, newer than the 300 s and 240 s rows) becomes last_fix, and mock_fixes_today stays 1.
- BatchAcceptanceTest changes come only from the base (one additive test and an optional signals parameter).
- SyncConvergenceFuzz is untouched.
- No assertion was removed or loosened in these four files. The only removed assert lines in backend/ are in DeviceEnrolmentTest, and those come from the integration base.

Not verified: nothing was built or run, so I have not seen the new tests pass. I did not run the reviewer-accept and return-to-device paths for an out-of-window bad record; I traced them by reading the code only. I did not read the whole of docs/24 s4.5 or the Android AckRules mapping beyond the tests quoted.

LOWS:
- Older than this merge, not introduced by it: finalReject always registers a 'rejected' row for the record's hash. So a CORRECTED resend under the same client_uuid after any final schema_invalid (closed_by included) is answered payload_conflict, not accepted. The phone treats schema_invalid as terminal and never reuses a uuid for a correction, so this is by design here. But docs/reviews/sr-server-followups2-2026-11-18.md line 10 says 'registry untouched', which is inaccurate: the registry does get a rejected row.
- Return-to-device of an out-of-window item with a bad closed_by: the phone's resend is processed again (returnedWithoutOpenItem) and answered rejected schema_invalid. But register()'s ON CONFLICT ... WHERE (parked, or quarantined with a device hold code) skips the update, so ingest_registry stays 'quarantined/business_date_out_of_window' while sync_rejected says schema_invalid. Every later resend gives the same answer, so the outcome is stable. Only the registry status and the rejected log disagree. The same mismatch exists for other post-registry final rejects on returned items.
- Consistency note: closed_by is an enum value (AttendanceClosedBy). It is now judged after the registry, while other member shape and value errors (step 1 shapeError, moneyOutOfRange) still run before it. So a known uuid with a shape error answers schema_invalid, but with a bad closed_by it answers payload_conflict. This is documented in D-SV2-22 and the contract-1.19 request file and is harmless, but the two orders now differ.
- Test gaps in CheckoutClosedByTest @Order(7): (1) no case for a reviewer accept of the out-of-window quarantined bad-closed_by item, which should give 409 with the item still open, and an accept-with-fix that stores rep or phone; (2) the payload_conflict copy is not resent a second time to show the answer stays payload_conflict; (3) for the out-of-window copies it does not assert that no attendance_event row was stored or what the registry status is; (4) there is no positive in-window closed_by=phone check_out resend-equals-duplicate assertion in this test (it may exist in earlier orders; I did not check).
