# Request to the web lane: the Astha target upload page (lane portal-loyalty-api, 2026-11-12)

Operation `putAsthaTargets`, `PUT /v1/programmes/astha/targets`, contract 1.15.0 (`docs/requests/contract-1.15-loyalty-field.md` section 8). Server side is built and tested
(`AsthaTargetUpload.kt`, `LoyaltyFieldRoutesTest`); this file is what the page needs.

## Who sees the page
SUPERADMIN, and the roles in `cfg.astha.target_entry_roles` (default ADMIN and TSO). A TSO may only upload outlets of its own territory; any other outlet in the file is refused
`403 ERR_FORBIDDEN` with pointer `body.rows[i].outlet_id`. ADMIN needs the `targets` module grant (the path `/v1/programmes/**` is on module `targets` today; the lead may move
the loyalty phone paths to `loyalty`, see the status file).

## Request (closed body)
`{ "batch_uuid": <uuid v4, create it when the file is chosen, keep it for retries>, "quarter": "YYYY-Qn", "change_reason": <10..500 characters>, "rows": [ ... ] }`, at most 50,000 rows.
Row: `outlet_id` (integer), `brand_id` (integer or null: null is the memo-target "All Brand" row), `month` (`YYYY-MM`, one of the quarter's three months), `std_target` (decimal text, up to
3 decimals, STD in base units: sticks or pieces), `memo_target` (integer count), `value_target_mtk` (optional integer milli-taka as TEXT, "0" when left out).
Never negative, never a fraction in milli-taka: the server answers `400 ERR_VALIDATION`. The quarter label is `YYYY-Qn` with the year of the quarter's first month.

## Answers
- `200 { batch_uuid, inserted, updated, unchanged }`. The same `batch_uuid` with the same rows answers the FIRST counts again (retry-safe: keep the uuid until the answer is seen).
- `400 ERR_VALIDATION` with `errors[].pointer` such as `body.rows[12].std_target`, `body.rows[3].value_target_mtk`, `body.rows[7].month` (code `outside_quarter`), `body.rows[7]`
  (code `duplicate`: the same outlet, brand and month twice), `body.rows[2].brand_id` (`not_a_brand`), `body.rows[5].outlet_id` (`not_found`), `body.change_reason`. Light the row named by the pointer.
- `403 ERR_FORBIDDEN` (role, or an outlet outside the territory), `409 ERR_CONFLICT` pointer `body.batch_uuid` (the uuid was used for a different file: make a new one), `401`.
- All-or-nothing: on any error nothing in the file is stored.

## Page behaviour asked for
Show the file's rows in a table before sending; show the server's counts after; a retry after a network error reuses the same `batch_uuid`; a changed file gets a new one. Percentages
elsewhere guard divide-by-zero (the old app showed -37,500%); this page only uploads targets.
