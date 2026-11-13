# Lane android-parity-tso (P-TSO): TSO app parity with the Apsis app (audit PARITY-AUDIT-2026-11-13, "## TSO app")

Branch `lane/android-parity-tso` from INT 4e064555. Only `android/app-tso` and `android/feature-tso` (plus tests and goldens) are touched; no shared module. Nothing is walked on a device in this lane; the lane CI is the proof. The container has no Android SDK, so nothing is compiled or run locally.

Decision key: FIX, RECORD (exists, record every state then compare), KEEP (a standing rule, the manual or an owner ruling overrides the screenshot).

## Slice 1: the six high findings

| Finding | Decision | Commit | Image |
|---|---|---|---|
| Final Submit 23: five cascading dropdowns on every path | FIX: the flat-zone fallback now also draws Wing, Division, Territory, House (disabled, names unreadable offline) above the flat Zone; screenshot tests use the cascade (D-PAR-TSO-02) | slice 1 | `tso_final_submit_pick_bn`, `_review_en`, `_done_en`, `_200_*` re-recorded |
| Final Submit 25: red clear icon on cards with data | FIX the icon (D-PAR-TSO-03); the clear action has no server call: request row 1, the icon does nothing until the host wires it | slice 1 | `tso_fs_s25_*` |
| Drawer header (27, 09, 30) | FIX: no header block, no gear; starts with the Dashboard row; Settings is a plain drawer row above Log Out (lead ruling, D-PAR-TSO-01) | slice 1 | drawer goldens `tso_drawer_*` |
| My Team 28 and Retailer 29: online map with fullscreen and recentre | FIX in code (D-PAR-TSO-06/07): map card with fullscreen and recentre buttons, legend/as-of/refresh/info rows only on the no-network list; RECORD of the online state is not possible in Robolectric (Google map view): request row 2 | slice 1 | none for the online state |
| Visit query 37: text fields only | FIX: Yes/No variant removed, questions in Bangla in both languages (D-PAR-TSO-05); golden `tso_visit_query_bn.png` deleted | slice 1 | `tso_visit_query_3line_bn` |

Also in slice 1 (medium, same screens): Final Submit routes lose the "not due" line and the "Not uploaded" pill (D-PAR-TSO-04; the warning dialog still names routes not uploaded, so no safety is lost); My Team and Retailer extras above.

## Next slices
Medium findings (login, drawer icons, Leave, quick ticket, Final Submit details and Success dialog, My Call, targets, feedback, logout dialog), then the small lows. Rows are added here as they are done.
