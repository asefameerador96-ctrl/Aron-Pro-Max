# tso-manual A2 android-tickets: lane status

Lane A2 of `docs/requests/tso-manual-build-2026-10.md` (s5 A2). Branch `tso-man/A2`, no pull request.
Written 2026-10-29 (steps 1 to 3, then the checker's fix round). Base: 95a35b7d (contract 1.10.0, V0130-V0140, F3 rules); step 3 merged
`tso-man/A1-stubs` (5e9c52b0) first, as s5 asks.

## Done

### Step 1: gallery import (core-media), A2's first commit, used by A3 (feedback gallery)

Branch pointer for A3: `tso-man/A2-gallery`.

| file | what |
|---|---|
| `android/core-media/src/main/kotlin/com/aktcl/aron/core/media/GalleryImport.kt` (new) | `GalleryImporter<I>`, `GalleryImportException` (+ `Reason`, `messageRes`), `rememberGalleryPicker(maxItems, onUnavailable, onPicked)`, `galleryImagesRequest()`, `pickedWithin` |
| `MediaComponents.kt` (append, `// tso-manual A2`) | `val gallery: GalleryImporter<Bitmap>`, `suspend fun importFromGallery(mediaUuid: String, uri: Uri, ref: MediaRef): MediaItem` (one import line `android.net.Uri` marked too) |
| `res/values/strings_a2.xml`, `res/values-bn/strings_a2.xml` (new, core-media) | `media_gallery_failed`, `media_gallery_too_large`, `media_gallery_unavailable` (en and bn) |
| `src/test/.../GalleryImportTest.kt` (new) | see Verify |

How a caller uses it (A2 ticket sheet and create screen, A3 feedback):

```kotlin
val pick = rememberGalleryPicker(maxItems = cfgMax - photos.size, onUnavailable = { show(R.string.media_gallery_unavailable) }) { uris ->
    scope.launch {
        uris.forEach { uri ->
            val id = newUuidV4()                       // keep it in the form's saved state with the record UUID
            try { photos += media.importFromGallery(id, uri, MediaRef("support", "support_ticket", ticketUuid)) }
            catch (e: GalleryImportException) { show(e.messageRes) }
        }
    }
}
```

Behaviour:
- The picture goes through `PhotoCapturer` itself (the picked file is its `CameraSource`): same compressor and
  `cfg.media.*` budget (150 KB, 1024 px), upright, every APPn/COM segment dropped (no GPS, maker, original date),
  SHA-256 and dHash, thumbnail, the same 200 MB low-storage rule, the same media store; then `MediaComponents.attach`
  (no shutter fix) claims it and calls `MediaWorkScheduler.photoQueued`, so upload, SAS, PUT and `media_meta` are the
  camera's path unchanged.
- Idempotent by media UUID: a repeat (or a relaunch with the read grant gone) returns the stored item without reading
  the URI; a claimed picture cannot move to another record (IllegalStateException, as for the camera).
- The read stops at 32 MiB (`MAX_SOURCE_BYTES`), so a huge file never fills a 2 GB phone's memory.
- Picker: `PickVisualMedia` / `PickMultipleVisualMedia`, images only. No storage permission anywhere.


### Step 2: the tickets module `android/feature-tickets` (TK-01..13, TK-19, TK-22, CR-8, D-TSO-MAN-17..25)

`settings.gradle.kts`: `include(":android:feature-tickets") // tso-manual A2` after the feature-tso line (the one line).
Package `com.aktcl.aron.feature.tickets`, role-agnostic (no TSO type, no "tso." key, no nav): depends on core-ui,
core-common (TicketMath, Percent, Formats through shared:rules) and `api(:shared:contract)` (the queue port carries the
generated `SupportTicketPayload` / `SupportTicketEventPayload`).

| file | what |
|---|---|
| `TicketPorts.kt` | host ports: `TicketSource` (issueTypes, cached, list, detail, cachedDetail, outbox), `DetailRead`, `TicketQueue` (ticket, event: outbox row + mirror row + photo claim in one transaction, idempotent by uuid), `TicketPhotos` (max, capture, rememberGallery, import, discard, Thumbnail), `PhotoOwner`, `PhotoResult`, `TicketDeps(source, queue, photos, now, newUuid)` |
| `TicketModel.kt` | `TicketStatus`, `TicketPriority`, `TicketOrigin`, `TicketTab`, `IssueType`, `Delivery` (Sent / Pending / Refused(code)), `Ticket`, `TicketEvent`, `TicketDetail`, `LocalEvent`, `TicketOutbox`, `TicketSnapshot`; `TicketLists` (merge by uuid, local search, tabs, recent 10, counts through TicketMath); `TicketDraft`, `CommentDraft`, `TicketRules` (validation, payloads, the unsent row, `closeUuid`); `TicketThread` (thread merge, effective status, canClose, canComment); `TicketIds.nameV4` |
| `TicketWire.kt` | `TicketWire` (SupportTicketPage / Summary / Detail bodies to the model; a bad row is dropped, not the page), `TicketMirror` (the host's mirror rows as JSON), `IssueTypes.parse` (bundle `code_lists` -> `ticket_issue_type`, sort order, icon fallback (sort-1) mod 8) |
| `TicketStates.kt` | `TicketDashboardState`, `TicketListState`, `TicketFormState` (Quick sheet and Create), `IssueTypesState`, `TicketDetailState` (close, comment, photos), `FormPhase`, `DetailPhase`, `TicketSavers` (rememberSaveable savers: the draft and its uuid survive rotation and process death) |
| `TicketKit.kt` | `TicketFrame` (the host's top bar slot) and `plainTicketFrame`, `TicketPalette` (+ `LocalTicketPalette`), the issue hues and glyphs, `TicketTags`, `TicketTime` (Asia/Dhaka 12-hour), cards, pills, fields, buttons, the in-layout sheet, the resolution bar |
| `TicketDashboardScreen.kt` | screenshot 11 (+ `rememberTicketDashboardState`) |
| `TicketListScreen.kt` | screenshots 12, 13 (+ `rememberTicketListState`: tab and search text saved) |
| `TicketDetailScreen.kt` | screenshot 14 (+ `rememberTicketDetailState`: the comment draft saved) |
| `QuickTicketScreen.kt` | screenshots 15, 16 (tiles, the sheet) |
| `CreateTicketScreen.kt` | screenshots 17, 18 (the form, the issue list) |
| `res/values/strings.xml`, `res/values-bn/strings.xml` | every visible text, en and bn (`tickets_` prefix) |
| tests | `TicketModelTest`, `TicketListStateTest`, `TicketFlowTest`, `TicketScreensTest` (Robolectric), `TicketFakes.kt` (FakeServer, FakeDevice, FakePhotos) |
| `android/ui-screenshots/.../TicketScreenshotTests.kt` | 27 goldens (names under Verify); one appended dependency line in `android/ui-screenshots/build.gradle.kts` (see Deviations) |

How a host wires it (next step, app-tso `TicketsHost.kt`):

```kotlin
val frame: TicketFrame = { title, content -> TsoFrame(title, nav, homeButton = false) { content() } }
TsoScreen.TICKET_DASHBOARD to { nav, _ -> TicketDashboardScreen(rememberTicketDashboardState(deps), frame, onOpen = { nav.open(TsoScreen.TICKET_DETAIL, it) }, onSeeAll = { nav.open(TsoScreen.TICKET_LIST) }, onQuick = { nav.open(TsoScreen.TICKET_QUICK) }) }
TsoScreen.TICKET_LIST to { nav, _ -> TicketListScreen(rememberTicketListState(deps), frame, onOpen = { nav.open(TsoScreen.TICKET_DETAIL, it) }) }
TsoScreen.TICKET_DETAIL to { _, arg -> TicketDetailScreen(rememberTicketDetailState(deps, arg!!), frame) }
TsoScreen.TICKET_QUICK to { nav, _ -> QuickTicketScreen(deps, frame, onCreate = { nav.open(TsoScreen.TICKET_CREATE) }, onSubmitted = { nav.open(TsoScreen.TICKET_LIST) }) }
TsoScreen.TICKET_CREATE to { nav, _ -> CreateTicketScreen(deps, frame, onSubmitted = { nav.open(TsoScreen.TICKET_LIST) }) }
```

Behaviour (s5 A2 rules):
- One uuid per form: the draft (uuid, fields, staged photo uuids, `queued`) lives in `rememberSaveable` through
  `TicketSavers`, so rotation, a language switch and process death keep it; a retry after a kill mid-save queues the same
  uuid and the outbox keeps one row (the port returns false for a uuid it holds, which the form treats as saved). A
  second tap while saving, or any tap after the save, does nothing. Comments: one uuid per comment, a new one after each
  save. Close: one uuid per ticket (D-A2-11).
- Quick: title = the issue's `label_en`, description optional (up to 2000). Create: issue type, title (1..120) and
  description (1..2000) required. Validation runs before the queue; nothing waits for the network.
- Unsent tickets show "Not sent yet · <issue>" instead of the INC number (amber); acked ones not yet read back show
  "Sent, number follows"; refused ones keep their row with "Not accepted: <reason>" (ticket_state_invalid,
  issue_type_unknown, scope_out_of_reach, else the code).
- Local search: number, title, issue label (en and bn), case-insensitive, Bengali digits match the number. Tabs All, New,
  In Progress, Resolved, Closed; All also shows Cancelled (and any unknown status). Recent tickets: 10, newest first.
- Counts and percentages only through `TicketMath` / `TicketCounts.resolvedPctText` / `openPctText` (R-F-14; F1 ask 15
  and F3's A2 ask done); total 0 prints "0%". Offline: the copy plus unsent tickets as New, merged by uuid
  (`TicketMath.counts(cached, unsentUuids)`).
- Times: ages through `TicketMath.ageText` (D-TSO-MAN-25); thread times Asia/Dhaka 12-hour ("5:34 PM"; another day
  carries its date first).
- Details: Submit Review & Close only on a Resolved ticket the server holds; after the tap the ticket shows Closed with
  "Closing. Not sent yet." until read back. The composer shows only for New and In Progress; Resolved, Closed and
  Cancelled show the fixed note (screenshot 14's text for Resolved). Support is always "Support" (never a name).
- Reads only on open and pull-to-refresh (`PullToRefreshBox`); no timer anywhere.

### Step 3: host wiring (app-tso), the s5 A2 host items

Merged `tso-man/A1-stubs` first (`git merge --no-ff`, no conflict). Files:

| file | what |
|---|---|
| `android/app-tso/.../TicketsHost.kt` (A2's since a1-stubs) | `TicketsHostDeps(host)`: `api`, `device`, `maxPhotos`, `photos`, one stable `deps`; `ticketScreens(deps)`: TICKET_DASHBOARD, TICKET_LIST, TICKET_QUICK, TICKET_CREATE, TICKET_DETAIL (uuid argument) in `TsoFrame(title, nav, homeButton = false)`; `TicketNav.afterSave` (back, then All Tickets); `TicketPush.target(reason)` (`ticket_updated` -> TICKET_DASHBOARD); `HostTicketSource` (the `TicketSource`), `TicketDevice` (the `TicketQueue`, mirrors, delivery, bundle reads), `MediaTicketPhotos` (the `TicketPhotos`) |
| `android/app-tso/.../TicketApi.kt` (new) | `TicketApi`: `listSupportTickets(cursor, limit = 200)`, `summary()`, `get(uuid)` (GET only, no scope id, a non-UUID never reaches a path) and its own file cache (`keepList` / `keptList` / `forgetList`, `keepDetail` / `keptDetail` / `forgetDetail`); `TicketCopy` (the kept copy = the raw pages and summary, read back through `TicketWire`) |
| `android/app-tso/build.gradle.kts` | one appended line `implementation(project(":android:feature-tickets")) // tso-manual A2` (end of the dependencies block) |
| `android/core-media/.../MediaComponents.kt` (A2's append) | `importFromGallery(mediaUuid, uri, ref, claim: Boolean = true)`: `claim = false` only stages (A3's three-argument calls unchanged) |
| `android/core-media/.../GalleryImportTest.kt` | + `aStagedPictureIsNotClaimedUntilTheSaveAndARemovedOneIsDropped` |
| `android/app-tso/src/test/.../TicketWriteReplayTest.kt` (new) | 12 tests, see Verify |
| `android/ui-screenshots/.../TicketScreenshotTests.kt` | the real host frame (`TsoFrame(title, nav, homeButton = false)`, as TicketsHost builds it) instead of the stand-in bar; + `tickets_list_resolved_200_bn`, `tickets_quick_sheet_200_en`, `tickets_issue_types_200_bn` (every screenshot 11 to 18 now has en, bn and a 200 % case) |
| `android/feature-tickets/.../TicketKit.kt` | `hueFill(page, hue, fraction)` (was `amount`: the money-float scan read the colour mix as money; see Verify); `TicketPill` wraps to two lines (ellipsis after that) instead of dropping a word ("সমাধান হয়েছে" at 200 %) |
| `android/feature-tickets/.../TicketListScreen.kt` | the selected status tab is scrolled into view once the row is laid out (Resolved was off screen in bn and at 200 %; found by looking at the recorded PNGs) |
| `docs/08-tso-app.md` | appended "Tickets" section (s5 header: A2 appends it) |

Behaviour:
- **Writes** (`TicketDevice`): `support_ticket` / `support_ticket_event` through the one `TsoOutbox` (family self, rank 0,
  envelope from trusted time), mirror rows `tso.unsent.ticket.<uuid>` / `tso.unsent.ticket_event.<uuid>` (the module's
  `TicketMirror` JSON) in the SAME transaction as the row, then one debounced sync. A uuid the outbox holds, or one purged
  after its ack (`purged_uuid`), answers false and claims nothing. Photos (at most 3) are claimed for the record
  (`MediaRef("support", "support_ticket" | "support_ticket_event", uuid)`) before the commit, as the feedback queue does.
  Payloads are the generated DTOs encoded with absent nulls (a close review has no `body` member; no affected username
  means no member).
- **Reads** (`HostTicketSource`): on open and pull-to-refresh only. List = pages of 200 (cursor) up to 5 pages, then the
  summary; `complete` false when cut. A read that fails half way returns null and keeps the old copy whole. The mirror of
  every ticket the list returns is dropped; a thread read drops the mirrors of its events (and of its ticket). Detail 404
  (an unsent ticket, or not the caller's) is NotFound and its copy is dropped. `outbox()` delivery per D-A2-15.
- **Bundle**: issue types from the section `code_lists` (`IssueTypes.parse`; chunked sections read whole through
  `ReferenceRepository.section`); `cfg.support.ticket_max_photos` read each time a ticket screen opens, clamped 0..3,
  default 3.
- **Photos** (`MediaTicketPhotos`): camera through the on-screen media components (`host.media()`, whose overlay
  MainActivity draws); gallery through `rememberGalleryPicker` and `importFromGallery(claim = false)` (staged, EXIF gone,
  compressed); `discard` drops a removed one; thumbnails decoded off the main thread from the media store.
- **Navigation**: Dashboard -> Detail (uuid), See all -> All Tickets, Need help -> Quick, Quick's "Report a different
  issue" -> Create; after a save the form is closed and All Tickets opens. A detail without an argument goes back.

### Fix round (independent checker, 2026-10-29): 1 high, 8 low

| # | sev | defect | fix | proof |
|---|---|---|---|---|
| 1 | high | Quick sheet could never be closed: `dismiss` nulled the sheet, then `abandon` read the stale opening draft and wrote it back, reopening the sheet; staged photos never discarded | `SheetSlot` (QuickTicketScreen.kt): a form's write reaches the sheet only while it shows that uuid, and `read` returns the form's last draft, never the opening one. `dismiss` takes the photos with `TicketFormState.leave()` BEFORE closing, then drops them on `TicketDeps.background` (`discardLater`); it does nothing while the save runs. The sheet's form is now built in the screen (needed by the leave hook, #3) | `TicketScreensTest.theQuickSheetClosesForGoodOnTheXAndOnBackAndDropsItsPhoto` (open tile, camera photo, X: sheet gone and stays gone after 2 s, photo in `FakePhotos.discarded`; again with system back through the activity's `OnBackPressedDispatcher`: sheet gone, tiles still there, second photo discarded; then a new tile saves one ticket). Mutation check: with the old read/write/dismiss restored the test fails (Verify) |
| 2 | low | Photos claimed before the outbox commit; a failed or killed commit left them claimed, so a photo removed afterwards was still sent with the retry; KDoc said "one transaction" | Claim before commit is kept (it is the media layer's documented rule: `MediaComponents.attach` "BEFORE committing that record"). `MediaTicketPhotos.discard` now also drops a photo claimed for a ticket record that was never saved (`TicketDevice.holds(uuid)`: no outbox row and not purged), only for purpose `support` and ref types `support_ticket` / `support_ticket_event`; a saved record's photo and any other feature's photo are kept. KDoc of `TicketQueue`, `TicketPhotos.discard`, `TicketDevice` corrected. `removePhoto`, `removeCommentPhoto` and `leave` do nothing while a save runs (the save owns the photos it took) | `TicketWriteReplayTest.aPhotoRemovedAfterAClaimWhoseCommitFailedIsNeverSentWithTheRetry` (claim two, commit fails, remove one: gone from the media store; retry: one row, only the kept photo, claimed for the ticket; a saved record's photo survives a discard), `aFormLeftAfterAKilledSaveDropsItsClaimedPhotosAndKeepsOtherRecordsPhotos` (claimed and staged dropped after a relaunch; a `visit` photo untouched); `TicketFlowTest.aRunningSaveOwnsItsPhotosRemoveAndLeaveWaitForIt`. Note: the checker's second case (an abandoned claimed photo uploads and is parked `parent_missing`) does not happen on this code: `MediaUploader` uploads only when the record is acked or was seen in the outbox, and deletes an unseen one after `pending_photo_grace_days`; it is now dropped at once |
| 3 | low | Photos staged in Create or an unsent comment leaked when the rep left without saving | `OnLeaveScreen` (TicketKit.kt): runs once when the screen leaves the composition while the lifecycle is at least STARTED (back, top-bar back, drawer); never on a recreation (rotation, language, process death: the lifecycle is already below STARTED and the saved draft brings the photos back). Create, Quick (sheet open) and Details (comment) drop their staged photos through it on `TicketDeps.background` (new constructor parameter, default an IO scope), so leaving never cancels the drop. A photo the camera or the picker returns after a form was left is dropped at once. The media layer's own backstop stays (MediaUploader housekeeping drops unclaimed photos older than a day when a media job runs) | `TicketScreensTest.leavingAScreenDropsItsStagedPhotosButARecreationKeepsThem` (Create, Quick with sheet, Details with comment photos: each dropped on leave; with the lifecycle at CREATED the next screen's photo is kept); `TicketFlowTest.aLeftFormIsDoneAndAPhotoStagedAfterwardsIsDroppedAtOnce` |
| 4 | low | Data budget: every open of Dashboard / All Tickets (and every return from Details) read every page plus the summary | `TicketLists.fresh`: a list copy read on this phone less than `TicketLimits.FRESH_MS` (3 min, trusted clock; `TicketSnapshot.readAtMs`, kept in the copy as `read_at_ms`) ago is shown without a read when a screen opens. Pull to refresh (`load(refresh = true)`) always reads; a Dashboard opened from a ticket push reads (`refreshOnOpen`, via `TicketPush.open(nav, reason)` which passes the reason as the route argument). An old copy without `read_at_ms`, a clock that went back, or an older copy reads | `TicketFlowTest.aFreshCopyIsNotReadAgainOnOpenButAPullToRefreshAndAnOldCopyAre` (Dashboard -> All Tickets -> Dashboard within a minute: 1 read; a new unsent ticket still counted; refresh reads; after 3 min the open reads); `TicketModelTest.aCopyIsFreshForThreeMinutesFromItsReadOnThisPhone`; replay test: `readAtMs` set by the host read, kept through the file copy, absent in an old copy; `TicketPush.open` |
| 5 | low | All Tickets cards drew a chevron (screenshots 12, 13 have none) | `TicketCard(chevron: Boolean = true)`; TicketListScreen passes false, the age is then centred vertically at the right; the Dashboard keeps it | `TicketScreensTest.allTicketsCardsHaveNoChevronAndTheAgeCentredWhileTheDashboardCardsHaveOne` (age centre = card centre within 1.5 px); goldens `tickets_list_*` looked at (Verify) |
| 6 | low | Quick tiles in one row had different heights (screenshot 15: one height, label at the bottom) | `IssueTile` reserves `labelLines` x the style's line height for the label and aligns it to the bottom of that box, so every tile has one height and labels of a row end on one line | `TicketScreensTest.quickTilesAreOneHeightAndTheLabelsOfARowEndOnOneLine`; goldens `tickets_quick_*` looked at (Verify) |
| 7 | low | s8 row 15 "tile caption test" and row 16 "offline quick create, gallery and camera" not proven | new `TicketScreensTest.theTileCaptionIsTheCategoryAndTheUrgencyInEnglishAndBangla` ("Tickets · High", "Tickets · Medium", "টিকিট · উচ্চ", "টিকিট · নিম্ন", unknown codes shown as sent); `offlineQuickCreateIsSavedAtOnceAndShownNotSentYet` now stages a gallery pick and a camera photo and asserts `rec.photos` = the draft's two photo uuids in order | the tests named |
| 8 | low | three edits outside the s5 A2 list (ui-screenshots build line, core-media `strings_a2.xml`, the `android.net.Uri` import) without a lead confirmation | nothing to change in code; the ask to the lead is restated under Asks (confirm before the B1..A3 merge, or move to the owner) | – |
| 9 | low | push deep link only a mapping | kept A1's ask, now with one call for A1: `TicketPush.open(nav, reason)`; the end-to-end test stays under Next step | `aTicketPushOpensTheTicketDashboardAndASaveOpensAllTickets` (+ `open`) |

## Decisions

- **D-A2-01** Gallery failures are a typed exception (`GalleryImportException.reason`, `messageRes`), not a camera
  notice, because the camera's "Take it again." text is wrong for a picked picture and `CameraCapture.kt` is not A2's
  file. No space reuses the existing `media_no_space` text. The three new texts live in new core-media files
  `strings_a2.xml` (en, bn), following the s5 "each lane has values/strings_<lane>.xml" rule; no existing key is touched.
- **D-A2-02** Gallery pictures are never evidence: `force_sale`, `outlet_capture`, `outlet_verification` are refused
  before anything is read (IllegalArgumentException), because MediaItem documents evidence as camera-only (docs/24 s5.5)
  and a gallery picture would defeat the anti-spoofing photo rule.
- **D-A2-03** `taken_at` of a gallery picture is the import time on the trusted clock. The picture's EXIF date is
  untrusted and is stripped with the rest; `MediaMetaPayload` has no source field, so nothing else changes on the wire.
- **D-A2-04** The picker enforces `maxItems` itself (`pickedWithin`: distinct, first N), because the documents-picker
  fallback on old phones ignores the photo picker's limit. `maxItems = 0` opens nothing; a phone without any picker
  calls `onUnavailable` (ActivityNotFoundException).
- **D-A2-05** The "no storage permission in the merged manifest" check is on the manifest Robolectric runs core-media's
  tests with, which AGP merges with every dependency's manifest (androidx.activity's picker included; the test proves
  the merge by resolving the test-only `ComponentActivity`). The app-level merged manifests are audited by core-system's
  `ManifestPermissionAuditTest` (not A2's file; see Asks).

- **D-A2-06** `TicketSource` carries the summary inside the list answer (`TicketSnapshot.summary`), not as a separate
  port call: the host reads `GET /v1/support/tickets` (every page up to its cap) and `/summary` together on open and
  pull-to-refresh. `TicketSnapshot.complete` says whether every page was read.
- **D-A2-07** Dashboard counts: a complete list gives `TicketMath.counts(list rows, unsent uuids)` (exact, merged by
  uuid); a list cut at the host's page cap takes the server's summary and adds as New only the unsent tickets the cut list
  does not hold. "Unsent" is every outbox ticket not refused (pending, or acked and not yet read back: the server has it
  as New). A refused ticket is not counted (it is not a ticket on the server) but stays in the list with its reason.
- **D-A2-08** The optional affected username (D-TSO-MAN-24) is offered only for the account issue types `user_login`,
  `login_otp`, `password_reset`, `auto_logout`, so the Create form at rest is screenshot 17 exactly and the field appears
  where the manual uses it (a login problem raised from another user's phone). Bengali digits typed are sent as ASCII.
- **D-A2-09** Colours: a night look (page luminance under 0.18, not Sunlight) gets `TicketPalette.Night`, sampled from
  screenshots 11 to 17 (page #03103A, card #0A183F, chips #293250, accent #2E5BFE, success #34D399, help gradient
  #7B4FF5 to #9D7BF7, support bubble #1E1D2D with a #7A5A3A border); any day look and Sunlight derive from the look's own
  tokens, so Sunlight keeps black on white. A host may set `LocalTicketPalette`. This keeps the screens matching the
  screenshots whatever card colour A1's TsoNight picks for its glow cards.
- **D-A2-10** The issue icons (key, fingerprint, screen_off, navigation, inbox, error, check, bolt, gallery, add photo,
  headset, expand, send) are module-local vector glyphs (Material path data or simple shapes); sync, receipt, cart,
  battery, clock, camera, search, close and chevron come from `AronIcons`. core-ui is A1's; a later kit move is one import.
- **D-A2-11** The close_review event uuid is name-based per ticket (`TicketIds.nameV4("aron.ticket.close-review:" +
  uuid)`, a valid v4): a double tap, a second detail screen or a retry after a kill can never queue two closes.
- **D-A2-12** The Quick sheet and the issue list are drawn inside the screen (scrim + panel, system back closes them), not
  as dialog windows, so the goldens of screenshots 16 and 18 capture them. The sheet covers the content below the host's
  top bar (screenshot 16 also dims the bar; recorded difference).
- **D-A2-13** Screens take a `TicketFrame` slot (title + content) and own their titles in their strings; the TSO host
  passes its `TsoFrame`, so the module stays role-agnostic.
- **D-A2-14** Bangla times: Bengali digits with "পূর্বাহ্ণ" / "অপরাহ্ণ"; Bangla priority labels "P১ - সর্বোচ্চ" to
  "P৪ - নিম্ন" (no ASCII digit in Bangla text). Status pills are upper case in English as in the screenshots.
- **D-A2-15** `TicketSource.outbox()` delivery: outbox row pending or in flight gives `Pending`; acked with the mirror
  still there gives `Sent`; rejected or quarantined gives `Refused(last_code)`. The host drops a ticket's mirror row when
  the list read returns it, and an event's when the thread read returns it.
- **D-A2-16** A comment on a ticket still unsent is allowed (it is New; the outbox sends the ticket first). A refused
  ticket takes no comment.
- **D-A2-17** The ticket's description is drawn as the reporter's first bubble; `created` events are not drawn (no
  duplicate). status_change, priority_change, assign and close_review are one small centred line each.

- **D-A2-18** The ticket copies are files under `cacheDir/tso_api_<userId>/tickets/` (TicketApi's own cache, like
  TsoApi), inside the folder MainActivity already deletes after a logout that was not refused, so no MainActivity edit is
  needed and a shared phone never shows one rep's tickets to the next. Bounded: 40 files, 14 days, 2 M characters per
  copy; written to a temp file and renamed (a kill mid-write leaves the old copy). The copy holds the raw server bodies,
  so `TicketWire` stays the only mapping.
- **D-A2-19** List read: pages of 200 (the contract's maximum) up to 5 pages (1000 own tickets), then the summary. Any
  failure returns null and keeps the old copy; a 403 or 404 on the list or the summary drops the copy (as TsoApi: a
  refusal never serves an old copy). The "as of" is the summary's `as_of`, else the trusted now.
- **D-A2-20** The camera uses the media components MainActivity draws the overlay for (`host.media()`): `mediaFor()`
  makes a new `MediaComponents` with its own camera host, which has no overlay, so a capture through it would never
  open. Stage, discard and claim use `media() ?: mediaFor()` (same files).
- **D-A2-21** Stage-only gallery import is the additive `claim` parameter of A2's `importFromGallery` (default true:
  A3's calls and the step 1 tests are unchanged).
- **D-A2-22** The replay check also asks `purged_uuid` before anything is claimed or written, so a retry of a ticket
  acked and purged long ago is not captured again (TsoOutbox itself only checks the live outbox).
- **D-A2-23** After a save the form is popped and All Tickets is opened (manual s6: "The ticket appears in All Tickets
  with status New"); the saved form never stays in the back stack.
- **D-A2-24** Push: `TicketPush.target(reason)` maps the `sync_nudge` reason `ticket_updated` (B1's push) to
  TICKET_DASHBOARD. The push itself reads nothing; the Dashboard reads as it opens. Showing the notice and opening the
  screen from its tap are A1's files (ask below).
- **D-A2-25** The photo picker's limit is fixed when it is drawn; a launch with another limit redraws it with that limit
  and opens it one frame later (so "1 left" opens the single picker and "3 left" the multiple one), capped at 3.
- **D-A2-26** The photo limit is a compose state in `TicketsHostDeps`, re-read from the bundle each time a ticket screen
  opens (one local read), so the forms follow a config change without a restart and `TicketDeps` never changes identity
  (the screens' states are keyed on it).
- **D-A2-27** `TicketScreenshotTests` draws the real host frame (`TsoFrame(title, nav, homeButton = false)`, the same
  lambda as TicketsHost), so the goldens recorded after A1's merge show A1's TSO bar; the night page stays a stand-in
  until `AronLook.TsoNight` is on the branch (one line).

- **D-A2-28** (fix round) A closed Quick sheet stays closed: the form's draft is written to the sheet only while the
  sheet shows that form's uuid (`SheetSlot`); X, the scrim and back do nothing while the save runs (milliseconds), so a
  dismiss never races the save.
- **D-A2-29** "Leaving" a ticket screen is the screen leaving the composition while the activity is at least STARTED
  (`OnLeaveScreen`). The TSO back stack (`TsoApp`, `AronScreenTransition`) keeps no saveable state of a popped screen, so a
  left draft is gone for good and its staged photos are dropped; a recreation disposes the composition with the lifecycle
  already below STARTED, and the saved draft keeps its photos. The drop runs on `TicketDeps.background` (default an IO
  `SupervisorJob` scope per `TicketDeps`, which the TSO host builds once per user), not on the screen's scope.
- **D-A2-30** Data budget (constraint 3): a list copy younger than 3 minutes by the phone's trusted clock is not read again
  when the Dashboard or All Tickets opens; pull to refresh and a push-opened Dashboard always read. 3 minutes keeps a quick
  Dashboard -> Details -> back -> All Tickets walk at one read, while a rep who comes back later sees fresh counts.
  Paging All Tickets (read page 1, more on scroll) was not chosen: the local search and the tabs work on the whole list
  offline (s5 A2), which paging would break.
- **D-A2-31** Photo claim stays before the commit (the media layer's rule; a commit-first order would need a reconciler to
  claim the photos of a committed row after a kill, and the photo uuids are not in the ticket payload). Instead the ticket
  photo port drops a photo claimed for a ticket record that is not saved, when the rep removes it or leaves the form.
- **D-A2-32** All Tickets cards have no chevron and the age is centred (screenshots 12, 13); the Dashboard's recent cards
  keep the chevron above the age (screenshot 11).
- **D-A2-33** Quick tiles reserve the label's lines (2, or 4 in the one-column large-text layout) at the style's line
  height, label at the bottom, so all tiles are one height (screenshot 15).

## Deviations

- Step 1: none.
- Step 2: `android/ui-screenshots/build.gradle.kts` gets one appended line (`testImplementation(project(":android:feature-tickets")) // tso-manual A2`,
  last line of the dependencies block). s5 A2 owns `TicketScreenshotTests.kt` in that module but does not list its
  build file; the test cannot compile without the line. Nothing else in that file changed.
- Step 2: `TicketScreenshotTests` draws a test-local stand-in for the TSO top bar (navy, grey round back button,
  green-to-yellow title) and a night look stand-in (`AronLook.Default.copy(page = #03103A, ...)`), because this branch
  does not hold A1's `TsoFrame` restyle or `AronLook.TsoNight` yet. When A1's shell lands, the test switches to the real
  frame and look (one helper each); the goldens are recorded after that, on the Linux runner.
- Step 2: screenshot 16 dims the host's top bar under the sheet; the in-layout sheet (D-A2-12) dims only the content.
- Step 2: on a 360 dp phone Submit Review & Close does not fit beside the two pills with the kit's font, so it moves to
  its own line, right-aligned, with its whole label (screenshot 14's phone fits all three on one line). The pills and the
  button are a flow: never squeezed, never clipped, also at 200 % text.

- Step 3: the push deep link is only the host's mapping (`TicketPush.target`). The notice for reason `ticket_updated`
  (core-sync `PushMessage` / `PushNotices`), reading the tap's intent (MainActivity) and opening a screen on start
  (`TsoApp`) are A1's files; asked below.
- Step 3: screenshot 10 (the drawer's Tickets group) is not in `TicketScreenshotTests`: A1's `TsoChrome` is not on this
  branch (only the stubs are). A1 has `tso_drawer_tickets_en`; bn and 200 % of it are asked below.
- Fix round: none. Every edit is in A2's own files (feature-tickets, app-tso `TicketsHost.kt`, `TicketApi.kt`,
  `TicketWriteReplayTest.kt`, the docs/08 Tickets section, this file); core-media was not touched (the photo rule is in
  the ticket photo port, through the public `MediaStore.get` / `deleteIf`).
- Step 3: `TicketKit.hueFill`'s parameter renamed `amount` -> `fraction` in feature-tickets (A2's own file) because
  `tools/ci/money-float-scan.py` flags a Float named "amount" as money (a step 2 line; the scan reported it not allowed).

## Asks

- **A1 (shell step):** none blocking. `TicketsHost.kt` passes `{ title, content -> TsoFrame(title, nav, homeButton =
  false) { content() } }` as the ticket screens' frame (s8: no Home button on 11 to 18). The drawer's Tickets group opens
  TICKET_DASHBOARD, TICKET_LIST, TICKET_QUICK, TICKET_CREATE; TICKET_DETAIL takes the ticket uuid as its argument.
- **F1/F2 (optional, 1.10.x / V0141+):** an issue type attr `affected_user: true` would let the server decide which
  types offer the affected username; today the app keeps the four codes of D-A2-08.
- **lead:** the goldens of the step 2 list (Verify) are recorded on the Linux runner once A1's frame and TsoNight are in.

- **lead / core-system owner (optional):** add the storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`,
  `READ_MEDIA_VISUAL_USER_SELECTED`, `MANAGE_EXTERNAL_STORAGE`, ...) to the forbidden set of
  `ManifestPermissionAuditTest.mergedAppManifestsRequestNoMicrophoneNorBackgroundLocation`, so the three app manifests
  are guarded too.
- **device lab (D-UI check, pilot phone on dev):** the photo picker opens on a DPC-managed pilot phone. If an app is
  ever pinned in lock-task mode, the picker's package (MediaProvider photo picker, or DocumentsUI on old phones) must be
  allowed, or the Browse Gallery button does nothing. Not testable on the laptop.

- **A1 (push, D-TSO-MAN-26 step):** for `sync_nudge` with `reason = ticket_updated` (B1's push), show a fixed bn/en
  notice and put the reason in `PushNotices.EXTRA_OPEN`; in MainActivity read it (onCreate and onNewIntent) and, through
  the TSO navigator, call `TicketPush.open(nav, extra)` (app-tso `TicketsHost.kt`; it opens TICKET_DASHBOARD with the
  reason as the route argument, so the Dashboard reads online even when its copy is fresh, D-A2-30; false for any other
  push). If the navigator is not reachable there, a `TsoApp(..., open: TsoRoute?)` parameter with
  `TsoRoute(TicketPush.target(extra), TicketPush.REASON)` does the same. A2 cannot edit PushMessage, PushNotices,
  MainActivity or TsoApp.
- **lead (fix round, checker defect 8):** please confirm, before the B1..A3 merge, the three edits outside the s5 A2
  list, all marked `// tso-manual A2` and listed under Deviations: (a) the appended
  `testImplementation(project(":android:feature-tickets"))` line in `android/ui-screenshots/build.gradle.kts` (the owned
  TicketScreenshotTests cannot compile without it); (b) the new core-media `values/strings_a2.xml` and
  `values-bn/strings_a2.xml` (the s5 rule "each lane has values/strings_<lane>.xml", used by the owned GalleryImport.kt);
  (c) the `import android.net.Uri // tso-manual A2` line at the top of `MediaComponents.kt` (the owned
  `importFromGallery(…, uri: Uri, …)` append needs it; a Kotlin import cannot go at the end of the file). Or name the
  lane that should carry them.
- **A1 (goldens):** screenshot 10 in bn and at 200 % (the drawer with the Tickets group open), next to your
  `tso_drawer_tickets_en`, so s8 row 10 has en, bn and 200 %.
- **lead (merge order B1, B2, B3, W1, A1, A2, A3):** after A1's shell is merged, `TicketScreenshotTests.night` becomes
  `AronLook.TsoNight` (or `AronTheme(tso = true)`), one line; then record the goldens (list under Verify) on Linux.

## Not done

- Goldens: recorded on the Linux runner after A1's shell and look are merged (names under Verify); nothing committed
  here (Windows laptop).
- The push deep link end to end (A1's files, ask above). The host side (`TicketPush.target`, `TicketPush.open`) is done
  and tested.
- The lead's confirmation of the three edits outside the s5 A2 list (checker defect 8, ask above).
- Device lab (pilot phone against dev, B1 on dev): the five screens, gallery (photo picker on the DPC-managed phone) and
  camera, offline create then sync, the INC number replacing "Not sent yet", Submit Review & Close on a resolved ticket.
- SR and AMO wiring of feature-tickets (D-TSO-MAN-17: later; the module is role-agnostic).

## Verify

Fix round, on this laptop (JDK 21, UTC, via gl.sh), 2026-10-29, code commits `fa220093` (feature-tickets) and `f03e04c7`
(app-tso); every run below is on that code (the status and docs/08 edits came after).

| command | result |
|---|---|
| `gl.sh :android:feature-tickets:testDebugUnitTest :android:app-tso:testDebugUnitTest --tests 'com.aktcl.aron.tso.TicketWriteReplayTest' :android:ui-screenshots:compileDebugUnitTestKotlin --continue` (run 1) | BUILD FAILED only in app-tso test compile: the old `MediaTicketPhotos(…) { cfg }` call bound its trailing lambda to the new `recordSaved` parameter (fixed: `maxNow = { cfg }`). feature-tickets 4 classes, 56 tests, 0 failures (TicketFlowTest 15, TicketListStateTest 6, TicketModelTest 24, TicketScreensTest 11); ui-screenshots test sources compiled |
| mutation check: the old sheet behaviour restored in QuickTicketScreen (read falls back to the opening draft, unconditional write, dismiss = null then `abandon()`), `gl.sh :android:feature-tickets:testDebugUnitTest --tests '*TicketScreensTest*' :android:app-tso:testDebugUnitTest --tests 'com.aktcl.aron.tso.TicketWriteReplayTest' --continue` | as wanted: `theQuickSheetClosesForGoodOnTheXAndOnBackAndDropsItsPhoto` FAILED at the "and it does not come back" assertion (TicketScreensTest.kt:203), 11 tests, 1 failed; TicketWriteReplayTest 14, 0 failures. The file was then restored byte for byte |
| **Final verify**: `gl.sh :android:feature-tickets:testDebugUnitTest :android:core-media:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:app-tso:assembleDebug :android:ui-screenshots:testDebugUnitTest --tests '*TicketScreenshotTests*' -Paron.screenshots.recordTo=<scratchpad>/goldens/A2 :android:core-ui:testDebugUnitTest --tests 'com.aktcl.aron.core.ui.HardcodedStringScanTest' --continue` | BUILD SUCCESSFUL in 1m 27s. feature-tickets 56, 0 failures (FROM-CACHE: the same inputs as run 1, whose results these are); core-media 14 classes, 95 tests, 0 failures (UP-TO-DATE: no core-media change in this round); app-tso 5 classes, 37 tests, 0 failures (TicketWriteReplayTest 14); `app-tso-debug.apk` built; TicketScreenshotTests 30 recorded to the scratch dir, 0 failures; HardcodedStringScanTest 5, 0 failures |
| `python tools/ci/money-float-scan.py`, `wallclock-scan.py`, `date-pin-scan.py` | exit 0 each; money-float 0 not allowed (17/17 allowed); no ticket file among the report-only findings |

Looking at the recorded PNGs against the owner's JPGs: `tickets_quick_en` / `_bn` now have every tile one height with
the label at the bottom (screenshot 15; "Astha Target" beside "Astha Gift Choice & Disb…"); `tickets_quick_200_bn` (one
column) is one height with room for four label lines; `tickets_list_en` has no chevron and the age centred at the right
(screenshot 12). The Dashboard keeps the chevron (tested). The goldens of `tickets_list_*` and `tickets_quick_*` change
content; the names are unchanged (list below).

Not run here: the whole ui-screenshots module in verify mode (it fails by design on the ticket goldens, which are not
committed from this laptop); a real phone (device lab).

Step 3, on this laptop (JDK 21, UTC, via gl.sh), 2026-10-29. Most of the wall time was the Gradle lock (B1, B2, B3 backend
runs); every run below is on the commits named.

| command | result |
|---|---|
| `gl.sh :android:app-tso:testDebugUnitTest --tests 'com.aktcl.aron.tso.TicketWriteReplayTest' :android:core-media:testDebugUnitTest --tests '…GalleryImportTest' :android:ui-screenshots:compileDebugUnitTestKotlin --continue` (first run) | BUILD FAILED only in the test source: `TicketWriteReplayTest.kt:98` lambda type (`-> Any` for `-> Unit`); main code of app-tso, feature-tickets and core-media compiled; GalleryImportTest 14, 0 failures; ui-screenshots test sources compiled |
| same app-tso filter (second run) | 3 failures, all the test fixture: `MediaStore.put` refused a staged photo whose sha256 did not match its bytes (fixed in the test: the real digest) |
| same app-tso filter (third run) | BUILD SUCCESSFUL; TicketWriteReplayTest 12, 0 failures |
| `gl.sh :android:ui-screenshots:testDebugUnitTest --tests '*TicketScreenshotTests*' -Paron.screenshots.recordTo=<scratchpad>/goldens/A2 :android:core-ui:testDebugUnitTest --tests '…HardcodedStringScanTest' --continue` | BUILD SUCCESSFUL; 30 recorded, 0 failures; HardcodedStringScanTest 5, 0 failures. Looking at the PNGs: Resolved was off screen in `tickets_list_resolved_bn` and `_200_bn`, and the status pill lost its second word at 200 % (fixed in `3732e6a9`, `e1d4e0a8`; a bring-into-view first did nothing because it ran before layout) |
| **Final verify**: `gl.sh :android:feature-tickets:testDebugUnitTest :android:core-media:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:app-tso:assembleDebug :android:ui-screenshots:testDebugUnitTest --tests '*TicketScreenshotTests*' -Paron.screenshots.recordTo=<scratchpad>/goldens/A2 :android:core-ui:testDebugUnitTest --tests 'com.aktcl.aron.core.ui.HardcodedStringScanTest' --continue` on `e1d4e0a8` (the step's Final Verify tasks plus the screenshot record and the string scan in the same call) | BUILD SUCCESSFUL in 5m 35s. feature-tickets 4 classes, 47 tests, 0 failures; core-media 14 classes, 95 tests, 0 failures (UP-TO-DATE: its inputs are unchanged since it ran in the exact Final Verify call `gl.sh :android:feature-tickets:testDebugUnitTest :android:core-media:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:app-tso:assembleDebug --continue` on `cb0cdfce`: BUILD SUCCESSFUL in 4m 59s, the same counts); app-tso 5 classes, 35 tests, 0 failures (TicketWriteReplayTest 12, TsoWriteReplayTest 14, AppIconTest 4, AppCoexistenceTest 3, TsoBundleReadsTest 2); `app-tso-debug.apk` built; TicketScreenshotTests 30 recorded to the scratch dir, 0 failures; HardcodedStringScanTest 5, 0 failures |
| `python tools/ci/money-float-scan.py` | before the rename: 1 not allowed (`TicketKit.kt:559 hueFill(…, amount: Float)`, a step 2 line); after `0a730a3a`: exit 0, 0 not allowed, 17/17 allowed |
| `python tools/ci/wallclock-scan.py`, `date-pin-scan.py` | exit 0; no ticket file among the offenders (report-only scan) |

What TicketWriteReplayTest proves (real file database closed and reopened as a kill, real media store, OkHttp fake
server behind the real `AronApiClient`):
- a ticket: one `support_ticket` row (family self, rank 0, Dhaka business date), payload exactly `issue_type_code`,
  `title` (= label_en for a quick ticket), `description`, `source` (no `affected_username` member when empty), mirror
  `tso.unsent.ticket.<uuid>`, one sync request; its photo claimed `MediaRef("support", "support_ticket", uuid)` before
  the commit; after a relaunch the replay is false, byte-identical payload and sha, same mirror, no second sync, no
  second claim; the unsent row reads back as New, no number, Pending;
- a kill between the claim and the commit leaves no row and no mirror; the retry commits one row and the photo stays the
  ticket's; a purged uuid is never queued again; more than 3 photos is refused before anything is written;
- a comment and Submit Review & Close: one `support_ticket_event` row each, replays change nothing, the close uuid is
  name-based (same on a second tap or screen), the close payload has no `body` member, the two mirror families never mix;
- delivery: pending and in_flight Pending; acked Sent; rejected `issue_type_unknown` and quarantined
  `scope_out_of_reach` Refused with the code; a row purged after its ack Sent;
- reads: GET only, `limit=200` and the cursor only (no user, zone or scope id); two pages then the summary; the mirror of
  the ticket the server returned is dropped and the unsent one stays; offline after a relaunch the copy comes back with the
  server's `as_of`; a failed summary keeps the old copy; a list cut at 5 pages is `complete = false` and the summary's
  1500 is kept; a thread read drops the returned comment's mirror (and the ticket's) and keeps the unsent comment's; offline
  the kept thread; 404 is NotFound and drops the copy; `../summary` never reaches a path;
- bundle: issue types from `code_lists` in sort order; `cfg.support.ticket_max_photos` 1, 0, 9 -> 3, "x" -> 3, absent 3;
  the photo port clamps; without the on-screen camera a capture does nothing;
- `TicketPush.target("ticket_updated")` is TICKET_DASHBOARD (others null); `TicketNav.afterSave` is back then All Tickets.

Not run here: a real phone (device lab), goldens on Linux, the push tap end to end (A1's files).

Golden names needed (TicketScreenshotTests, record on the Linux runner after A1's frame and look are merged, 30):
tickets_dashboard_en, tickets_dashboard_bn, tickets_dashboard_200_bn, tickets_dashboard_offline_unsent_en,
tickets_dashboard_empty_bn, tickets_dashboard_sunlight_en, tickets_list_en, tickets_list_bn, tickets_list_200_en,
tickets_list_resolved_en, tickets_list_resolved_bn, tickets_list_resolved_200_bn, tickets_list_no_match_en,
tickets_details_en, tickets_details_bn, tickets_details_200_en, tickets_details_open_thread_en, tickets_details_offline_bn,
tickets_quick_en, tickets_quick_bn, tickets_quick_200_bn, tickets_quick_sheet_en, tickets_quick_sheet_bn,
tickets_quick_sheet_200_en, tickets_create_en, tickets_create_bn, tickets_create_200_en, tickets_issue_types_en,
tickets_issue_types_bn, tickets_issue_types_200_bn. Screenshot 10 is A1's drawer golden (`tso_drawer_tickets_en`; bn and
200 % asked). The PNGs recorded here show the a1-stubs top bar (the Calm Glass gradient), not A1's TSO bar, because only
the stubs are on this branch; the ticket content matched the JPGs 11 to 18 as in step 2.


Step 2, on this laptop (JDK 21, UTC, via gl.sh), 2026-10-29, one Gradle call (07:47 to 08:08 Dhaka, most of it waiting
for the lock):

| command | result |
|---|---|
| `gl.sh :android:feature-tickets:testDebugUnitTest :android:core-ui:testDebugUnitTest --tests 'com.aktcl.aron.core.ui.HardcodedStringScanTest' :android:ui-screenshots:testDebugUnitTest --tests '*TicketScreenshotTests*' -Paron.screenshots.recordTo=<scratchpad>/goldens/A2 --continue` | BUILD SUCCESSFUL; feature-tickets 47 tests, 0 failures (TicketModelTest 23, TicketFlowTest 12, TicketListStateTest 6, TicketScreensTest 6); HardcodedStringScanTest 5, 0 failures (every new text is a resource with its Bangla twin); TicketScreenshotTests 27 recorded to the scratch dir, 0 failures |

Earlier runs of the same step: the first run had TicketScreensTest 1 failure (the test rendered without the app theme,
so the digits were Bangla and legacy graphics measured text at 1 px per character; fixed in the test: AronTheme(EN) and
native graphics). The recorded PNGs were then looked at against the owner's JPGs 11 to 18; three layout faults were
fixed (the close button squeezed to "Submit", the Quick grid blank at 200 % because of a 2-column span in a 1-column
grid, ticket pills clipped at 200 %), and the run above is after those fixes.

What the tests prove (fakes: FakeServer upserts by uuid, FakeDevice is the host outbox with mirror rows and a sync):
- TicketFlowTest: offline quick create (saved at once, "Not sent yet", counts New 1, one toast); offline manual create
  (errors first, nothing queued; then title, description, affected username and a photo claimed with the record); a
  double tap queues one ticket (the second tap never reaches the queue); a kill mid-save before the commit leaves nothing
  and the retry from the saved draft (through the real Saver) is one ticket; a kill right after the commit gives one
  ticket on retry and one on the server; a full disk shows the failure and the retry keeps the uuid; photos staged up to
  the limit and dropped when removed or abandoned; offline close is one tap, one event, shows Closed, a second screen
  offers no button, the server closes it on sync; offline comment queued once with a new uuid for the next, read back as
  sent; a comment the server refuses shows ticket_state_invalid; replay after reconnect (answers lost, then two more
  replays) never doubles a ticket or a comment, INC100001..3 in order, and the device counts equal the server's summary
  with every mirror row dropped after read back; an unsent ticket opens from the phone, a gone one says not found.
- TicketListStateTest: tabs (All also Cancelled and unknown statuses), search (number, title, issue label en and bn,
  Bengali digits), unsent merge by uuid (server row wins, newest first, unsent under New), recent 10, the state's
  offline copy then server read keeping tab and query, a refused ticket stays visible and is not counted.
- TicketModelTest: validation and payloads of both forms (quick title = label_en), affected username rules, photo limit,
  the unsent row, comment bounds, close uuid (stable, valid v4), s4.4 counts 723 / 18 -> "2%" and "0%" (bn "২%"), total 0
  "0%", offline merge, the page-cap rule, thread rules (close only from resolved, comments only while open, pending close
  shows Closed, refused leaves the status), the wire mapping (page, summary without percentages, detail order), the
  bundle issue types, the mirror round trip, Dhaka 12-hour times (5:34 PM, 12:00 AM, 12:00 PM, other day with date, bn
  digits), ages (4d, 23h, just now).
- TicketScreensTest (Robolectric, en): dashboard 33 % / 67 % by tag and Need help opens Quick; a Quick tile opens the
  sheet and Submit saves one ticket and closes it; an unsent ticket reads "Not sent yet · Outlet Related Issue" offline;
  Create shows the errors, the issue list picks a type, Submit saves a manual ticket; a resolved ticket shows the note, no
  composer, one close and then the closed note; an open ticket has the composer and a sent comment shows in the thread.

Not run here: the app-tso host (next step), a real phone (device lab), goldens on Linux.

Golden names needed (TicketScreenshotTests, record on the Linux runner after A1's frame and look are merged):
tickets_dashboard_en, tickets_dashboard_bn, tickets_dashboard_200_bn, tickets_dashboard_offline_unsent_en,
tickets_dashboard_empty_bn, tickets_dashboard_sunlight_en, tickets_list_en, tickets_list_bn, tickets_list_200_en,
tickets_list_resolved_en, tickets_list_resolved_bn, tickets_list_no_match_en, tickets_details_en, tickets_details_bn,
tickets_details_200_en, tickets_details_open_thread_en, tickets_details_offline_bn, tickets_quick_en, tickets_quick_bn,
tickets_quick_200_bn, tickets_quick_sheet_en, tickets_quick_sheet_bn, tickets_create_en, tickets_create_bn,
tickets_create_200_en, tickets_issue_types_en, tickets_issue_types_bn (27). Screenshot 10 (the Tickets menu) is A1's
drawer golden.

Step 1, on this laptop (JDK 21, UTC, via gl.sh), 2026-10-29:

| command | result |
|---|---|
| `gl.sh :android:core-media:testDebugUnitTest --tests 'com.aktcl.aron.core.media.GalleryImportTest'` | BUILD SUCCESSFUL; GalleryImportTest 13 tests, 0 failures |
| `gl.sh :android:core-media:testDebugUnitTest :android:core-ui:testDebugUnitTest --tests 'com.aktcl.aron.core.ui.HardcodedStringScanTest' --continue` | BUILD SUCCESSFUL; core-media 14 classes, 94 tests, 0 failures (GalleryImportTest 13); HardcodedStringScanTest 5, 0 failures (the new core-media strings have their Bangla) |

GalleryImportTest covers: compressed to the cap (150 KB, 1024 px) from a 2400 x 1600 camera JPEG; the configured cap
(40 KB, 640 px); every EXIF field gone (GPS, make, model, original date; no APP1..APP15 segment); orientation applied
first; PNG screenshot becomes a JPEG; same media UUID twice gives one item and one read (also after a relaunch with the
read grant gone, and two imports at once); the claim cannot move to another record; evidence purposes refused before any
read; unreadable, refused read grant, too large (the cap is inclusive), no space (nothing read): nothing stored; en and
bn messages; queued like a camera photo (the `aron-media` job enqueued, then MediaUploader SAS, PUT and `media_meta`
with no fix); no storage permission in the merged manifest (CAMERA present, so the parse is real); the picker asks for
`image/*` only, keeps the limit (3 picked, 2 kept), cancel adds nothing, limit 0 opens nothing.

Not run here: no mutation run of the manifest check (adding a storage permission to see it fail); the photo picker on a
real phone (device lab).

Golden names needed: none for this step (no screen).

## Next step

Step 3 and the checker's fix round are done on this branch (code `fa220093`, `f03e04c7`; every high and low defect fixed
except defect 8, which waits for the lead's confirmation under Asks, and defect 9, which waits for A1). What is left for
A2: (1) when A1's shell is merged, switch `TicketScreenshotTests.night` to A1's look and look at the recorded PNGs once
more against the JPGs 11 to 18 (the bar is then A1's TSO bar); (2) after A1 wires the push tap with
`TicketPush.open(nav, reason)`, a test of the whole path (push reason -> TICKET_DASHBOARD with the reason argument ->
an online read even with a fresh copy); (3) the device lab checks under Not done, plus on a pilot phone: close the Quick
sheet with X, outside tap and back (it stays closed), rotate with a photo staged (it stays), back out of Create with a
photo (it is gone from `files/media/u<id>`). No other s5 A2 item is open.
