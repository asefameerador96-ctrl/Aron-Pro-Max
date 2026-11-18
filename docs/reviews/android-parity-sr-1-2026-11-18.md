# SR parity branch 1 (lane/android-parity-sr-1) at b08c3782, lead check, 2026-11-18

Base INT 2c7ff3c1bd. Three lenses (sale correctness: Opus; state/UI and tests/goldens: Sonnet). Reviewers read the code; no Gradle runs; nothing walked on a device. RESULT: PASS, no blocking.


## sale-correctness — PASS

PASS for the sale-correctness lens; findings come from reading the code only (no Gradle, tests not run). Merge base = 2c7ff3c1bd = second parent of b08c3782a6, so the base-to-merge diff is exactly what the lane brings.

Merge: in SaleFlow.kt, SaleUiState carries both `notice` (lane) and `laterChangesNotSaved` (landed D-AGATE-25). Diffing lane parent 442fdccf38 against the merge shows the landed commit() replay block was kept in full: an existing memo is looked up before the closed-call refusal, the draft is cleared, and it sets `s.copy(committed = landed, laterChangesNotSaved = differs)`, which keeps `notice`. SaleRoute.kt keeps the landed later-changes dialog. Landed SaleScreens EARLIER_CALL_OPEN string fix and the Sr/AmoSaleKit `slipNotPrintedOn(date)` change also kept. Comparing base against the merge, the only base lines removed are the ones the lane replaced on purpose: withEdit's `check(!qcCompleted)` (now an early return false plus the Locked notice), the edit/editLocked signatures, and the start() stored-draft predicate. No line from base is lost.

Neither field overwrites or hides the other:
- On an accepted change, editLocked builds a fresh SaleUiState, which clears notice, refusal and laterChangesNotSaved, as documented. It requires `committed == null`, so it cannot run after a commit or a replay.
- On a locked refusal it uses `s.copy(notice = Locked)`, which keeps the other fields.

No double memo:
- commit() is still serialised by the mutex, returns `s.committed`, then checks `committer.existing(draft.memoUuid)`, before writing.
- The new start() rule drops a leftover SALE draft whose memo already landed when an edit opens (it only calls `committer.existing`). That prevents the 'sale already committed' crash and adds no write.

QC-locked edits are refused at three layers, with nothing prefilled:
- prepareEdit's EditGate (qcEnteredForOutlet).
- Sr/AmoSaleKit.beginEdit returns false right after start() when `draft.qcCompleted`, before any setQuantity/setPaid; the hosts then show qc_locked and call onDone.
- SaleFlow.withEdit returns false with no give-back of stock.
start() also skips the give-back for a locked draft.

Locked drafts stay unchanged: SaleDraftOps still `check(!qcCompleted)` on quantity, slide and QC. editLocked turns that into a no-write Locked notice. setQuantity returns the quantity that stands. QcLockedFlowTest and SrSaleLockedRouteTest assert the draft is unchanged. No money or quantity arithmetic changed: the stock clamp and the milli-taka review are untouched. setPaid stays allowed after QC, as in base.

VM guard: refusalGuard turns only DraftRefusedException into a Refused notice and rethrows everything else, so real defects still crash.

ActionGate: the lane touches no core-database writer. The CallContentHost diff has no record/save changes, and SaleFlow's commit still goes through the committer and the landed AttendanceRefused handling.

Strings: qc_dropped_notice and sale_change_refused are in both values and values-bn. The removed sr_glyph_dropdown has no remaining references.

State across process death: the draft is persisted before every publish. The lane deliberately leaves the stage and the QC page unsaved, and the route shows Review when the draft is locked.

- low: SaleFlow.kt commit(): the normal success path does `s.copy(committed = result)`, so a stale `notice` (for example Locked or Refused from an earlier tap) stays on the state, and its banner still shows over the committed sale. It is only cosmetic. The landed-replay path keeps `notice` the same way. Clearing `notice` on commit would be cleaner.
- low: SaleFlow.kt editLocked(): on a QC-locked draft, every IllegalStateException from the change becomes SaleNotice.Locked. Example: confirmZeroSale on a locked draft that has lines throws 'a zero sale has no lines', and the rep sees the lock text instead of 'change refused'. Nothing is written, so this is only wording. The route hides Entry while locked, so this path is hard to reach.
- low: SaleFlow.kt start(): `previousNotice` carries a notice into a restart of the same visit, but `publish` resets `laterChangesNotSaved` to false. This matches base behaviour, because base start() never kept that flag either. I note it only because the two fields now behave differently across start().
- low: SaleRoute.kt: `laterShown` uses plain `remember` (landed code, unchanged by the merge), so the D-AGATE-25 dialog shows again after a rotation. This is the base behaviour.
- low: SrQcRouteRestoreTest.kt is deleted relative to base. The lane deleted it on purpose in c48337db8a, not by accident in the merge. SrSaleLockedRouteTest.anOpenQcPageIsNotRestored covers the same assertion (step 2 open, restoration, QC reopens on step 1 with 0 qty fields), plus the new locked and QC-dropped cases.

## state-ui — PASS

I read the diff for CallContentHost.kt, CallContentViews.kt, SearchPicker.kt (PickerSheet), the SaleRoute/SaleViewModel/SaleFlow changes and the resource diffs. I found no blocking state/UI defects. I did not read SrQcRouteRestoreTest, SrSaleLockedRouteTest or SurveyStepViewTest, and nothing was run.

- **Process death and rotation:** the survey answers (including photo uuids) and the confirm flag use rememberSaveable with a custom Saver. The picker query is saveable. The sale route now forces Review when QC is submitted, so a restored Entry stage cannot send a locked change.
- **Back and dismiss:** the survey confirm dialog keeps back and outside-tap dismissal, because `onNo` is wired as the dialog's `onDismiss`.
- **Strings:** the extracted views take all their text from `SurveyTexts`, which the host builds from resources. I did not check the long-Bangla layout of these views.
- **Accessibility:** the dropdown row's description names the question and the answer, the photo box has Role.Button, and touch targets use `AronTokens.Touch.Min`. The 96dp photo box is above the minimum.
- **Performance:** the picker's LazyColumn is keyed by item key.
- **Refusal guard:** `SaleViewModel`'s refusalGuard turns only `DraftRefusedException` into a notice and rethrows everything else, so real bugs still surface. QC-locked changes become the Locked notice with nothing written, and the draft is saved before state is published.

- low: CallContentViews.kt: SurveyStepView lists question rows with a plain forEach inside a verticalScroll Column (not lazy, unkeyed). Survey sizes are small so this is fine on 2 GB phones, but a very long survey would compose every row.
- low: CallContentHost.kt: the content `index` and `items` use plain remember, not rememberSaveable. After process death the AV/KV sequence restarts from the pending list. This is safe because logged items drop out of pendingContent, and the survey answers and confirm flag are saveable.
- low: CallContentHost.kt: the KV Image has contentDescription = null and the AV VideoView has no semantics, so TalkBack gets nothing for the item itself. The band heading and the close button are labelled.
- low: SearchPicker.kt: PickerSheet hardcodes Color(0xFFEBE5F0) rather than a look token. This is carried over from the previous code and was not introduced here, but it ignores Sunlight and dark themes.
- low: SaleRoute.kt: `stage` is still plain remember, so a relaunch returns to Entry. The new `locked` redirect to Review covers the QC-locked case, and the QC two-step doc now states this plainly. The QcDropped notice is carried across start() by previousNotice, so the 'told once' wording is approximate.
- low: Resources: every string the new views use (sr_content_*, sr_survey_*, sr_yes/no/ok) exists in both values and values-bn. The new qc_dropped_notice and sale_change_refused have bn translations. The removed sr_glyph_dropdown has no remaining references.

## tests-goldens — PASS

I found no weakened, deleted or loosened assertion. The only existing test file changed is QcLockedFlowTest, and its diff is purely additive (+92 lines, no removed or altered lines). The one deletion is SrQcRouteRestoreTest. It is superseded by SrSaleLockedRouteTest, which keeps the same assertions (6 qty fields on step 2, 0 after restoration, step 1 reopens) but at SaleRoute level instead of the host level (low 1). The new tests assert real behaviour.

- QcLockedFlowTest: quantity and slide changes on a locked draft leave it unchanged, set the Locked notice and return the standing quantity. An accepted change clears the notice. withEdit is refused on a locked sale and leaves nothing given back in the catalog. QcDropped appears only when typed QC was dropped. A leftover draft file of an already-landed memo no longer breaks an edit start.
- AmoBeginEditLockedTest and SrBeginEditLockedTest: both are mirrors of each other. They assert that on a QC-locked draft beginEdit returns false, the draft is equal to before, edit is null, paidMtk is null and the catalog is unchanged. A positive control confirms an unlocked edit still opens.
- SaleViewModelGuardTest: a negative payment becomes the Refused notice with the draft unchanged. Other exceptions are rethrown rather than worded as a refusal. The source-level launch check is brittle (low 3) but works today.
- SurveyStepViewTest: asserts the exact spoken description for answered and unanswered rows.
- SrParity1bContentTests: golden tests in record or verify mode that fail on a missing golden.

I read the tests, not Gradle output (read-only review), so none of these tests were run.

Goldens: 12 PNGs, all new (status A), none modified. Each maps to one test in SrParity1bContentTests:
- srp1b_content_kv.png, srp1b_content_kv_sunlight.png, srp1b_content_kv_font20.png
- srp1b_content_survey.png, srp1b_content_survey_answered.png, srp1b_content_survey_photo_asked.png, srp1b_content_survey_sunlight.png, srp1b_content_survey_font20.png, srp1b_content_survey_confirm.png
- srp1b_cluster_picker_open.png, srp1b_cluster_picker_filtered.png, srp1b_cluster_picker_none.png

The diff against 2c7ff3c1bd touches no other screenshot file. No integrity-block or programmes golden was overwritten, and the ui-screenshots change is the new test file plus the 12 new PNGs (13 files, additions only).

Wall-clock: the new tests do not use wall-clock for assertions. Fixed ISO strings are injected into the committer and ledger. The only System.currentTimeMillis use is a 10 s poll timeout in SrSaleLockedRouteTest (low 2).

- low: SrQcRouteRestoreTest was deleted and replaced by SrSaleLockedRouteTest.anOpenQcPageIsNotRestored. The replacement checks the same outcome (step 2 open, restore, QC reopens on step 1, 0 qty fields) but at SaleRoute level with StateRestorationTester. The old test ran the real SrFrame and SaleHost and used ActivityScenario.recreate(), so SaleHost/SrFrame wiring and real activity recreation are no longer covered for QC restoration. This is a narrowing, not a loss of the assertion; consider keeping one host-level test.
- low: SrSaleLockedRouteTest.until() uses a System.currentTimeMillis deadline (10 s) with Thread.sleep(10), and the file runs the main looper manually. It only bounds a timeout and no assertion depends on the clock, but a slow CI machine could time out. Not a correctness issue.
- low: SaleViewModelGuardTest.everyGuardedLaunchUsesTheNetAndStartHasNone is a source-text test. It reads SaleViewModel.kt by a path relative to the module working directory (src/main/kotlin/...) and filters lines containing 'viewModelScope.launch'. It is brittle to refactoring, such as a launch moved to another line or a different launch helper. It does catch a new unguarded launch today, and the two behavioural tests beside it are real.
- low: SrParity1bContentTests uses a stand-in picture for the KV step, which the file says openly. The AV step is not captured, as the file also says. Both are acceptable but are coverage gaps. The cluster-picker goldens render PickerSheet with a title frame, not the SR Frame.

## CI note

Lane run 44372290509: every job green except the web summary job, step 'Download the shard reports', which failed twice (attempt 1 and the single allowed re-run) with HTTP 503 'Egress is over the account limit' from GitHub's artifact storage while downloading an e2e shard report. The five e2e shards themselves passed. Two other runs minutes earlier passed the same step. Read as a GitHub-side storage limit, not a code fault; not re-run again; the train branch CI retests the same code.
