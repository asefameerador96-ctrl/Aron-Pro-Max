# Verification: Outlets, photo moves the outlet, maps, team location, PII masking

Batch theme: "Outlets, photo moves the outlet, maps, team location, PII masking".
Entries checked: G-man-016, G-man-017, G-man-033, G-man-034, G-man-056, G-man-058, G-man-059, G-man-062, G-man-079 (plus section 2.7 rows C-06, C-07, C-13, C-14, C-15, C-16, C-19, C-30, C-41, C-51 and inside-manual rows I-22, I-23).
Date of check: 2026-10-04. Method: every cited PDF page was opened as an image and read (SR pp 21-34, 40-42, 53-60, 74-76; AMO pp 8-10, 12, 16-17, 21, 24-26, 32-33, 39-42, 47-66, 74; TSO pp 14-19; Web pp 38-41). Badge digits on AMO pp 8-10 and 47 and the TSO Select Outlets card were re-rendered at 300-900 dpi. Compared against docs/01-13, docs/22, docs/ui-reference and the plan lens files. PDF page numbers below are the PDF's own page index (page 1 = cover), which matches the register's page cites.

## Summary table

| Id | Verdict | One-line reason |
|---|---|---|
| G-man-016 | PARTLY | The "camera with no force-sale step" evidence is a misread: p27, p31 and p42 are the camera step that FOLLOWS Force Sale (p25/p41) on the same out-of-range shop. No page shows a photo for an in-range call. The in-range path is simply never shown. The 50 GB/day scenario has no support. |
| G-man-017 | PARTLY | The sentence "as soon as the outlet photo is taken, the outlet's location information will be updated" is on SR p27/p31/p42 and AMO p26. But the manual never says the stored outlet coordinates change, that it is instant on the server, or that no approval exists. docs/05 and docs/07 genuinely disagree with each other. |
| G-man-033 | CONFIRMED | SR and AMO create/close/info forms pick Cluster, not Route. Corrections: AMO verification forms carry BOTH a Route and a Cluster dropdown; the AMO close/info picker label is "name (code-phone-cluster)", not "name (sub-channel)"; GEO+photo "must" is written only for info change. |
| G-man-034 | PARTLY | Web lifecycle (Pending -> Verify -> Verified -> Reject/Approve, three types, Approve confirm dialog) is on the pages. "AMO app Save = Verify" and "Cancel discards" are inferences; the AMO manual shows neither a status nor a reject. The "contradiction" with docs/07's "Cancel (reject)" is unproven either way. |
| G-man-056 | CONFIRMED | The red badge is on the AMO Outlet tile only, in every AMO home screenshot; the SR home (p75) carries the Task Delegation badge. The proposed badge definition (sum of the three hub badges) is NOT supported: the home badge reads a different number from the hub badges. |
| G-man-058 | CONFIRMED | Both manuals call it "live location" (AMO p8, p33; TSO p15) and show a Google map pin with no timestamp or age. The spec says last synced fix. Winner (spec, with an age label) is sound. |
| G-man-059 | PARTLY | Spec silence is real, but "provider undecided" misses that the manuals identify the current provider: AMO p33 and p65 name "Google Maps" in the callouts and show the Google watermark. The TSO My Teams map is 3D; the TSO Outlets map and the AMO maps are flat. |
| G-man-062 | CONFIRMED | AMO SR list shows the SR phone as 11 asterisks (p40, p41); docs/07 says the list shows phone. Corrections: only the SR phone is masked, retailer phones stay visible in AMO picker labels; the lifted-stock page has no date control or unit column. |
| G-man-079 | PARTLY | No outlet cap, no search/select-all and no repeat-Set-Plan behaviour is shown (that part holds). But "the card shows address, not cluster" is unsupported: the second line on the card has no label, and the spec's field list (name, code, cluster, owner, phone) matches the card exactly if that line is the cluster. C-19 is probably a false contradiction. |

---

## G-man-016: Is an outlet photo required on every call, or only on Force Sale?

**Claim (register).** Section 1 row: kind underspecified, severity major. Cites SR S-24 p27 and p31 (camera with no force-sale step) against p25, p41, p42. Spec: docs/05 photo only on out-of-range Force Sale; docs/04 photo budget 100-200 KB; docs/22 P-01 3.5 lakh calls/day. Fix: confirm on the live app; if every call needs a photo the budget changes by about 50 GB/day (3.5 lakh x 150 KB); default to the spec behind `cfg.sale.outlet_photo_every_call`.

**What I saw.**
- SR p22: "Sale" opens an outlet picker (alphabet strip), callout: choose the shop, then tap "এগিয়ে যান".
- SR p23 and p24: shop "Moin Store (DHK-344-005-[phone]-Apsis Cluster)" with Sale History / Points buttons and a red-triangle message "আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।" (You are not within the selected retailer's range) plus two buttons "ফোর্স সেল" and "রিফ্রেশ".
- SR p25 (title ফোর্স সেল প্রক্রিয়া): callout "From the specified outlet, if outside the set range, you will see the Force Sale option... click Refresh to update the location information". After Force Sale: "আপনার ফোর্স সেলের কারণ কী?" with two buttons "ইন্টারনেট সমস্যা" and "লোকেশন চেঞ্জ". Callout: "after choosing one, the Camera opens to take the outlet's photo". Yellow note: the phone's location must be on.
- SR p26: Android camera and microphone permission prompts ("Allow ARON SR to take pictures and record video?", "Allow ARON SR to record audio?").
- SR p27 (title DRP সংগ্রহ প্রক্রিয়া): camera viewfinder of a shop front; callout "take the photo of the specified outlet, and as soon as the photo is taken, the location information for that outlet will be updated". Right phone: same Moin Store screen now with "আপনি কি কল শুরু করতে চান?" (Do you want to start the call?) Yes/No and "Collect DRP Discount" / "এগিয়ে যান" in the footer.
- SR p31 (title সেল প্রক্রিয়া): the same camera screenshot (identical photo and callout) and the same start-call dialog (Moin Store). SR p41 (title জিরো বিক্রয় প্রক্রিয়া) shows the Force Sale reason screen (same as p25) and p42 the same camera + start-call (Moin Store) with "এগিয়ে যান" highlighted.
- The only in-range sale screens are Savar Metro (p30, p34): SKU entry with no photo, no geo warning and no start-call dialog visible. Nothing on any page shows a camera for a shop that is in range.

**Finding.** p27, p31 and p42 are not "camera with no force-sale step". They are the continuation of the Force Sale chain (p25 states the camera opens after the reason; p26 is the permission prompt; p27 is the photo). The manual reuses one screenshot set for three walkthroughs. Every camera screenshot is on Moin Store, which p23-p25 show as out of range. No page shows, or hints at, a photo requirement for an in-range call. What is true is that the manual never walks through an in-range call start, so the in-range path is unobserved.

**Spec quotes.**
- docs/05: "Out of range or no fix → Force Sale. The SR picks a reason (`internet_problem` or `location_change`) and takes an outlet photo."
- docs/06 step 4b: "In range → start call. Out of range → **Force Sale** (reason + outlet photo) or Refresh."
- docs/04: "Camera/photos: capture, compress (target ≤ 100–200 KB/photo, long edge ~1024 px), queue, release."
- The spec also lists no "start call? yes/no" confirmation (register C-04 covers that separately).

**Verdict: PARTLY.**

**Corrected statement.** The manual shows the outlet photo only inside the Force Sale path (p25 reason -> p26 permission -> p27/p31/p42 photo -> "start call?"). It shows no in-range call start at all, so it neither requires nor excludes a photo for in-range calls; the cited pages are not evidence of an every-call photo. Docs/05 stands. The 50 GB/day figure is a hypothetical with no page behind it. The "start call?" dialog does follow the photo in these screenshots; for an in-range call it is unobserved.

**Build implication.**
- No schema change. Keep `visit.photo_validated` separate from `visit.geo_validated` (docs/05).
- Downgrade: severity major -> minor, phase P2 -> P3. The live-app check is one question for the pilot ("does an in-range call ever open the camera?"), not a budget risk.
- `cfg.sale.outlet_photo_every_call`: optional inert switch, default false; do not size the media queue or docs/04 budget for it.
- UI: reason picker has exactly two choices on the SR app ("ইন্টারনেট সমস্যা", "লোকেশন চেঞ্জ"), matching docs/05 `internet_problem` / `location_change`; the camera permission also asks for microphone (Q15 stands).

---

## G-man-017: Photo capture (Force Sale, Manual Override) overwrites the outlet location immediately

**Claim (register).** Kind contradiction, major. SR S-24 p27/31/42, AMO S-11 p21, S-13 p25, S-14 p26: Force Sale and Manual Override overwrite the outlet's stored location the moment the photo is taken, with no approval. Spec: docs/05 "routed through the normal outlet-change/verification flow"; docs/07 override "updates location"; `cfg.geo.outlet_location_change_approval`; not on docs/13 "deliberately changed" list. Winner: spec (integrity); photo raises a location-change request, provisional device-side fix, AMO override rules (`cfg.geo.override_max_per_day`).

**What I saw.**
- SR p27, p31, p42 and AMO p26 carry the identical callout: "এরপর নির্দিষ্ট আউটলেটের ছবি উঠাবেন, এবং ছবি উঠানোর সাথেই নির্দিষ্ট আউটলেটের জন্য লোকেশনের তথ্য আপডেট হয়ে যাবে" = "then take the photo of the specified outlet, and along with taking the photo, the location information for the specified outlet will be updated".
- AMO p21 (labelled Control Call but showing the Joint Call screen, I-14) and p25 (Control Call) show the red message "AMO এবং রিটেইলার রেঞ্জের মধ্যে নেই।" (AMO and retailer are not within range) with two buttons "ম্যানুয়াল ওভাররাইড" and "রিফ্রেশ". AMO p26 (ম্যানুয়াল ওভার রাইড প্রক্রিয়া): camera, then the Control Call menu (Sale, SR Perf. Assessment, Survey) is available. No reason picker. The camera has a close "x"; its effect is not shown.
- AMO Update Base (p64-p65): confirm dialog "আপনি কি নিশ্চিত, খুচরা বিক্রেতার অবস্থান আপডেট করতে?" with "আপডেট" and "বাতিল", then the camera, then a map titled "অবস্থান নিশ্চিত করুন" with a "নিশ্চিত করুন" button. Callout: after the photo, pick the outlet's location from the Google Maps option and confirm; this completes the base-location update.
- No page shows a pending state, an approval, a "request sent" message or any reference to the web panel for a Force Sale or Manual Override photo. The Outlet Approval Panel (Web p40) handles only New Outlets / Close Outlets / Info Changes (no location-change type).

**Finding.** The manual does state that the outlet's location info is updated when the photo is taken. It does not say (a) that the stored outlet master coordinates are overwritten (it could mean the visit's captured location), (b) that this is instant on the server, or (c) that there is no approval. What can be said: the UI offers no approval step, and the identical sentence is used for the explicit base-location update flow, so the intent is a master-data location refresh.

**Spec quotes.**
- docs/05: "The outlet photo updates the outlet's location (a correction path for moved/mislocated shops), routed through the normal outlet-change/verification flow so it can't be abused silently."
- docs/07 Control Call: "'AMO not within retailer range' → Manual Override (outlet photo, which updates location) or Refresh."
- docs/13 "Things we deliberately changed from Apsis": no entry for outlet location.
- The spec is internally inconsistent: docs/05 says routed through verification; docs/07 says "updates location".

**Verdict: PARTLY.**

**Corrected statement.** SR p27/p31/p42 and AMO p26 say the outlet's location information is updated as soon as the photo is taken; nothing on the pages says whether that overwrites the stored outlet coordinates or only stamps the visit, and nothing shows an approval or pending step (absence of UI, not proof of absence on the server). The conflict with docs/05 is real at spec level (docs/05 vs docs/07 disagree with each other). Treat the Apsis behaviour as "photo refreshes the outlet's location with no visible approval" and verify against the dump (Q18) by comparing outlet coordinate changes with force-sale visit dates.

**Build implication.**
- Keep the register's resolution (constraint wins, record as a deliberate decision, D-id to be assigned): photo + fix on Force Sale and Manual Override create a `location_change_request` (purpose force_sale / manual_override) instead of overwriting `outlet.latitude/longitude`; the visit proceeds (photo_validated true, geo_validated false) and is flagged for the TSO.
- Device side: the provisional fix for the SR's later in-range checks (same cost-saver as the register) so an SR is not forced to Force Sell at that shop daily; overwrite immediately only when the outlet has a missing or placeholder location (docs/22 P-09: 1,093 missing, 34,454 on 11,222 shared points).
- Add to docs/13 "deliberately changed" so AKTCL signs off. Resolve the docs/05 vs docs/07 wording in one place (docs/07 should say "raises a location correction").
- AMO Manual Override: no reason picker on the page (SR has two reasons); if a reason is wanted it is an addition. `cfg.geo.override_max_per_day` and a supervisor-visible override count are additions, labelled as such.
- Config: `cfg.geo.outlet_location_change_approval` (default required), `cfg.geo.override_max_per_day`.

---

## G-man-033: Outlet new / close / info-change forms select Cluster, not Route (SR and AMO)

**Claim (register).** Kind contradiction, major. SR S-46..49 p53-60, AMO S-40..42 p55-62, AMO S-34 p48-50 (route + cluster pre-filled). Spec: docs/06 "route, shop name, owner, mobile"; docs/07 and F-AMO-025..027 "as F-SR-037..039". Fix: cluster pickers; derive route_id; outlet picker filtered by cluster labelled "name (sub-channel)"; 11-digit BD mobile; GEO + photo mandatory on new and info; new shop saves with no confirm ("সফল"); close and info confirm; closure guard for open dues.

**What I saw.**
- SR p54 New Shop: "ক্লাস্টার নির্বাচন করুন" dropdown ("Molobe Para"), shop name, owner name, mobile ([phone], 11 digits), "GEO এবং ছবি ধারন করুন" button, "সংরক্ষণ" button. No route field. p55: after the photo, Save, a "সফল" dialog with OK; no confirm step.
- SR p56 Close: cluster dropdown, then "আউটলেট নির্বাচন করুন" dropdown with label "Sifat St (Diamond)" (name + sub-channel), a read-only block (shop name, owner, mobile [phone] shown without the leading 0), Save. p57: confirm "আপনি কি নিশ্চিত? এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" with yes/no, then "সফল".
- SR p58-p60 Info change: cluster, outlet ("Ebraj St (Diamond)"), editable name/owner/mobile, GEO+photo button. Callout: after changing, "you must select 'GEO এবং ছবি ধারন করুন'" (the word "হবে" = must). Photo done -> dialog "ছবি ধারণ করা সম্পন্ন হয়েছে". Save -> confirm "আপনি কি নিশ্চিত? এই পরিবর্তন সংরক্ষণ করা হবে" -> "সফল".
- AMO own operations p56-p62: New shop (p56-p57) has a cluster dropdown only (no route), name, owner, mobile, GEO+photo, Save; success text is "ডেটা সফলভাবে সংরক্ষিত হয়েছে" (not "সফল"). Close (p58-p59): "ক্লাস্টার নির্বাচন করুন" + "রিটেইলার নির্বাচন করুন" with label "Kamal Store (1618408-[phone]-Madrasa Road)" (name-code-phone-cluster) and a read-only block; confirm "এই দোকানটি স্থায়ীভাবে বন্ধ হতে যাচ্ছে" then success. Info change (p60-p62): cluster, retailer "Sujon Store (1618414--Madrasa Road)", editable name/owner/mobile, GEO+photo ("ছবি ধারণ করা সম্পন্ন হয়েছে"), Save -> confirm "এই পরিবর্তন সংরক্ষণ করা হবে" -> "ডেটা সফলভাবে সংরক্ষিত হয়েছে".
- AMO verification forms (p48 new, p52 close, p54 info): carry BOTH "একটি রুট সিলেক্ট করুন" (route, e.g. "Savar BazarDaily") and "ক্লাস্টার নির্বাচন করুন" (cluster, "Madrasa Road") dropdowns, plus the AMO-only Sub-Channel and Geo Classification selects (p48). The request cards on p48/p51/p53 list Route, Cluster, retailer name, owner, contact.
- No pending-state UI anywhere in the SR manual after Save.

**Spec quotes.**
- docs/06: "**New shop:** route, shop name, owner, mobile → "Capture GEO & photo" → Save." "**Permanently closed:** route, outlet → Save → confirm." "**Info change:** route, outlet, edit name/owner/mobile → must re-capture GEO + photo → Save." "All three go to the AMO for verification, then web approval. The SR's app shows pending state."
- docs/07: "**AMO own ops:** New shop; Permanent close; Info change; **Update Base** ..."

**Verdict: CONFIRMED** (core right; three details to correct).

**Corrected statement.** SR and AMO new/close/info forms pick a Cluster; the SR forms have no route field and the AMO own-operations forms have none either. The AMO verification forms for SR requests show both a Route and a Cluster dropdown, and the Web Outlet Approval Panel lists both (Web p40), so both ids exist on a request. Corrections to the fix: (1) the outlet picker label is "name (sub-channel)" on the SR app but "name (outlet code-phone-cluster)" on the AMO app; (2) "GEO + photo mandatory" is written only for info change ("must", SR p59, AMO p60); for new shop the callouts say "after input, click GEO and photo" and the Save button is visible without it, so enforcement is unobserved; (3) the SR new-shop success text is "সফল" but the AMO new-shop success is "ডেটা সফলভাবে সংরক্ষিত হয়েছে"; (4) the close and info confirm texts quoted are exact on both apps.

**Build implication.**
- Schema: `outlet_change_request` gets `cluster_id` next to `route_id` (nullable `route_id` for SR/AMO-created requests; the AMO verification step has a route dropdown, so the AMO can set it when verifying SR requests; AMO-created new outlets have no route field, Q-31 stays open).
- UI: replace route pickers by cluster pickers on SR and AMO forms; the outlet picker label differs by role (SR: name + sub-channel; AMO: name-code-phone-cluster). Keep the AMO verification form's route + cluster + sub-channel + geo-class fields.
- Rules: same confirm dialogs as the manual (close, info); new shop has no confirm on the SR app. Mobile validation (11 digits) and mandatory GEO + photo on new are additions (the manual only shows 11-digit samples and a Save that appears usable before the photo); label them as improvements. Closure guard `cfg.outlet.close_block_if_dues` stays an addition (the manual shows no guard).
- Strings: localise the four dialog texts (confirm title "আপনি কি নিশ্চিত?", close and info bodies, "সফল", "ডেটা সফলভাবে সংরক্ষিত হয়েছে", "ছবি ধারণ করা সম্পন্ন হয়েছে"), and keep the English "Data Updated Successfully" seen on AMO p52/p54 as a known inconsistency (one localised string).
- Note p50 shows a request card whose route is "Court Bari(Sun, Tue, Thu)" (route names may embed visit days, as in "Savar Bazar(Sat, Mon, Wed)" on SR p74): never key on route name.

---

## G-man-034: Outlet request lifecycle (verify in app or web, reject only on web, approve on web; all three request types)

**Claim (register).** Kind contradiction, major. AMO S-34/37/39 p48-54, Web S-38..40 p39-41. Spec: docs/07 "Cancel (reject) / Save (approve)"; docs/09 Outlet Approval Panel "pending new-outlet requests + who verified"; docs/03 `verified_by` (AMO) / `approved_by` (web). Fix: AMO Save = Verify (Pending -> Verified); Reject and Approve exist only on Verified rows on the web; "বাতিল" discards with no state change (confirm on live app); one panel for New, Close, Info via an Outlet Type filter; record verified_via; reason box on Reject; confirm dialogs for Verify and Reject (texts unknown); approving a closure sets status closed (never delete).

**What I saw.**
- AMO p47 hub: caption "এস আর থেকে আশা অনুরোধ গুলো যাচাই করুন" (Verify requests coming from the SR); three tiles "নতুন আউটলেট যাচাইকরণ", "আউটলেট বন্ধের যাচাইকরণ", "আউটলেট সংশোধনের", each with a red badge that reads ২ at 400 dpi (2/2/2). Below, the AMO's own operations (four tiles, no badge).
- AMO p48 (new), p51-p52 (close), p53-p54 (info): a list of request cards (Route, Cluster, retailer name, owner, contact), then a form with buttons "বাতিল" (red) and "সংরক্ষণ" (green). The Cancel icon is a floppy disk on p49, p50, p54 and an x-in-circle on p52 (I-23). Callouts explain only Save ("after clicking Save an alert appears, click Yes" on p52/p54); no callout explains "বাতিল". After Save: "ডেটা সফলভাবে সংরক্ষিত হয়েছে" (new, p50) or "Data Updated Successfully" (close p52, info p54). No status word (Verified/Approved/Rejected) appears in the AMO manual. No reject button or reason box.
- Web p39 SR Outlets Reports: Report Category New Outlets / Close Outlets / Info Changes; table with Outlet Name, Owner, Phone, Latitude, Longitude and a Status column showing "Approved". Callout: "to see Approved & Rejected outlet lists, click SR Outlets Reports". So a Rejected status exists in the data.
- Web p40 Outlet Approval Panel (sheet header "Firefly Outlets Reports"): filters Wing/Division/Territory/House/Zone + "Outlet Type" (New Outlets / Close Outlets / Info Changes), "Get Data" and "Get Excel". Columns: Division Code ... Route, Route Code, Cluster, Owner Name, Phone Number, Latitude, Longitude, Status, Actions (no Outlet Name column). Rows: two with Status "Pending" and one button "Verify"; one with Status "Verified" and two buttons "Reject" (red) and "Approve" (green). Callout: Verify/Approve/Reject from the last column.
- Web p41: confirm dialog "Approve outlet request? Do you want to approve this request?" with "Yes, approve it" / "Cancel". No dialog is shown for Verify or Reject.

**Spec quotes.**
- docs/07 Outlet: "On verify, the AMO also sets **Sub-Channel + Geo Classification**, can re-capture GEO + photo → Cancel (reject) | Save (approve). → updates `outlet_change_request.status`, then web approval."
- docs/06: "All three go to the AMO for verification, then web approval."
- docs/09: "**Outlet:** SR Outlets Reports (created/changed), Outlet Approval Panel (pending new-outlet requests + who verified)."
- docs/03: `outlet_change_request` ... "`requested_by` (SR), `verified_by` (AMO), `approved_by` (web), status".

**Verdict: PARTLY.**

**Corrected statement.** On the Web manual the lifecycle is explicit: a Pending row has a "Verify" button; a Verified row has "Reject" and "Approve"; Approve asks "Approve outlet request?"; one panel serves New, Close and Info through an Outlet Type filter; the SR Outlet Reports show Approved and Rejected statuses. The web panel can Verify, so verification is possible on the web as well as in the AMO app. The AMO app shows only Save and Cancel (no reject, no status). The register's three inferences are not on the pages: (a) that the AMO Save sets "Verified" (no status shown), (b) that "বাতিল" discards without a state change, (c) that the AMO cannot reject. The claimed contradiction with docs/07 "Cancel (reject)" is unproven either way: the pages do not prove Cancel is not a reject. The real delta is additive: docs/09 omits the web Verify step, the Reject/Approve actions on Verified rows, the Close/Info types, and the Approve confirm dialog.

**Build implication.**
- State machine: `outlet_change_request.status` = pending -> verified (AMO app Save or web Verify) -> approved | rejected (web only). Keep `verified_by`, `approved_by`, add `verified_via` (app/web) and `rejected_by`/`reject_reason`. Whether AMO Cancel rejects is an open question for the pilot (add to the manual questions, Q-29 already covers it); until confirmed implement Cancel = discard the form without a server call.
- Web panel: Outlet Type filter (three values), columns as in Web p40 plus Outlet Name, request date, requester and "verified by" (additions), Verify / Reject / Approve with confirm dialogs (only the Approve text is evidenced: "Approve outlet request? / Do you want to approve this request? / Yes, approve it / Cancel"; author the other two and label them as authored). The manual's header "Firefly Outlets Reports" is a vendor string, do not reproduce (I-37).
- Effect of approve: new -> assign outlet code and make visible in the route; close -> `outlet.status = closed` (never delete, docs/22 P-08); info -> apply the proposed JSON. Rejected requests: show a state to the SR (the manual shows none; addition).
- Scope: verify and approve visibility derived server-side from the user's scope (CLAUDE.md #4).
- Config: `cfg.outlet.verify_roles`, `cfg.outlet.reject_requires_reason` as additions.

---

## G-man-056: AMO dashboard badge sits on Outlet only, not on Task Delegation

**Claim (register).** Kind contradiction, minor. AMO S-06/S-32 p8-10, 12, 47, 51, 53, 66, 74. Spec: docs/07 "Task Delegation (badge = pending SR requests) ... Outlet (badge)"; F-AMO-001 repeats it. Fix: remove the Task Delegation badge from the AMO tile set (it belongs to the SR app); define the Outlet badge as the sum of the three hub badges, always shown with its number.

**What I saw.**
- AMO home grids on p8, p9, p10, p12, p17, p21, p25, p39-p42, p47, p55, p66 and p74 (all that I opened): there is one small red badge on the Outlet tile (third column, third row). The Task Delegation tile (third column, second row) carries no badge in any of them. AMO p8 describes Task Delegation as "view your team's Assigned Tasks as a list and assign tasks" with no mention of a count.
- SR p75 (Task Delegation): the SR home shows a red badge "২" on the "টাস্ক ডেলিগেশন" tile; callout "you can see the number of tasks assigned to the SR user from the AMO as a notification". SR p74 (Tutorial walkthrough, home grid with no pending tasks) shows no badge.
- Badge digit on the AMO home: renders blurred and tiny at 900 dpi (p9, p47); the inventory reads it as ৮ on p8-p10 (user amo5756) and ০ on later pages (user amo5001). The hub tiles (p47) read ২, ২, ২ clearly at 400 dpi. So the home badge value (৮ or ০) does not equal the sum of the hub badges (6).
- The Outlet badge appears even when its value seems to be ০ (red dot kept).

**Spec quotes.**
- docs/07 Home: "Tiles: Attendance, Joint Call, Control Call, Team Location, Team Performance, Task Delegation (badge = pending SR requests), Live Dashboard, SR Stock, Outlet (badge), ..."
- docs/06 Home: "Task Delegation (badge)" (SR, correct).

**Verdict: CONFIRMED** (core). The proposed definition is not supported.

**Corrected statement.** The AMO home carries a badge on the Outlet tile only; Task Delegation has none on any AMO page, while the SR Task Delegation tile has the numeric task-count badge (SR p75). The docs/07 phrase "Task Delegation (badge = pending SR requests)" is a misplacement: "pending SR requests" are the outlet verification requests. The Outlet badge definition is NOT derivable from the manual: the home value (৮ or ০) disagrees with the hub badges (২+২+২=6) and the new-shop list shows 4 cards against a badge of 2 (I-22). Treat the definition as unknown; implement "count of pending verification requests in the AMO's scope" as the working definition and confirm with a live account.

**Build implication.**
- UI: AMO home Outlet tile badge (count of pending requests across new, close, info, from the bundle/delta), no Task Delegation badge on the AMO; SR keeps the task-count badge.
- Display rule: show the badge only when the count is above zero (an improvement; the manual shows a dot even at ০, which is probably a bug: label as a deliberate change).
- Fix docs/07 wording and F-AMO-001. Open question: what the home value counts (add to the manual questions list).

---

## G-man-058: Team Location is "live" in the manuals, last-synced fix in the spec (AMO and TSO)

**Claim (register).** Kind contradiction, major. AMO S-24 p33, TSO S-13 p15. Spec: docs/07/08 "last synced fixes; not continuous tracking - battery"; CLAUDE.md #3. Fix: keep last-synced fix with "last seen HH:MM (n min ago)" and source; grey out past `cfg.tso.team_location_max_age_min`; list fallback offline; optional on-demand ping; add to docs/13 "deliberately changed"; check what Apsis records (Q18/Q52). Winner: spec.

**What I saw.**
- AMO p8 (dashboard legend): "টিম লোকেশন - আপনার টিমের SR দের লাইভ লোকেশন দেখার জন্য টিম লোকেশন বাটনে ক্লিক করুন" (to see your team's SRs' live location, click Team Location).
- AMO p33 (টিম লোকেশন দেখার প্রক্রিয়া): a screen titled "টিম লোকেশন" with an SR dropdown (the list shows "SR-Kakoli - 1" twice) and a Google map; callout: "after selecting the SR you can see that SR's name and the SR's live location on Google Maps". The map shows one scooter-icon marker with a name label ("SR-Kakoli - 1"). No timestamp, no "last seen", no accuracy circle, no refresh button.
- TSO p15 (My Periphery): callout "In the My Periphery menu you will get the live location of all the TSO's SRs and the retailers' locations. My Team: route-wise live locations of all SRs. Retailer: retailer locations by radius per Zone." The My Teams screen shows a zone dropdown and a map (3D buildings) with one scooter marker; no timestamp. The Outlets screen has a Zone dropdown and a Radius list (50, 100, 300).
- Nothing on any page states how the location reaches the server (continuous tracking, per visit, per sync).

**Spec quotes.**
- docs/07 Team: "**Team Location:** SR list → live locations on a map. (Uses the SRs' last synced fixes; not continuous tracking — battery.)"
- docs/08 My Periphery: "**My Team:** zone → live SR locations on a map (last synced fixes)." "**Retailer:** zone + radius (50 / 100 / 300 m) → outlets around the TSO's current location on a map."
- CLAUDE.md #3: "no continuous GPS; sample location on demand".

**Verdict: CONFIRMED.**

**Corrected statement.** The manuals describe Team Location as "live location" (AMO p8, p33; TSO p15) and show one marker per SR with no age or source. The spec's last-synced fix is a deliberate softening. The pages do not reveal how fresh the position is, so "live" may in practice already mean "last position the server holds"; this is the question to put to the Apsis dump (Q18, Q52). One adjustment to the register: the markers show a name but no time, so the "last seen HH:MM" label is an addition, not parity.

**Build implication.**
- Keep: last-synced fix (check-in, visit, sync) with an age label and source; grey beyond `cfg.tso.team_location_max_age_min` (apply to AMO too); list fallback when offline; no continuous tracking. Record in docs/13 "deliberately changed" for AKTCL sign-off; D-id to be assigned.
- Schema: `user_last_fix` (user_id, lat, lng, accuracy_m, captured_at, source) updated by attendance, visit and sync. Endpoint F-API-023 returns it with age.
- UI parity: SR dropdown (route/SR display name), one marker with name label, Google-style map; add age text and an "as of" in the label.

---

## G-man-059: Map and geocoding provider is undecided (AMO Team Location/Update Base, TSO My Team/Retailer, Attendance address)

**Claim (register).** Kind feature, major. AMO S-08, S-24, S-44 p17, 33, 65; TSO S-13/14 p15. Spec: docs/07 "on a map"; plan F-AMO-028 "map tiles (cached?)"; docs/04 2 GB/day; no provider, key, cost or offline decision anywhere. Fix: decide Google Maps SDK (key in Key Vault, restricted by package and signing SHA, billing alert) vs MapLibre/OSM; either way 2D only (the TSO map shows 3D buildings), lite mode, load a map only when its screen opens, bounded tile cache (`cfg.map.tile_cache_mb`), no map on dashboards; offline Update Base accepts the current fix and lets the user adjust numerically; Attendance address degrades to coordinates; quota/cost for 1,051 AMOs plus TSOs.

**What I saw.**
- AMO p33: callout says "... live location on Google Maps"; both maps carry the Google watermark (bottom-left) and Google's building-footprint style, with an "open in Maps / directions" icon pair bottom-right on the right-hand screen.
- AMO p65 (Update Base): after the photo, a map titled "অবস্থান নিশ্চিত করুন" with a centered avatar pin and a full-width "নিশ্চিত করুন" button; callout: "after taking the outlet's photo, select the outlet's location from the Google Maps option and click Confirm". Google watermark visible.
- TSO p15: My Teams map is a tilted 3D buildings view; the Outlets map is a flat zoomed-out Dhaka view in Google style (Bangla and English labels, red hospital icons).
- AMO p17 (Attendance): the address line "4th Floor), 28, 4th Floor), Gulshan, Dhaka, Dhaka District, Dhaka Division, 1213, Bangladesh" next to a refresh icon; callout says the user sees the current location and presses Refresh if there is a problem. The string pattern (name, street, sub-locality, locality, district, division, postal code, country, with a duplicated component) looks like a joined Placemark from a device reverse-geocoder, not a single formatted address; inference only. It requires the network (reverse geocoding), so Attendance is not fully offline for the address line.
- Nothing shows offline behaviour, tile caching, a key, quota or cost.

**Spec quotes.**
- docs/07: "**Team Location:** SR list → live locations on a map." and "**Update Base** (base-location recalibration: route + outlet → confirm → outlet photo → pick exact point on the map → Confirm)." and "Attendance shows reverse-geocoded current address + refresh."
- docs/08: "outlets around the TSO's current location on a map". docs/09: "SR positions on a map" on the web dashboard. docs/02, docs/04, docs/13: no map provider, SDK, key, cost, tile or offline decision (grep for map/tile/geocod finds only the lines above).

**Verdict: PARTLY.**

**Corrected statement.** The spec has no map-provider decision (true). But the manuals show the current provider: Google Maps in the AMO Team Location and Update Base screens (named in callouts on p33 and p65, watermark on both), with the TSO maps in the same style. So parity baseline = Google Maps; the MapLibre/OSM option is a deliberate change that must be argued (cost, offline), not a free choice. The 3D claim is right for the TSO My Teams screen only; the TSO Outlets map and the AMO maps are flat. The reverse-geocoded Attendance address is a separate online-only dependency, and its provider is not identifiable from the manual.

**Build implication.**
- Decision (new Q in docs/13 plus a D-id): default Google Maps SDK (parity); key in Key Vault, restricted by package name and signing SHA-1, billing alert; map loaded only when its screen opens (AMO Team Location, Update Base, TSO My Team, TSO Retailer); no map on dashboards. 2D, lite mode on low-end phones; no 3D buildings (a deliberate change).
- Keep MapLibre/OSM as the fallback if cost or the offline requirement (docs/04) blocks Google; add `cfg.map.provider`, `cfg.map.tile_cache_mb`, `cfg.map.lite_mode`.
- Offline: Update Base accepts the current fix and numeric adjustment when no tiles; Attendance address degrades to coordinates plus the zone name; reverse-geocode on the server at sync for reports, not on the device.
- Budget a worst-case map session in docs/04; count cost for ~1,051 AMOs (one per zone, docs/22 P-13) plus 291 territories' TSOs.

---

## G-man-062: AMO SR Stock: phone masking, SR identifier, lifted-stock period and total

**Claim (register).** Kind contradiction, minor. AMO S-30/31 p40-41, R-070/071, U-38/59. Spec: docs/07 "SR list (code, route, phone, total outlets)"; lens-security app_user.phone visible to supervisors in scope; `cfg.pii.mask_style`. Fix: adopt the manual (mask SR phones in the AMO view, `cfg.pii.mask_style = hide`), define lifted stock = sum of today's issue movements per SKU for the SR's route (optional date picker), drop the mixed-unit grand total or show per category (sample 58,050 sums sticks, pieces, boxes), define the SR display-name rule ("SR-12237", "SR-Kakoli - 1", sr334001).

**What I saw.**
- AMO p40: "এসআর লিস্ট" with rows "SR-12237 / SenbagDaily / [phone icon] ***********" and "মোট আউটলেট 78"; every row shows 11 asterisks in place of the phone. Callout: "from here you can see the SR list".
- AMO p41: tapping a row opens "লিফটেড স্টক" with the same header (SR-12237, SenbagDaily, masked phone, total outlets 78) and a two-column table "এসকেইউ | ইস্যু" with 8 SKU rows (MaxR-10S 10,000; Avon-20S 11,000; ARIS-O-20s 11,000; SAB-12s 9,000; EAB 13,x50; Aster 1,000; FB 1,500; SL 1,200) and a total row "মোট ৫৮,০৫০". There is no date picker, no unit column and no category subtotal on this screen. The callout says "lifted stock by SKU".
- Identifiers on three screens: the SR list shows the code-style "SR-12237" plus the route name (not the person's name); Team Location shows "SR-Kakoli - 1" (p33); the SR home shows "SR - Testing Banani (sr334001)" or "SR-16858 (sr16858)" (SR p22, p74). Retailer phones are not masked in AMO pickers: "Babu Store (1618411-[phone]-Madrasa Road)" (p21) and on the request cards (p48, p51, p53: "Contact [phone]").
- The sum check: rows read as 10,000 + 11,000 + 11,000 + 9,000 + 13,x50 + 1,000 + 1,500 + 1,200 reach 58,050 only if the EAB value is 13,350; the exact digit is not certain at this resolution but the total is internally consistent with eight SKUs.

**Spec quotes.**
- docs/07 SR Stock: "**SR Stock:** SR list (code, route, phone, total outlets) → lifted stock by SKU + total."
- docs/03: "PII note: NID, TIN, trade licence and phone are sensitive. Expose only to roles that need them (see docs/09)." docs/09 Rules: "PII: outlet NID/TIN/phone returned only to roles that need them."

**Verdict: CONFIRMED.**

**Corrected statement.** The AMO SR list masks the SR's phone as a fixed 11 asterisks (p40, p41); docs/07's list says it shows the phone. Three refinements: (1) only the SR's phone is masked; retailer phones are shown unmasked on the AMO app (picker labels, request cards, verification forms), so the masking rule is "SR phones hidden from AMO", not a blanket phone mask; (2) "lifted stock" has no period control or unit column on the page, only an "ইস্যু" (issue) heading and a total that adds SKU rows of different categories (SKU codes span cigarette, bidi and Aster/FB/SL), so the mixed-unit total is plausible but the units are not shown; (3) the SR list identifier is "SR-<number> + route name", different from the person-name label of Team Location and the "<name> (<username>)" label of the SR home.

**Build implication.**
- Rule: field-role phone masking by consumer (cfg.pii.field_roles / `cfg.pii.mask_style = hide` for SR phones on AMO views); the server returns no SR phone to the AMO (not just a masked string): fix lens-security row "app_user.phone visible to supervisors in scope" for the AMO. Decide separately whether TSO and web see it.
- Retailer phone remains visible to SR/AMO/TSO on route-scoped screens (docs/09 "only to roles that need them" is satisfied); do not extend masking to outlet labels (parity with the SR label "name (code-phone-cluster)" in docs/06 and the pickers).
- UI: SR Stock list = SR id + route name + masked phone + total outlets; lifted stock = per-SKU issue for the day (define: sum of issue movements for the business date; the manual shows no date control; optional date picker is an addition) and a total per category rather than one mixed grand total (a deliberate improvement; parity total optional behind a flag).
- Display-name rule table: SR list "SR-<code> + route"; Team Location "<name> - <n>"; home "<name> (<username>)".

---

## G-man-079: Set Plan: outlet card shows address (not cluster), invented 30-outlet cap, repeat Set Plan

**Claim (register).** Kind contradiction, minor. TSO S-19/20 p18, R-38/39, U-23. Spec: docs/08 "name, code, cluster, owner, phone"; lens M-35 UNIQUE (planner_id, plan_date, route_id); `cfg.tso.visit_plan_max_outlets = 30` (assumption); docs/22 P-12/P-13. Fix: card shows address when present else cluster (address blank for all but 13 of 734,789 outlets); default the cap to none (median route 64 outlets, max 214), keep configurable; add search and select-all/clear (improvement); repeat Set Plan for the same date and route as a union by outlet, idempotent by client uuid; no un-planning; plan date today or later (assumption). Winner: manual.

**What I saw.**
- TSO p18 (My Call): Set Plan form with "Select Date" (April 27, 2026), "Select Zone" (Banani - Test), "Select Route" (placeholder) and "Show Outlet". Callout: choose date, zone, route, click Show Outlet; then select outlets and click Set Plan; later complete visits from My Visit Plan.
- "Select Outlets" list (same page, read at 300 dpi): each card has three lines with no labels: (1) bold shop name; (2) "<7-digit code> | <place text>" for example "2689479 | Jobbar Tower Lake Par", "2689477 | Jobbar Tower", "2672269 | Fojle Rabbi Park"; (3) "<owner> | <phone without leading 0>". A round selector at the right (a green check on the first card). A green "Set Plan" button at the bottom and the round blue home button.
- No page shows an outlet count limit, a search box, select-all, an un-plan action, a confirmation after Set Plan, or any repeat/duplicate-plan behaviour. TSO p16 My Visit Plan date reads April 26, 2026; the Set Plan date reads April 27, 2026 (U-23; one day apart, the default may be tomorrow, but the two screenshots may be from different days).
- By contrast TSO p16/p17 Visit Plan Outlets cards DO label their fields (Code, Owner, Contact, Channel, Cluster).

**Finding.** The place text on the Select Outlets card is unlabeled. The register (and the inventory's M-17 "<code> | <address>") labelled it address by assumption. docs/22 P-12 says address is filled for 13 of 734,789 outlets; the sample outlets carry real-format 7-digit codes, owner names and phones, and two different place strings appear for consecutive codes of the same route ("Jobbar Tower Lake Par" vs "Jobbar Tower"), which is what clusters look like on the other screens ("Madrasa Road", "Molobe Para"). The second line is far more likely the cluster. If so the card shows name, code, cluster, owner, phone, exactly the docs/08 list, and C-19 is not a contradiction.

**Spec quotes.**
- docs/08 Set Plan: "**Set Plan:** date, zone, route → Show Outlet → multi-select outlets (name, code, cluster, owner, phone) → Set Plan."
- docs/22 P-12: "Address is filled for 13." P-13: "11,336 routes (median 64 outlets, 99th percentile 112, max 214)."
- `cfg.tso.visit_plan_max_outlets = 30` and "plan date today or later" are plan assumptions (lens-config line 321, lens-features F-TSO-013), not in docs/01-13.

**Verdict: PARTLY.**

**Corrected statement.** The manual shows a multi-select list of a route's outlets with name, code, an unlabeled place line (most likely the cluster, since address is empty in the data), owner and phone, and a Set Plan button; it shows no cap, no search/select-all and no confirmation, so the 30-outlet cap is an invented default (that part of the register holds). "Card shows address, not cluster" is not established and probably wrong. Repeat Set Plan, un-planning and past-date handling are not documented either way. The date default on the screen (April 27) hints at planning for a coming day.

**Build implication.**
- Card: name, code, cluster (fall back to address if the cluster is null), owner, phone: no spec change to docs/08.
- Config: `cfg.tso.visit_plan_max_outlets` default none (0 = unlimited; a route holds up to 214 outlets), keep the key for ops.
- Rule: Set Plan is idempotent by client uuid, UNIQUE (planner, plan_date, route, outlet) as a union (re-running adds outlets), no un-plan (not documented; an addition if wanted), plan date today or later as an assumption; default the date picker to tomorrow only if the live app confirms.
- UI additions (label as improvements): search by name/code, select all/clear.
- Retract C-19 as a contradiction and C-41's "30 outlets" row remains valid (assumption not supported).

---

## Cross-entry notes

1. Photo/location chain (G-016, G-017, G-018 Update Base): one camera component and one "location correction" model serve Force Sale, Manual Override and Update Base; the manual uses the same sentence for all three. The register's "photo moves the outlet" finding should be restated as "the photo carries a fresh fix that refreshes the outlet's location with no visible approval", and the integrity decision (approval queue plus provisional device-side fix) stays.
2. Maps (G-058, G-059): one provider (Google, per the manuals) behind a single wrapper; four screens use it (AMO Team Location, AMO Update Base, TSO My Team, TSO Retailer). The web dashboard's "SR positions on a map" (docs/09) is a fifth consumer and a cost line.
3. PII (G-062 and C-51): masking is by role and by subject. The manuals mask the SR phone for the AMO, leave retailer phones visible to field roles, and (Web p4, C-51) show NID/TIN/licence to the TSO. docs/22 P-12 makes phone and owner the real sensitive fields. Mask policy needs a role x subject matrix, not a single `cfg.pii.mask_style`.
4. Items that cannot be settled from the PDFs and need the live app or the dump: what AMO "বাতিল" does (Q-29); what the home Outlet badge counts; whether an in-range call ever opens the camera; how fresh a Team Location marker is (Q52); whether Force Sale/Manual Override change the stored outlet coordinates (Q18, compare coordinates against force-sale dates).
