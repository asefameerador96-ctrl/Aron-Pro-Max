# Lead review: lane/portal-loyalty-api-c at c4113eed (points adjustment backend, /admin/loyalty page, push-B lows)

Reviewed 2026-11-18 by the lead (Opus pass, three lenses, three refuters per blocking finding). Read from code only; nothing built or run.

## ledger-idempotency verdict=fail
SUMMARY: I reviewed the points-adjustment backend (backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/AdminLoyalty.kt: postAdjustment, putAdjustments, listLedger, adjBalances), how it touches LoyaltyLedgerJob and BundleProgrammes.loyalty, the V0007 ledger DDL (UNIQUE (source_type, source_id), append-only trigger, api_rw INSERT grant), PortalLevels and AdminModules, the BFF (web/src/lib/admin/loyalty-server.ts, route.ts), the form and its helpers, and the new tests in AdminLoyaltyTest.

What holds up:
- Idempotency for the same uuid. Each call takes a pg_advisory_xact_lock on its request or batch uuid inside the write transaction, and the UNIQUE ledger key backs it up. A replay is checked against the stored row and answers 200 with the same row. A reused uuid with a different outlet, points or expires_on, or a different programme, gets 409. Racing resends are tested for both single (6x) and bulk (5x).
- Bulk all-or-nothing. The apply is one INSERT…unnest plus a row-count check plus the audit row, all in one transaction, so a failure part-way leaves no partial rows to double-post. The replay is detected by the `batch:%` prefix before programme state is checked.
- Dry run writes nothing, and the apply re-runs every check itself rather than trusting the dry run.
- Programme must be ACTIVE. This is checked on the server at apply time, and FOR SHARE on the programme row blocks a concurrent status change.
- Input bounds and types. Points are integers in ±1,000,000, zero is refused, sums are Long, rows are capped at 20,000, duplicate outlets in one file are refused, unknown keys are refused, allow_overdraw must be a JSON boolean.
- Who and why. The ledger stays append-only. The audit row is written in the same transaction with the actor from the token, a required reason of 10 to 500 characters, request id and Dhaka business_date; created_at is UTC.
- Server-side roles and scope. Only SUPERADMIN, or ADMIN with the loyalty `w` grant, can write; a read grant gets 403. BRAND and phone tokens are refused at the guard and in the handler. Ledger reads are cut to the caller's geography.
- The BFF re-checks role and grant and validates the body with strict zod schemas, but it is not the only check.
- No migration, and the contract change is additive.
- The adjustment does not invent an earning rule and does not touch redemption-at-zero.

Blocking: one. The below-zero check on a debit runs without any per-outlet lock, so concurrent debits can take a balance negative without allow_overdraw.

Not verified: I did not build or run any tests. I did not check the size limit in bodyObject or in the Next BFF, AuditWriter internals, the contract slices against openapi.yaml line by line, the Bangla and English strings, the e2e spec, or the push-B follow-up test rewrites beyond a skim (they appear to track a behaviour change, not a weakening). I also did not check whether audit_log has an index for the ledger's reason lookup.

### BLOCKING upheld=3/3: Debit overdraw check has no per-outlet lock and reads the balance in two snapshots
WHERE: backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/AdminLoyalty.kt: postAdjustment (the `if (row.points < 0 && !allow) adjBalances(...)` line, then the INSERT), putAdjustments (the `adjBalances` loop, then the unnest INSERT), and adjBalances (two separate SELECTs)
WHY: The only locks taken are the advisory lock on the request or batch uuid, which differs between two independent debits, and FOR SHARE on the programme row, which shared lockers do not block each other on. The transaction is the default READ COMMITTED. So two debits with different uuids can both read the same balance and both insert. The ledger job already serialises balance writers with pg_advisory_xact_lock('loyalty:<outlet>:<programme>') (LoyaltyLedger.lockPair), but the portal path does not take it. adjBalances also runs the ledger sum and the unposted-redemptions sum as two statements. Under READ COMMITTED each gets its own snapshot, so a redemption the job posts in between is counted by neither, and the balance comes out too high. BundleProgrammes.loyalty does this in ONE statement for exactly this reason.
SCENARIO: An outlet has a balance of 100 and allow_overdraw is false. Two admins (or one admin in two tabs, or a single post next to a bulk file) each submit -80 with different request uuids at the same moment. Both call adjBalances and see 100, both pass 100-80 >= 0, both insert, both commit. The balance is now -60 with no overdraw flag on either request. Second case: the ledger job posts an 8-point redemption between adjBalances' first and second SELECT, so the check sees 10 instead of 2, and a -5 debit is accepted, leaving -3. No test covers concurrent debits with different uuids. Fix: in the same transaction, before reading the balance, take the job's pair lock (hashtextextended('loyalty:<outlet>:<programme>',0)), for bulk over the distinct outlets in sorted order to avoid deadlocks. Then compute the balance in one statement, as BundleProgrammes.loyalty does. Add a race test: two -80 debits on 100, exactly one 201 and one 409.

LOWS:
- Using allow_overdraw is not recorded anywhere. The ledger row gets flags '{portal}' only, the audit payload (programme_id, outlet_id, points, expires_on, business_date; for bulk only rows and total) leaves out the flag and the balance before, and replays ignore the flag. Once posted, a deliberate overdraw cannot be told apart from an ordinary debit. Because the owner has not decided the overdraw policy, these posts should be visible: add an 'overdraw' flag on the row and put allow_overdraw and balance_before in the audit when the debit took the balance below zero.
- Enrolment is not checked. The outlet only has to fall inside the current version's scope (ProgrammeScopeSql.outlet). BundleProgrammes.loyalty shows balances only for outlets with a current programme_enrolment, so a credit to an in-scope outlet that is not enrolled is posted, audited and listed in the ledger, but never appears on the phone. Either refuse with an 'not_enrolled' code or return a warning in the dry run and the response, and log the choice in DECISIONS.md as an owner question rather than deciding it silently.
- A debit row can carry expires_on (adjRow accepts expires_on for any sign). Expiry semantics only apply to credits (the job's lapsing query uses points > 0), so the date is meaningless. Refuse expires_on when points < 0.
- The web retry keys include fields the server ignores when matching a replay. In loyalty-adjust-form.tsx the single form's key is JSON.stringify(checked.body), which includes reason and allow_overdraw, and bulkKey includes the reason and overdraw too. The server only compares outlet, points and expires_on (single) or the rows (bulk). Scenario: an admin posts +500, the response is lost (a network-error banner shows), the admin fixes a typo in the reason and resubmits. The form mints a new uuid and the server posts a second +500. Key the attempt on outlet, points and expires_on for single, and on the rows for bulk.
- Single and bulk use separate uuid namespaces (source_id '<uuid>' versus '<uuid>:<i>', and different advisory lock keys). The same uuid sent once as request_uuid and once as batch_uuid posts both, with no conflict. Not a retry path, but you could cross-check the two.
- A single replay is checked after the scope check. If the outlet has moved out of the programme's scope since the first post, a genuine resend gets 403 instead of its stored row. Nothing is double-posted, but the client sees a refusal for something that was posted.
- The balance check uses the plain ledger sum. Credits that lapsed today but whose expiry correction the job has not posted yet still count, so a debit can pass and the balance goes negative after the expiry pass. This matches the bundle's documented 'less favourable' lag; it should be written down for the adjustment too.
- The bulk audit row holds only the row count and total points. The outlet-level detail lives only in the ledger rows, which are found through the batch-id prefix. That is acceptable, but an auditor cannot see the per-outlet changes from audit_log alone.
- The browser reads the whole CSV with file.text() and no size check before parsing (client side only; the server and BFF cap rows at 20,000). Add a byte cap of a few MB. No CSV export exists, so there is no formula-injection surface; the reason is rendered as React text.
- ADJ_SELECT runs a correlated audit_log subquery for every ledger row returned (up to the page limit). I did not verify an index on audit_log(entity, entity_id).

## authz-scope-contract verdict=fail
SUMMARY: Lens: authorization, scope and contract, for the points adjustment at c4113eed (lane diff ce6792f67...c4113eed). This was a read-only review: I built nothing and ran no tests. The git fetch hit a ref-lock error, but origin/lane/portal-loyalty-api-c already pointed at c4113eed.

What is right:
- Roles are checked in the API, not only in the web layer. Writes (POST and PUT /admin/programmes/{id}/adjustments) need Need.WRITE: SUPERADMIN, or ADMIN with the `loyalty` grant set to "w". A read grant gets 403.
- The ledger read (GET /admin/programmes/{id}/ledger) needs Need.READ_ENROLMENTS, which leaves out BRAND. A Brand user can neither post nor read ledger rows, and the page hides the ledger card for BRAND.
- Wing, DMO and TSO users only reach a programme visible in their geography (adjProgramme uses loyVisSql, otherwise 404), and the ledger rows are cut to the outlet's zone or route reach.
- The outlet from the body must exist (400), sit in the current programme version's scope (ProgrammeScopeSql.outlet) and be in the caller's reach (403). A request uuid reused on another programme or outlet gets 409 and never shows the stored row, so the programme id in the path cannot be mixed with another programme's outlet.
- Ledger pages are bounded: pageLimit allows 1 to 500, and the keyset cursor is l.id < cursor inside the programme.
- The existing table fits. The adjustment reuses app.loyalty_ledger (V0007), whose CHECKs all pass: source_type 'adjustment' matches its pattern, points <> 0, and source_id is at most 64 characters (uuid plus ":19999" is 42). UNIQUE(source_type, source_id) and the append-only trigger stay in force, and api_rw has INSERT on app.*.
- No migration was added or edited; the diff touches nothing under db/.
- Every write has an audit row in the same transaction (AuditWriter, entity programme_adjustment; one row for a single post, one per bulk batch). A dry run writes nothing.
- Retries are idempotent. A single post is keyed by the request uuid (advisory lock, then the stored row is compared). A bulk batch is keyed by batch uuid plus row index and is all-or-nothing. Six parallel single posts and five parallel bulk applies are tested.
- The contract change is additive: three new operations and new schemas, version 1.23.0. The only removed lines in openapi.yaml are push-B description rewrites already passed in docs/reviews/loyalty-push-b-2026-11-18.md. openapi.d.ts and the zod bodies on the web side match the yaml.
- CSV handling is safe. The server caps a batch at 20,000 rows and returns at most 200 errors with a count. Each row's errors are reported, and a repeated outlet in one file is refused. Nothing is exported as CSV, and reasons render as React text, so there is no formula-injection path in this diff.
- Bangla and English strings are in messages-loyalty.ts.
- No test was weakened. The push-B follow-up tests replace or tighten earlier assertions.

One blocking finding: the overdraw check is not serialised per outlet and programme. Two concurrent debits can both pass the check and leave a negative balance without allow_overdraw.

Not verified:
- Behaviour at runtime: no tests were run.
- The body byte limit of bodyObject for a 20,000-row PUT.
- The ADMIN case where p.modules is null (grants not live): an older pattern, and every ADMIN can then write.
- Index use of the audit_log reason subquery.
- That the web mock matches the API.
- How the ledger job treats a debit sent with expires_on.

### BLOCKING upheld=3/3: The overdraw check races: concurrent debits (or a debit beside the ledger job) can take the balance below zero without allow_overdraw
WHERE: backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/AdminLoyalty.kt postAdjustment and putAdjustments (lockRequest(h, request, "adjustment_req" / "adjustment_batch"), adjProgramme ... FOR SHARE OF p, adjBalances, then INSERT); compare LoyaltyLedgerJob.lockPair, which locks "loyalty:$outlet:$programme" with pg_advisory_xact_lock(hashtextextended(...))
WHY: The only locks taken are an advisory lock on the request or batch uuid and FOR SHARE on the programme row. Shared locks do not block each other, so nothing orders two adjustments on the same outlet and programme. Each transaction reads the balance (sum of the ledger minus unposted redemptions) under READ COMMITTED, checks balance + points >= 0, and inserts. The insert does not use the pair advisory lock that LoyaltyLedgerJob.reconcilePair and expire hold. The rule that a debit below zero needs the explicit flag (the owner has not decided the overdraw policy) is therefore enforced only for requests that arrive one at a time. The tests cover the same uuid sent concurrently, never two different uuids on one pair.
SCENARIO: Outlet 42 in programme 7 has a balance of 100. Admin A posts a single -100 with request uuid U1, and admin B (or the same admin in a second tab, or a bulk CSV) posts -100 for outlet 42 with U2 or batch B1 at the same moment. Both transactions read 100, both pass the overdraw check, and both insert. The balance ends at -100 with allow_overdraw never sent, and the phone bundle then shows a negative balance. The same can happen while the ledger job posts a redemption or an expiry correction for the pair: the adjustment reads the balance before the job commits. Fix: in both handlers, take the job's pair lock (pg_advisory_xact_lock(hashtextextended('loyalty:'||outlet||':'||programme, 0))) before adjBalances. In bulk, lock the outlets in ascending id order to avoid deadlocks. Then add a test with two different uuids debiting the same pair concurrently, expecting one 201 and one 409 would_overdraw.

LOWS:
- A replay does not compare the reason or allow_overdraw. On the single path, the same request_uuid with a different reason, or with allow_overdraw added, returns 200 with the stored row. On the bulk path, change_reason and allow_overdraw are not part of the comparison. Nothing is double-posted, but the existing fingerprint() helper would catch the mismatch and give 409, matching how the programme writes behave.
- Single and bulk ids are not deduplicated against each other. A single post's source_id is the bare uuid and a bulk row's is '<uuid>:<i>', so the same uuid sent once as request_uuid and once as batch_uuid posts twice. The uuids are client-generated and the namespaces are documented, so this is unlikely, but the shared space could be closed or the separation stated in the contract.
- A replay is judged after the scope check. If the programme version's scope changes between the first send and the resend, the resend gets 403 out_of_scope (bulk: not_found or out_of_scope) instead of the stored answer. The contract describes this, but it means a retry is not always answered as a replay.
- Mixed bulk errors are inconsistent. When would_overdraw rows sit next to other errors, adjErrorProblem returns 400 ERR_VALIDATION with would_overdraw codes and no balance_points, while overdraw alone returns 409 with balance_points. The form maps 409 to the overdraw banner only.
- The balance check ignores credits that have lapsed but whose expiry row is not posted yet (expires_on < today, waiting for the job's daily sweep). A debit can pass the check, and the job's expiry correction later leaves the pair in debt, so the balance goes negative without the flag. This matches the bundle's balance_points rule, but it is a second way for the balance to go below zero without allow_overdraw, and it should be noted in D-LOY-API-35.
- A debit may carry expires_on. adjRow accepts expires_on with negative points; the replay ignores expiry on debits, so this is harmless but meaningless. It should be refused (400) or documented.
- The ledger rows carry no actor. LedgerEntry has the reason (from the first audit row via split_part) but not who posted it, so 'who' is only in the audit log. The bulk audit row stores only rows and total_points, not each row's outlet and points; these can be rebuilt from ledger rows sharing the batch-uuid prefix, which is acceptable but worth stating.
- The client reads the whole file with file.text() before the 20,000-row check, with no byte cap. This only affects the browser (the server caps rows), but a 50 MB file would freeze the tab, so a file.size guard (about 1 MB) is cheap.
- The CSV parser splits on comma, semicolon or tab with no handling of quotes. A quoted cell such as "123" is reported as not_an_integer rather than accepted. This is safe but should be in the hint text.
- The ADMIN gate relies on p.modules being present. If it is null (grants not live), every ADMIN can write adjustments. This is older behaviour shared with the other loyalty writes and was not verified for this deployment.
- The ledger reason is read with a correlated subquery on app.audit_log(entity, entity_id) for each row (up to 500 rows a page). The index use was not verified.

## web-page-and-tests verdict=fail
SUMMARY: Read-only review of git diff ce6792f67...c4113eed (lane/portal-loyalty-api-c), with the web page, BFF and tests as the focus. I read the files with git show. Nothing was built, run or installed.

What is correct:

BFF routes (web/src/app/api/bff/admin/loyalty/[id]/adjustments/route.ts -> lib/admin/loyalty-server.ts handleAdjustPost/handleAdjustPut)
- They forward the USER's session token (apiClient(session.at)). There is no privileged server credential, so the API is the authority.
- The BFF checks are an extra layer on top of the API: session, accessFor by path, and the loyalty 'w' grant through canWriteLoyalty. A zod schema in strict mode rejects unknown members, range is ±1,000,000, zero is refused, the reason must be 10..500 characters and there are at most 20,000 rows.
- CSRF: authenticate() checks sameOrigin (Sec-Fetch-Site and Origin vs Host) on top of a SameSite=Strict cookie.
- Only POST and PUT are exported.
- allow_overdraw defaults to false and only an explicit true passes.
- The API's 403/404/409 answers pass through unchanged.

API (AdminLoyalty.kt)
- Writes need Need.WRITE: SUPERADMIN, or ADMIN with module 'w'. A read grant is refused with 'read-only access'.
- The ledger read needs LOY_ENROLMENT_READERS (BRAND excluded) and is cut by geography.
- Scope is checked before a replay is answered; programme state is checked after it.
- Idempotency: an advisory lock per uuid plus UNIQUE(source_type, source_id). A replay is compared against the stored row. The bulk insert is all-or-nothing, source ids are '<batch>:<index>', and there is one audit row per batch.
- The audit row is written in the same transaction.
- The dry run writes nothing.

Page (/admin/loyalty/{id})
- The adjustment card renders only when canWrite; an inactive programme shows a note.
- The ledger card is hidden for BRAND, matching the API refusal.
- A read-grant ADMIN sees the ledger only (covered by the e2e test).
- The reason is rendered as React text, not markup.

Form (loyalty-adjust-form.tsx)
- The request uuid is kept by nextAttempt keyed on the exact body. A double click, an Enter press or a retry after a network error therefore resends the same uuid.
- It is cleared only on success, when the form is also reset.
- The bulk batch_uuid is shared by the dry run and the apply. That is safe because the dry run records nothing, and a dry run after a timed-out apply that actually committed reports replayed=true.
- Apply is enabled only after a fresh valid preview of the same rows, reason and flag.
- Errors are placed by field and by row: the first 50 local errors, and the server's errors with error_count.
- All visible strings are in messages-loyalty.ts in English and Bangla, apart from the small items listed in the lows.

Other checks
- The lane does not change the earning rule or the redemption-at-zero behaviour. Overdraw is refused only for admin debits, and only unless the flag is sent.
- No migration in the diff.
- The contract change is additive: new operations and schemas only. The openapi history keeps the 1.21.0 entry.

Tests
- No existing web test was changed.
- The backend assertions that were removed are the push-B replacements already reviewed at 4cdece42: the current-month test replaced by the quarter test, the size-test previous-month row moved to the month before the quarter, and the renamed version test.
- The new ProgrammesBundleSizeTest cases are strict: exact named months, the set of months sent, and the budget for a realistic three-month quarter.
- AdminLoyaltyTest asserts ledger counts, balances, audit rows by action and reason, no audit on refusal, replay after the balance moved, the dry run writing nothing, and overdraw on the single and bulk paths, including an unposted redemption.

Next.js advisory
- web/package.json (next ^16.4.1) and package-lock.json (next 16.4.2) are identical in the base ce6792f67 and the lane; the lane does not touch either file. Whatever advisory `npm run build` prints is not introduced by this lane.
- The 'build' script is plain 'next build' (prebuild = lint); no script in package.json prints an advisory, so the message comes from Next or npm tooling.
- I could not check whether 16.4.2 is affected by a published advisory (no network or npm audit run).

One blocking finding: a race between concurrent debits that defeats the overdraw guard. Several lows follow, including a mock/API mismatch on where balance_points sits in the overdraw problem body.

Not verified:
- No tests, tsc, Playwright or CI were run.
- The concurrency race was found by reading the code, not reproduced.
- ProgrammeScopeSql.outlet was not re-read.
- MAX_BATCH_ROWS was assumed to equal 20,000.
- I did not check that openapi.d.ts and the slices match openapi.yaml byte for byte.
- accessFor's path table for /api/bff/admin/loyalty/* was not re-read.
- I did not check whether t() throws or echoes the key for an unknown loy.adj.err.* code.

### BLOCKING upheld=3/3: Overdraw guard is check-then-insert without a per-outlet lock: two concurrent debits (or a debit plus a redemption) can take the balance below zero without allow_overdraw
WHERE: backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/AdminLoyalty.kt postAdjustment / putAdjustments (adjBalances read, then INSERT); the only locks are lockRequest(request or batch uuid) and 'FOR SHARE OF p' on the programme
WHY: The balance is read under READ COMMITTED (the default; no isolation override in the platform code). Nothing stops another transaction on the same (programme, outlet) from committing between the read and the insert. The advisory lock is keyed on the request or batch uuid, so it serialises only resends of the SAME request, not two different debits. FOR SHARE on the programme row is a shared lock and does not serialise writers. The project rule is that a balance must not go negative without the explicit flag. The existing tests use parallel resends of one uuid only, so this race is untested.
SCENARIO: Outlet 301 in programme 1 has a balance of 100. Admin A posts -80 (uuid U1) and, at the same moment, admin B (or the same admin in a second tab, or a bulk file that contains 301) posts -80 (uuid U2), both without allow_overdraw. Both transactions run adjBalances and see 100, both pass 100-80>=0, both INSERT and commit. The balance is now -60 with no overdraw flag sent. The same happens if a phone redemption is stored between the read and the commit. Fix: before reading balances, take pg_advisory_xact_lock per (programme_id, outlet_id) for the single post, and for the bulk path take the same locks over the debit outlets in sorted order. Add a test with two different uuids sent in parallel.

LOWS:
- Mock vs API mismatch on the overdraw problem body: the real API puts balance_points under Problem.context (ApiProblem context -> Http.kt 'context = e.context'), while the mock (web/mock/loyalty.ts overdraw()) puts it at the top level, and the form's refused() reads p.balance_points at the top level. Against the real API, a refused single debit shows 'Would take the balance below zero (now 0)' whatever the real balance is. The vitest and Playwright tests pass because they run against the mock. AdminLoyaltyTest only checks that the body text contains "balance_points":30, which is true for the nested form too. Fix: read p.context?.balance_points in the form and nest the value under context in the mock. The bulk path's per-row balance_points is consistent on both sides.
- The mock's balanceOf is the plain ledger sum, but the API's adjBalances also subtracts stored, unposted, non-voided redemptions. A test can therefore pass on the mock where the API answers would_overdraw. The mock's scope check also uses only zone nodes, the API uses wing/division/territory/zone/route, and the mock ignores the caller's reach. A reader's ledger reach is applied by zone only in the mock (route reach is ignored).
- Idempotency key on the client includes fields the server's replay check ignores. The single-form key is JSON of the whole body, including reason and allow_overdraw; the bulk key includes the trimmed reason and the overdraw flag. If an apply or post times out but actually committed, and the admin then fixes a typo in the reason (or ticks the overdraw box) before retrying, a NEW uuid is generated and the same points are posted twice. On bulk, the new preview says replayed=false because the uuid is new. Suggestion: key on {outlet, points, expires_on} for single and on the rows for bulk; or, on a network error, lock the form until the admin explicitly chooses 'retry' or 'new'. A page reload after a timeout also loses the uuid; this is inherent, but the 'already applied' state could be checked through the ledger.
- Unbounded input sizes. The browser reads the whole CSV with file.text() and parses every line before the 20,000-row check, collecting every error. The BFF calls req.json() with no body-size limit before zod (App Router route handlers apply no default limit). Only authenticated writers can reach it. I did not check whether the API has a size cap. No CSV export exists, so formula injection does not apply here.
- Strings not fully localised. In the problem table the 'Field' column shows raw API column names (outlet_id, points, expires_on, row). The ledger 'Kind' column falls back to the raw source_type for any type outside the four known ones. errText() builds 'loy.adj.err.<code>' for any server code, but codes such as pattern, not_an_object, not_a_boolean, out_of_range and unknown members have no or only partial keys (out_of_range and invalid_value exist). A line with more than 3 cells is reported as 'not_an_integer' ('A whole number is needed'), which is the wrong message.
- BundleProgrammesLowsTest.anAmoAndATsoGetTheirOwnZoneChainAndNeverAnotherUsers: for a user with no home zone the assertion is 'geo == null || geo.isNotEmpty()', which is always true. If amo1001 or tso1001 has no home zone in TsoWorld, the AMO/TSO part of push-B low (geography for AMO and TSO) asserts nothing. The same test class also leaves fixtures behind: zone Z-LOWS, programme LOWS-A, and a zone renamed with ' (renamed)' that is never restored.
- theQuarterStartMonthIsAGlobalOnlyKey... asserts a cfg_key fact (scope_levels = {global}) rather than making the bundle and GiftChoiceRecords read through one rule. It is acceptable as a guard, but if the key is ever made scoped this test fails and the code still has two reads.
- AmoManualContractTest.versionIs1230: the explanatory history string no longer includes the 1.21.0 (android-integrity) summary that the base had. This is cosmetic; the exact version assertion is kept, and the openapi history keeps 1.21.0.
- API ADMIN grant check: loyaltyPrincipal applies the module grant only when p.modules is non-null ('when the grants are live'). An ADMIN token with no modules map would pass Need.WRITE. This existed before the lane and the guard checks first, but the adjustment now relies on it as well.
- The ledger 'more' link keeps only the lc cursor and drops vc/ec, so paging the ledger resets the versions and enrolments pages. The single-adjustment submit button uses only aria-disabled (pointer-events-none); pressing Enter can still submit while busy, which is harmless because the uuid is reused.
- No web test covers the uuid reuse on a retry inside the component, or the BRAND ledger refusal through the page. nextAttempt itself is unit-tested in web/tests/loyalty.test.ts.


## Re-review c4113eed..37ddbcbd, 2026-11-18

Read from code only; nothing built or run.

### lock-and-ledger verdict=pass
SUMMARY: Lens: the race fix and the ledger lows at 37ddbcbd (delta c4113eed..37ddbcbd). Read-only: I read AdminLoyalty.kt (postAdjustment, putAdjustments, adjOutlets, adjLockPairs, adjBalances, loyaltyPrincipal), LoyaltyLedgerJob.kt, BundleProgrammes.kt (loyalty, pendingGiftVerifications), GiftPhotoRecords.kt, V0193, JdbiStores grantsLive, AdminModules.check, RequestIsolation, postgres.bicep and the new tests in AdminLoyaltyTest. I built and ran nothing.

The previous blocking item is fixed:
- **Same lock key.** adjLockPairs uses exactly the job's key and call: the string "loyalty:$o:$programme", `pg_advisory_xact_lock(hashtextextended(:k, 0))`, wrapped in the same SELECT.
- **Lock taken before the balance is read.** Single post: the pair lock is taken after the scope, state and enrolment checks but before adjBalances, then the INSERT runs. Bulk apply: the pair locks are taken over the enrolled outlets, distinct and numerically sorted, before adjBalances. A dry run takes no pair lock and writes nothing, which is fine.
- **One-statement balance.** adjBalances is one statement: the ledger sum minus the non-voided redemptions that have no 'redemption' ledger row. That is the same figure BundleProgrammes.loyalty sends as balance_points.
- **New race tests.** Three concurrent -80 debits on 100 (two single posts and one bulk) give exactly one pass, two 409s and a balance of 20. A second test holds the job's pair lock while a redemption is stored, and the debit waits and then sees it.

Lock order:
- Every adjustment takes its uuid advisory locks first, then FOR SHARE on the programme row, then the pair locks.
- The ledger job takes one pair lock per transaction and no other explicit lock. Its ledger INSERT takes only FK KEY SHARE on the programme row, which does not conflict with FOR SHARE.
- Programme status changes take FOR UPDATE on the programme row and no pair lock.
- Redemption ingest and expiry take no pair lock outside the job (expiry runs inside reconcilePair).
- So nothing waits in a cycle among pair locks: two bulks lock in the same ascending order, and the job holds at most one pair. Any residual cycle would be caught by the PostgreSQL deadlock detector and answered 40P01, which maps to a transient 503, not a hang. The one real inversion is between the two uuid locks (see the lows).

The other points in this lens:
- **Replay before scope.** The replay is answered after the strict write-grant check and the programme-visibility 404, so a caller who lost the grant gets 403 or 404 and never the stored row. Another writer who replays someone else's uuid gets only a row they can already read through GET /ledger. Writers are national today.
- **uuid cross-conflict.** Checked both ways under both uuid locks, and tested.
- **Overdraw record.** The 'overdraw' flag is set on the ledger row. The single-post audit holds allow_overdraw, balance_before and overdrawn; the bulk audit holds allow_overdraw and overdrawn_rows.
- **debit_has_no_expiry.** Enforced in adjRow for single and bulk posts, and in the web helper.
- **Strict modules-claim check.** A claim-less ADMIN token is refused on both adjustment routes. In production the grants are latched live once V0089 exists (it ships), and then AdminModules.check answers 401 ERR_SCOPE_CHANGED for a claim-less ADMIN. The older writers' gap therefore only exists on a database before that migration; I judge it low or nil.
- **Contract.** The delta is additive: optional `units_captured`, new codes and longer descriptions inside the still-provisional 1.23.0. No migration.
- **units_captured.** Correct for every row it is sent on: distinct unit_no of photos that are not voided and not duplicates, unit_no not null, filtered to 1..qty. A photo with an out-of-range or missing unit is flagged is_duplicate by GiftPhotoRecords/V0193, so it is excluded.
- **Tests.** No test was weakened. The removed lines are refactors of app() and activeProg() to take parameters.

No blocking finding. Two lows matter more than the rest:
- A 20,000-row bulk takes up to 20,000 advisory locks, one round trip each, including outlets that only receive credits.
- The campaign exemption to not_enrolled contradicts the bundle.

Not verified:
- Runtime behaviour.
- The actual lock-table size and max_connections on the B1ms, B2s, D2ds_v5 and D8ds_v5 servers (from memory: about 50, 429, 859 and 3,437 connections at 64 locks per transaction).
- Row-lock queueing between FOR SHARE, FOR UPDATE and FK KEY SHARE, beyond the deadlock-detector argument above.
- Query plans for the LIKE prefix lookups.
- The phone's use of units_captured.
- The web and e2e changes, beyond the retry-key and size-cap lows.

LOWS:
- Bulk lock count and hold time (AdminLoyalty.kt putAdjustments -> adjLockPairs). The apply takes one pg_advisory_xact_lock per distinct enrolled outlet: up to 20,000, each its own round trip. That includes credit-only rows, which the guard does not need (a credit racing a debit only makes the debit's check more conservative, and the job already picks up late-committed adjustments through the id window and the daily sweep). Advisory locks never use the fast path, so every one takes a slot in the shared lock table, sized max_locks_per_transaction (64) x (max_connections + background processes). Scenario: on a Burstable B1ms server (dev-lite, about 50 connections, so roughly 3,000 to 5,000 slots), a 20,000-row apply fails with 53200 'out of shared memory' after rollback-safe partial acquisition. While it holds thousands of slots, other sessions that need a lock outside the fast path, such as a sync upload touching more than 16 relations, can fail with the same error. A pair waiter, such as a single debit or the job, can also exceed the api role's 15 s statement_timeout while a large bulk holds its pair, giving a 500 or a failed job pair that is retried next pass. Nothing is double-posted and nothing hangs, and the final D8ds_v5 server has headroom (about 220k slots), so this is low, but it should be bounded. Fix: lock only the debit outlets, take them in one statement (SELECT pg_advisory_xact_lock(hashtextextended('loyalty:'||o||':'||:p,0)) FROM unnest(sorted array) o ORDER BY o), and either cap debit rows per file (for example 2,000) or document the limit.
- The two uuid advisory locks are taken in opposite orders. postAdjustment locks adjustment_req:U then adjustment_batch:U; putAdjustments locks adjustment_batch:U then adjustment_req:U. Scenario: the same uuid is sent at the same moment as a request_uuid and as a batch_uuid. Each transaction holds its first lock and waits for the other's, PostgreSQL aborts one with 40P01 (mapped to a transient 503), and the retry gets the intended 409. This needs uuid misuse, so impact is minimal. Take the two locks in one fixed order in both handlers.
- The CAMPAIGN exemption to not_enrolled is wrong. adjOutlets treats every in-scope outlet as enrolled when kind = 'campaign', on the grounds that 'the bundle shows its balance to every outlet in scope'. But BundleProgrammes.loyalty, used by both the bundle and ProgrammesReads, builds its pairs only from app.programme_enrolment, for every kind. Only programmes() exempts campaigns. Scenario: an admin credits +500 to a non-enrolled outlet in a campaign programme. The post is accepted (201), audited and listed in the ledger, but the phone never shows the balance: the exact dead-points case not_enrolled was added to prevent. The contract text ('a campaign programme needs none ... the phone shows a balance only for an enrolled outlet') has the same contradiction, and the test only asserts the 201. Either drop the exemption, or make the bundle's loyalty() include in-scope campaign outlets; then log it in DECISIONS.
- pending_gift_verifications row selection (BundleProgrammes.pendingGiftVerifications). A row is listed only when `photos < qty`, and `photos` counts every non-duplicate, non-voided photo, including units above the CURRENT qty. units_captured is filtered to 1..qty but photos_captured is not. Scenario: a redemption has two lines of the same gift (qty 4) and photos for units 3 and 4. One line is voided later, so qty becomes 2; the stored photos for units 3 and 4 stay non-duplicate because check_flag is only judged on arrival. Now photos = 2 >= qty = 2, so the row is dropped although units 1 and 2 are open, and the phone skips missing photos. The filter predates S-1 and the case is rare. units_captured is correct on every row that is listed. Fix: select rows where the count of distinct units in 1..qty is below qty, and compute photos_captured the same way.
- Redemption ingest takes no pair lock. The guard sees redemptions already stored and those the job is posting, but not one that is mid-ingest. A debit can pass at balance B while a field redemption commits a moment later, so the pair ends below zero. This is consistent with the ledger model (redemptions are field facts, never refused, and overdraw is derived), but D-LOY-API-37 and the contract say 'two concurrent debits cannot both pass the guard'. Add one line saying a concurrent field redemption can still overdraw, and that the derived `overdrawn` flag on the phone shows it.
- Bulk overdraw audit: the audit row records only allow_overdraw and the overdrawn_rows count. Which rows went below zero, and their balance_before, appear only in the ledger flags ('overdraw'), and balance_before is not recorded at all for bulk. An auditor cannot see from audit_log alone what each overdrawn outlet held. Consider adding an array of {outlet_id, balance_before} for flagged rows (bounded at 200).
- The cross-namespace and replay lookups use `source_id LIKE '<uuid>:%'` on a database collated en_US.utf8. The UNIQUE(source_type, source_id) btree can then only seek on source_type = 'adjustment' and filters every adjustment row, so the cost of every single post and bulk apply grows with the total number of adjustment rows ever posted (20,000 per large file). Not a correctness issue, and I did not check the plan. A range predicate (source_id >= '<uuid>:' AND source_id < '<uuid>;') or a COLLATE "C" comparison would seek.
- Replay before scope: the replay comes after the strict grant check and the programme-visibility check, so nothing leaks to a caller who lost the grant. Any writer who presents another admin's uuid with the same content gets that admin's stored row and reason; the same writer can already read it through GET /ledger. The replay also skips the outlet-reach check, which matters only once a writer's reach can be narrower than national. Acceptable; note it in the contract.
- Strict modules check: an ADMIN token without the modules claim fails closed on both adjustment routes, and this is tested both with grants live and not live. In production the grants latch live as soon as app.user_module_grant exists (V0089 ships), and AdminModules.check then answers 401 ERR_SCOPE_CHANGED for a claim-less ADMIN on every module route. The older loyalty writers' gap therefore exists only before that migration. Severity: nil in production, a test-fixture concern only.
- Not verified: whether the eleven earlier lows are all closed. Seen fixed: the overdraw flag and audit, not_enrolled, debit_has_no_expiry, the retry keys (single post keyed on outlet, points and expires_on; bulk batch uuid keyed on rows only), the uuid cross-check, replay before scope, and the 2 MiB client file cap. Not checked: that the lag of lapsed-but-not-yet-expired credits is now written down for the adjustment, and the ADJ_SELECT audit_log subquery index.

### s1-contract-web-tests verdict=fail
SUMMARY: Read-only review of 37ddbcbd. I read the delta c4113eed..37ddbcbd (24 files), compared it with the previous review's blocking finding and lows, and read the code paths each check depends on: LoyaltyLedger.lockPair, GiftPhotoRecords.flagUnit, the gift_photo_one_per_unit index and the V0193 election trigger, mapDb, the mock and the BFF. Nothing was built or run.

The previous blocking finding is fixed. The single and bulk paths take the job's pair lock (same key, outlets sorted) before one balance statement, and in that statement the ledger sum and the unposted redemptions are read in one snapshot.

The fix brings in one new blocking problem. A bulk apply takes one transaction-level advisory lock per outlet, and the API allows up to MAX_BATCH_ROWS = 20,000 rows. That can fill Postgres's shared lock table.

S-1 units_captured is correct:
- Only non-voided, non-duplicate photos of that redemption and gift count. A unit photo with no unit number or outside 1..qty is flagged by flagUnit with is_duplicate = true, so it never counts.
- The unique per-unit index and the "first wins" election leave one winner per unit, so a duplicate of a unit adds nothing.
- Refused or quarantined photos are not stored, so they cannot appear.
- Results are DISTINCT and ascending, with a defensive Kotlin filter to 1..qty.
- The outlets come from the caller's own outlet list, so scope is the existing one.
- Cost is one indexed correlated subquery per pending item, with at most 500 items.
- The bundle version is a digest of the content, so a new photo moves it.
- The new field is optional in the YAML, in openapi.yaml and in openapi.d.ts. No Kotlin phone DTO reads pending_gift_verifications yet, so nothing can drift.

Other checks:
- **Contract and schema:** no migration; the contract changes are additive.
- **Roles:** strict modules fail closed on both adjustment verbs, and that is tested.
- **Audit:** the audit rows now carry allow_overdraw, balance_before and overdrawn (overdrawn_rows for bulk).
- **Web retry key:** it resets to null after every success. Two intended +500s on one outlet therefore get two uuids and do not collapse into one. After a refusal the key is kept, so ticking allow_overdraw or editing the reason reuses the uuid, which is safe because nothing was posted.
- **Tests:** in the delta, outlets were renumbered from 301/401 to 101/102/103 and new assertions were added. I found no assertion removed or loosened. The geography assertion is now an exact equality.

Not verified:
- Any test run, or the lane's claim that removing the lock made the race test fail.
- The real max_locks_per_transaction and max_connections on the test Postgres.
- Whether planner.routesFor returns the first route in route-id order, which the geography test's oracle assumes.
- Whether a gift-photo arrival marks a pre-generated bundle snapshot dirty. On-request builds rebuild the digest.
- Row-lock interplay between the adjustment's FOR SHARE on the programme, a concurrent status change's FOR UPDATE, and the job's foreign-key KEY SHARE. Postgres would detect a deadlock rather than hang.
- The phone side of S-1, which has no consumer yet.

#### BLOCKING upheld=3/3: A large bulk apply takes up to 20,000 advisory locks in one transaction and can run Postgres out of lock memory, failing this write and other sessions' writes
WHERE: backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/AdminLoyalty.kt adjLockPairs (l.1062-1064), called from putAdjustments for every enrolled outlet of a non-dry, non-replayed file; MAX_BATCH_ROWS = 20_000 (l.115); the web ADJ_MAX_ROWS is 20_000 too
WHY: pg_advisory_xact_lock entries live in the shared lock table, whose size is max_locks_per_transaction (default 64) times (max_connections + max_prepared_transactions). That is about 6,400 slots at 100 connections and about 3,200 on a 50-connection burstable SKU. The Postgres docs warn that filling it leaves the server unable to grant any lock. Each outlet also costs one sequential round trip, so 20,000 statements run while the programme row is held FOR SHARE. The lock is misused in a way that can fail writes: the bulk itself fails, and so do unrelated writers such as sync ingest and the ledger job that need a lock while it is held. The race tests use one outlet, so nothing exercises the contract's documented maximum.
SCENARIO: An admin uploads a 10,000-row opening-balance CSV for a loyalty programme (allowed up to 20,000). The dry run passes because it takes no pair locks. On apply, the loop reaches about 3,000 to 6,000 locks and Postgres raises 53200 'out of shared memory, increase max_locks_per_transaction'. mapDb does not map that state, so the BFF gets a 500 and nothing is posted. While the transaction is still acquiring, a concurrent sync upload or ledger-job pair lock fails with the same error. Retrying the same batch_uuid fails the same way, so a valid file can never be applied. Possible fixes: one programme-level exclusive advisory lock for a bulk apply, with the job and single adjustments taking that key shared before their pair lock (or the pair lock only below a small row count); or cap a bulk file to a few hundred outlets per transaction and document the limit; plus a test with a file of several thousand rows.

LOWS:
- The single and bulk paths take the two per-uuid locks in opposite orders. postAdjustment takes adjustment_req then adjustment_batch; putAdjustments takes adjustment_batch then adjustment_req (l.1114-1115 vs l.1160-1161). If the same uuid is sent at once as a request_uuid and as a batch_uuid, the two transactions deadlock. Postgres aborts one with 40P01, which mapDb does not map, so the caller gets a 500 instead of the documented 409. No legitimate write is lost (the loser would have been a 409), but taking the locks in one fixed order everywhere is a one-line fix.
- Pending-list membership is still decided by the SQL count of all winners (`photos < qty`), while units_captured is filtered to 1..qty in Kotlin. If a redemption has two lines of one gift and one line is voided after its units were photographed, qty drops below the highest photographed unit. Example: qty 3 becomes 2, with photos on units 1 and 3. Then photos = 2 and the item leaves the list although unit 2 has no photo, or photos_captured disagrees with units_captured.size. Count only unit_no BETWEEN 1 AND qty in SQL so both come from one predicate. units_captured itself is never wrong.
- The S-1 test covers only an empty list and units [1, 2] with one duplicate. It has no voided photo, no gap such as [1, 3], no unit above qty and no refused photo. The contract also does not tell the phone what to do when units_captured is missing (older server); the phone should fall back to photos_captured.
- DECISIONS.md D-LOY-API-36 says photos without a unit_no 'count in photos_captured only'. In the code such a campaign-unit photo is flagged unit_out_of_range with is_duplicate = true and counts nowhere, so the text is inaccurate.
- The contract ACHIEVEMENT RULE text in ProgrammesSection repeats itself. The 'astha_as_of / as_of display only' sentence appears twice, and single-flight appears twice: first as 'both directions', then as 'never uploads while a bundle request is in flight'. It also keeps 'if an acknowledgement arrives during the request it discards ... and refetches', which single-flight makes unreachable apart from pushed resolutions. The quarantine carve-out (never added until a resolution or ack reaches the phone) is stated for memos only. The loyalty sentence ('the SAME rule ...') does not say a quarantined redemption is not subtracted twice once a reviewer accepts it without an upload ack. State each rule once and extend the quarantine clause to redemptions explicitly.
- The race tests are timing-based, not latch-based. Three async calls on Dispatchers.IO have no barrier, so they may run one after another and pass without the lock. The held-lock case relies on Thread.sleep(400): if the request reaches the lock after the holder commits, it reads 10 and is refused anyway, so the test passes without the lock. Make it deterministic by polling pg_locks/pg_stat_activity until the adjustment is waiting on the advisory lock, then insert and commit.
- The contract says 'two concurrent debits cannot both pass the guard'. That holds for admin debits and job passes, but sync redemption ingest does not take the pair lock. A redemption committing during an admin debit can still take the balance below zero with no overdraw flag. Offline redemptions are unguarded by design, but the wording should say the guard covers admin debits and job passes only.
- The BFF cap compares raw.length, which counts UTF-16 code units, not bytes. A Bangla-heavy body can be up to about 3 times 2 MiB in bytes. With no Content-Length (chunked), the whole body is read into memory before the check. The browser checks file.size in bytes, so the two caps disagree.
- The mock does not model the strict modules claim (an ADMIN token with no claim is refused) and its audit rows lack allow_overdraw, balance_before and overdrawn. Those parts of mock = API are not covered by web tests.
- The geography oracle in BundleProgrammesLowsTest uses CURRENT_DATE (database UTC) and `valid_to >= CURRENT_DATE`. The server uses planner.routesFor(user, Dhaka business date) plus weekend handling. Between 18:00 and 24:00 UTC, or with a weekday-specific plan, the oracle may disagree with the server. The test then fails spuriously; it is not weakened.
- ProblemTable renders t(`loy.adj.field.${column}`) for any column. A server row error whose pointer ends in an unexpected key falls back to whatever t() returns for a missing key, which I did not check.


## Landing review at aae4a9648, 2026-11-18

Read from code only; nothing built or run.

### lock-fix-round verdict=pass
SUMMARY: Read-only review of lane/portal-loyalty-api-c at aae4a9648, covering the fix-round delta 37ddbcbd..aae4a9648 (lane commits 65dc2a24f and 245880614 plus the base merge) and what would land (ffbe8d6fd...aae4a9648: 48 files). I read AdminLoyalty.kt (adjLockPairs, adjLockUuid, adjDb, postAdjustment, putAdjustments, adjOutlets), LoyaltyLedgerJob.kt (lockPair, reconcilePair, pairsToReconcile), BundleProgrammes.pendingGiftVerifications, the new AdminLoyaltyTest, GiftPhotoRecordsTest and BundleProgrammesLowsTest tests, the contract text (postProgrammeAdjustment, putProgrammeAdjustments, ProgrammesSection ACHIEVEMENT RULE), the BFF boundedJson, the mock, the web helper, and D-LOY-API-38/-39. Nothing was built or run.

No blocking finding. The previous blocking item (up to 20,000 advisory locks) is fixed.

(1) Guarded-debit locking:
- Bulk: only distinct outlets that get a debit locked when the file has no allow_overdraw.
- Single post: any debit is locked; a credit is not.
- One statement over a sorted bigint[] (ORDER BY + OFFSET 0 fence).
- Key text `'loyalty:'||o||':'||p` and hashtextextended(..,0) match LoyaltyLedger.lockPair exactly. The lock function is volatile, so a count(*) wrapper cannot prune it.
- The cap (more than 2,000 guarded debit rows, `too_many_debit_rows`) is checked in putAdjustments after the write-grant check and before any database work. It runs on every call, so it holds for a dry run and for an apply sent without one.
- 53200 is caught inside mapDb and returned as 503 with nothing written.
- A credit-only file takes no pair lock; the test counts pg_locks.
- The lens's "1,999 guarded + 18,000 allowed" file cannot exist: allow_overdraw is a file-level flag. The related race (allowed debits take no lock) is the first low.

(2) uuid locks: one fixed order (batch, then request) in both handlers via adjLockUuid.

(3) not_enrolled: now checked for every kind. This matches the bundle's loyalty(), which uses the same valid_from/valid_to predicate. Tested for single and bulk on a campaign programme.

(4) adjustHook: it is a constructor parameter that defaults to null. The only production construction (Wiring.kt:412) does not pass it, and no request or configuration can set it. The barrier test really overlaps the three transactions. Without the lock all three would post, so it can fail (the lane also says it proved this once). The held-lock test is mostly deterministic (see lows).

(5) S-1: one filtered set (not voided, not duplicate, unit present, 1..current qty) decides membership, units_captured and photos_captured. Outlets are the bundle's own. Cost is one correlated subquery per group, capped at 501 rows. The tests cover a gap, a unit above qty, a voided photo, a parent_refused photo and the stale-qty case.

(6) The ACHIEVEMENT RULE now states its five rules once each, and it describes today's inference behaviour correctly in the main. But it names a nonexistent resolution `rejected`, and it inherits holes the lead has already ruled against (see lows).

(7) The BFF cap now counts bytes.

(8) The oracle now uses world.today() (Dhaka date).

Contract: still additive against the deployed 1.21.0. The lines removed on the landing side are rewritten descriptions only; no member, enum value or required flag is removed. No migration. The base merge reverted nothing in the six shared files. No test was weakened: the old timing-based race test was replaced by stricter latch tests, and the campaign test now expects the ruled 400 before enrolment.

Not verified:
- Any runtime behaviour or CI result.
- That the latch tests actually fail with the lock removed (the lane's claim).
- That the test client really runs the three requests concurrently.
- world.today() and planner.routesFor weekday handling.
- Whether the earlier lows (bulk per-row balance_before in the audit, the LIKE prefix lookups, the gaps between the mock and the API) are closed.
- The Bangla strings beyond the two added keys.

LOWS:
- Overdraw-allowed bulk debits take no pair lock, so under concurrency the guard and the 'overdraw' flag can both be wrong, and neither the contract nor DECISIONS says so. AdminLoyalty.kt putAdjustments locks only when !allow (the single post locks any debit, so the two paths also differ). Scenario: outlet balance 100. Admin A sends a single -80 without allow_overdraw: it takes the pair lock and reads 100. At the same instant admin B applies a bulk file with allow_overdraw containing that outlet at -80: no lock, it also reads 100. Both commit. The balance is -60, yet neither ledger row carries 'overdraw'. A's audit says overdrawn=false with balance_before=100, and B's says overdrawn_rows=0. Two allowed bulks on the same outlet give the same result. This contradicts the contract sentences 'two concurrent ADMIN debits (single or bulk) ... cannot both pass the guard' and 'a debit that goes below zero with allow_overdraw is flagged overdraw', and D-LOY-API-38's 'with allow_overdraw there is nothing to guard'. I judge it not blocking: the negative balance is authorised by an allow_overdraw request recorded in its audit row, the points equal a serial order (A then B), and the bundle's derived overdrawn flag shows the state. Only the per-row marker and the counts are wrong. Fix: either lock the debit outlets of allowed files too (under the same cap) or state that the 'overdraw' flag and overdrawn_rows are best-effort when allowed files run concurrently.
- ACHIEVEMENT RULE text (ProgrammesSection in contract/openapi.yaml): each of the five rules now appears once. But rule (5) says 'a resolution `rejected` removes it'. The Resolution enum is [accepted, accepted_with_fix, discarded], and return_to_device is also stored as 'discarded'. The lead upheld this as blocking on sr-targets-server for the same wording. A phone built from this text would never see `rejected` and would keep a discarded memo as awaiting. The text calls itself 'the one rule' for the inference model, which the lead has since ruled fails (7 holes; ruling: EXACT BY IDENTITY with probe uuids, lead-state 15:10). At least one of those holes applies here: rule (3) subtracts an acknowledged memo voided or superseded locally, with no held state for the void or edit. If that void is quarantined and a reviewer releases it, the server drops the memo while the phone keeps subtracting it, so it is subtracted twice. As a description of today's server guarantee the text is correct. Change `rejected` to `discarded` (noting that return_to_device arrives as discarded), and add a line saying the phone arithmetic is to be replaced by the probe rule (serverHolds(uuid)). Expect a merge conflict with lane sr-targets-server on the same description.
- Loyalty in the ACHIEVEMENT RULE: 'the SAME rule covers the loyalty balances', but the quarantine clause (5) is worded for memos only. It does not say that a quarantined redemption is never subtracted again once a reviewer accepts it without an upload acknowledgement. The previous review raised this and D-LOY-API-38 (g) did not address it. The sentence 'the phone subtracts its own redemptions not acknowledged when it sent the request' also appears twice (once in the opening and once in the loyalty sentence).
- aFullSharedLockTableIsACleanRefusalNotA500 really exhausts the cluster-wide shared lock table (pg_advisory_xact_lock over generate_series(1, 2,000,000)). gradle.properties has org.gradle.parallel=true, so another module's database test running against the same Postgres at that moment can fail with 53200. The lane reports 53200 in V0140/V0118 migration tests in its own container and calls it 'not this change'; that is plausibly this test. Production behaviour is fine, but this is a CI flake source. Suggestion: give the test its own lock-heavy window (a dedicated cluster or a lower max_locks setting), or simulate 53200 by throwing a PSQLException with that SQLSTATE from the hook.
- aDebitHeldBehindALedgerJobPassSeesTheRedemptionThePassStored is not strictly deterministic in the no-lock case. 'reached' fires before the lock, and without the lock the debit's balance read can still run after the holder's commit, so it reads 10 and gets 409: the test then passes without the lock. Firing the latch at after_balance_read only once the holder has inserted but not yet committed (or holding the commit until after_balance_read) would make the failure certain. The barrier test (twoDebitsWithDifferentIdsOnOneBalanceNeverBothPass) is sound. It does assume the Ktor test client sends the three requests concurrently, which I did not verify.
- BFF boundedJson (web/src/lib/admin/loyalty-server.ts) now compares Buffer.byteLength, which is correct. Without a Content-Length header (a chunked body), req.text() still reads the whole body into memory before the check. Only authenticated writers reach it. A streaming read that stops at ADJ_MAX_FILE_BYTES would close this.
- The guarded-debit cap in the API counts only rows that parsed (parsed != null && points < 0), while the mock counts every raw element with a numeric negative points value. A file can hold over 2,000 debits of which some have a shape error (for example a bad expires_on). The mock then answers too_many_debit_rows, while the API reports the shape errors and only applies the cap to valid rows. This is a minor gap between the mock and the API, and it does not affect safety (the API bound on locks holds).
- The geography oracle now uses the Dhaka date, but it still compares route_assignment.valid_to >= today, while enrolment uses valid_to > today, and the server's planner.routesFor may apply weekday or plan handling that the oracle ignores. I did not verify routesFor; a spurious failure is still possible on a weekday-specific plan. The test is not weakened.
- Not checked from earlier rounds: whether bulk overdraw rows now get a per-outlet balance_before in the audit; the source_id LIKE '<uuid>:%' lookups, which seek poorly under en_US collation; and the mock gaps (strict modules claim, audit fields, route reach).

### landing-gate verdict=fail
SUMMARY: This was a read-only landing gate on ffbe8d6fd...aae4a9648: 48 files, +3019/-89. The merge base is ffbe8d6fd itself, so the three-dot diff equals the two-dot diff. I read the lane commits 65dc2a24f (the L-A13 fix round), 2458806140 and aae4a9648, and the merge 5ae8ec29d2. Nothing was built or run.

**(1) Merge.** No base work is lost. Six files changed on both sides since ce6792f67: DECISIONS.md, contract/openapi.yaml, contract/slices/INDEX.md, docs/24-build-spec.md, WireDtos.kt and openapi.d.ts. Against the base:
- DECISIONS.md, docs/24 and WireDtos have additions only.
- INDEX.md changes only the version line, the counts and the sizes.
- `git diff --cc` shows no hand-resolved hunks.

**(2) Contract.** The diff against 1.21.0 is additive. It has exactly 6 removed lines: info.version 1.21.0 -> 1.23.0, and the descriptions of RedemptionPayload.balance_before_points, ProgrammesSection, astha_as_of and astha_truncated.
- Added paths: POST and PUT /v1/admin/programmes/{id}/adjustments (postProgrammeAdjustment, putProgrammeAdjustments) and GET /v1/admin/programmes/{id}/ledger (listProgrammeLedger, with optional query parameters outlet_id, source_type, Limit and Cursor).
- Added schemas: BundleGeoName, BundleGeography, ProgrammeAdjustmentWrite, ProgrammeAdjustmentRow, ProgrammeAdjustmentBatch, ProgrammeAdjustmentRowError, ProgrammeAdjustmentBatchResult, LedgerEntry, LedgerPage.
- Added optional members on existing schemas: BundleUser.geography (nullable), LoyaltyBalance.tier_code, AsthaTargetRow.brand_name and brand_name_bn, PendingGiftVerification.units_captured, ProgrammesSection.astha_truncated_months.
- No required list on an existing schema changed and no enum value was removed. The only enums are new ones on new schemas (programme_status: draft, active, inactive).
- Generated artefacts are consistent: all 18 changed slice files match their INDEX.md byte sizes; openapi.d.ts carries the three operations and the new members; WireDtos adds only `geography: JsonElement? = null`; AmoManualContractTest pins 1.23.0.
- No docs/requests/contract-1.22-*.md or contract-1.23-*.md exists. tools/ci/contract-breaking.sh asks for a request file only when oasdiff reports a breaking (level 3) change, and I found none by reading, so the gate should pass. oasdiff itself was not run.
- Deployed phones parse with WireJson ignoreUnknownKeys, and no deployed Android code reads astha_targets. So moving the bundle from month rows to quarter rows cannot give a wrong figure on a deployed app.

**(3) Migrations.** `git diff --stat ffbe8d6fd aae4a9648 -- db/migrations` is empty. Every table and column the code relies on exists in shipped migrations:
- loyalty_ledger: source_type, source_id (length 64 or less; `<uuid>:<row>` is at most 42), expires_on, flags text[], UNIQUE(source_type, source_id). V0007.
- programme_enrolment: valid_from, valid_to, tier_code. V0007.
- astha_target, redemption, redemption_line. V0007.
- gift_photo: unit_no, is_duplicate, voided_at. V0007 and V0193.
- programme_version. V0158.

**(4) Authorization.**
- POST and PUT adjustments: SUPERADMIN, or ADMIN with module `loyalty` = `w`. The check is strict, so an ADMIN token without the modules claim gets 403. Phone tokens get 403. The programme must be in the caller's reach (else 404), and the outlet in the programme's scope and the caller's reach (else 403).
- GET ledger: LOY_READERS minus BRAND (SUPERADMIN, ADMIN with the r grant, NATIONAL, TOP, WM, DMO, TSO). WM, DMO and TSO are cut to the zones and routes of their reach; BRAND and phone tokens get 403.
- An SR token on these admin routes is refused by the isPhone check. No new phone route is in this diff: the bundle changes reuse the existing token scope.

**(5) Web.** The page shows the form only when canWrite is true, and hides the ledger for BRAND. The BFF goes through gate(req, "programme"), validates with zod, caps the body in bytes and pre-checks the guarded-debit cap. No secret is in client code. Every string is an English and Bangla pair. A unit test file and an e2e spec were added.

**(6) Tests.** Nothing was weakened and nothing is @Disabled.
- The removed assertions in AsthaBundleSectionTest and ProgrammesBundleSizeTest are month-to-quarter semantics, replaced by quarter assertions.
- The removed race test in AdminLoyaltyTest was replaced by barrier and latch tests that fail without the lock.
- AmoManualContractTest is a version pin.

**(7) Outside the lane.** docs/24-build-spec.md only: three rows in the endpoint table. Nothing under android/, infra/, .github/ or tools/ci/.

**The previous blocking finding is fixed.** The bulk apply locks only the outlets that receive a guarded debit, in one statement over a sorted array; guarded debits are capped at 2,000 per file; SQLSTATE 53200 maps to a 503; the two uuid locks are taken in one fixed order. Two items from the last round are also closed: the campaign programme now needs an enrolment, and the S-1 pending list uses one filtered set of units.

**New blocking finding.** The fix goes further than the previous review asked: a bulk apply with allow_overdraw takes NO pair lock at all. That reopens the check-then-insert race of the earlier blocking finding against guarded debits, and leaves a negative balance with no 'overdraw' flag on any ledger row.

**What the deploy does:** no migration, so the database is unchanged. The API image adds the three admin routes and the bundle changes (geography, Astha rows for the whole quarter, tier_code, brand names, units_captured). The web image adds the adjustment form, the ledger card and the BFF route.

**Not verified:**
- Any build or test run.
- oasdiff output.
- The real lock-table size on the test server.
- Whether the latch tests pass reliably on CI.
- The phone's use of units_captured.
- The e2e behaviour.

#### BLOCKING upheld=3/3: A bulk apply with allow_overdraw takes no pair lock, so a concurrent guarded debit can pass and the balance ends below zero with no 'overdraw' flag on any row
WHERE: backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/AdminLoyalty.kt putAdjustments: `if (!replayed && !dry && !allow && debitOutlets.isNotEmpty()) { ...; adjLockPairs(h, id, debitOutlets) }`, then `adjBalances` and the `flagged` computation on an unlocked read. Compare postAdjustment, which locks every debit (`if (row.points < 0) ... adjLockPairs`). The behaviour is pinned by AdminLoyaltyTest.onlyOutletsThatReceiveAGuardedDebitTakeAPairLockAndACreditOnlyFileOf20000RowsTakesNone ("a debit with allow_overdraw takes no pair lock") and justified in D-LOY-API-38 ("with allow_overdraw there is nothing to guard").
WHY: The reasoning holds for credits, but not for a debit that is allowed to overdraw. Under READ COMMITTED, a guarded debit that holds the pair lock does not see the uncommitted bulk debit, and the bulk debit decides its own 'overdraw' flag from a read nobody serialises. So two admin debits can both pass, and the result is never recorded:
- the ledger flags on both rows are just {portal};
- the bulk audit says overdrawn_rows 0;
- the single post's audit says overdrawn false.

This is exactly the property the previous round's blocking finding restored, and it is the gate criterion 'a balance going negative without the overdraw flag being recorded'. It also contradicts the contract text in this push: 'two concurrent ADMIN debits (single or bulk) and a ledger-job pass cannot both pass the guard'. No test covers a bulk with allow_overdraw against a concurrent debit. The existing test asserts the missing lock as intended behaviour.
SCENARIO: An outlet's balance is 100.
1. Admin A uploads a correction file with allow_overdraw: true that contains a -50 row for that outlet. No pair lock is taken; adjBalances reads 100; 100 - 50 >= 0, so the row is not flagged.
2. Before A commits, admin B posts a single -80 debit without allow_overdraw. B takes the pair lock (nobody else holds it), reads the committed 100, and passes the guard.
3. Both commit. The balance is -30.

Result: neither ledger row carries 'overdraw', and B's audit row says overdrawn false and allow_overdraw false. Only the derived bundle `overdrawn` flag shows anything.

Two concurrent allow_overdraw bulks on one outlet, or an allow_overdraw single post (which locks) beside an allow_overdraw bulk (which does not), end the same way: negative, with no row flagged.

Fix: lock every debit outlet whether or not allow_overdraw is set (one statement, sorted, as now), and count all debit rows toward the 2,000 cap (or cap allow_overdraw debit rows separately). Update the lock-count assertion in the test, and add a latch test: a bulk with allow_overdraw beside a guarded single debit.

LOWS:
- The new test aFullSharedLockTableIsACleanRefusalNotA500 really exhausts the SERVER-wide shared lock table: up to 2,000,000 pg_advisory_xact_lock calls until 53200. gradle.properties sets org.gradle.parallel=true, and the jvm job runs :db, :backend:sync, :backend:masterdata and the other modules' builds and tests against one Postgres service. During that window any concurrent test transaction that needs a lock outside the fast path can fail with 53200, which would show up as an unrelated flaky test. Make the module's tests serial for this case, or fill the table with a bounded number of locks and inject the 53200, for example by raising it from the hook.
- The 2,000 guarded-debit cap is per file, not per server. On a B1ms-class test server (about 50 connections, so roughly 3,000 to 4,000 lock slots), two concurrent 2,000-debit applies, or one apply beside a heavy sync, can still reach 53200. That now gives a clean 503 with nothing written, so this is acceptable; note the per-server figure in the docs/28 profile.
- Credits, and with this change allow_overdraw debits, no longer take the job's pair lock. The ledger job's expiry correction is a target recomputed each pass (E - L), so a credit that commits during a job pass only delays the correction to the next pass. Not a correctness issue; write it down beside D-LOY-API-38.
- Still open from the last round: the replay and cross-namespace lookups use `source_id LIKE '<uuid>:%'` (AdminLoyalty.kt l.1150 and l.1196). Under en_US collation this cannot seek on the UNIQUE btree, so the cost grows with every adjustment row ever posted.
- Still open from the last round: the bulk audit records only allow_overdraw and overdrawn_rows. It does not record the per-outlet balance_before of the flagged rows.
- The single post reads `balance_before` for a credit without a lock and stores it in the audit row. It is informational only, but it may be stale under concurrency.
- S-1: photos_captured is now the size of the distinct-unit set in 1..qty rather than a count of all photos. This is correct given V0193, which flags a unit-less or out-of-range photo as a duplicate. A legacy campaign photo with a null unit_no that is not flagged as a duplicate, if one exists in data loaded before V0193, would now not count, and the gift would show as pending again. That is unlikely at pilot size.
- No docs/requests/contract-1.22-*.md or contract-1.23-*.md exists. The CI gate needs one only for an oasdiff level-3 change, and I found none by reading, but I could not run oasdiff. If the lead wants a request file for every provisional version bump, add one before the train.
- docs/24-build-spec.md is the only file outside the lane's own area (three endpoint rows). There are no changes under android/, infra/, .github/ or tools/ci/.


## Final delta check aae4a9648..d18ff776d, 2026-11-18: PASS (cleared for the loyalty server train)

Read from code only; nothing built or run.

### locking-and-audit verdict=pass
SUMMARY: Read-only review of d18ff776d (delta aae4a9648..d18ff776d, landing diff ffbe8d6fd...d18ff776d). No blocking findings. The earlier blocking item is fixed. In putAdjustments (AdminLoyalty.kt ~L1214-1224), every bulk debit outlet that can be posted (debit and enrolled, with or without allow_overdraw) is now locked in one sorted statement, using the same key string as LoyaltyLedger.lockPair ('loyalty:<outlet>:<programme>'). The lock is taken after adjLockUuid and adjProgramme(FOR SHARE) and before adjBalances. bal, the would_overdraw guard, the flagged set and the audit balance_before are all derived from the locked read. Debit rows that are not enrolled are not locked, but they always end as a not_enrolled error and are never posted. The single post locks every debit before its read (unchanged). Credits are not locked. The dry run takes no lock, which is fine because it writes nothing. The cap counts every raw debit row in putAdjustments before jdbi is touched, on the dry run and on apply. It parses points the same way JsonObject.int does (non-string primitive, toLongOrNull), so a row the parser would post as a debit cannot slip past the count. Exponent or decimal forms are not counted, but the parser rejects them anyway. The BFF and the mock now also count all debits.

Deadlocks: none found. The ledger job takes one pair per transaction (reconcilePair). Admin paths take the uuid locks (batch then req, the same in both handlers), then the programme FOR SHARE, then the pair locks in ascending order in one statement (ORDER BY + OFFSET 0 fence). Nothing waits on another pair lock after that. Two bulks wait in sorted order, a single holds at most one pair, and the job holds one. So two bulks, bulk vs single, and bulk vs the job cannot wait in a cycle. Lock exhaustion is bounded at 2,000 + 2 locks per file. A 53200 maps to 503 through adjDb.

Shared auditObj (AdminProducts.kt:463, internal, so only the masterdata module can call it): there are 13 call sites in AdminLoyalty (4), AdminOffers (1), AdminPrices (7) and AsthaTargetUpload (1). I checked the type of every argument: String, Long, Int, Boolean, LocalDate, BigDecimal.toPlainString(), UUID.toString(), Long?, String?. None is a JsonElement. The only JsonElement passed is the new 'overdrawn' JsonArray in the bulk audit. Before this change it would have been stored as its JSON text inside a string (JsonPrimitive toString keeps the quotes, JsonNull would become "null"); now it is stored as JSON. No existing caller's payload changes shape, so no reader, export or dw view of old payloads is affected.

Replay and cross-namespace lookups: a stored batch is all-or-nothing, because it is posted only when errs is empty, which needs every row parsed. Its source_ids are therefore exactly <b>:0..n-1 from WITH ORDINALITY, and row 0 exists whether it is a credit or a debit. Gaps and filtered rows cannot happen. The single-post probe '<uuid>:0' finds any batch with that uuid. The batch path probes source_id = '<uuid>' for a single. The uuid locks serialise a single and a batch racing on the same uuid. A replay reads <b>:0..parsed.size: a stored batch of size m>n returns n+1 rows and gives 409, m<n returns m rows and gives 409, m=n is compared row by row. UNIQUE(source_type, source_id) exists (V0007), so the lookups use equality on the unique index. UUID.toString contains no ':', so there is no collision.

53200 test: adjustHook defaults to null and Wiring.kt:412 builds AdminLoyaltyDeps without it, so the hook that raises 53200 is test-only and cannot run in production. The test now covers both single and bulk.

Latch tests:
- Allowed bulk vs guarded single: without the lock both read 100, the single posts (201), the bulk is not flagged and the balance ends at -30. The single==201 branch then fails on flaggedRows==1.
- Two allowed bulks: without the lock both read 10 and neither is flagged, so the assertion that exactly one row is flagged fails.
- Pair-lock count test: expects 2+1 held locks for the allowed debit; it was 2 before.
- Ledger-job test: the pass now waits on readDone, so without the lock the debit's read comes before the commit.
- twoDebits test (unchanged).
These are five tests that can fail with the lock removed, so the claim is plausible.

No migration anywhere in ffbe8d6fd...d18ff776d. The contract changes are description-only edits inside the provisional 1.23.0 (and the ProgrammesSection text). The resolution wording now matches the Resolution enum (accepted / accepted_with_fix / discarded). Landing: against 77873a8ea, and against the current integration tip 0aa547b625 (which has moved on with trains 124 and android-sr-programmes), the only conflict is the append-only DECISIONS.md, and no other files overlap.

Not verified: I did not build or run any test, including the Kotlin concurrency tests and the web vitest. I did not run real Postgres advisory-lock acquisition order or the size of the shared lock table on the Azure test server. I did not read the rest of the ledger-job test body past the changed lines, the web BFF chunked-body handling (the lane says it is unchanged), or the full contract diff text beyond the changed lines.

LOWS:
- Pre-existing, not in this delta: in the bulk insert in putAdjustments, flags = posted.indices.map { parsed.indexOf(posted[i]) } is O(n^2) identity scans. That is about 2e8 comparisons for a 20,000-row credit file, all inside the transaction while the uuid locks and the programme FOR SHARE are held. Using withIndex would remove the scan.
- The Overlap barrier waits only 2 s. Without the lock, if one request reaches after_balance_read more than 2 s after the other (cold start, a slow CI runner), the barrier breaks, the two run in sequence, and the two new allowed-overdraw tests can pass by chance. They can still fail, but not every run would catch a removed lock. A longer bound for the no-lock case, or a latch that requires both reads before either posts, would make them deterministic.
- The simulated 53200 test now proves only the adjDb mapping. Nothing shows that a real pg_advisory_xact_lock exhaustion surfaces as UnableToExecuteStatementException with SQLState 53200 on this query path. This was accepted in the previous review; the decision log should say so.
- Shared lock table: at 2,000 debit locks per file, three concurrent maximum-size debit files would come close to the default 64 x max_connections slots. A 53200 at that moment can also hit unrelated transactions on the server, not only the loyalty request (it is a shared table). This is fine at pilot size; record it in the docs/28 final-account sizing notes.
- The bulk audit lists at most 200 overdrawn rows (MAX_ADJ_ERRORS); overdrawn_rows carries the full count. This is documented in the contract, but beyond 200 the balance_before of the remaining rows is recoverable only from the ledger history, not from the audit row.
- DECISIONS.md conflicts textually when merged with the integration branch (both 77873a8ea and the current tip 0aa547b625). It is an append-only log; keep both sides.

### text-contract-landing verdict=pass
SUMMARY: Contract and landing review of lane/portal-loyalty-api-c at d18ff776d. I looked at the delta aae4a9648..d18ff776d and at what would land, ffbe8d6fd...d18ff776d. Nothing blocks this lane from landing as a server train.

1. **Contract.** All four openapi.yaml hunks are description text: info changelog, the PUT and POST adjustment endpoints, and ProgrammesSection. info.version is 1.23.0. No schema, enum, required list or path changed, so the contract is still additive against 1.21.0.
2. **ACHIEVEMENT RULE.** Each required statement now appears once:
   - the resolution is `discarded`, and a `return_to_device` also arrives as `discarded`. Both values exist in the ResolveRequest enum.
   - a held (quarantined) void or edit is never applied by the phone.
   - clause (5) covers a held redemption.
   - one sentence says the phone arithmetic will be replaced by the server's per-uuid answer (the identity probe).
3. **Adjustment endpoint text.** It now says every debit, single or bulk, guarded or `allow_overdraw`, is serialised under the pair lock. The overdraw flag and `balance_before` are decided under the lock, and the cap of 2,000 covers every debit row.
4. **Generated files.** The contract slices are regenerated: the byte sizes in INDEX.md match the files (7342, 4018 and 2773 B). openapi.d.ts carries the same three description changes.
5. **Request docs.** docs/requests/contract-1.22-loyalty-push-b.md and contract-1.23-loyalty-adjustment.md exist and match the changes (1.23 lists `too_many_debit_rows`, the 2,000 cap and the 503).
6. **No migration.** `git diff --stat ffbe8d6fd d18ff776d -- db/migrations` is empty.
7. **Error code.** `too_many_debit_rows` is used the same way in the API, BFF, mock, form and i18n. All of them now count every debit (the API and mock count numeric `points` < 0 on the raw rows). The Bangla and English banner texts both dropped the 'tick the overdraw box' advice.
8. **Code fix.** The blocking item from the last review is fixed. putAdjustments locks `debitOutlets` whenever it applies a file that has debits, `allow_overdraw` or not. The balance read, the guard, the flag and the audit `overdrawn[]` (with `balance_before`) all come after that lock. The single POST already locked every debit. The LIKE lookups are replaced by equality probes: `<uuid>:0` for the single post, `ANY(<batch>:0..n)` for a bulk replay. A stored batch of a different size still produces 409.
9. **Shared helper.** The new `is JsonElement` branch in `auditObj` does not change what other modules write. auditObj is `internal` to masterdata. I checked all 14 call sites in AdminLoyalty, AdminOffers, AdminPrices and AsthaTargetUpload. The other modules pass only String, Number, Boolean, Long? and LocalDate, never a JsonElement, so their output is the same.
10. **Tests.** None is weakened.
    - The two new barrier tests can fail. Without the lock, the single+bulk test ends with balance -30 and no flag, which fails both of its branches. The two-bulks test gets zero flagged rows and fails.
    - The existing latch test gained a `readDone` latch, which makes its ordering certain rather than likely.
    - The cap tests now say the opposite on purpose (`allow_overdraw` no longer lifts the cap) and add a 2,001-credit case.
    - The 53200 test now simulates the error code instead of filling the lock table, as the last review asked. It also covers the bulk path now.
11. **Merge onto 77873a8ea.** The two sides share one file: DECISIONS.md. `git merge-tree` reports a content conflict there because both sides append at the end. Keeping both blocks resolves it; nothing else overlaps.
12. **DECISIONS.md.** It is append-only: +13/-0 against the base. D-LOY-API-40 corrects -38 in a new entry and leaves -38 unchanged.

**What I did not verify:** I built and ran nothing, per the brief, so the Kotlin and vitest suites are unrun and the barrier tests' timing on CI is unobserved. I did not regenerate the slices or the TS types; I compared byte sizes and diffs only. I did not check the database server's lock-table size against concurrent 2,000-debit applies.

LOWS:
- The merge onto 77873a8ea is not conflict-free. DECISIONS.md conflicts because both sides append at the end; the train merge has to keep both blocks (there is no merge=union rule in .gitattributes). Nothing else overlaps.
- The ProgrammesSection description still opens with '(... the phone subtracts its own redemptions not acknowledged when it sent the request)' (also in openapi.d.ts). The ACHIEVEMENT RULE now says this as 'a redemption being treated like a memo'. The two agree, but the redemption rule is stated twice, and the intro does not mention the held-redemption clause. Consider pointing the intro at the rule only.
- The 53200 test now raises SQLSTATE 53200 with RAISE EXCEPTION just before the pair lock. It proves the adjDb mapping to 503 and that nothing is posted, for both single and bulk. It no longer proves that a real full lock table produces that code. This is acceptable because the mapping is by SQLSTATE, and the last review asked for it.
- Lock-table load is unverified (same design as D-LOY-API-38, now with allowed debits under the same cap). One apply can take up to 2,000 advisory xact locks from the server-wide shared lock table. Two concurrent maximum-size applies, plus the ledger job, could exhaust it on a small server. The adjustment itself gets a clean 503, but an unrelated transaction, such as a sync upload, could get 'out of shared memory' at that moment. max_locks_per_transaction and max_connections are not set anywhere in infra, so the headroom on the test-account server is unknown.
- In a bulk file, `debitOutlets` includes enrolled outlets that are out of scope. Their rows fail anyway, so they only add locks; this is harmless.
- The new two-bulks test reads the audit row through scalar(), keyed by programme_id and overdrawn_rows=1. That works only if each test's activeProg() creates a fresh programme; I did not run it to confirm.
