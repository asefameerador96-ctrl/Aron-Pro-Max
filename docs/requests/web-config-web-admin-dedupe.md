# Decision: web-config and web-admin duplicates (rule of the lead: the version that reached INT first stays)

| Row | Both built | Reached INT first | Kept | Deleted |
|---|---|---|---|---|
| F-ADM-033 working-day calendar | web-config `/admin/calendar` (weekend days through a config change, holidays, make-up and emergency days); web-admin a holidays entity | web-config (commit 80df816, before 8607a04) | web-config page, linked from the master-data hub (`app/admin/master-links.ts`) | web-admin's holidays entity is already gone from the registry; nothing else to remove |
| F-ADM-023 QC fault types, F-ADM-060 reason tables | web-config `/admin/code-lists` + `/admin/qc-faults`; web-admin `/admin/code-lists/[key]` (17 lists, attrs) | web-admin (commit 8607a04) | web-admin's editor | web-config's editor, view, page, helper, `code-list.put` op and its tests were deleted; the menu entry "QC fault types" now opens `/admin/code-lists/qc_fault_type` |
| F-ADM-046 master-data hub | web-admin | web-admin | web-admin hub | web-config only added the calendar link (`master-links.ts`) |

web-admin: please check that your editor covers the web-config acceptance texts (F-ADM-023: 11 codes, group MFC or MKT, applies_to app or web, reason on every save, 403 for a role without the right; F-ADM-060: Bangla and English labels, retire by `valid_to`, never delete). Checker findings on the deleted editor that apply to yours: an added row needs a Discard button (a blank code blocks the whole save), existing items missing `attrs` must default to the first option, Retire must not set `valid_to` before `valid_from`, never PUT an empty `items` array (CodeListWrite needs at least one), keep `sort` a safe integer.
